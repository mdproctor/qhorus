package io.quarkiverse.qhorus.runtime.mcp;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.channel.ChannelSemantic;
import io.quarkiverse.qhorus.runtime.channel.ReactiveChannelService;
import io.quarkiverse.qhorus.runtime.data.ReactiveDataService;
import io.quarkiverse.qhorus.runtime.instance.Instance;
import io.quarkiverse.qhorus.runtime.instance.ReactiveInstanceService;
import io.quarkiverse.qhorus.runtime.ledger.ReactiveLedgerWriteService;
import io.quarkiverse.qhorus.runtime.store.ReactiveMessageStore;
import io.quarkiverse.qhorus.runtime.watchdog.ReactiveWatchdogService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

/**
 * Reactive implementation of Qhorus MCP tools.
 * Category A tools (20): pure reactive — instance, observer, channel, data, watchdog.
 * Category B tools (blocking) are added in Task 2.
 *
 * <p>
 * All business logic exceptions ({@link IllegalArgumentException} and
 * {@link IllegalStateException}) are automatically wrapped in
 * {@link io.quarkiverse.mcp.server.ToolCallException} by the quarkus-mcp-server interceptor.
 */
@WrapBusinessError({ IllegalArgumentException.class, IllegalStateException.class })
@Alternative
@ApplicationScoped
public class ReactiveQhorusMcpTools extends QhorusMcpToolsBase {

    private static final Logger LOG = Logger.getLogger(ReactiveQhorusMcpTools.class);

    // Reactive services (Category A tools)
    @Inject
    ReactiveChannelService channelService;

    @Inject
    ReactiveInstanceService instanceService;

    @Inject
    ReactiveDataService dataService;

    @Inject
    ReactiveWatchdogService watchdogService;

    // Used by blockingSendMessage in Task 2 via reactiveLedgerWriteService.recordEvent()
    @Inject
    ReactiveLedgerWriteService reactiveLedgerWriteService;

    @Inject
    ReactiveMessageStore messageStore;

    // Non-service beans (blocking in-memory / config)
    @Inject
    io.quarkiverse.qhorus.runtime.config.QhorusConfig qhorusConfig;

    @Inject
    io.quarkiverse.qhorus.runtime.channel.RateLimiter rateLimiter;

    @Inject
    io.quarkiverse.qhorus.runtime.instance.ObserverRegistry observerRegistry;

    // Blocking services (Category B tools — Task 2)
    @Inject
    io.quarkiverse.qhorus.runtime.channel.ChannelService blockingChannelService;

    @Inject
    io.quarkiverse.qhorus.runtime.instance.InstanceService blockingInstanceService;

    @Inject
    io.quarkiverse.qhorus.runtime.message.MessageService blockingMessageService;

    @Inject
    io.quarkiverse.qhorus.runtime.data.DataService blockingDataService;

    @Inject
    io.quarkiverse.qhorus.runtime.ledger.LedgerWriteService blockingLedgerWriteService;

    @Inject
    io.quarkiverse.qhorus.runtime.ledger.AgentMessageLedgerEntryRepository ledgerRepo;

    // ---------------------------------------------------------------------------
    // Category A: Instance tools
    // ---------------------------------------------------------------------------

    @Tool(name = "register", description = "Register an agent instance with capability tags. "
            + "Returns active channels and online instances as immediate context.")
    public Uni<RegisterResponse> register(
            @ToolArg(name = "instance_id", description = "Unique human-readable identifier for this agent") String instanceId,
            @ToolArg(name = "description", description = "Description of this agent's role") String description,
            @ToolArg(name = "capabilities", description = "Capability tags for peer discovery", required = false) List<String> capabilities,
            @ToolArg(name = "claudony_session_id", description = "Optional Claudony session ID for managed workers", required = false) String claudonySessionId) {
        List<String> caps = capabilities != null ? capabilities : List.of();
        return instanceService.register(instanceId, description, caps, claudonySessionId)
                .flatMap(instance -> channelService.listAll()
                        .flatMap(channels -> instanceService.listAll()
                                .flatMap(instances -> buildInstanceInfoListReactive(instances)
                                        .map(onlineInstances -> {
                                            List<ChannelSummary> summaries = channels.stream()
                                                    .map(ch -> new ChannelSummary(ch.name, ch.description,
                                                            ch.semantic.name()))
                                                    .toList();
                                            return new RegisterResponse(instance.instanceId, summaries, onlineInstances);
                                        }))));
    }

    @Tool(name = "list_instances", description = "List registered agent instances. "
            + "Optionally filter by capability tag.")
    public Uni<List<InstanceInfo>> listInstances(
            @ToolArg(name = "capability", description = "Filter by capability tag (optional)", required = false) String capability) {
        Uni<List<Instance>> source = (capability != null && !capability.isBlank())
                ? instanceService.findByCapability(capability)
                : instanceService.listAll();
        return source.flatMap(this::buildInstanceInfoListReactive);
    }

    // ---------------------------------------------------------------------------
    // Category A: Observer tools
    // ---------------------------------------------------------------------------

    @Tool(name = "register_observer", description = "Subscribe a read-only observer to event messages on one or more channels. "
            + "Observers receive EVENT messages without joining the instance registry — "
            + "they are invisible to agents and cannot send messages. "
            + "Observer registrations are in-memory and reset on restart.")
    public Uni<ObserverRegistration> registerObserver(
            @ToolArg(name = "observer_id", description = "Unique observer identifier (use a distinct namespace from instance IDs, e.g. 'dashboard', 'monitor-1')") String observerId,
            @ToolArg(name = "channel_names", description = "List of channel names to subscribe to") List<String> channelNames) {
        return Uni.createFrom().item(() -> {
            observerRegistry.register(observerId, channelNames);
            return new ObserverRegistration(observerId, observerRegistry.getSubscriptions(observerId));
        });
    }

    @Tool(name = "deregister_observer", description = "Remove an observer subscription. "
            + "After deregistration the ID is no longer treated as an observer.")
    public Uni<DeregisterObserverResult> deregisterObserver(
            @ToolArg(name = "observer_id", description = "Observer ID to remove") String observerId) {
        return Uni.createFrom().item(() -> {
            boolean removed = observerRegistry.deregister(observerId);
            return new DeregisterObserverResult(observerId, removed,
                    removed ? "Observer '" + observerId + "' deregistered."
                            : "Observer '" + observerId + "' was not registered.");
        });
    }

    // ---------------------------------------------------------------------------
    // Category A: Channel tools
    // ---------------------------------------------------------------------------

    @Tool(name = "create_channel", description = "Create a named channel with declared semantic. "
            + "Semantic defaults to APPEND if not specified.")
    public Uni<ChannelDetail> createChannel(
            @ToolArg(name = "name", description = "Unique channel name") String name,
            @ToolArg(name = "description", description = "Channel purpose description") String description,
            @ToolArg(name = "semantic", description = "Channel semantic: APPEND (default), COLLECT, BARRIER, EPHEMERAL, LAST_WRITE", required = false) String semantic,
            @ToolArg(name = "barrier_contributors", description = "Comma-separated contributor names (BARRIER channels only)", required = false) String barrierContributors,
            @ToolArg(name = "allowed_writers", description = "Comma-separated allowed writers: bare instance IDs and/or capability:tag / role:name patterns. Null = open to all.", required = false) String allowedWriters,
            @ToolArg(name = "admin_instances", description = "Comma-separated instance IDs permitted to manage this channel (pause/resume/force_release/clear). Null = open to any caller.", required = false) String adminInstances,
            @ToolArg(name = "rate_limit_per_channel", description = "Max messages per minute across all senders. Null = unlimited.", required = false) Integer rateLimitPerChannel,
            @ToolArg(name = "rate_limit_per_instance", description = "Max messages per minute from a single sender. Null = unlimited.", required = false) Integer rateLimitPerInstance) {
        ChannelSemantic sem;
        if (semantic == null || semantic.isBlank()) {
            sem = ChannelSemantic.APPEND;
        } else {
            try {
                sem = ChannelSemantic.valueOf(semantic.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Invalid semantic '" + semantic + "'. Valid values: APPEND, COLLECT, BARRIER, EPHEMERAL, LAST_WRITE");
            }
        }
        return channelService.create(name, description, sem, barrierContributors, allowedWriters,
                adminInstances, rateLimitPerChannel, rateLimitPerInstance)
                .flatMap(ch -> messageStore.countByChannel(ch.id)
                        .map(count -> toChannelDetail(ch, count.longValue())));
    }

    @Tool(name = "set_channel_rate_limits", description = "Update the rate limits on an existing channel. "
            + "Pass null to remove a limit (restores unrestricted behaviour). "
            + "Limits are enforced via an in-memory sliding 60-second window that resets on restart.")
    public Uni<ChannelDetail> setChannelRateLimits(
            @ToolArg(name = "channel_name", description = "Name of the channel to update") String channelName,
            @ToolArg(name = "rate_limit_per_channel", description = "Max messages per minute across all senders. Null = unlimited.", required = false) Integer rateLimitPerChannel,
            @ToolArg(name = "rate_limit_per_instance", description = "Max messages per minute from a single sender. Null = unlimited.", required = false) Integer rateLimitPerInstance) {
        return channelService.setRateLimits(channelName, rateLimitPerChannel, rateLimitPerInstance)
                .flatMap(ch -> messageStore.countByChannel(ch.id)
                        .map(count -> toChannelDetail(ch, count.longValue())));
    }

    @Tool(name = "set_channel_writers", description = "Update the write ACL on an existing channel. "
            + "Pass null or blank to open the channel to all writers.")
    public Uni<ChannelDetail> setChannelWriters(
            @ToolArg(name = "channel_name", description = "Name of the channel to update") String channelName,
            @ToolArg(name = "allowed_writers", description = "Comma-separated allowed writers (instance IDs and/or capability:tag / role:name). Null = open to all.", required = false) String allowedWriters) {
        return channelService.setAllowedWriters(channelName, allowedWriters)
                .flatMap(ch -> messageStore.countByChannel(ch.id)
                        .map(count -> toChannelDetail(ch, count.longValue())));
    }

    @Tool(name = "set_channel_admins", description = "Update the admin instance list on an existing channel. "
            + "Admins may invoke pause_channel, resume_channel, force_release_channel, and clear_channel. "
            + "Pass null or blank to open management to any caller.")
    public Uni<ChannelDetail> setChannelAdmins(
            @ToolArg(name = "channel_name", description = "Name of the channel to update") String channelName,
            @ToolArg(name = "admin_instances", description = "Comma-separated instance IDs permitted to manage this channel. Null = open to any caller.", required = false) String adminInstances) {
        return channelService.setAdminInstances(channelName, adminInstances)
                .flatMap(ch -> messageStore.countByChannel(ch.id)
                        .map(count -> toChannelDetail(ch, count.longValue())));
    }

    @Tool(name = "list_channels", description = "List all channels with message count and last activity.")
    public Uni<List<ChannelDetail>> listChannels() {
        return channelService.listAll().flatMap(channels -> {
            if (channels.isEmpty()) {
                return Uni.createFrom().item(List.of());
            }
            List<Uni<ChannelDetail>> unis = channels.stream()
                    .map(ch -> messageStore.countByChannel(ch.id)
                            .map(count -> toChannelDetail(ch, count.longValue())))
                    .toList();
            return Uni.join().all(unis).andFailFast();
        });
    }

    @Tool(name = "find_channel", description = "Search channels by keyword in name or description.")
    public Uni<List<ChannelDetail>> findChannel(
            @ToolArg(name = "keyword", description = "Search term (case-insensitive)") String keyword) {
        String lowerKeyword = keyword.toLowerCase();
        return channelService.listAll().flatMap(channels -> {
            // In-memory filter: ReactiveChannelQuery does not support OR-predicate across name+description.
            // Acceptable for typical channel counts; future improvement: add keyword search to ReactiveChannelStore.
            List<Channel> matches = channels.stream()
                    .filter(ch -> (ch.name != null && ch.name.toLowerCase().contains(lowerKeyword))
                            || (ch.description != null && ch.description.toLowerCase().contains(lowerKeyword)))
                    .toList();
            if (matches.isEmpty()) {
                return Uni.createFrom().item(List.of());
            }
            List<Uni<ChannelDetail>> unis = matches.stream()
                    .map(ch -> messageStore.countByChannel(ch.id)
                            .map(count -> toChannelDetail(ch, count.longValue())))
                    .toList();
            return Uni.join().all(unis).andFailFast();
        });
    }

    @Tool(name = "pause_channel", description = "Pause a channel — blocks send_message and returns empty on check_messages. "
            + "Idempotent. Use to stop agent work flowing through a channel for human review.")
    public Uni<ChannelDetail> pauseChannel(
            @ToolArg(name = "channel_name", description = "Name of the channel to pause") String channelName,
            @ToolArg(name = "caller_instance_id", description = "Instance ID of the caller. Required when the channel has an admin_instances list.", required = false) String callerInstanceId) {
        return channelService.findByName(channelName)
                .map(opt -> opt.orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName)))
                .invoke(ch -> checkAdminAccess(ch, callerInstanceId, "pause_channel"))
                .flatMap(ignored -> channelService.pause(channelName))
                .flatMap(ch -> messageStore.countByChannel(ch.id)
                        .map(count -> toChannelDetail(ch, count.longValue())));
    }

    @Tool(name = "resume_channel", description = "Resume a paused channel — re-enables send_message and check_messages. "
            + "Idempotent.")
    public Uni<ChannelDetail> resumeChannel(
            @ToolArg(name = "channel_name", description = "Name of the channel to resume") String channelName,
            @ToolArg(name = "caller_instance_id", description = "Instance ID of the caller. Required when the channel has an admin_instances list.", required = false) String callerInstanceId) {
        return channelService.findByName(channelName)
                .map(opt -> opt.orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName)))
                .invoke(ch -> checkAdminAccess(ch, callerInstanceId, "resume_channel"))
                .flatMap(ignored -> channelService.resume(channelName))
                .flatMap(ch -> messageStore.countByChannel(ch.id)
                        .map(count -> toChannelDetail(ch, count.longValue())));
    }

    // ---------------------------------------------------------------------------
    // Category A: Data tools
    // ---------------------------------------------------------------------------

    @Tool(name = "share_data", description = "Store a large artefact by key. "
            + "Supports chunked upload via append=true; last_chunk=true marks the artefact complete. "
            + "Returns the artefact UUID for use in message artefact_refs.")
    public Uni<ArtefactDetail> shareData(
            @ToolArg(name = "key", description = "Unique key for this artefact") String key,
            @ToolArg(name = "description", description = "Human-readable description", required = false) String description,
            @ToolArg(name = "created_by", description = "Owner instance identifier") String createdBy,
            @ToolArg(name = "content", description = "Content to store or append") String content,
            @ToolArg(name = "append", description = "Append to existing content (default false)", required = false) Boolean append,
            @ToolArg(name = "last_chunk", description = "Mark artefact complete (default true)", required = false) Boolean lastChunk) {
        boolean doAppend = append != null && append;
        boolean isLastChunk = lastChunk == null || lastChunk;
        return dataService.store(key, description, createdBy, content, doAppend, isLastChunk)
                .map(this::toArtefactDetail);
    }

    @Tool(name = "get_shared_data", description = "Retrieve a shared artefact by key or UUID. Exactly one of key or id must be provided.")
    public Uni<ArtefactDetail> getSharedData(
            @ToolArg(name = "key", description = "Artefact key", required = false) String key,
            @ToolArg(name = "id", description = "Artefact UUID", required = false) String id) {
        boolean hasKey = key != null && !key.isBlank();
        boolean hasId = id != null && !id.isBlank();
        if (!hasKey && !hasId) {
            throw new IllegalArgumentException("Either 'key' or 'id' must be provided");
        }
        Uni<Optional<io.quarkiverse.qhorus.runtime.data.SharedData>> source = hasKey
                ? dataService.getByKey(key)
                : dataService.getByUuid(UUID.fromString(id));
        String lookupDesc = hasKey ? "key=" + key : "id=" + id;
        return source.map(opt -> toArtefactDetail(
                opt.orElseThrow(() -> new IllegalArgumentException("Artefact not found: " + lookupDesc))));
    }

    @Tool(name = "list_shared_data", description = "List all artefacts with metadata.")
    public Uni<List<ArtefactDetail>> listSharedData() {
        return dataService.listAll().map(list -> list.stream().map(this::toArtefactDetail).toList());
    }

    @Tool(name = "claim_artefact", description = "Declare this instance holds a reference to an artefact. Prevents GC.")
    public Uni<String> claimArtefact(
            @ToolArg(name = "artefact_id", description = "Artefact UUID") String artefactId,
            @ToolArg(name = "instance_id", description = "Claiming instance UUID") String instanceId) {
        try {
            UUID aId = UUID.fromString(artefactId);
            UUID iId = UUID.fromString(instanceId);
            return dataService.claim(aId, iId)
                    .map(ignored -> "claimed")
                    .onFailure(e -> e instanceof IllegalArgumentException || e instanceof IllegalStateException)
                    .recoverWithItem(e -> toolError((Exception) e));
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(toolError(e));
        }
    }

    @Tool(name = "release_artefact", description = "Release a reference to an artefact. GC-eligible when all claims released.")
    public Uni<String> releaseArtefact(
            @ToolArg(name = "artefact_id", description = "Artefact UUID") String artefactId,
            @ToolArg(name = "instance_id", description = "Releasing instance UUID") String instanceId) {
        try {
            UUID aId = UUID.fromString(artefactId);
            UUID iId = UUID.fromString(instanceId);
            return dataService.release(aId, iId)
                    .map(ignored -> "released")
                    .onFailure(e -> e instanceof IllegalArgumentException || e instanceof IllegalStateException)
                    .recoverWithItem(e -> toolError((Exception) e));
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(toolError(e));
        }
    }

    // ---------------------------------------------------------------------------
    // Category A: Watchdog tools
    // ---------------------------------------------------------------------------

    @Tool(name = "register_watchdog", description = "Register a condition-based watchdog that posts an alert to a notification channel "
            + "when the condition is met. Condition types: BARRIER_STUCK, APPROVAL_PENDING, AGENT_STALE, CHANNEL_IDLE, QUEUE_DEPTH. "
            + "Requires quarkus.qhorus.watchdog.enabled=true.")
    public Uni<WatchdogSummary> registerWatchdog(
            @ToolArg(name = "condition_type", description = "BARRIER_STUCK | APPROVAL_PENDING | AGENT_STALE | CHANNEL_IDLE | QUEUE_DEPTH") String conditionType,
            @ToolArg(name = "target_name", description = "Channel name, instance_id, or '*' for all") String targetName,
            @ToolArg(name = "threshold_seconds", description = "Time threshold in seconds (for time-based conditions)", required = false) Integer thresholdSeconds,
            @ToolArg(name = "threshold_count", description = "Count threshold (for QUEUE_DEPTH)", required = false) Integer thresholdCount,
            @ToolArg(name = "notification_channel", description = "Channel to post alert events to") String notificationChannel,
            @ToolArg(name = "created_by", description = "Who is registering this watchdog") String createdBy) {
        requireWatchdogEnabled();
        return watchdogService.register(conditionType, targetName, thresholdSeconds, thresholdCount,
                notificationChannel, createdBy)
                .map(this::toWatchdogSummary);
    }

    @Tool(name = "list_watchdogs", description = "List all registered watchdog conditions. "
            + "Requires quarkus.qhorus.watchdog.enabled=true.")
    public Uni<List<WatchdogSummary>> listWatchdogs() {
        requireWatchdogEnabled();
        return watchdogService.listAll().map(list -> list.stream().map(this::toWatchdogSummary).toList());
    }

    @Tool(name = "delete_watchdog", description = "Remove a registered watchdog by its ID. "
            + "Requires quarkus.qhorus.watchdog.enabled=true.")
    public Uni<DeleteWatchdogResult> deleteWatchdog(
            @ToolArg(name = "watchdog_id", description = "UUID of the watchdog to delete") String watchdogId) {
        requireWatchdogEnabled();
        final UUID watchdogUuid;
        try {
            watchdogUuid = UUID.fromString(watchdogId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid watchdog_id '" + watchdogId + "' — must be a UUID (e.g. 550e8400-e29b-41d4-a716-446655440000)");
        }
        return watchdogService.delete(watchdogUuid)
                .map(deleted -> deleted ? new DeleteWatchdogResult(watchdogId, true, "Watchdog " + watchdogId + " deleted")
                        : new DeleteWatchdogResult(watchdogId, false, "Watchdog not found: " + watchdogId));
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    private Uni<List<InstanceInfo>> buildInstanceInfoListReactive(List<Instance> instances) {
        if (instances.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        return Multi.createFrom().iterable(instances)
                .onItem().transformToUniAndConcatenate(i -> instanceService.findCapabilityTagsForInstance(i.instanceId)
                        .map(tags -> new InstanceInfo(i.instanceId, i.description, i.status, tags,
                                i.lastSeen.toString())))
                .collect().asList();
    }

    private void requireWatchdogEnabled() {
        if (!qhorusConfig.watchdog().enabled()) {
            throw new IllegalStateException(
                    "Watchdog module is disabled. Set quarkus.qhorus.watchdog.enabled=true to activate.");
        }
    }

    // Category B @Blocking tools added in Task 2
}
