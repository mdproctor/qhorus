package io.casehub.qhorus.runtime.gateway;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import io.quarkus.scheduler.Scheduled;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.qualifier.CrossTenant;
import io.casehub.qhorus.runtime.config.DeliveryConfig;
import io.casehub.qhorus.api.store.CrossTenantMessageStore;
import io.casehub.qhorus.api.gateway.DeliveryCursor;
import io.casehub.qhorus.api.store.DeliveryCursorStore;

/**
 * Event-driven delivery pump for {@link io.casehub.qhorus.api.gateway.DeliveryGuarantee#AT_LEAST_ONCE}
 * backends. Consumes signals from {@link DeliverySignalQueue} and drives per-backend delivery
 * via {@link DeliveryBatchExecutor}.
 *
 * <p>The pump is the sole delivery path for tracked backends — {@code fanOut()} skips them.
 * This eliminates all concurrency problems: no duplicate delivery, no cursor races.
 *
 * <p>Health tracking acts as an in-memory circuit breaker: unhealthy backends are skipped by
 * the event-driven pump but retried by the scheduled reconciler.
 *
 * <p>Refs #132.
 */
@ApplicationScoped
public class DeliveryService implements DeliveryBatchExecutor.HealthCallback {

    private static final Logger LOG = Logger.getLogger(DeliveryService.class);

    DeliverySignalQueue signalQueue;
    DeliveryConfig config;
    ChannelGateway gateway;
    MeterRegistry meterRegistry;
    /**
     * Typed as {@link Executor} so CDI-free unit tests can supply a synchronous
     * implementation. CDI injects {@link ManagedExecutor} which is-a {@link Executor}.
     */
    Executor executor;
    DeliveryBatchExecutor batchExecutor;
    DeliveryCursorStore cursorStore;
    CrossTenantMessageStore messageStore;

    /** Guards concurrent processing of the same (channel, backend) pair. */
    private final Set<String> activeDeliveries = ConcurrentHashMap.newKeySet();

    /** Consecutive failure count per backendId. */
    private final ConcurrentHashMap<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();

    /** Backends that have exceeded the failure threshold. */
    private final Set<String> unhealthy = ConcurrentHashMap.newKeySet();

    volatile boolean running;

    /** CDI injection constructor. */
    @Inject
    public DeliveryService(DeliverySignalQueue signalQueue,
                           DeliveryConfig config,
                           ChannelGateway gateway,
                           ManagedExecutor managedExecutor,
                           DeliveryBatchExecutor batchExecutor,
                           DeliveryCursorStore cursorStore,
                           @CrossTenant CrossTenantMessageStore messageStore,
                           Instance<MeterRegistry> meterRegistryInstance) {
        this.signalQueue = signalQueue;
        this.config = config;
        this.gateway = gateway;
        this.executor = managedExecutor;
        this.batchExecutor = batchExecutor;
        this.cursorStore = cursorStore;
        this.messageStore = messageStore;
        this.meterRegistry = meterRegistryInstance.isResolvable() ? meterRegistryInstance.get() : null;
    }

    /** CDI-free unit test constructor. */
    DeliveryService() {
    }

    @PostConstruct
    void start() {
        if (meterRegistry != null) {
            Gauge.builder("qhorus.delivery.backends.unhealthy", unhealthy, Set::size)
                    .register(meterRegistry);
        }
        if (!config.enabled()) {
            LOG.info("Delivery pump disabled (casehub.qhorus.delivery.enabled=false)");
            return;
        }
        running = true;
        executor.execute(this::pumpLoop);
        LOG.info("Delivery pump started");
    }

    @PreDestroy
    void stop() {
        running = false;
        // Active deliveries will complete naturally — ManagedExecutor handles shutdown
        LOG.info("Delivery pump stopping");
    }

    /**
     * Main pump loop. Blocks on signal queue, deduplicates channel IDs, processes each channel.
     * Top-level try-catch per channel prevents one channel's failure from killing the pump thread.
     */
    void pumpLoop() {
        List<UUID> batch = new ArrayList<>();
        while (running) {
            try {
                UUID first = signalQueue.poll(5, TimeUnit.SECONDS);
                if (first != null) {
                    batch.add(first);
                    signalQueue.drainTo(batch);
                    Set<UUID> unique = new HashSet<>(batch);
                    batch.clear();
                    for (UUID channelId : unique) {
                        try {
                            processChannel(channelId);
                        } catch (Exception e) {
                            LOG.errorf(e, "Error processing channel %s — pump continues", channelId);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Processes all tracked backends for a channel. Spawns a managed task per backend,
     * guarded by {@code activeDeliveries} to prevent concurrent processing of the same
     * (channel, backend) pair.
     *
     * <p>Unhealthy backends are skipped by the event-driven pump — the scheduled reconciler
     * retries them.
     */
    void processChannel(UUID channelId) {
        for (ChannelGateway.BackendEntry entry : gateway.trackedEntries(channelId)) {
            if (isUnhealthy(entry.backend().backendId())) {
                continue; // circuit breaker — reconciler retries
            }
            spawnDelivery(channelId, entry.backend());
        }
    }

    /**
     * Spawns a delivery task for a single backend, guarded by {@code activeDeliveries}.
     * Package-private helper called by {@link #processChannel} (with unhealthy guard) and
     * {@link #reconcileAll} (no guard — retries all backends).
     */
    void spawnDelivery(UUID channelId, ChannelBackend backend) {
        String key = channelId + ":" + backend.backendId();
        if (activeDeliveries.add(key)) {
            try {
                executor.execute(() -> {
                    try {
                        deliverPending(channelId, backend);
                    } finally {
                        activeDeliveries.remove(key);
                    }
                });
            } catch (Exception e) {
                // Thread creation failure — clean up guard to prevent permanent lockout
                activeDeliveries.remove(key);
                LOG.errorf(e, "Failed to submit delivery task for channel %s backend %s",
                        channelId, backend.backendId());
            }
        }
    }

    /**
     * Self-driving delivery loop. Calls {@link DeliveryBatchExecutor#deliverBatch} until
     * the backend is caught up (EMPTY) or a failure occurs (FAILED).
     */
    void deliverPending(UUID channelId, ChannelBackend backend) {
        while (running) {
            DeliveryBatchExecutor.BatchResult result =
                    batchExecutor.deliverBatch(channelId, backend, this);
            if (result.deliveredCount() > 0 && meterRegistry != null) {
                meterRegistry.counter("qhorus.delivery.messages.delivered",
                        "backendId", backend.backendId())
                        .increment(result.deliveredCount());
            }
            if (result.status() == DeliveryBatchExecutor.Status.EMPTY
                    || result.status() == DeliveryBatchExecutor.Status.FAILED) {
                break;
            }
        }
    }

    /**
     * Scheduled reconciler — scans all cursors, joins with gateway registry, calls
     * {@link #processChannel} for each channel with tracked backends. Retries ALL
     * backends including unhealthy ones — when a retry succeeds, the health flag is
     * cleared automatically by the batch executor callback.
     *
     * <p>Cursor initialization is lazy: only channels that have received at least one
     * delivery signal (via {@link #signal}) will have cursors. Newly registered backends
     * on channels that have never been pumped are not reconciled until the event-driven
     * path processes them for the first time.
     *
     * <p>The {@code activeDeliveries} guard prevents concurrent processing with
     * the event-driven pump.
     */
    @Scheduled(every = "${casehub.qhorus.delivery.reconciliation-interval:30s}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void reconcileAll() {
        if (!config.enabled() || !running) {
            return;
        }
        List<DeliveryCursor> allCursors = cursorStore.findAll();
        if (meterRegistry != null) {
            computeCursorLag(allCursors);
        }
        Set<UUID> channelIds = new HashSet<>();
        for (DeliveryCursor cursor : allCursors) {
            channelIds.add(cursor.channelId());
        }
        for (UUID channelId : channelIds) {
            // Reconciler processes ALL backends, including unhealthy — bypass the health check
            for (ChannelGateway.BackendEntry entry : gateway.trackedEntries(channelId)) {
                spawnDelivery(channelId, entry.backend());
            }
        }
    }

    private void computeCursorLag(List<DeliveryCursor> cursors) {
        for (DeliveryCursor cursor : cursors) {
            long head = messageStore.findLastMessage(cursor.channelId())
                    .map(m -> m.id()).orElse(0L);
            long lag = head - cursor.lastDeliveredId();
            Tags tags = Tags.of("backendId", cursor.backendId());
            Gauge.builder("qhorus.delivery.cursor.lag", () -> lag)
                    .tags(tags)
                    .register(meterRegistry);
        }
    }

    // ── Health tracking (HealthCallback implementation) ─────────────────────────

    @Override
    public void recordFailure(String backendId) {
        int count = consecutiveFailures.merge(backendId, 1, Integer::sum);
        if (count >= config.maxConsecutiveFailures()) {
            unhealthy.add(backendId);
        }
        if (meterRegistry != null) {
            meterRegistry.counter("qhorus.delivery.failures", "backendId", backendId).increment();
        }
    }

    @Override
    public void resetHealth(String backendId) {
        consecutiveFailures.remove(backendId);
        unhealthy.remove(backendId);
    }

    /**
     * Returns whether the given backend is marked unhealthy (circuit open).
     */
    public boolean isUnhealthy(String backendId) {
        return unhealthy.contains(backendId);
    }

    // ── Test accessors ──────────────────────────────────────────────────────────

    /** Package-private — test accessor. */
    Set<String> activeDeliveries() {
        return activeDeliveries;
    }

    /** Package-private — test accessor for gauge registration. */
    Set<String> unhealthySet() {
        return unhealthy;
    }
}
