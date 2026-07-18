package io.casehub.qhorus.runtime.mcp;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.ActorTypeResolver;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.channel.ChannelDetail;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.channel.ChannelSlugValidator;
import io.casehub.qhorus.api.channel.Space;
import io.casehub.qhorus.api.data.SharedData;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.Senders;
import io.casehub.qhorus.api.instance.Instance;
import io.casehub.qhorus.api.instance.InstanceInfo;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.InstanceActorIdProvider;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.CommitmentStore;
import io.casehub.qhorus.api.store.DataStore;
import io.casehub.qhorus.api.store.InstanceStore;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.ReactiveMessageStore;
import io.casehub.qhorus.api.store.WatchdogStore;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.casehub.qhorus.runtime.channel.ReactiveChannelService;
import io.casehub.qhorus.runtime.data.ReactiveDataService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.instance.ReactiveInstanceService;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntry;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntryRepository;
import io.casehub.qhorus.runtime.message.ProjectionRegistry;
import io.casehub.qhorus.runtime.watchdog.ReactiveWatchdogService;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reactive implementation of Qhorus MCP tools.
 * Category A tools (20): pure reactive — instance, observer, channel, data, watchdog.
 * Category B tools (19): {@code @Blocking} wrappers delegating to private {@code blockingXxx} helpers
 * that use blocking services for Panache entity operations and long-poll loops.
 *
 * <p>
 * All business logic exceptions ({@link IllegalArgumentException} and
 * {@link IllegalStateException}) are automatically wrapped in
 * {@link io.quarkiverse.mcp.server.ToolCallException} by the quarkus-mcp-server interceptor.
 */
@McpServer("qhorus")
@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@WrapBusinessError({ IllegalArgumentException.class, IllegalStateException.class })
@ApplicationScoped
public class ReactiveQhorusMcpTools extends QhorusMcpToolsBase {

    private static final Logger LOG = Logger.getLogger(ReactiveQhorusMcpTools.class);

    @Inject
    CurrentPrincipal currentPrincipal;

    // Reactive services (Category A tools)
    @Inject
    ReactiveChannelService channelService;

    @Inject
    ReactiveInstanceService instanceService;

    @Inject
    ReactiveDataService dataService;

    @Inject
    ReactiveWatchdogService watchdogService;

    @Inject
    ReactiveMessageStore messageStore;

    // Non-service beans (blocking in-memory / config)
    @Inject
    io.casehub.qhorus.runtime.config.QhorusConfig qhorusConfig;

    // Blocking services (Category B @Blocking tools)
    @Inject
    io.casehub.qhorus.runtime.channel.ChannelService blockingChannelService;

    @Inject
    io.casehub.qhorus.runtime.instance.InstanceService blockingInstanceService;

    @Inject
    io.casehub.qhorus.runtime.message.MessageService blockingMessageService;

    @Inject
    io.casehub.qhorus.runtime.data.DataService blockingDataService;
    @Inject
    io.casehub.qhorus.runtime.message.TopicService blockingTopicService;


    @Inject
    MessageStore blockingMessageStore;

    @Inject
    ChannelStore channelStore;

    @Inject
    DataStore dataStore;

    @Inject
    InstanceStore instanceStore;

    @Inject
    WatchdogStore watchdogStore;

    @Inject
    MessageLedgerEntryRepository ledgerRepo;

    @Inject
    CommitmentStore commitmentStore;
    @Inject
    io.casehub.qhorus.runtime.channel.ChannelMembershipService membershipService;

    @Inject
    io.casehub.qhorus.api.store.ChannelMembershipStore membershipStore;


    @Inject
    ChannelGateway channelGateway;

    @Inject
    jakarta.enterprise.inject.Instance<io.casehub.qhorus.api.gateway.ChannelBackend> availableBackends;

    @Inject
    InstanceActorIdProvider instanceActorIdProvider;

    @Inject
    ProjectionRegistry projectionRegistry;

    // ---------------------------------------------------------------------------
    // Private reactive helper — resolves channel by UUID or name
    // ---------------------------------------------------------------------------

    private Uni<Channel> resolveChannelAsync(String channel) {
        UUID parsed = ChannelSlugValidator.tryParseUuid(channel);
        if (parsed != null) {
            return channelService.findById(parsed)
                    .map(opt -> opt.orElseThrow(() ->
                            new IllegalArgumentException("Channel not found: " + channel)));
        }
        return channelService.findByName(channel)
                .map(opt -> opt.orElseThrow(() ->
                        new IllegalArgumentException("Channel not found: " + channel)));
    }

    // ---------------------------------------------------------------------------
    // Category A: Instance tools
    // ---------------------------------------------------------------------------

    @Tool(name = "register", description = "Register an agent instance with capability tags. "
            + "Set read_only=true for dashboard/observer instances that only read EVENT messages. "
            + "Returns active channels and online instances as immediate context.")
    public Uni<RegisterResponse> register(
            @ToolArg(name = "instance_id", description = "Unique human-readable identifier for this agent") String instanceId,
            @ToolArg(name = "description", description = "Description of this agent's role") String description,
            @ToolArg(name = "capabilities", description = "Capability tags for peer discovery", required = false) List<String> capabilities,
            @ToolArg(name = "claudony_session_id", description = "Optional Claudony session ID for managed workers", required = false) String claudonySessionId,
            @ToolArg(name = "read_only", description = "If true, instance is read-only: cannot send messages, and check_messages with include_events=true returns EVENT messages. Default false.", required = false) Boolean readOnly) {
        List<String> caps = capabilities != null ? capabilities : List.of();
        boolean ro = readOnly != null && readOnly;
        return instanceService.register(instanceId, description, caps, claudonySessionId, ro)
                .flatMap(instance -> channelService.listAll()
                        .flatMap(channels -> instanceService.listAll()
                                .flatMap(instances -> buildInstanceInfoListReactive(instances)
                                        .map(onlineInstances -> {
                                            List<ChannelSummary> summaries = channels.stream()
                                                    .map(ch -> new ChannelSummary(ch.name(), ch.description(),
                                                            ch.semantic().name()))
                                                    .toList();
                                            return new RegisterResponse(instance.instanceId(), summaries, onlineInstances);
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

    // (Observer tools removed — replaced by read_only flag on Instance)

    // ---------------------------------------------------------------------------
    // Category A: Channel tools
    // ---------------------------------------------------------------------------

    @Tool(name = "create_channel", description = "Create a named channel with declared semantic. "
                                                 + "Semantic defaults to APPEND if not specified. "
                                                 + "Use allowed_types to restrict which MessageType values may be sent to this channel "
                                                 + "(enforced at both MCP and service layers). "
                                                 + "Use denied_types to explicitly block specific types regardless of allowed_types — "
                                                 + "denial wins if a type appears in both. "
                                                 + "Optionally attach a connector binding by supplying all four connector fields together.")
    public Uni<ChannelDetail> createChannel(
            @ToolArg(name = "name", description = "Unique channel name.") String name,
            @ToolArg(name = "description", description = "Channel purpose description") String description,
            @ToolArg(name = "semantic", description = "Channel semantic: APPEND (default), COLLECT, BARRIER, EPHEMERAL, LAST_WRITE", required = false) String semantic,
            @ToolArg(name = "barrier_contributors", description = "Comma-separated contributor names (BARRIER channels only)", required = false) String barrierContributors,
            @ToolArg(name = "allowed_writers", description = "Comma-separated allowed writers.", required = false) String allowedWriters,
            @ToolArg(name = "admin_instances", description = "Comma-separated admin instance IDs.", required = false) String adminInstances,
            @ToolArg(name = "rate_limit_per_channel", description = "Max messages per minute across all senders.", required = false) Integer rateLimitPerChannel,
            @ToolArg(name = "rate_limit_per_instance", description = "Max messages per minute from a single sender.", required = false) Integer rateLimitPerInstance,
            @ToolArg(name = "allowed_types", description = "Comma-separated permitted MessageType names.", required = false) String allowedTypes,
            @ToolArg(name = "denied_types", description = "Comma-separated denied MessageType names.", required = false) String deniedTypes,
            @ToolArg(name = "space_id", description = "Space UUID to place this channel in. Null = top-level channel.", required = false) String spaceId,
            @ToolArg(name = "inbound_connector_id", description = "Inbound connector type identifier.", required = false) String inboundConnectorId,
            @ToolArg(name = "external_key", description = "Connector-specific lookup key.", required = false) String externalKey,
            @ToolArg(name = "outbound_connector_id", description = "Outbound connector type identifier.", required = false) String outboundConnectorId,
            @ToolArg(name = "outbound_destination", description = "Outbound destination address.", required = false) String outboundDestination) {
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
        ChannelCreateRequest req = ChannelCreateRequest.builder(name)
                                                       .description(description)
                                                       .semantic(sem)
                                                       .barrierContributors(splitCsv(barrierContributors))
                                                       .allowedWriters(splitCsv(allowedWriters))
                                                       .adminInstances(splitCsv(adminInstances))
                                                       .rateLimitPerChannel(rateLimitPerChannel)
                                                       .rateLimitPerInstance(rateLimitPerInstance)
                                                       .allowedTypes(MessageType.parseTypes(allowedTypes))
                                                       .deniedTypes(MessageType.parseTypes(deniedTypes))
                                                       .spaceId(spaceId != null ? resolveSpace(spaceId).id() : null)
                                                       .inboundConnectorId(inboundConnectorId)
                                                       .externalKey(externalKey)
                                                       .outboundConnectorId(outboundConnectorId)
                                                       .outboundDestination(outboundDestination)
                                                       .build();
        if (req.hasConnectorBinding()) {
            return Uni.createFrom().item(() -> blockingChannelService.create(req))
                      .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                      .flatMap(ch -> messageStore.countByChannel(ch.id())
                                                 .map(count -> toChannelDetail(ch, count.longValue())));
        }
        return channelService.create(req)
                             .invoke(ch -> channelGateway.initChannel(ch.id(), new ChannelRef(ch.id(), ch.name())))
                             .flatMap(ch -> messageStore.countByChannel(ch.id())
                                                        .map(count -> toChannelDetail(ch, count.longValue())));
    }

    @Tool(name = "update_channel_binding", description = "Update the outbound connector fields of an existing channel binding. "
            + "Use this to rotate an outbound destination (e.g. a refreshed Slack webhook URL or a new phone number) "
            + "without a service restart. Fires ChannelInitialisedEvent to refresh in-memory caches.")
    @Blocking
    public ChannelDetail updateChannelBinding(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "outbound_connector_id", description = "New outbound connector identifier") String outboundConnectorId,
            @ToolArg(name = "outbound_destination", description = "New outbound destination (e.g. webhook URL, phone number)") String outboundDestination) {
        Channel ch = resolveChannel(channel);
        blockingChannelService.updateConnectorBinding(ch.id(), outboundConnectorId, outboundDestination);
        return toChannelDetail(ch, 0L);
    }

    @Tool(name = "set_channel_rate_limits", description = "Update the rate limits on an existing channel. "
            + "Pass null to remove a limit (restores unrestricted behaviour). "
            + "Limits are enforced via an in-memory sliding 60-second window that resets on restart.")
    public Uni<ChannelDetail> setChannelRateLimits(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "rate_limit_per_channel", description = "Max messages per minute across all senders. Null = unlimited.", required = false) Integer rateLimitPerChannel,
            @ToolArg(name = "rate_limit_per_instance", description = "Max messages per minute from a single sender. Null = unlimited.", required = false) Integer rateLimitPerInstance) {
        return resolveChannelAsync(channel)
                .flatMap(resolved -> channelService.setRateLimits(resolved.id(), rateLimitPerChannel, rateLimitPerInstance))
                .flatMap(ch -> messageStore.countByChannel(ch.id())
                        .map(count -> toChannelDetail(ch, count.longValue())));
    }

    @Tool(name = "set_channel_writers", description = "Update the write ACL on an existing channel. "
            + "Pass null or blank to open the channel to all writers.")
    public Uni<ChannelDetail> setChannelWriters(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "allowed_writers", description = "Comma-separated allowed writers (instance IDs and/or capability:tag / role:name). Null = open to all.", required = false) String allowedWriters) {
        return resolveChannelAsync(channel)
                .flatMap(resolved -> channelService.setAllowedWriters(resolved.id(), splitCsv(allowedWriters)))
                .flatMap(ch -> messageStore.countByChannel(ch.id())
                        .map(count -> toChannelDetail(ch, count.longValue())));
    }

    @Tool(name = "set_channel_admins", description = "Update the admin instance list on an existing channel. "
            + "Admins may invoke pause_channel, resume_channel, force_release_channel, and clear_channel. "
            + "Pass null or blank to open management to any caller.")
    public Uni<ChannelDetail> setChannelAdmins(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "admin_instances", description = "Comma-separated instance IDs permitted to manage this channel. Null = open to any caller.", required = false) String adminInstances) {
        return resolveChannelAsync(channel)
                .flatMap(resolved -> channelService.setAdminInstances(resolved.id(), splitCsv(adminInstances)))
                .flatMap(ch -> messageStore.countByChannel(ch.id())
                        .map(count -> toChannelDetail(ch, count.longValue())));
    }

    @Tool(name = "set_channel_type_constraints",
            description = "Replace the allowed_types and denied_types constraints on an existing channel. "
                    + "This is a full-replacement operation: both fields are overwritten on every call. "
                    + "Pass null for a field to clear the constraint; pass the current value to preserve it. "
                    + "Constraint enforcement is type-discriminated: COMMAND and QUERY are hard-enforced "
                    + "(a violation throws and the message is not dispatched — these types create Commitments; "
                    + "wrong-channel dispatch creates orphan obligations). All other types are advisory: "
                    + "a violation warning is returned in the advisories field of the dispatch result and "
                    + "the message is dispatched. Denial wins over allowed_types when both are set. "
                    + "Constraints are prospective only — messages already in the channel are unaffected.")
    public Uni<ChannelDetail> setChannelTypeConstraints(
            @ToolArg(name = "channel",
                     description = "Channel name or UUID") String channel,
            @ToolArg(name = "allowed_types",
                     description = "Comma-separated MessageType names permitted on this channel. "
                             + "Null = clear allowed_types (all types permitted). "
                             + "Example: \"EVENT\" for a telemetry-only observe channel.",
                     required = false) String allowedTypes,
            @ToolArg(name = "denied_types",
                     description = "Comma-separated MessageType names explicitly denied on this channel. "
                             + "Null = clear denied_types. "
                             + "Example: \"EVENT\" for an oversight channel open to all agent messages but not telemetry.",
                     required = false) String deniedTypes) {
        return resolveChannelAsync(channel)
                .flatMap(ch -> channelService.setTypeConstraints(ch.id(),
                        MessageType.parseTypes(allowedTypes), MessageType.parseTypes(deniedTypes)))
                .flatMap(ch -> messageStore.countByChannel(ch.id())
                        .map(count -> toChannelDetail(ch, count.longValue())));
    }

    @Tool(name = "list_channels", description = "List all channels with message count and last activity.")
    public Uni<List<ChannelDetail>> listChannels() {
        return Uni.createFrom().item(bindingStore::findAll)
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .flatMap(allBindings -> channelService.listAll().flatMap(channels -> {
                    if (channels.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }
                    List<Uni<ChannelDetail>> unis = channels.stream()
                            .map(ch -> messageStore.countByChannel(ch.id())
                                    .map(count -> toChannelDetail(ch, count.longValue(), allBindings)))
                            .toList();
                    return Uni.join().all(unis).andFailFast();
                }));
    }

    @Tool(name = "find_channel", description = "Search channels by keyword in name or description.")
    public Uni<List<ChannelDetail>> findChannel(
            @ToolArg(name = "keyword", description = "Search term (case-insensitive)") String keyword) {
        String lowerKeyword = keyword.toLowerCase();
        return channelService.listAll().flatMap(channels -> {
            // In-memory filter: ReactiveChannelQuery does not support OR-predicate across name+description.
            // Acceptable for typical channel counts; future improvement: add keyword search to ReactiveChannelStore.
            List<Channel> matches = channels.stream()
                                                  .filter(ch -> (ch.name() != null && ch.name().toLowerCase().contains(lowerKeyword))
                            || (ch.description() != null && ch.description().toLowerCase().contains(lowerKeyword)))
                                                  .toList();
            if (matches.isEmpty()) {
                return Uni.createFrom().item(List.of());
            }
            List<Uni<ChannelDetail>> unis = matches.stream()
                    .map(ch -> messageStore.countByChannel(ch.id())
                            .map(count -> toChannelDetail(ch, count.longValue())))
                    .toList();
            return Uni.join().all(unis).andFailFast();
        });
    }

    @Tool(name = "pause_channel", description = "Pause a channel — blocks send_message and returns empty on check_messages. "
            + "Idempotent. Use to stop agent work flowing through a channel for human review.")
    public Uni<ChannelDetail> pauseChannel(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "caller_instance_id", description = "Instance ID of the caller. Required when the channel has an admin_instances list.", required = false) String callerInstanceId) {
        return resolveChannelAsync(channel)
                .invoke(ch -> checkAdminAccess(ch, callerInstanceId, "pause_channel"))
                .flatMap(ch -> channelService.pause(ch.id()))
                .flatMap(ch -> messageStore.countByChannel(ch.id())
                        .map(count -> toChannelDetail(ch, count.longValue())));
    }

    @Tool(name = "resume_channel", description = "Resume a paused channel — re-enables send_message and check_messages. "
            + "Idempotent.")
    public Uni<ChannelDetail> resumeChannel(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "caller_instance_id", description = "Instance ID of the caller. Required when the channel has an admin_instances list.", required = false) String callerInstanceId) {
        return resolveChannelAsync(channel)
                .invoke(ch -> checkAdminAccess(ch, callerInstanceId, "resume_channel"))
                .flatMap(ch -> channelService.resume(ch.id()))
                .flatMap(ch -> messageStore.countByChannel(ch.id())
                        .map(count -> toChannelDetail(ch, count.longValue())));
    }

    @Tool(name = "delete_channel", description = "Delete a named channel. "
            + "Rejects with an error if the channel has messages unless force=true. "
            + "When force=true, all messages in the channel are deleted before the channel is removed. "
            + "Subject to admin_instances check if the channel has an admin list.")
    public Uni<DeleteChannelResult> deleteChannel(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "force", description = "When true, deletes all messages in the channel then "
                    + "deletes the channel. When false (default), rejects if messages exist.", required = false) Boolean force,
            @ToolArg(name = "caller_instance_id", description = "Instance ID of the caller. Required when the channel has an admin_instances list.", required = false) String callerInstanceId) {
        return resolveChannelAsync(channel)
                .invoke(ch -> checkAdminAccess(ch, callerInstanceId, "delete_channel"))
                .invoke(ch -> membershipStore.deleteAll(ch.id()))
                .invoke(ch -> commitmentStore.deleteAll(ch.id()))
                .flatMap(ch -> channelService.delete(ch.id(), Boolean.TRUE.equals(force))
                        .invoke(ignored -> channelGateway.closeChannel(ch.id(), new ChannelRef(ch.id(), ch.name())))
                        .map(deleted -> new DeleteChannelResult(ch.name(), deleted, "deleted")));
    }

    @Tool(name = "list_backends", description = "List all registered channel backends for a channel. "
            + "Always includes 'qhorus-internal' (the Qhorus agent backend). "
            + "External backends (human-participating, human-observer) appear after registration.")
    public Uni<List<BackendInfo>> listBackends(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel) {
        return resolveChannelAsync(channel)
                .map(ch -> channelGateway.listBackends(ch.id()).stream()
                        .map(r -> new BackendInfo(r.backendId(), r.backendType(),
                                r.actorType().name().toLowerCase()))
                        .toList());
    }

    @Tool(name = "deregister_backend", description = "Remove a registered backend from a channel. "
            + "Cannot remove 'qhorus-internal'.")
    public Uni<DeregisterBackendResult> deregisterBackend(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "backend_id", description = "ID of the backend to remove") String backendId) {
        return resolveChannelAsync(channel)
                .map(ch -> {
                    channelGateway.deregisterBackend(ch.id(), backendId);
                    return new DeregisterBackendResult(ch.name(), backendId, true,
                            "Backend " + backendId + " deregistered from " + ch.name());
                });
    }

    @Tool(name = "register_backend", description = "Associate a CDI-registered channel backend with a channel. "
            + "The backend must already exist as a CDI bean (identified by its backendId). "
            + "backend_type: 'human_participating' (at most one per channel) or 'human_observer' (unlimited). "
            + "Cannot register 'qhorus-internal' as a human backend — it is always the agent backend.")
    public Uni<RegisterBackendResult> registerBackend(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "backend_id", description = "backendId() of the CDI backend to register") String backendId,
            @ToolArg(name = "backend_type", description = "human_participating or human_observer") String backendType) {
        if (!"human_participating".equals(backendType) && !"human_observer".equals(backendType)) {
            throw new IllegalArgumentException(
                    "Invalid backend_type '" + backendType + "' — must be 'human_participating' or 'human_observer'");
        }
        if ("qhorus-internal".equals(backendId)) {
            throw new IllegalArgumentException(
                    "Cannot register 'qhorus-internal' as a human backend — it is always the agent backend.");
        }
        return resolveChannelAsync(channel)
                .map(ch -> {
                    io.casehub.qhorus.api.gateway.ChannelBackend backend =
                            java.util.stream.StreamSupport.stream(availableBackends.spliterator(), false)
                                    .filter(b -> backendId.equals(b.backendId()))
                                    .findFirst()
                                    .orElseThrow(() -> new IllegalArgumentException(
                                            "No CDI backend registered with backendId: " + backendId
                                            + ". Ensure the backend bean is deployed and its backendId() returns '"
                                            + backendId + "'."));
                    channelGateway.registerBackend(ch.id(), backend, backendType);
                    return new RegisterBackendResult(ch.name(), backendId, backendType,
                            "Backend " + backendId + " registered as " + backendType + " on channel " + ch.name());
                });
    }

    // ---------------------------------------------------------------------------
    // Category A: Data tools
    // ---------------------------------------------------------------------------

    @Tool(name = "share_artefact", description = "Store a large artefact by key. "
            + "Supports chunked upload via append=true; last_chunk=true marks the artefact complete. "
            + "Returns the artefact UUID for use in message artefact_refs.")
    public Uni<ArtefactDetail> shareArtefact(
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

    @Tool(name = "begin_artefact", description = "Begin a chunked artefact upload. "
            + "Creates the artefact in incomplete state with the first chunk of content. "
            + "Follow with append_chunk for additional chunks and finalize_artefact to complete.")
    public Uni<ArtefactDetail> beginArtefact(
            @ToolArg(name = "key", description = "Unique key for this artefact") String key,
            @ToolArg(name = "description", description = "Human-readable description", required = false) String description,
            @ToolArg(name = "created_by", description = "Owner instance identifier") String createdBy,
            @ToolArg(name = "content", description = "First chunk of content") String content) {
        return dataService.store(key, description, createdBy, content, false, false)
                .map(this::toArtefactDetail);
    }

    @Tool(name = "append_chunk", description = "Append a chunk to an in-progress artefact upload. "
            + "The artefact must have been created with begin_artefact and not yet finalized.")
    public Uni<ArtefactDetail> appendChunk(
            @ToolArg(name = "key", description = "Artefact key (from begin_artefact)") String key,
            @ToolArg(name = "content", description = "Content chunk to append") String content) {
        return dataService.store(key, null, null, content, true, false)
                .map(this::toArtefactDetail);
    }

    @Tool(name = "finalize_artefact", description = "Finalize a chunked artefact upload, optionally appending a last chunk. "
            + "Marks the artefact complete. Returns the final artefact UUID for use in message artefact_refs.")
    public Uni<ArtefactDetail> finalizeArtefact(
            @ToolArg(name = "key", description = "Artefact key (from begin_artefact)") String key,
            @ToolArg(name = "content", description = "Optional final chunk of content to append", required = false) String content) {
        String chunk = content != null ? content : "";
        return dataService.store(key, null, null, chunk, true, true)
                .map(this::toArtefactDetail);
    }

    @Tool(name = "get_artefact", description = "Retrieve a shared artefact by key or UUID. Exactly one of key or id must be provided.")
    public Uni<ArtefactDetail> getArtefact(
            @ToolArg(name = "key", description = "Artefact key", required = false) String key,
            @ToolArg(name = "id", description = "Artefact UUID", required = false) String id) {
        boolean hasKey = key != null && !key.isBlank();
        boolean hasId = id != null && !id.isBlank();
        if (!hasKey && !hasId) {
            throw new IllegalArgumentException("Either 'key' or 'id' must be provided");
        }
        Uni<Optional<SharedData>> source = hasKey
                ? dataService.getByKey(key)
                : dataService.getByUuid(UUID.fromString(id));
        String lookupDesc = hasKey ? "key=" + key : "id=" + id;
        return source.map(opt -> toArtefactDetail(
                opt.orElseThrow(() -> new IllegalArgumentException("Artefact not found: " + lookupDesc))));
    }

    @Tool(name = "get_artefact_refs", description = "Get artefact references attached to a message.")
    @Blocking
    public java.util.List<io.casehub.qhorus.api.message.ArtefactRef> getArtefactRefs(
            @ToolArg(name = "message_id", description = "Message ID") Long messageId) {
        io.casehub.qhorus.api.message.Message msg = blockingMessageStore.find(messageId)
                                                                        .orElseThrow(() -> new IllegalArgumentException("Message not found: " + messageId));
        return msg.artefactRefs() != null ? msg.artefactRefs() : java.util.List.of();
    }

    @Tool(name = "join_channel", description = "Join a channel as a member.")
    @Blocking
    public MembershipSummary joinChannel(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "sender", description = "Instance ID of the joining member") String sender,
            @ToolArg(name = "role", description = "PARTICIPANT, OBSERVER, or MODERATOR. Defaults to PARTICIPANT.", required = false) String role) {
        io.casehub.qhorus.api.channel.Channel ch = resolveChannel(channel);
        io.casehub.qhorus.api.channel.MemberRole memberRole = (role != null && !role.isBlank())
                                                              ? io.casehub.qhorus.api.channel.MemberRole.valueOf(role.toUpperCase())
                                                              : io.casehub.qhorus.api.channel.MemberRole.PARTICIPANT;
        io.casehub.qhorus.api.channel.ChannelMembership m = membershipService.join(
                ch.id(), sender, memberRole, currentPrincipal.tenancyId());
        return new MembershipSummary(ch.id().toString(), ch.name(), m.memberId(),
                                     m.role().name(), m.joinedAt().toString(), m.lastReadMessageId());
    }

    @Tool(name = "leave_channel", description = "Leave a channel, removing membership.")
    @Blocking
    public String leaveChannel(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "sender", description = "Instance ID of the leaving member") String sender) {
        io.casehub.qhorus.api.channel.Channel ch = resolveChannel(channel);
        membershipService.leave(ch.id(), sender);
        return sender + " left " + ch.name();
    }

    @Tool(name = "list_members", description = "List all members of a channel with their roles.")
    @Blocking
    public java.util.List<MembershipSummary> listMembers(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel) {
        io.casehub.qhorus.api.channel.Channel ch = resolveChannel(channel);
        return membershipService.listMembers(ch.id()).stream()
                                .map(m -> new MembershipSummary(ch.id().toString(), ch.name(), m.memberId(),
                                                                m.role().name(), m.joinedAt().toString(), m.lastReadMessageId()))
                                .toList();
    }

    @Tool(name = "mark_channel_read", description = "Mark a channel as read up to a message ID. Null = latest.")
    @Blocking
    public String markChannelRead(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "sender", description = "Instance ID of the member") String sender,
            @ToolArg(name = "message_id", description = "Message ID to mark read up to. Null = latest.", required = false) Long messageId) {
        io.casehub.qhorus.api.channel.Channel ch          = resolveChannel(channel);
        Long                                  effectiveId = messageId;
        if (effectiveId == null) {
            effectiveId = blockingMessageStore.findLastMessage(ch.id()).map(io.casehub.qhorus.api.message.Message::id).orElse(0L);
        }
        membershipService.markRead(ch.id(), sender, effectiveId);
        return "Marked " + ch.name() + " read up to message " + effectiveId;
    }

    @Tool(name = "get_unread_counts", description = "Get unread counts across all channels for a member.")
    @Blocking
    public java.util.List<io.casehub.qhorus.api.channel.UnreadCount> getUnreadCounts(
            @ToolArg(name = "sender", description = "Instance ID of the member") String sender) {
        return new java.util.ArrayList<>(membershipService.getUnreadCounts(sender, currentPrincipal.tenancyId()).values());
    }

    @Tool(name = "create_space", description = "Create an organizational space to group related channels.")
    public Uni<Space> createSpace(
            @ToolArg(name = "name", description = "Space name (free-form text, max 200 chars)") String name,
            @ToolArg(name = "description", description = "Space purpose description", required = false) String description,
            @ToolArg(name = "parent_space_id", description = "Parent space UUID for nesting. Null = root space.", required = false) String parentSpaceId) {
        UUID parentId = ChannelSlugValidator.tryParseUuid(parentSpaceId);
        return Uni.createFrom().<Space>item(() -> spaceService.create(new io.casehub.qhorus.api.channel.SpaceCreateRequest(name, description, parentId)))
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Tool(name = "list_spaces", description = "List spaces. Without parent_space_id, returns root spaces.")
    public Uni<List<Space>> listSpaces(
            @ToolArg(name = "parent_space_id", description = "Parent space UUID or name. Null = list root spaces.", required = false) String parentSpaceId) {
        return Uni.createFrom().item(() -> {
            if (parentSpaceId == null) {return spaceService.listRoots();}
            return spaceService.listChildren(resolveSpace(parentSpaceId).id());
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Tool(name = "list_space_channels", description = "List all channels belonging to a space.")
    public Uni<List<ChannelDetail>> listSpaceChannels(
            @ToolArg(name = "space", description = "Space UUID or name") String space) {
        return Uni.createFrom().item(() -> {
            UUID          sid      = resolveSpace(space).id();
            List<Channel> channels = spaceService.listChannels(sid);
            return channels.stream().map(ch -> toChannelDetail(ch, blockingMessageStore.count(
                    MessageQuery.builder().channelId(ch.id()).build()))).toList();
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Tool(name = "delete_space", description = "Delete a space. Fails if the space contains channels or child spaces.")
    public Uni<DeleteSpaceResult> deleteSpace(
            @ToolArg(name = "space", description = "Space UUID or name to delete") String space) {
        return Uni.createFrom().item(() -> {
            Space s = resolveSpace(space);
            spaceService.delete(s.id());
            return new DeleteSpaceResult(s.id().toString(), true);
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Tool(name = "get_space", description = "Get a space by UUID or name.")
    public Uni<Space> getSpace(
            @ToolArg(name = "space", description = "Space UUID or name") String space) {
        return Uni.createFrom().item(() -> resolveSpace(space))
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Tool(name = "rename_space", description = "Rename a space.")
    public Uni<Space> renameSpace(
            @ToolArg(name = "space", description = "Space UUID or name") String space,
            @ToolArg(name = "new_name", description = "New space name") String newName) {
        return Uni.createFrom().item(() -> spaceService.rename(resolveSpace(space).id(), newName))
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Tool(name = "update_space_description", description = "Update a space's description. Null clears the description.")
    public Uni<Space> updateSpaceDescription(
            @ToolArg(name = "space", description = "Space UUID or name") String space,
            @ToolArg(name = "description", description = "New description. Null clears it.", required = false) String description) {
        return Uni.createFrom().item(() -> spaceService.updateDescription(resolveSpace(space).id(), description))
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Tool(name = "move_space", description = "Move a space to a new parent. Null parent_space_id makes it a root space.")
    public Uni<Space> moveSpace(
            @ToolArg(name = "space", description = "Space UUID or name to move") String space,
            @ToolArg(name = "parent_space_id", description = "New parent space UUID or name. Null = make root.", required = false) String parentSpaceId) {
        return Uni.createFrom().item(() -> {
            UUID parentId = parentSpaceId != null ? resolveSpace(parentSpaceId).id() : null;
            return spaceService.moveSpace(resolveSpace(space).id(), parentId);
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Tool(name = "move_channel_to_space", description = "Move a channel into a space. Null space_id removes from space.")
    public Uni<ChannelDetail> moveChannelToSpace(
            @ToolArg(name = "channel", description = "Channel UUID or name") String channel,
            @ToolArg(name = "space_id", description = "Target space UUID or name. Null = remove from space.", required = false) String spaceId) {
        return Uni.createFrom().item(() -> {
            UUID    sid     = spaceId != null ? resolveSpace(spaceId).id() : null;
            Channel updated = spaceService.moveChannelToSpace(resolveChannel(channel).id(), sid);
            return toChannelDetail(updated, blockingMessageStore.count(
                    MessageQuery.builder().channelId(updated.id()).build()));
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }


    @Tool(name = "list_artefacts", description = "List all artefacts with metadata.")
    public Uni<List<ArtefactDetail>> listArtefacts() {
        return dataService.listAll().map(list -> list.stream().map(this::toArtefactDetail).toList());
    }

    @Tool(name = "claim_artefact", description = "Manually claim an artefact reference. Prevents GC. "
            + "Usually not needed — send_message with artefact_refs auto-claims for the sender.")
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

    @Tool(name = "release_artefact", description = "Manually release an artefact reference. GC-eligible when all claims released. "
            + "Usually not needed — commitment resolution (RESPONSE/DONE/DECLINE/FAILURE) auto-releases.")
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

    @Tool(name = "register_watchdog", description = "Register a watchdog condition that fires alert events to a notification channel "
                                                    + "when the condition is met. Condition types: BARRIER_STUCK, APPROVAL_PENDING, AGENT_STALE, CHANNEL_IDLE, QUEUE_DEPTH, "
                                                    + "CONTEXT_PRESSURE, LOOP_DETECTED, OBLIGATION_FAN_OUT, CONVERSATION_STALL, ECHO_CHAMBER. "
                                                    + "Requires casehub.qhorus.watchdog.enabled=true.")
    public Uni<WatchdogSummary> registerWatchdog(
            @ToolArg(name = "condition_type", description = "BARRIER_STUCK | APPROVAL_PENDING | AGENT_STALE | CHANNEL_IDLE | QUEUE_DEPTH | CONTEXT_PRESSURE | LOOP_DETECTED | OBLIGATION_FAN_OUT | CONVERSATION_STALL | ECHO_CHAMBER") String conditionType,
            @ToolArg(name = "target_name", description = "Channel name, instance_id, or '*' for all") String targetName,
            @ToolArg(name = "threshold_seconds", description = "Time threshold in seconds", required = false) Integer thresholdSeconds,
            @ToolArg(name = "threshold_count", description = "Count threshold", required = false) Integer thresholdCount,
            @ToolArg(name = "similarity_pct", description = "Content similarity percentage threshold 0-100 (for LOOP_DETECTED, ECHO_CHAMBER)", required = false) Integer similarityPct,
            @ToolArg(name = "notification_channel", description = "Channel to post alert events to") String notificationChannel,
            @ToolArg(name = "created_by", description = "Who is registering this watchdog") String createdBy) {
        requireWatchdogEnabled();
        String tenancyId = currentPrincipal.tenancyId();
        return watchdogService.register(conditionType, targetName, thresholdSeconds, thresholdCount,
                                        similarityPct, notificationChannel, createdBy, tenancyId)
                              .map(this::toWatchdogSummary);
    }

    @Tool(name = "list_watchdogs", description = "List all registered watchdog conditions. "
            + "Requires casehub.qhorus.watchdog.enabled=true.")
    public Uni<List<WatchdogSummary>> listWatchdogs() {
        requireWatchdogEnabled();
        // Capture tenancyId on the calling thread (request context active here; not safe inside Uni pipeline)
        String tenancyId = currentPrincipal.tenancyId();
        return watchdogService.listByTenant(tenancyId).map(list -> list.stream().map(this::toWatchdogSummary).toList());
    }

    @Tool(name = "delete_watchdog", description = "Remove a registered watchdog by its ID. "
            + "Requires casehub.qhorus.watchdog.enabled=true.")
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
                .onItem().transformToUniAndConcatenate(i -> instanceService.findCapabilityTagsForInstance(i.instanceId())
                        .map(tags -> new InstanceInfo(i.instanceId(), i.description(), i.status(), tags,
                                i.lastSeen().toString(), i.readOnly())))
                .collect().asList();
    }

    private void requireWatchdogEnabled() {
        if (!qhorusConfig.watchdog().enabled()) {
            throw new IllegalStateException(
                    "Watchdog module is disabled. Set casehub.qhorus.watchdog.enabled=true to activate.");
        }
    }

    // ---------------------------------------------------------------------------
    // Category B: @Blocking tools (Task 2)
    // ---------------------------------------------------------------------------

    // 2. force_release_channel
    @Tool(name = "force_release_channel", description = "Force-deliver all accumulated messages and clear a BARRIER or COLLECT channel, "
            + "bypassing normal release conditions. Use when a BARRIER is stuck (missing contributors) "
            + "or to collect early from a COLLECT channel. Posts an audit event. "
            + "Only valid for BARRIER and COLLECT semantics.")
    @Transactional
    @Blocking
    public Uni<ForceReleaseResult> forceReleaseChannel(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "reason", description = "Reason for the force-release (recorded in audit event)", required = false) String reason,
            @ToolArg(name = "caller_instance_id", description = "Instance ID of the caller. Required when the channel has an admin_instances list.", required = false) String callerInstanceId) {
        Channel ch = resolveChannel(channel);
        return Uni.createFrom().item(() -> blockingForceReleaseChannel(ch.name(), reason, callerInstanceId));
    }

    private ForceReleaseResult blockingForceReleaseChannel(String channelName, String reason, String callerInstanceId) {
        Channel ch = blockingChannelService.findByName(channelName)
                                           .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));
        checkAdminAccess(ch, callerInstanceId, "force_release_channel");

        if (ch.semantic() != ChannelSemantic.BARRIER && ch.semantic() != ChannelSemantic.COLLECT) {
            throw new IllegalArgumentException(
                    "force_release_channel only applies to BARRIER and COLLECT channels, not "
                    + ch.semantic().name());
        }

        List<Message> messages = blockingMessageStore.scan(MessageQuery.builder().channelId(ch.id()).excludeTypes(List.of(MessageType.EVENT)).build());
        blockingMessageStore.deleteNonEvent(ch.id());

        String auditTelemetry = (reason != null && !reason.isBlank())
                                ? telemetryJson("action", "force_release_channel", "reason", reason)
                                : telemetryJson("action", "force_release_channel");
        blockingMessageService.dispatch(MessageDispatch.builder()
                                                       .channelId(ch.id()).sender("system").type(MessageType.EVENT)
                                                       .telemetry(auditTelemetry).actorType(ActorType.SYSTEM).build());

        blockingChannelService.updateLastActivity(ch.id(), ch.tenancyId());

        List<MessageSummary> summaries = messages.stream().map(this::toMessageSummary).toList();
        return new ForceReleaseResult(ch.name(), ch.semantic().name(), messages.size(), summaries);}

    // 3. send_message
    @Tool(name = "send_message", description = "Post a typed message to a channel. "
            + "For QUERY and COMMAND types, correlation_id is auto-generated if not supplied.")
    @Transactional
    @Blocking
    public Uni<DispatchResult> sendMessage(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "sender", description = "Sender identifier") String sender,
            @ToolArg(name = "type", description = "The message type. Choose: QUERY (asking for information, no side effects), COMMAND (asking for action to be taken, side effects expected), RESPONSE (answering a QUERY, carries correlationId), STATUS (reporting progress on a COMMAND, extends deadline), DECLINE (refusing a QUERY or COMMAND, content must explain why), HANDOFF (transferring obligation to another agent, target required), DONE (signalling successful completion of a COMMAND), FAILURE (signalling unsuccessful termination, content must explain why), EVENT (telemetry only, not delivered to agents)") String type,
            @ToolArg(name = "content", description = "Message content") String content,
            @ToolArg(name = "correlation_id", description = "Correlation ID (auto-generated for QUERY and COMMAND if omitted)", required = false) String correlationId,
            @ToolArg(name = "in_reply_to", description = "ID of the message being replied to", required = false) Long inReplyTo,
            @ToolArg(name = "artefact_refs", description = "UUIDs of shared artefacts to attach. Auto-claims each artefact for the sender; auto-released on commitment resolution (RESPONSE/DONE/DECLINE/FAILURE).", required = false) List<String> artefactRefs,
            @ToolArg(name = "target", description = "Addressing target: instance:<id>, capability:<tag>, or role:<name>. Null/omitted = broadcast to all.", required = false) String target,
            @ToolArg(name = "deadline", description = "Optional deadline as ISO-8601 duration (e.g. PT30M for 30 minutes). Only meaningful for QUERY and COMMAND. Defaults to channel config when not provided.", required = false) String deadline,
            @ToolArg(name = "subject_id", description = "Optional UUID of the domain aggregate this message concerns (for ledger indexing).", required = false) String subjectId,
            @ToolArg(name = "caused_by_entry_id", description = "Optional UUID of the ledger entry that triggered this dispatch (for causal chain tracing).", required = false) String causedByEntryId) {
        Channel ch = resolveChannel(channel);
        return Uni.createFrom().item(
                () -> blockingSendMessage(ch.name(), sender, type, content, correlationId, inReplyTo, artefactRefs, target,
                        deadline, subjectId, causedByEntryId));
    }

    private DispatchResult blockingSendMessage(String channelName, String sender, String type, String content,
            String correlationId, Long inReplyTo, List<String> artefactRefs, String target, String deadline,
            String subjectId, String causedByEntryId) {
        Channel ch = blockingChannelService.findByName(channelName)
                                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));

        // Read-only instance check — read_only instances cannot send any messages (MCP-specific)
        blockingInstanceService.findByInstanceId(sender).ifPresent(inst -> {
            if (inst.readOnly()) {
                throw new IllegalStateException(
                        "Instance '" + sender + "' is read-only and cannot send messages. "
                                + "Use check_messages with include_events=true to receive EVENT messages.");
            }
        });

        MessageType msgType = MessageType.valueOf(type.toUpperCase());

        if (msgType.requiresContent() && (content == null || content.isBlank())) {
            throw new IllegalArgumentException(msgType.name() + " requires non-empty content explaining the reason.");
        }
        if (msgType.requiresTarget() && (target == null || target.isBlank())) {
            throw new IllegalArgumentException(
                    "HANDOFF requires a non-null target (instance:id, capability:tag, or role:name).");
        }

        String corrId = correlationId;
        if (corrId == null && msgType.requiresCorrelationId()) {
            corrId = java.util.UUID.randomUUID().toString();
        }

        // Parse optional UUID params — fail early if malformed
        final UUID subjectIdUuid = parseOptionalUuid("subject_id", subjectId);
        final UUID causedByEntryIdUuid = parseOptionalUuid("caused_by_entry_id", causedByEntryId);

        // Build ArtefactRef list — selective validation: UUID refs validated against SharedData, non-UUID bypass
        java.util.List<io.casehub.qhorus.api.message.ArtefactRef> refsList = null;
        if (artefactRefs != null && !artefactRefs.isEmpty()) {
            java.util.List<io.casehub.qhorus.api.message.ArtefactRef> built = new java.util.ArrayList<>(artefactRefs.size());
            java.util.List<java.util.UUID> uuidRefs = new java.util.ArrayList<>();
            for (String ref : artefactRefs) {
                try {
                    java.util.UUID parsed = java.util.UUID.fromString(ref);
                    uuidRefs.add(parsed);
                    built.add(new io.casehub.qhorus.api.message.ArtefactRef(ref, io.casehub.qhorus.api.message.ArtefactType.DOCUMENT, null, null));
                } catch (IllegalArgumentException e) {
                    built.add(new io.casehub.qhorus.api.message.ArtefactRef(ref, io.casehub.qhorus.api.message.ArtefactType.EXTERNAL, null, null));
                }
            }
            if (!uuidRefs.isEmpty()) {
                java.util.List<java.util.UUID> found = dataStore.findByIds(uuidRefs).stream().map(SharedData::id).toList();
                java.util.List<java.util.UUID> unknown = uuidRefs.stream().filter(u -> !found.contains(u)).toList();
                if (!unknown.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Unknown artefact ref(s): " + unknown.stream().map(java.util.UUID::toString).collect(java.util.stream.Collectors.joining(", ")));
                }
                blockingInstanceService.findByInstanceId(sender).ifPresent(inst -> {
                    for (java.util.UUID uuid : uuidRefs) {
                        blockingDataService.claim(uuid, inst.id());
                    }
                });
            }
            refsList = java.util.List.copyOf(built);
        }

        // Validate and normalise target — null/blank → no addressing (broadcast)
        String normalisedTarget = (target == null || target.isBlank()) ? null : target.strip();
        if (normalisedTarget != null) {
            if (!normalisedTarget.startsWith("instance:") &&
                    !normalisedTarget.startsWith("capability:") &&
                    !normalisedTarget.startsWith("role:")) {
                throw new IllegalArgumentException(
                        "Invalid target format: '" + normalisedTarget
                                + "'. Must be instance:<id>, capability:<tag>, or role:<name>.");
            }
            String valuePart = normalisedTarget.substring(normalisedTarget.indexOf(':') + 1);
            if (valuePart.isBlank()) {
                throw new IllegalArgumentException(
                        "Invalid target format: '" + normalisedTarget
                                + "'. Value after prefix cannot be empty.");
            }
        }

        ActorType resolvedActorType =
                ActorTypeResolver.resolve(instanceActorIdProvider.resolve(sender));

        DispatchResult dispatchResult = blockingMessageService.dispatch(
                MessageDispatch.builder()
                        .channelId(ch.id())
                        .sender(sender)
                        .type(msgType)
                        .content(content)
                        .correlationId(corrId)
                        .inReplyTo(inReplyTo)
                        .artefactRefs(refsList)
                        .target(normalisedTarget)
                        .subjectId(subjectIdUuid)
                        .causedByEntryId(causedByEntryIdUuid)
                        .actorType(resolvedActorType)
                        .build());

        if (deadline != null && !deadline.isBlank() && msgType.requiresCorrelationId()) {
            Message msg = blockingMessageService.findById(dispatchResult.messageId()).orElseThrow();
            blockingMessageStore.put(msg.toBuilder()
                    .deadline(java.time.Instant.now().plus(java.time.Duration.parse(deadline))).build());
        }

        // Auto-release artefact claims when a commitment resolves (RESPONSE/DONE/DECLINE/FAILURE).
        // Find the original QUERY/COMMAND message by correlationId and release the requester's claims.
        // HANDOFF delegates obligation — claims stay until the delegate resolves.
        if (dispatchResult.correlationId() != null && (msgType == MessageType.RESPONSE || msgType == MessageType.DONE
                || msgType == MessageType.DECLINE || msgType == MessageType.FAILURE)) {
            try {
                blockingMessageService.findByCorrelationId(dispatchResult.correlationId()).ifPresent(original -> {
                    if (original.artefactRefs() != null && !original.artefactRefs().isEmpty()) {
                        blockingInstanceService.findByInstanceId(original.sender()).ifPresent(inst -> {
                            for (io.casehub.qhorus.api.message.ArtefactRef ref : original.artefactRefs()) {
                                try { blockingDataService.release(UUID.fromString(ref.uri()), inst.id()); }
                                catch (IllegalArgumentException ignored) {}
                            }
                        });
                    }
                });
            } catch (Exception e) {
                LOG.warnf("Auto-release artefact claims failed for correlationId '%s': %s",
                        dispatchResult.correlationId(), e.getMessage());
            }
        }

        return dispatchResult;
    }

    // 4. check_messages
    @Tool(name = "check_messages", description = "Poll for messages on a channel after a given cursor ID. "
            + "Excludes EVENT type by default — set include_events=true to receive EVENT messages "
            + "(intended for read_only instances acting as dashboards/observers). "
            + "Returns messages and last_id for subsequent polling. "
            + "Behaviour varies by channel semantic: EPHEMERAL deletes on read, "
            + "COLLECT delivers all and clears, BARRIER blocks until all contributors have written.")
    @Transactional
    @Blocking
    public Uni<CheckResult> checkMessages(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "after_id", description = "Return messages with ID > after_id (use 0 for all)", required = false) Long afterId,
            @ToolArg(name = "limit", description = "Maximum messages to return (default 20)", required = false) Integer limit,
            @ToolArg(name = "sender", description = "Filter by sender (optional)", required = false) String sender,
            @ToolArg(name = "reader_instance_id", description = "Calling agent's instance ID for target filtering. "
                    + "When provided, only broadcast (null target) and instance:<reader> messages are returned.", required = false) String readerInstanceId,
            @ToolArg(name = "include_events", description = "If true, include EVENT messages in results (default false). "
                    + "Used by read_only instances to receive telemetry events.", required = false) Boolean includeEvents) {
        Channel ch = resolveChannel(channel);
        return Uni.createFrom()
                .item(() -> blockingCheckMessages(ch.name(), afterId, limit, sender, readerInstanceId, includeEvents));
    }

    private CheckResult blockingCheckMessages(String channelName, Long afterId, Integer limit, String sender,
            String readerInstanceId, Boolean includeEvents) {
        Channel ch = blockingChannelService.findByName(channelName)
                                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));

        if (ch.paused()) {
            return new CheckResult(List.of(), afterId != null ? afterId : 0L, "Channel is paused");
        }

        long cursor = afterId != null ? afterId : 0L;
        int pageSize = limit != null ? limit : 20;
        boolean events = includeEvents != null && includeEvents;

        return switch (ch.semantic()) {
            case EPHEMERAL -> blockingCheckMessagesEphemeral(ch, cursor, pageSize, readerInstanceId);
            case COLLECT -> blockingCheckMessagesCollect(ch, readerInstanceId);
            case BARRIER -> blockingCheckMessagesBarrier(ch, readerInstanceId);
            default -> blockingCheckMessagesAppend(ch, cursor, pageSize, sender, readerInstanceId, events);
        };
    }

    private CheckResult blockingCheckMessagesEphemeral(Channel ch, long cursor, int pageSize, String readerInstanceId) {
        List<Message> fetched = blockingMessageService.pollAfter(ch.id(), cursor, pageSize);
        // Filter BEFORE deleting — must not consume messages targeted at other readers.
        List<Message> visible = fetched.stream()
                                             .filter(m -> isVisibleToReader(m, readerInstanceId,
                        () -> blockingInstanceService.findCapabilityTagsForInstance(readerInstanceId)))
                                             .toList();
        if (!visible.isEmpty()) {
            List<Long> ids = visible.stream().map(m -> m.id()).toList();
            ids.forEach(blockingMessageStore::delete);
        }
        List<MessageSummary> summaries = visible.stream().map(this::toMessageSummary).toList();
        Long lastId = summaries.isEmpty() ? cursor : summaries.getLast().messageId();
        return new CheckResult(summaries, lastId, null);
    }

    private CheckResult blockingCheckMessagesCollect(Channel ch, String readerInstanceId) {
        List<Message> messages = blockingMessageStore.scan(MessageQuery.builder().channelId(ch.id()).excludeTypes(List.of(MessageType.EVENT)).build());
        if (!messages.isEmpty()) {
            blockingMessageStore.deleteNonEvent(ch.id());
        }
        List<MessageSummary> summaries = messages.stream()
                .filter(m -> isVisibleToReader(m, readerInstanceId,
                        () -> blockingInstanceService.findCapabilityTagsForInstance(readerInstanceId)))
                .map(this::toMessageSummary).toList();
        Long lastId = summaries.isEmpty() ? 0L : summaries.getLast().messageId();
        return new CheckResult(summaries, lastId, null);
    }

    private CheckResult blockingCheckMessagesBarrier(Channel ch, String readerInstanceId) {
        Set<String> required = ch.barrierContributors() != null
                ? new java.util.HashSet<>(ch.barrierContributors())
                : Set.of();

        if (required.isEmpty()) {
            return new CheckResult(List.of(), 0L, "Waiting for: (no contributors declared — check channel configuration)");
        }

        List<String> written = messageStore.distinctSendersByChannel(ch.id(), MessageType.EVENT)
                .await().indefinitely();

        Set<String> pending = required.stream()
                .filter(r -> !written.contains(r))
                .collect(Collectors.toSet());

        if (!pending.isEmpty()) {
            String status = "Waiting for: " + String.join(", ", pending.stream().sorted().toList());
            return new CheckResult(List.of(), 0L, status);
        }

        // All contributors have written — deliver and clear
        List<Message> messages = blockingMessageStore.scan(MessageQuery.builder().channelId(ch.id()).excludeTypes(List.of(MessageType.EVENT)).build());
        blockingMessageStore.deleteNonEvent(ch.id());
        List<MessageSummary> summaries = messages.stream()
                .filter(m -> isVisibleToReader(m, readerInstanceId,
                        () -> blockingInstanceService.findCapabilityTagsForInstance(readerInstanceId)))
                .map(this::toMessageSummary).toList();
        Long lastId = summaries.isEmpty() ? 0L : summaries.getLast().messageId();
        return new CheckResult(summaries, lastId, null);
    }

    private CheckResult blockingCheckMessagesAppend(Channel ch, long cursor, int pageSize, String sender,
                                                    String readerInstanceId, boolean includeEvents) {
        List<Message> messages = (sender != null && !sender.isBlank())
                ? blockingMessageService.pollAfterBySender(ch.id(), cursor, pageSize, sender, includeEvents)
                : blockingMessageService.pollAfter(ch.id(), cursor, pageSize, includeEvents);
        List<MessageSummary> summaries = messages.stream()
                .filter(m -> isVisibleToReader(m, readerInstanceId,
                        () -> blockingInstanceService.findCapabilityTagsForInstance(readerInstanceId)))
                .map(this::toMessageSummary).toList();
        Long lastId = summaries.isEmpty() ? cursor : summaries.getLast().messageId();
        return new CheckResult(summaries, lastId, null);
    }

    // 5. get_replies
    @Tool(name = "get_replies", description = "Retrieve direct replies to a specific message.")
    @Blocking
    public Uni<List<MessageSummary>> getReplies(
            @ToolArg(name = "message_id", description = "ID of the parent message") Long messageId,
            @ToolArg(name = "reader_instance_id", description = "Calling agent's instance ID for target filtering", required = false) String readerInstanceId,
            @ToolArg(name = "after_id", description = "Return replies with id > after_id (cursor pagination)", required = false) Long afterId,
            @ToolArg(name = "limit", description = "Maximum replies to return (default 20, max 100)", required = false) Integer limit) {
        return Uni.createFrom().item(() -> blockingGetReplies(messageId, readerInstanceId, afterId, limit));
    }

    private List<MessageSummary> blockingGetReplies(Long messageId, String readerInstanceId, Long afterId, Integer limit) {
        final int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 100) : 20;
        MessageQuery.Builder mqb = MessageQuery.builder().inReplyTo(messageId).limit(effectiveLimit);
        if (afterId != null) mqb.afterId(afterId);
        final List<Message> messages = blockingMessageStore.scan(mqb.build());
        return messages.stream()
                .filter(m -> isVisibleToReader(m, readerInstanceId,
                        () -> blockingInstanceService.findCapabilityTagsForInstance(readerInstanceId)))
                .map(this::toMessageSummary)
                .toList();
    }

    // 6. search_messages
    @Tool(name = "search_messages", description = "Full-text keyword search across messages. Excludes EVENT type.")
    @Blocking
    public Uni<List<MessageSummary>> searchMessages(
            @ToolArg(name = "query", description = "Keyword to search for (case-insensitive)") String query,
            @ToolArg(name = "channel", description = "Channel name or UUID (optional, restricts search to this channel)", required = false) String channel,
            @ToolArg(name = "limit", description = "Maximum results (default 20)", required = false) Integer limit,
            @ToolArg(name = "reader_instance_id", description = "Calling agent's instance ID for target filtering (optional)", required = false) String readerInstanceId) {
        String resolvedName = null;
        if (channel != null && !channel.isBlank()) {
            resolvedName = resolveChannel(channel).name();
        }
        final String channelName = resolvedName;
        return Uni.createFrom().item(() -> blockingSearchMessages(query, channelName, limit, readerInstanceId));
    }

    private List<MessageSummary> blockingSearchMessages(String query, String channelName, Integer limit,
            String readerInstanceId) {
        int pageSize = limit != null ? limit : 20;

        Channel ch = null;
        if (channelName != null && !channelName.isBlank()) {
            ch = blockingChannelService.findByName(channelName)
                                                     .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));
        }
        UUID channelId = ch != null ? ch.id() : null;

        MessageQuery.Builder sqb = MessageQuery.builder()
                .contentPattern(query)
                .excludeTypes(List.of(MessageType.EVENT))
                .limit(pageSize);
        if (channelId != null) sqb.channelId(channelId);
        List<Message> results = blockingMessageStore.scan(sqb.build());

        return results.stream()
                .filter(m -> isVisibleToReader(m, readerInstanceId,
                        () -> blockingInstanceService.findCapabilityTagsForInstance(readerInstanceId)))
                .map(this::toMessageSummary).toList();
    }

    // 7. wait_for_reply
    @Tool(name = "wait_for_reply", description = "Block until a RESPONSE message with the given correlation_id "
            + "arrives on the channel, or until timeout_seconds seconds elapse. "
            + "Returns immediately if a matching response already exists.")
    @Blocking
    public Uni<WaitResult> waitForReply(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "correlation_id", description = "UUID matching the correlation_id on the expected RESPONSE") String correlationId,
            @ToolArg(name = "timeout_seconds", description = "Seconds to wait before timing out (default 90)", required = false) Integer timeoutS,
            @ToolArg(name = "instance_id", description = "Waiting agent's instance ID for tracking (optional)", required = false) String instanceId) {
        Channel ch = resolveChannel(channel);
        return Uni.createFrom().item(() -> blockingWaitForReply(ch.name(), correlationId, timeoutS, instanceId));
    }

    private WaitResult blockingWaitForReply(String channelName, String correlationId, Integer timeoutS, String instanceId) {
        Channel ch = blockingChannelService.findByName(channelName)
                                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));

        int timeout = timeoutS != null ? timeoutS : 90;
        java.time.Instant expiresAt = java.time.Instant.now().plusSeconds(timeout);

        // Poll loop — each check is its own short transaction so we don't hold a connection.
        // Commitment was already created by CommitmentService.open() when QUERY/COMMAND was sent.
        long pollMs = 100;
        while (java.time.Instant.now().isBefore(expiresAt)) {
            Optional<Commitment> opt = commitmentStore.findByCorrelationId(correlationId);
            if (opt.isEmpty()) {
                // Commitment deleted by cancel_wait — return cancelled
                return new WaitResult(false, false, correlationId, null,
                        "Wait cancelled for correlation_id=" + correlationId);
            }
            Commitment commitment = opt.get();
            if (commitment.state() == CommitmentState.FULFILLED
                    || commitment.state() == CommitmentState.OPEN
                    || commitment.state() == CommitmentState.ACKNOWLEDGED
                    || commitment.state() == CommitmentState.DELEGATED) {
                // Check for RESPONSE or DONE message — covers both the normal FULFILLED path and
                // the race-condition path where a RESPONSE arrived before the QUERY created the Commitment
                // (e.g. approval gate with pre-seeded responses, or distributed message races).
                Message response = blockingMessageService
                        .findResponseByCorrelationId(ch.id(), correlationId)
                        .orElse(null);
                if (response != null) {
                    return new WaitResult(true, false, correlationId, toMessageSummary(response),
                            "Response received for correlation_id=" + correlationId);
                }
                Message done = blockingMessageService
                        .findDoneByCorrelationId(ch.id(), correlationId)
                        .orElse(null);
                if (done != null) {
                    return new WaitResult(true, false, correlationId, toMessageSummary(done),
                            "Done received for correlation_id=" + correlationId);
                }
            }
            if (commitment.state() == CommitmentState.DECLINED) {
                return new WaitResult(false, false, correlationId, null,
                        "Request was DECLINED for correlation_id=" + correlationId);
            }
            if (commitment.state() == CommitmentState.FAILED) {
                return new WaitResult(false, false, correlationId, null,
                        "Request FAILED for correlation_id=" + correlationId);
            }
            if (commitment.state() == CommitmentState.EXPIRED) {
                return new WaitResult(false, true, correlationId, null,
                        "Commitment EXPIRED for correlation_id=" + correlationId);
            }
            // OPEN, ACKNOWLEDGED, DELEGATED with no message yet — keep waiting
            try {
                Thread.sleep(pollMs);
                pollMs = Math.min(pollMs * 2, 500); // backoff up to 500ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return new WaitResult(false, true, correlationId, null,
                "Timed out after " + timeout + "s waiting for response to correlation_id=" + correlationId);
    }

    // 8. request_approval
    @Tool(name = "request_approval", description = "Send an approval request to a channel and block until a human responds. "
            + "Returns the human's response or a timeout result. "
            + "Pair with list_pending_commitments (for human to discover) and respond_to_approval (for human to answer).")
    @Blocking
    public Uni<WaitResult> requestApproval(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "content", description = "The approval request content shown to the human") String content,
            @ToolArg(name = "timeout_seconds", description = "Seconds to wait for human response (default 300)", required = false) Integer timeoutS) {
        Channel ch = resolveChannel(channel);
        return Uni.createFrom()
                .item(() -> blockingRequestApproval(ch.name(), content, UUID.randomUUID().toString(), timeoutS));
    }

    private WaitResult blockingRequestApproval(String channelName, String content, String correlationId, Integer timeoutS) {
        int timeout = timeoutS != null ? timeoutS : 300;
        blockingSendMessage(channelName, "agent", "query", content, correlationId, null, null, null, null, null, null);
        return blockingWaitForReply(channelName, correlationId, timeout, null);
    }

    // 9. respond_to_approval
    @Tool(name = "respond_to_approval", description = "Human-callable: send a response to a pending approval request. "
            + "Use correlation_id from list_pending_commitments to identify which request to answer.")
    @Transactional
    @Blocking
    public Uni<DispatchResult> respondToApproval(
            @ToolArg(name = "correlation_id", description = "Correlation ID of the approval request (from list_pending_commitments)") String correlationId,
            @ToolArg(name = "response_text", description = "The approval decision or message to send back") String responseText,
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel) {
        Channel ch = resolveChannel(channel);
        return Uni.createFrom().item(() -> blockingRespondToApproval(correlationId, responseText, ch.name()));
    }

    private DispatchResult blockingRespondToApproval(String correlationId, String responseText, String channelName) {
        return blockingSendMessage(channelName, Senders.HUMAN, "response", responseText, correlationId, null, null, null, null, null, null);
    }

    // 10. cancel_wait
    @Tool(name = "cancel_wait", description = "Cancel a pending wait_for_reply by its correlation_id. "
            + "The waiting agent receives status='cancelled' instead of timing out. "
            + "Use list_pending_commitments to discover what is blocked.")
    @Transactional
    @Blocking
    public Uni<CancelWaitResult> cancelWait(
            @ToolArg(name = "correlation_id", description = "Correlation ID of the pending wait to cancel") String correlationId) {
        return Uni.createFrom().item(() -> blockingCancelWait(correlationId));
    }

    private CancelWaitResult blockingCancelWait(String correlationId) {
        Optional<Commitment> opt = commitmentStore.findByCorrelationId(correlationId);
        if (opt.isPresent()) {
            commitmentStore.deleteById(opt.get().id());
            return new CancelWaitResult(correlationId, true,
                    "Cancelled pending wait for correlation_id=" + correlationId);
        } else {
            return new CancelWaitResult(correlationId, false,
                    "No pending wait found for correlation_id=" + correlationId);
        }
    }

    // 11. list_pending_commitments (consolidates former list_pending_waits + list_pending_approvals)
    @Tool(name = "list_pending_commitments", description = "List non-terminal commitments across all channels. "
            + "Returns oldest first. Use cancel_wait to unblock a specific wait, "
            + "or respond_to_approval to answer an approval request.")
    @Transactional
    @Blocking
    public Uni<List<CommitmentDetail>> listPendingCommitments() {
        return Uni.createFrom().item(() -> commitmentStore.findAllOpen().stream()
                .map(CommitmentDetail::from)
                .toList());
    }

    // 13. revoke_artefact
    @Tool(name = "revoke_artefact", description = "Force-delete a shared artefact and release all its claims. "
            + "Use for data breaches, PII removal, or invalid data. "
            + "get_shared_data will fail after revocation. Does not cascade to messages that reference this artefact.")
    @Transactional
    @Blocking
    public Uni<RevokeResult> revokeArtefact(
            @ToolArg(name = "artefact_id", description = "UUID of the artefact to revoke") String artefactId) {
        return Uni.createFrom().item(() -> blockingRevokeArtefact(artefactId));
    }

    private RevokeResult blockingRevokeArtefact(String artefactId) {
        java.util.UUID   uuid = java.util.UUID.fromString(artefactId);
        SharedData data = dataStore.find(uuid).orElse(null);
        if (data == null) {
            return new RevokeResult(artefactId, null, null, 0, 0, false,
                    "Artefact not found: " + artefactId);
        }
        String key = data.key();
        String createdBy = data.createdBy();
        long sizeBytes = data.sizeBytes();

        int claimsReleased = dataStore.countClaims(uuid);
        dataStore.delete(uuid);

        return new RevokeResult(artefactId, key, createdBy, sizeBytes, claimsReleased, true,
                "Artefact '" + key + "' revoked — " + claimsReleased + " claim(s) released");
    }

    // 14. delete_message
    @Tool(name = "delete_message", description = "Delete a single message by its sequence ID. "
            + "Use for PII removal, bad data, or agent mistakes. Does not cascade to replies.")
    @Transactional
    @Blocking
    public Uni<DeleteMessageResult> deleteMessage(
            @ToolArg(name = "message_id", description = "Sequence ID of the message to delete") Long messageId) {
        return Uni.createFrom().item(() -> blockingDeleteMessage(messageId));
    }

    private DeleteMessageResult blockingDeleteMessage(Long messageId) {
        Message msg = blockingMessageStore.find(messageId).orElse(null);
        if (msg == null) {
            return new DeleteMessageResult(messageId, false, null, null, null,
                    "Message not found: " + messageId);
        }
        String sender = msg.sender();
        String type = msg.messageType().name();
        String preview = msg.content() != null
                ? (msg.content().length() > 80 ? msg.content().substring(0, 80) + "…" : msg.content())
                : null;
        // Orphan replies (null out in_reply_to) before deleting — replies survive, FK satisfied
        blockingMessageStore.scan(MessageQuery.builder().inReplyTo(messageId).build()).forEach(reply -> blockingMessageStore.put(reply.toBuilder().inReplyTo(null).build()));
        // Post audit event to the channel
        blockingMessageService.dispatch(MessageDispatch.builder()
                .channelId(msg.channelId()).sender("system").type(MessageType.EVENT)
                .actorType(ActorType.SYSTEM).build());
        blockingMessageStore.delete(msg.id());
        return new DeleteMessageResult(messageId, true, sender, type, preview,
                "Message " + messageId + " deleted");
    }

    // 15. clear_channel
    @Tool(name = "clear_channel", description = "Delete ALL non-event messages from a channel. "
            + "Does not delete the channel itself or event messages. "
            + "Returns count of messages deleted.")
    @Transactional
    @Blocking
    public Uni<ClearChannelResult> clearChannel(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "caller_instance_id", description = "Instance ID of the caller. Required when the channel has an admin_instances list.", required = false) String callerInstanceId) {
        Channel ch = resolveChannel(channel);
        return Uni.createFrom().item(() -> blockingClearChannel(ch.name(), callerInstanceId));
    }

    private ClearChannelResult blockingClearChannel(String channelName, String callerInstanceId) {
        Channel ch = blockingChannelService.findByName(channelName)
                                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));
        checkAdminAccess(ch, callerInstanceId, "clear_channel");
        long deleted = blockingMessageStore.scan(MessageQuery.builder().channelId(ch.id()).excludeTypes(List.of(MessageType.EVENT)).build()).size();
        blockingMessageStore.deleteNonEvent(ch.id());
        // Post audit event (survives the clear)
        blockingMessageService.dispatch(MessageDispatch.builder()
                .channelId(ch.id()).sender("system").type(MessageType.EVENT)
                .actorType(ActorType.SYSTEM).build());
        blockingChannelService.updateLastActivity(ch.id(), ch.tenancyId());
        return new ClearChannelResult(channelName, (int) deleted, true);
    }

    // 16. deregister_instance
    @Tool(name = "deregister_instance", description = "Force-remove an agent instance and its capability tags from the registry. "
            + "Use for misbehaving agents that won't self-deregister. Does not delete past messages.")
    @Transactional
    @Blocking
    public Uni<DeregisterResult> deregisterInstance(
            @ToolArg(name = "instance_id", description = "Human-readable instance ID of the agent to remove") String instanceId) {
        return Uni.createFrom().item(() -> blockingDeregisterInstance(instanceId));
    }

    private DeregisterResult blockingDeregisterInstance(String instanceId) {
        Instance instance = instanceStore.findByInstanceId(instanceId).orElse(null);
        if (instance == null) {
            return new DeregisterResult(instanceId, false,
                    "Instance not found: " + instanceId);
        }
        // Delete capabilities first (no FK from capability to instance that would block)
        // capabilities deleted by instanceStore.delete()
        instanceStore.delete(instance.id());
        return new DeregisterResult(instanceId, true,
                "Instance '" + instanceId + "' deregistered");
    }

    // 17. get_channel_digest
    @Tool(name = "get_channel_digest", description = "Return a structured human-readable summary of a channel's recent activity. "
            + "Useful for human dashboards to understand state before intervening. "
            + "Includes message count, sender/type breakdowns, artefact refs, recent messages (truncated), and timestamps.")
    @Transactional
    @Blocking
    public Uni<ChannelDigest> channelDigest(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "limit", description = "Max recent messages to include (default 10)", required = false) Integer limit) {
        Channel ch = resolveChannel(channel);
        return Uni.createFrom().item(() -> blockingChannelDigest(ch.name(), limit));
    }

    private ChannelDigest blockingChannelDigest(String channelName, Integer limit) {
        Channel ch = blockingChannelService.findByName(channelName)
                                           .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));

        int           pageSize    = limit != null ? limit : 10;
        List<Message> allMessages = blockingMessageStore.scan(MessageQuery.builder().channelId(ch.id()).excludeTypes(List.of(MessageType.EVENT)).build());

        Map<String, io.casehub.qhorus.api.message.TopicSummary> topicSummaries =
                blockingTopicService.listTopics(ch.id()).stream()
                                    .collect(java.util.stream.Collectors.toMap(
                                            io.casehub.qhorus.api.message.TopicSummary::name, t -> t, (a, b) -> a));

        if (allMessages.isEmpty()) {
            List<TopicDigest> emptyTopics = topicSummaries.values().stream()
                                                          .map(t -> new TopicDigest(t.name(), 0,
                                                                                    t.lastActivityAt() != null ? t.lastActivityAt().toString() : null,
                                                                                    t.resolved(), t.resolvedAt() != null ? t.resolvedAt().toString() : null))
                                                          .toList();
            return new ChannelDigest(ch.name(), ch.semantic().name(), ch.paused(),
                                     0L, Map.of(), Map.of(), 0, List.of(), List.of(), null, null, emptyTopics);
        }

        Map<String, Long>              topicCounts       = new java.util.LinkedHashMap<>();
        Map<String, java.time.Instant> topicLastActivity = new java.util.LinkedHashMap<>();
        for (Message m : allMessages) {
            String topic = m.topic() != null ? m.topic() : "general";
            topicCounts.merge(topic, 1L, Long::sum);
            if (m.createdAt() != null) {
                topicLastActivity.merge(topic, m.createdAt(),
                                        (a, b) -> a.isAfter(b) ? a : b);
            }
        }

        List<TopicDigest> topicBreakdown = topicCounts.entrySet().stream()
                                                      .map(e -> {
                                                          String                                     name    = e.getKey();
                                                          io.casehub.qhorus.api.message.TopicSummary summary = topicSummaries.get(name);
                                                          java.time.Instant                          lastAct = topicLastActivity.get(name);
                                                          return new TopicDigest(name, e.getValue(),
                                                                                 lastAct != null ? lastAct.toString() : null,
                                                                                 summary != null && summary.resolved(),
                                                                                 summary != null && summary.resolvedAt() != null
                                                                                 ? summary.resolvedAt().toString() : null);
                                                      })
                                                      .toList();

        Map<String, Integer>  senderBreakdown = new java.util.LinkedHashMap<>();
        Map<String, Integer>  typeBreakdown   = new java.util.LinkedHashMap<>();
        java.util.Set<String> artefactUuids   = new java.util.LinkedHashSet<>();

        for (Message m : allMessages) {
            senderBreakdown.merge(m.sender(), 1, Integer::sum);
            typeBreakdown.merge(m.messageType().name(), 1, Integer::sum);
            if (m.artefactRefs() != null && !m.artefactRefs().isEmpty()) {
                m.artefactRefs().forEach(ref -> artefactUuids.add(ref.uri()));
            }
        }

        java.time.Instant cutoff = java.time.Instant.now().minusSeconds(300);
        List<String> activeAgents = allMessages.stream()
                                               .filter(m -> m.createdAt() != null && m.createdAt().isAfter(cutoff))
                                               .map(m -> m.sender())
                                               .distinct()
                                               .toList();

        List<MessagePreview> recent = allMessages.stream()
                                                 .skip(Math.max(0, allMessages.size() - pageSize))
                                                 .map(m -> {
                                                     String content = m.content() != null ? m.content() : "";
                                                     String preview = content.length() > 120
                                                                      ? content.substring(0, 120) + "…"
                                                                      : content;
                                                     return new MessagePreview(m.id(), m.sender(), m.messageType().name(),
                                                                               preview, m.createdAt() != null ? m.createdAt().toString() : null);
                                                 })
                                                 .toList();

        String oldest = allMessages.get(0).createdAt() != null
                        ? allMessages.get(0).createdAt().toString()
                        : null;
        String newest = allMessages.get(allMessages.size() - 1).createdAt() != null
                        ? allMessages.get(allMessages.size() - 1).createdAt().toString()
                        : null;

        return new ChannelDigest(ch.name(), ch.semantic().name(), ch.paused(),
                                 allMessages.size(), senderBreakdown, typeBreakdown,
                                 artefactUuids.size(), activeAgents, recent, oldest, newest, topicBreakdown);}

    // 18. get_channel_timeline
    @Tool(name = "get_channel_timeline", description = "Return all messages for a channel in chronological order, "
            + "interleaving regular messages and EVENT telemetry entries. "
            + "Each entry has a 'type' discriminator: 'MESSAGE' or 'EVENT'. "
            + "Supports cursor-based pagination via after_id (message.id() cursor).")
    @Transactional
    @Blocking
    public Uni<List<Map<String, Object>>> getChannelTimeline(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "after_id", description = "Return messages with id > after_id (cursor pagination)", required = false) Long afterId,
            @ToolArg(name = "limit", description = "Maximum messages to return (default 50, max 200)", required = false) Integer limit) {
        Channel ch = resolveChannel(channel);
        return Uni.createFrom().item(() -> blockingGetChannelTimeline(ch.name(), afterId, limit));
    }

    private List<Map<String, Object>> blockingGetChannelTimeline(String channelName, Long afterId, Integer limit) {
        Channel ch = blockingChannelService.findByName(channelName)
                                                 .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 200) : 50;

        List<Message> messages = blockingMessageStore.scan(
                MessageQuery.poll(ch.id(), afterId, effectiveLimit));

        // Batch-fetch ledger entries for all EVENT messages in one IN query. Refs #262.
        final List<Long> eventIds = messages.stream()
                .filter(m -> m.messageType() == MessageType.EVENT)
                .map(m -> m.id())
                .toList();
        final Map<Long, MessageLedgerEntry> ledgerByMessageId = eventIds.isEmpty()
                ? Map.of()
                : ledgerRepo.findByMessageIds(eventIds).stream()
                        .collect(Collectors.toMap(e -> e.messageId, e -> e));

        return messages.stream()
                .map(m -> entityMapper.toTimelineEntry(m,
                        m.messageType() == MessageType.EVENT ? ledgerByMessageId.get(m.id()) : null))
                .toList();
    }

    // 19. get_obligation_activity
    @Tool(name = "get_obligation_activity", description = "Return all ledger entries across ALL channels that share a given correlation_id, "
            + "ordered chronologically. Each entry includes a 'channel' field showing which channel it was sent on. "
            + "Use this to reconstruct the full cross-channel picture of an obligation: the COMMAND on work, "
            + "the tool-call EVENTs on observe (when agents pass the correlationId on EVENT messages), "
            + "any oversight escalation, and the terminal DONE/FAILURE/DECLINE.")
    @Blocking
    public Uni<List<Map<String, Object>>> getObligationActivity(
            @ToolArg(name = "correlation_id", description = "Correlation ID of the obligation to trace across channels") String correlationId,
            @ToolArg(name = "include_content_search", description = "Deprecated — reserved for future use. Has no effect.", required = false) Boolean includeContentSearch,
            @ToolArg(name = "limit", description = "Maximum entries to return (default 100, max 500)", required = false) Integer limit) {
        final String tenancyId = currentPrincipal.tenancyId();
        return Uni.createFrom().item(() -> {
            final int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 500) : 100;

            final List<MessageLedgerEntry> entries =
                    ledgerRepo.findByCorrelationIdAcrossChannels(correlationId, effectiveLimit, tenancyId);

            if (entries.isEmpty()) {
                return List.of();
            }

            final java.util.Set<java.util.UUID> channelIds = entries.stream()
                    .map(e -> e.channelId)
                    .collect(java.util.stream.Collectors.toSet());
            final java.util.Map<java.util.UUID, String> channelNameById = blockingChannelService.listAll().stream()
                    .filter(ch -> channelIds.contains(ch.id()))
                    .collect(java.util.stream.Collectors.toMap(ch -> ch.id(), ch -> ch.name()));

            return entries.stream()
                    .map(e -> toLedgerEntryMapWithChannel(e,
                            channelNameById.getOrDefault(e.channelId, "unknown")))
                    .toList();
        });
    }

    // 20. get_instance
    @Tool(name = "get_instance", description = "Look up a registered instance by its ID.")
    @Blocking
    public InstanceInfo getInstance(
            @ToolArg(name = "instance_id", description = "Instance ID to look up") String instanceId) {
        Instance instance = blockingInstanceService.findByInstanceId(instanceId)
                                                         .orElseThrow(() -> new IllegalArgumentException(
                        "Instance not found: " + instanceId));
        return buildInstanceInfoListBlocking(java.util.List.of(instance)).get(0);
    }

    private List<InstanceInfo> buildInstanceInfoListBlocking(List<Instance> instances) {
        return instances.stream()
                .map(i -> {
                    List<String> tags = blockingInstanceService.findCapabilityTagsForInstance(i.instanceId());
                    return new InstanceInfo(i.instanceId(), i.description(), i.status(), tags,
                            i.lastSeen().toString(), i.readOnly());
                })
                .toList();
    }

    // 20. get_message
    @Tool(name = "get_message", description = "Look up a message by its numeric ID. "
            + "Returns the message summary including content, type, sender, and metadata.")
    @Blocking
    public MessageSummary getMessage(
            @ToolArg(name = "message_id", description = "Numeric message ID") Long messageId) {
        Message message = blockingMessageService.findById(messageId)
                                                      .orElseThrow(() -> new IllegalArgumentException(
                        "Message not found: " + messageId));
        return toMessageSummary(message);
    }

    // 21. list_ledger_entries
    @Tool(name = "list_ledger_entries", description = "Query the immutable audit ledger for a channel. "
            + "Returns all ledger entries in chronological order — every speech act, every tool invocation. "
            + "Use type_filter to narrow by message type: 'COMMAND,DONE,FAILURE' for obligation lifecycle, "
            + "'EVENT' for telemetry only, omit for the full channel history. "
            + "Supports optional filters for sender, since (ISO-8601), correlation_id, sort (asc/desc), "
            + "and cursor-based pagination via after_id.")
    @Transactional
    @Blocking
    public Uni<List<Map<String, Object>>> listLedgerEntries(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "type_filter", description = "Comma-separated MessageType names (e.g. 'COMMAND,DONE'). Omit for all types.", required = false) String typeFilter,
            @ToolArg(name = "sender", description = "Filter by sender", required = false) String agentId,
            @ToolArg(name = "since", description = "ISO-8601 timestamp — entries at or after this time", required = false) String since,
            @ToolArg(name = "after_id", description = "sequence_number cursor for pagination", required = false) Long afterId,
            @ToolArg(name = "correlation_id", description = "Filter by correlation ID", required = false) String correlationId,
            @ToolArg(name = "sort", description = "Sort order: 'asc' (default) or 'desc'", required = false) String sort,
            @ToolArg(name = "limit", description = "Maximum entries (default 20, max 100)", required = false) Integer limit) {
        Channel ch        = resolveChannel(channel);
        final String  tenancyId = currentPrincipal.tenancyId();
        return Uni.createFrom().item(
                () -> blockingListLedgerEntries(ch.name(), typeFilter, agentId, since, afterId,
                        correlationId, sort, limit, tenancyId));
    }

    private List<Map<String, Object>> blockingListLedgerEntries(final String channelName,
            final String typeFilter, final String agentId, final String since, final Long afterId,
            final String correlationId, final String sort, final Integer limit, final String tenancyId) {
        final Channel ch = blockingChannelService.findByName(channelName)
                                                       .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));

        java.util.Set<String> types = null;
        if (typeFilter != null && !typeFilter.isBlank()) {
            types = java.util.Arrays.stream(typeFilter.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.toSet());
        }
        final int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 100) : 20;
        java.time.Instant sinceInstant = null;
        if (since != null && !since.isBlank()) {
            try {
                sinceInstant = java.time.Instant.parse(since);
            } catch (final java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "Invalid 'since' timestamp '" + since + "' — use ISO-8601 format");
            }
        }
        final boolean sortDesc;
        if (sort == null || sort.isBlank() || "asc".equalsIgnoreCase(sort)) {
            sortDesc = false;
        } else if ("desc".equalsIgnoreCase(sort)) {
            sortDesc = true;
        } else {
            throw new IllegalArgumentException("Invalid sort value '" + sort + "' — use 'asc' or 'desc'");
        }
        return ledgerRepo.listEntries(ch.id(), types, afterId, agentId, sinceInstant,
                correlationId, sortDesc, effectiveLimit, tenancyId)
                .stream().map(this::toLedgerEntryMap).toList();
    }

    // 22. get_obligation_chain
    @Tool(name = "get_obligation_chain", description = "Return computed enrichment for an obligation identified by correlation_id: "
            + "initiator, participants, handoff count, elapsed time, resolution, and live commitment state.")
    @Transactional
    @Blocking
    public Uni<ObligationChainSummary> getObligationChain(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "correlation_id", description = "Correlation ID of the obligation to inspect") String correlationId) {
        Channel ch        = resolveChannel(channel);
        final String  tenancyId = currentPrincipal.tenancyId();
        return Uni.createFrom().item(() -> blockingGetObligationChain(ch.name(), correlationId, tenancyId));
    }

    private ObligationChainSummary blockingGetObligationChain(final String channelName,
            final String correlationId, final String tenancyId) {
        final Channel ch = blockingChannelService.findByName(channelName)
                                                       .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));
        final List<MessageLedgerEntry> chain = ledgerRepo.findAllByCorrelationId(ch.id(), correlationId, tenancyId);
        if (chain.isEmpty()) {
            return new ObligationChainSummary(correlationId, null, null, null, null, null,
                    List.of(), 0, null);
        }
        final MessageLedgerEntry first = chain.get(0);
        final java.util.Set<String> terminal = java.util.Set.of("DONE", "FAILURE", "DECLINE");
        final MessageLedgerEntry terminalEntry = chain.stream()
                .filter(e -> terminal.contains(e.messageType)).findFirst().orElse(null);
        final String resolution = terminalEntry != null ? terminalEntry.messageType : null;
        final String resolvedAt = (terminalEntry != null && terminalEntry.occurredAt != null)
                ? terminalEntry.occurredAt.toString()
                : null;
        final Long elapsedSeconds = (terminalEntry != null && first.occurredAt != null
                && terminalEntry.occurredAt != null)
                        ? terminalEntry.occurredAt.getEpochSecond() - first.occurredAt.getEpochSecond()
                        : null;
        final List<String> participants = chain.stream().map(e -> e.actorId).distinct()
                .collect(java.util.stream.Collectors.toList());
        final int handoffCount = (int) chain.stream()
                .filter(e -> "HANDOFF".equals(e.messageType)).count();
        final CommitmentDetail commitment = commitmentStore.findByCorrelationId(correlationId)
                .map(CommitmentDetail::from).orElse(null);
        return new ObligationChainSummary(correlationId,
                first.actorId,
                first.occurredAt != null ? first.occurredAt.toString() : null,
                resolvedAt, elapsedSeconds, resolution, participants, handoffCount, commitment);
    }

    // 23. get_causal_chain
    @Tool(name = "get_causal_chain", description = "Compliance audit tool. Takes a ledger_entry_id (UUID from list_ledger_entries) "
            + "and walks causedByEntryId links upward to the root. Returns chain ordered oldest-first.")
    @Transactional
    @Blocking
    public Uni<List<CausalChainEntry>> getCausalChain(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "ledger_entry_id", description = "UUID of the ledger entry") String ledgerEntryId) {
        Channel ch        = resolveChannel(channel);
        final String  tenancyId = currentPrincipal.tenancyId();
        return Uni.createFrom().item(() -> blockingGetCausalChain(ch.name(), ledgerEntryId, tenancyId));
    }

    private List<CausalChainEntry> blockingGetCausalChain(final String channelName,
            final String ledgerEntryId, final String tenancyId) {
        final Channel ch = blockingChannelService.findByName(channelName)
                                                       .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));
        final UUID entryUuid;
        try {
            entryUuid = UUID.fromString(ledgerEntryId);
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid ledger_entry_id '" + ledgerEntryId + "' — must be a UUID");
        }
        return ledgerRepo.findAncestorChain(ch.id(), entryUuid, tenancyId).stream()
                .map(e -> new CausalChainEntry(
                        e.id != null ? e.id.toString() : null,
                        e.messageType, e.actorId, e.correlationId,
                        e.occurredAt != null ? e.occurredAt.toString() : null,
                        e.causedByEntryId != null ? e.causedByEntryId.toString() : null))
                .toList();
    }

    // 24. list_stalled_obligations
    @Tool(name = "list_stalled_obligations", description = "Return COMMAND entries with no terminal sibling "
            + "(DONE / FAILURE / DECLINE / HANDOFF) sharing the same correlation_id, "
            + "whose timestamp is older than the given threshold.")
    @Transactional
    @Blocking
    public Uni<List<StalledObligation>> listStalledObligations(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "older_than_seconds", description = "Minimum age in seconds (default 30)", required = false) Integer olderThanSeconds) {
        Channel ch        = resolveChannel(channel);
        final String  tenancyId = currentPrincipal.tenancyId();
        return Uni.createFrom().item(() -> blockingListStalledObligations(ch.name(), olderThanSeconds, tenancyId));
    }

    private List<StalledObligation> blockingListStalledObligations(final String channelName,
            final Integer olderThanSeconds, final String tenancyId) {
        final Channel ch = blockingChannelService.findByName(channelName)
                                                       .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));
        final int threshold = olderThanSeconds != null ? olderThanSeconds : 30;
        final java.time.Instant cutoff = java.time.Instant.now().minusSeconds(threshold);
        final java.time.Instant now = java.time.Instant.now();
        return ledgerRepo.findStalledCommands(ch.id(), cutoff, tenancyId).stream()
                .map(e -> new StalledObligation(e.correlationId, e.actorId, e.content,
                        e.occurredAt != null ? e.occurredAt.toString() : null,
                        e.occurredAt != null
                                ? now.getEpochSecond() - e.occurredAt.getEpochSecond()
                                : 0L))
                .toList();
    }

    // 25. get_obligation_stats
    @Tool(name = "get_obligation_stats", description = "Return obligation outcome statistics for a channel: "
            + "total commands, fulfilled, failed, declined, delegated, still open, stalled, fulfillment rate.")
    @Transactional
    @Blocking
    public Uni<ObligationStats> getObligationStats(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel) {
        Channel ch        = resolveChannel(channel);
        final String  tenancyId = currentPrincipal.tenancyId();
        return Uni.createFrom().item(() -> blockingGetObligationStats(ch.name(), tenancyId));
    }

    private ObligationStats blockingGetObligationStats(final String channelName, final String tenancyId) {
        final Channel ch = blockingChannelService.findByName(channelName)
                                                       .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));
        final Map<String, Long> counts = ledgerRepo.countByOutcome(ch.id(), tenancyId);
        final long total = counts.getOrDefault("COMMAND", 0L);
        final long fulfilled = counts.getOrDefault("DONE", 0L);
        final long failed = counts.getOrDefault("FAILURE", 0L);
        final long declined = counts.getOrDefault("DECLINE", 0L);
        final long delegated = counts.getOrDefault("HANDOFF", 0L);
        final long stillOpen = Math.max(0L, total - fulfilled - failed - declined - delegated);
        final long stalled = ledgerRepo
                .findStalledCommands(ch.id(), java.time.Instant.now().minusSeconds(30), tenancyId).size();
        final double rate = total > 0 ? (double) fulfilled / total : 0.0;
        return new ObligationStats((int) total, (int) fulfilled, (int) failed, (int) declined,
                (int) delegated, (int) stillOpen, (int) stalled, rate);
    }

    // 26. get_telemetry_summary
    @Tool(name = "get_telemetry_summary", description = "Aggregate EVENT telemetry for a channel, grouped by tool name. "
            + "Returns per-tool counts with average duration and total tokens, and channel-wide totals.")
    @Transactional
    @Blocking
    public Uni<TelemetrySummary> getTelemetrySummary(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "since", description = "ISO-8601 timestamp — include only events at or after this time", required = false) String since) {
        Channel ch        = resolveChannel(channel);
        final String  tenancyId = currentPrincipal.tenancyId();
        return Uni.createFrom().item(() -> blockingGetTelemetrySummary(ch.name(), since, tenancyId));
    }

    private TelemetrySummary blockingGetTelemetrySummary(final String channelName,
            final String since, final String tenancyId) {
        final Channel ch = blockingChannelService.findByName(channelName)
                                                       .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelName));
        java.time.Instant sinceInstant = null;
        if (since != null && !since.isBlank()) {
            try {
                sinceInstant = java.time.Instant.parse(since);
            } catch (final java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "Invalid 'since' timestamp '" + since + "' — use ISO-8601 format");
            }
        }
        final List<MessageLedgerEntry> events = ledgerRepo.findEventsSince(ch.id(), sinceInstant, tenancyId);
        if (events.isEmpty()) {
            return new TelemetrySummary(0, Map.of(), 0L, 0L);
        }
        final java.util.LinkedHashMap<String, long[]> agg = new java.util.LinkedHashMap<>();
        for (final MessageLedgerEntry e : events) {
            final long[] acc = agg.computeIfAbsent(e.toolName, k -> new long[3]);
            acc[0]++;
            acc[1] += e.durationMs != null ? e.durationMs : 0;
            acc[2] += e.tokenCount != null ? e.tokenCount : 0;
        }
        final Map<String, ToolTelemetry> byTool = new java.util.LinkedHashMap<>();
        for (final var entry : agg.entrySet()) {
            final long[] acc = entry.getValue();
            byTool.put(entry.getKey(),
                    new ToolTelemetry((int) acc[0], acc[0] > 0 ? acc[1] / acc[0] : 0L, acc[2]));
        }
        return new TelemetrySummary(events.size(), byTool,
                events.stream().mapToLong(e -> e.tokenCount != null ? e.tokenCount : 0L).sum(),
                events.stream().mapToLong(e -> e.durationMs != null ? e.durationMs : 0L).sum());
    }

    // ---------------------------------------------------------------------------
    // Projection tools
    // ---------------------------------------------------------------------------

    @Tool(name = "list_projections",
            description = "List all projection names registered with ProjectionRegistry. "
                    + "Pass a name from this list as the projection_name argument to project_channel.")
    public List<String> listProjections() {
        return projectionRegistry.registeredNames().stream().sorted().toList();
    }

    @Tool(name = "project_channel",
          description = "Project a channel's message history through a named RenderableProjection "
                        + "and return the rendered result as a String. "
                        + "The projection folds messages in ascending insertion order (oldest first). "
                        + "max_messages bounds the fold depth — use it to limit output size on busy channels. "
                        + "Null or non-positive max_messages folds the full history. "
                        + "topic scopes the fold to messages in a single topic — null folds all topics. "
                        + "On LAST_WRITE channels the fold sees only the current snapshot (one message per sender, "
                        + "not full history) — projections that assume a complete history will produce incorrect "
                        + "results on LAST_WRITE channels. "
                        + "Reads proceed on paused channels — projection is a read-only operation.")
    @Blocking
    public String projectChannel(
            @ToolArg(name = "channel",
                     description = "Channel name or UUID") String channel,
            @ToolArg(name = "projection_name",
                     description = "Name matching RenderableProjection.projectionName() "
                                   + "(e.g. 'channel-summary')") String projectionName,
            @ToolArg(name = "max_messages",
                     description = "Maximum number of messages to fold, in insertion order (oldest first). "
                                   + "Null or non-positive = fold full history. Default: null (unlimited).",
                     required = false) Integer maxMessages,
            @ToolArg(name = "topic",
                     description = "Scope the projection to messages in this topic only. "
                                   + "Null = fold all topics. Case-insensitive matching.",
                     required = false) String topic) {
        return projectAndRender(resolveChannel(channel).id(), projectionRegistry.get(projectionName), maxMessages, topic);
    }
}
