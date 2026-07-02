package io.casehub.qhorus.runtime.watchdog;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.qualifier.CrossTenant;
import io.casehub.qhorus.api.watchdog.AgentStaleContext;
import io.casehub.qhorus.api.watchdog.AlertContext;
import io.casehub.qhorus.api.watchdog.ApprovalPendingContext;
import io.casehub.qhorus.api.watchdog.BarrierStuckContext;
import io.casehub.qhorus.api.watchdog.ChannelIdleContext;
import io.casehub.qhorus.api.watchdog.QueueDepthContext;
import io.casehub.qhorus.api.watchdog.WatchdogAlertEvent;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.instance.Instance;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.watchdog.Watchdog;
import io.casehub.qhorus.runtime.config.QhorusConfig;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;
import io.casehub.qhorus.api.store.CrossTenantCommitmentStore;
import io.casehub.qhorus.api.store.CrossTenantMessageStore;
import io.casehub.qhorus.api.store.CrossTenantWatchdogStore;
import io.casehub.qhorus.api.store.InstanceStore;
import io.casehub.qhorus.api.store.WatchdogStore;
import io.casehub.qhorus.api.store.query.InstanceQuery;
import io.casehub.qhorus.api.store.query.MessageQuery;

/**
 * Evaluates all registered watchdog conditions and fires alert messages
 * to their notification channels when conditions are met.
 *
 * <p>
 * Called by the Quarkus Scheduler at a configurable interval, and directly
 * by tests via injection for deterministic testing without scheduler timing.
 *
 * <p>
 * Debounce rule: a watchdog does not re-fire if {@code lastFiredAt} is within
 * {@code thresholdSeconds} of now (or within 1 second for threshold=0 conditions).
 */
@ApplicationScoped
public class WatchdogEvaluationService {

    @Inject
    QhorusConfig config;

    @Inject
    MessageService messageService;

    @Inject
    WatchdogStore watchdogStore;

    @CrossTenant
    @Inject
    CrossTenantChannelStore crossTenantChannelStore;

    @CrossTenant
    @Inject
    CrossTenantMessageStore crossTenantMessageStore;

    @CrossTenant
    @Inject
    CrossTenantCommitmentStore crossTenantCommitmentStore;

    @CrossTenant
    @Inject
    CrossTenantWatchdogStore crossTenantWatchdogStore;

    @Inject
    InstanceStore instanceStore;

    @Inject
    Event<WatchdogAlertEvent> alertEvents;

    /** Evaluate all registered watchdogs and fire alerts for met conditions. */
    @Transactional
    public void evaluateAll() {
        if (!config.watchdog().enabled()) {
            return;
        }

        List<Watchdog> watchdogs = crossTenantWatchdogStore.listAll();
        Instant        now       = Instant.now();

        for (Watchdog w : watchdogs) {
            if (isDebounced(w, now)) {
                continue;
            }
            boolean fired = switch (w.conditionType()) {
                case "BARRIER_STUCK" -> evaluateBarrierStuck(w, now);
                case "APPROVAL_PENDING" -> evaluateApprovalPending(w, now);
                case "AGENT_STALE" -> evaluateAgentStale(w, now);
                case "CHANNEL_IDLE" -> evaluateChannelIdle(w, now);
                case "QUEUE_DEPTH" -> evaluateQueueDepth(w, now);
                default -> false;
            };
            if (fired) {
                Watchdog updated = new Watchdog(w.id(), w.conditionType(), w.targetName(),
                        w.thresholdSeconds(), w.thresholdCount(), w.notificationChannel(),
                        w.createdBy(), w.tenancyId(), w.createdAt(), now);
                watchdogStore.put(updated);  // route through store seam; return value intentionally discarded
            }
        }
    }

    /**
     * Debounce: skip if lastFiredAt is recent relative to threshold.
     * For threshold=0 conditions, use a 1-second window to prevent double-fire
     * within the same evaluation cycle.
     */
    private boolean isDebounced(Watchdog w, Instant now) {
        if (w.lastFiredAt() == null) {
            return false;
        }
        long windowSeconds = w.thresholdSeconds() != null && w.thresholdSeconds() > 0
                ? w.thresholdSeconds()
                : 1L;
        return w.lastFiredAt().isAfter(now.minusSeconds(windowSeconds));
    }

    private boolean evaluateBarrierStuck(Watchdog w, Instant now) {
        int threshold = w.thresholdSeconds() != null ? w.thresholdSeconds() : 300;
        Instant cutoff = now.minusSeconds(threshold);

        List<Channel> barriers = crossTenantChannelStore.listAll().stream()
                                                        .filter(ch -> ch.semantic() == ChannelSemantic.BARRIER)
                                                        .filter(ch -> "*".equals(w.targetName()) || ch.name().equals(w.targetName()))
                                                        .filter(ch -> ch.lastActivityAt() == null || ch.lastActivityAt().isBefore(cutoff) || threshold == 0)
                                                        .toList();

        boolean fired = false;
        for (Channel ch : barriers) {
            List<String> required = ch.barrierContributors() != null
                    ? ch.barrierContributors()
                    : List.of();
            if (required.isEmpty())
                continue;

            List<String> written = crossTenantMessageStore.distinctSendersByChannel(ch.id(), MessageType.EVENT);
            List<String> missing = required.stream()
                    .map(String::trim)
                    .filter(r -> !r.isBlank())
                    .filter(r -> !written.contains(r))
                    .toList();

            if (!missing.isEmpty()) {
                Instant effectiveActivity = ch.lastActivityAt() != null ? ch.lastActivityAt() : ch.createdAt();
                long elapsedSeconds = now.getEpochSecond() - effectiveActivity.getEpochSecond();
                String summary = "BARRIER_STUCK: channel='" + ch.name() + "' waiting for contributors";
                fireAlert(w, summary, new BarrierStuckContext(ch.id(), ch.name(), missing, elapsedSeconds), now);
                fired = true;
            }
        }
        return fired;
    }

    private boolean evaluateApprovalPending(Watchdog w, Instant now) {
        int threshold = w.thresholdSeconds() != null ? w.thresholdSeconds() : 300;

        // Threshold formula preserved verbatim from original: for threshold=300, fires for
        // commitments expired >240s ago; for threshold=60, expiring right now; for
        // threshold=0, all commitments with any expiry. See design spec 2026-05-28.
        List<Commitment> pending = crossTenantCommitmentStore.findAllOpen()
                                                             .stream()
                                                             .filter(c -> c.expiresAt() != null)
                                                             .filter(c -> threshold == 0 || c.expiresAt().isBefore(now.plusSeconds(60 - threshold)))
                                                             .toList();

        if (!pending.isEmpty()) {
            Instant oldestExpiry = pending.stream()
                    .map(Commitment::expiresAt)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            String summary = "APPROVAL_PENDING: " + pending.size() + " approval(s) awaiting human response";
            fireAlert(w, summary, new ApprovalPendingContext(pending.size(), oldestExpiry), now);
            return true;
        }
        return false;
    }

    private boolean evaluateAgentStale(Watchdog w, Instant now) {
        int threshold = w.thresholdSeconds() != null ? w.thresholdSeconds() : 300;
        Instant cutoff = now.minusSeconds(threshold);

        List<Instance> staleInstances = instanceStore.scan(
                InstanceQuery.builder().status("stale").staleOlderThan(cutoff).build());

        if (!staleInstances.isEmpty()) {
            List<String> ids = staleInstances.stream()
                    .limit(10)
                    .map(i -> i.id().toString())
                    .toList();
            String summary = "AGENT_STALE: " + staleInstances.size() + " stale agent(s) detected";
            fireAlert(w, summary, new AgentStaleContext(staleInstances.size(), ids), now);
            return true;
        }
        return false;
    }

    private boolean evaluateChannelIdle(Watchdog w, Instant now) {
        int threshold = w.thresholdSeconds() != null ? w.thresholdSeconds() : 600;
        Instant cutoff = now.minusSeconds(threshold);

        List<Channel> idle = crossTenantChannelStore.listAll().stream()
                                                    .filter(ch -> "*".equals(w.targetName()) || ch.name().equals(w.targetName()))
                                                    .filter(ch -> threshold == 0 || ch.lastActivityAt() == null || ch.lastActivityAt().isBefore(cutoff))
                                                    .toList();

        if (!idle.isEmpty()) {
            List<String> names = idle.stream().map(Channel::name).limit(3).toList();
            String joined = String.join(", ", names);
            String summary = "CHANNEL_IDLE: channel(s) idle > " + threshold + "s: " + joined;
            fireAlert(w, summary, new ChannelIdleContext(names, threshold), now);
            return true;
        }
        return false;
    }

    private boolean evaluateQueueDepth(Watchdog w, Instant now) {
        int threshold = w.thresholdCount() != null ? w.thresholdCount() : 100;

        List<Channel> channels = crossTenantChannelStore.listAll().stream()
                                                        .filter(ch -> "*".equals(w.targetName()) || ch.name().equals(w.targetName()))
                                                        .toList();

        // Fires on the FIRST channel that exceeds the threshold. If multiple channels
        // are over-depth, only one alert fires per evaluation cycle — pre-existing behaviour.
        for (Channel ch : channels) {
            long count = crossTenantMessageStore.count(
                    MessageQuery.builder()
                            .channelId(ch.id())
                            .excludeTypes(List.of(MessageType.EVENT))
                            .build());
            if (count >= threshold) {
                String summary = "QUEUE_DEPTH: channel='" + ch.name() + "' has " + count
                        + " messages (threshold=" + threshold + ")";
                fireAlert(w, summary, new QueueDepthContext(ch.name(), count, threshold), now);
                return true;
            }
        }
        return false;
    }

    private void fireAlert(Watchdog w, String summary, AlertContext context, Instant now) {
        // 1. Fire async event FIRST — external delivery is independent of internal channel
        //    success. fireAsync() dispatches immediately; it does not wait for the outer
        //    @Transactional boundary to commit.
        //    Ghost-notification risk (tx rollback after fire): narrow window, accepted.
        //    Crash/missed-alert risk (app crashes before observer delivers): accepted — CDI
        //    async is at-most-once; outbox pattern required for at-least-once.
        alertEvents.fireAsync(new WatchdogAlertEvent(
                w.id(), w.targetName(), w.notificationChannel(), summary, now, context));

        // 2. Internal channel dispatch SECOND — failure does not suppress the event above.
        //    Use cross-tenant lookup scoped to the watchdog's tenancy — no CDI request context
        //    available in the scheduler thread. Pass w.tenancyId() explicitly so MessageService
        //    can route without CurrentPrincipal (GE-20260531-446fea pattern).
        Optional<Channel> notifChannel = crossTenantChannelStore
                .findByNameAndTenancy(w.notificationChannel(), w.tenancyId());
        if (notifChannel.isEmpty()) {
            return;
        }
        messageService.dispatch(MessageDispatch.builder()
                .channelId(notifChannel.get().id())
                .sender("system:watchdog")
                .type(MessageType.STATUS)
                .content(summary)
                .actorType(ActorType.SYSTEM)
                .tenancyId(w.tenancyId())
                .build());
    }
}
