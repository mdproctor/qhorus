package io.casehub.qhorus.slack;

import static io.casehub.qhorus.api.message.MessageType.DECLINE;
import static io.casehub.qhorus.api.message.MessageType.DONE;
import static io.casehub.qhorus.api.message.MessageType.EVENT;
import static io.casehub.qhorus.api.message.MessageType.FAILURE;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import io.casehub.connectors.InboundConnectorIds;
import io.casehub.connectors.InboundMessage;
import io.casehub.connectors.slack.bot.SlackBotClient;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.HumanParticipatingChannelBackend;
import io.casehub.qhorus.api.gateway.InboundHumanMessage;
import io.casehub.qhorus.api.gateway.InboundNormaliser;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import org.eclipse.microprofile.config.Config;

/**
 * {@link HumanParticipatingChannelBackend} that delivers messages to Slack threads
 * via {@link SlackBotClient}. Activates for any channel that has a {@link SlackBotBinding}.
 *
 * <p>Thread continuity: outbound messages with a correlationId are posted as thread replies
 * using a composite cache — in-memory for speed, DB-backed for restart survival.
 */
@ApplicationScoped
public class SlackChannelBackend implements HumanParticipatingChannelBackend {

    static final String BACKEND_ID = "slack-bot";

    private static final Logger LOG = Logger.getLogger(SlackChannelBackend.class);

    private final SlackBotBindingStore bindingStore;
    private final SlackThreadCacheStore threadCacheStore;
    private final SlackBotClient slackBotClient;
    private final SlackInboundNormaliser slackInboundNormaliser;
    private final ChannelGateway gateway;
    private final Config config;

    // Forward: channelId → binding (for post())
    final ConcurrentHashMap<UUID, SlackBotBinding> bindingCache = new ConcurrentHashMap<>();
    // Reverse: slackChannelId → ChannelRef (for onInboundMessage())
    final ConcurrentHashMap<String, ChannelRef> slackToChannel = new ConcurrentHashMap<>();
    // Thread cache: channelId → (correlationId → threadTs)
    final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, String>> threadCache = new ConcurrentHashMap<>();

    public SlackChannelBackend(SlackBotBindingStore bindingStore,
                               SlackThreadCacheStore threadCacheStore,
                               SlackBotClient slackBotClient,
                               SlackInboundNormaliser slackInboundNormaliser,
                               ChannelGateway gateway,
                               Config config) {
        this.bindingStore = bindingStore;
        this.threadCacheStore = threadCacheStore;
        this.slackBotClient = slackBotClient;
        this.slackInboundNormaliser = slackInboundNormaliser;
        this.gateway = gateway;
        this.config = config;
    }

    @Override
    public String backendId() {
        return BACKEND_ID;
    }

    @Override
    public InboundNormaliser normaliserFor(UUID channelId) {
        return slackInboundNormaliser;
    }

    @Override
    public ActorType actorType() {
        return ActorType.HUMAN;
    }

    @Override
    public void open(ChannelRef channel, Map<String, String> metadata) {
        // No-op — backend self-registers via onChannelInitialised
    }

    /** Re-registers this backend and warms the in-memory caches when a Slack-bound channel initialises. */
    @Transactional
    public void onChannelInitialised(@Observes ChannelInitialisedEvent event) {
        UUID channelId = event.channelId();
        bindingStore.findByChannelId(channelId).ifPresent(binding -> {
            bindingCache.put(channelId, binding);
            slackToChannel.put(binding.slackChannelId, new ChannelRef(channelId, event.channelName()));

            // Warm thread cache from DB — preserves thread continuity across restarts
            List<SlackThreadCache> entries = threadCacheStore.findByChannelId(channelId);
            if (!entries.isEmpty()) {
                ConcurrentHashMap<UUID, String> channelThreads = threadCache
                        .computeIfAbsent(channelId, k -> new ConcurrentHashMap<>());
                entries.forEach(e -> channelThreads.put(e.id.correlationId, e.threadTs));
            }

            gateway.deregisterBackend(channelId, BACKEND_ID);
            gateway.registerBackend(channelId, this, "human_participating");
        });
    }

    @Override
    public void post(ChannelRef channel, OutboundMessage message) {
        // EVENT is the normaliser telemetry type — fanOut() fires for it unconditionally
        if (message.type() == EVENT) return;
        // Safety net for future null-content message types
        if (message.content() == null) return;

        SlackBotBinding binding = bindingCache.get(channel.id());
        if (binding == null) {
            LOG.debugf("No Slack binding for channel %s — skipping post", channel.name());
            return;
        }

        String token;
        try {
            token = resolveToken(binding.workspaceId);
        } catch (NoSuchElementException e) {
            LOG.warnf("No credential configured for workspace %s on channel %s — skipping post",
                    binding.workspaceId, channel.name());
            return;
        }

        // Thread lookup: memory first, then DB fallback (post-restart recovery)
        String threadTs = null;
        if (message.correlationId() != null) {
            UUID corrId = message.correlationId();
            Map<UUID, String> channelThreads = threadCache.get(channel.id());
            threadTs = channelThreads != null ? channelThreads.get(corrId) : null;
            if (threadTs == null) {
                threadTs = threadCacheStore.findThreadTs(channel.id(), corrId).orElse(null);
            }
        }

        SlackBotClient.PostResult result = slackBotClient.postMessage(
                token, binding.slackChannelId, message.content(), threadTs);

        if (!result.ok()) {
            LOG.warnf("Slack post failed on channel %s: %s", channel.name(), result.error());
            return;
        }

        boolean isTerminal = message.type() == DONE || message.type() == FAILURE || message.type() == DECLINE;

        // Cache new thread root — only on first post for a non-terminal correlationId.
        // Terminal types (DONE/FAILURE/DECLINE) are about to evict the entry; writing then
        // immediately deleting is a pointless persist+delete cycle.
        if (message.correlationId() != null && threadTs == null && result.ts() != null && !isTerminal) {
            UUID corrId = message.correlationId();
            threadCache.computeIfAbsent(channel.id(), k -> new ConcurrentHashMap<>())
                    .put(corrId, result.ts());
            threadCacheStore.save(channel.id(), corrId, result.ts());
        }

        // Evict on terminal commitment: DONE/FAILURE/DECLINE.
        // HANDOFF: do NOT evict — delegated agent continues in the same thread.
        // RESPONSE: do NOT evict — human may reply in the same Slack thread.
        if (isTerminal && message.correlationId() != null) {
            UUID corrId = message.correlationId();
            Map<UUID, String> channelThreads = threadCache.get(channel.id());
            if (channelThreads != null) channelThreads.remove(corrId);
            threadCacheStore.delete(channel.id(), corrId);
        }
    }

    /**
     * Routes inbound Slack messages to the appropriate Qhorus channel.
     *
     * <p>CorrelationId is resolved here — before {@code receiveHumanMessage()} — to prevent
     * a race where a fast agent RESPONSE arrives before the thread-cache entry is populated.
     */
    public CompletionStage<Void> onInboundMessage(@ObservesAsync InboundMessage msg) {
        if (!InboundConnectorIds.SLACK_INBOUND.equals(msg.connectorId())) {
            return CompletableFuture.completedFuture(null);
        }

        ChannelRef channelRef = slackToChannel.get(msg.externalChannelRef());
        if (channelRef == null) {
            LOG.debugf("No Slack-backed channel for Slack channel %s — discarding", msg.externalChannelRef());
            return CompletableFuture.completedFuture(null);
        }

        String slackThreadTs = msg.metadata().get("slack-thread-ts");
        String slackTs = msg.metadata().get("slack-ts");
        String corrIdStr;

        if (slackThreadTs != null && !slackThreadTs.equals(slackTs)) {
            // Thread reply — reverse-lookup existing corrId from DB
            corrIdStr = threadCacheStore.findCorrelationId(channelRef.id(), slackThreadTs)
                    .map(UUID::toString).orElse(null);
        } else {
            corrIdStr = null;
        }

        if (corrIdStr == null) {
            // New top-level message (or reply to an unknown thread) — generate corrId and cache
            UUID corrId = UUID.randomUUID();
            corrIdStr = corrId.toString();
            // rootTs: for thread replies, use slackThreadTs (the thread root).
            // For top-level messages, use slackTs (this message IS the root).
            // Slack requires thread_ts to equal the ROOT message's timestamp.
            String rootTs = (slackThreadTs != null && !slackThreadTs.equals(slackTs)) ? slackThreadTs : slackTs;
            if (rootTs != null) {
                // In-memory first (never throws), then DB best-effort — ORDERING INVARIANT
                threadCache.computeIfAbsent(channelRef.id(), k -> new ConcurrentHashMap<>())
                        .put(corrId, rootTs);
                try {
                    threadCacheStore.save(channelRef.id(), corrId, rootTs);
                } catch (Exception e) {
                    LOG.warnf(e, "Thread anchor DB write failed for channel=%s corrId=%s — " +
                            "in-memory anchor intact; restart recovery disabled for this corrId",
                            channelRef.id(), corrId);
                }
            }
        }

        gateway.receiveHumanMessage(channelRef,
                new InboundHumanMessage(msg.externalSenderId(), msg.content(), msg.receivedAt(),
                        msg.metadata(), corrIdStr, null));
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Evicts in-memory routing state for the channel (in-memory only).
     * Called from SlackBindingResource on admin unbinding.
     * DB thread cache rows are left for TTL cleanup — in-flight posts still return early cleanly.
     */
    void evict(UUID channelId) {
        SlackBotBinding binding = bindingCache.remove(channelId);
        if (binding != null) slackToChannel.remove(binding.slackChannelId);
        threadCache.remove(channelId);
    }

    /** Called by ChannelGateway on channel deletion — removes all state for the channel. */
    @Override
    public void close(ChannelRef channel) {
        SlackBotBinding binding = bindingCache.remove(channel.id());
        if (binding != null) slackToChannel.remove(binding.slackChannelId);
        threadCache.remove(channel.id());
        threadCacheStore.deleteAllByChannelId(channel.id());
        bindingStore.deleteByChannelId(channel.id());
    }

    /** Resolves bot token from MicroProfile Config using workspaceId as the credential key. */
    String resolveToken(String workspaceId) {
        return config.getValue("casehub.qhorus.slack-channel.credentials." + workspaceId, String.class);
    }
}
