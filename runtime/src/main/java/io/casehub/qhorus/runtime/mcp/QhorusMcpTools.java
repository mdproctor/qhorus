package io.casehub.qhorus.runtime.mcp;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.ActorTypeResolver;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelConnectorBinding;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.channel.ChannelDetail;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.channel.ChannelSlugValidator;
import io.casehub.qhorus.api.channel.Presence;
import io.casehub.qhorus.api.channel.PresenceStatus;
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
import io.casehub.qhorus.api.message.Reaction;
import io.casehub.qhorus.api.message.ReactionGroup;
import io.casehub.qhorus.api.message.Topic;
import io.casehub.qhorus.api.message.TopicSummary;
import io.casehub.qhorus.api.spi.InstanceActorIdProvider;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.CommitmentStore;
import io.casehub.qhorus.api.store.DataStore;
import io.casehub.qhorus.api.store.InstanceStore;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.ReactionStore;
import io.casehub.qhorus.api.store.TopicStore;
import io.casehub.qhorus.api.store.WatchdogStore;
import io.casehub.qhorus.api.store.query.ChannelQuery;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.casehub.qhorus.api.watchdog.Watchdog;
import io.casehub.qhorus.runtime.channel.ChannelSummaryService;
import io.casehub.qhorus.runtime.channel.PresenceService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.instance.CapabilityEntity;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntry;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntryRepository;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.ProjectionRegistry;
import io.casehub.qhorus.runtime.message.ReactionService;
import io.casehub.qhorus.runtime.message.TopicService;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.quarkus.arc.properties.UnlessBuildProperty;
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
 * All business logic exceptions ({@link IllegalArgumentException} and
 * {@link IllegalStateException}) thrown from any {@code @Tool} method are
 * automatically wrapped in {@link io.quarkiverse.mcp.server.ToolCallException}
 * by the quarkus-mcp-server interceptor, producing an {@code isError: true}
 * tool response with the exception message as text content. This gives Claude
 * readable errors without changing the happy-path return types of the 37
 * structured-return tools. See ADR-0001.
 */
@McpServer("qhorus")
@UnlessBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true", enableIfMissing = true)
@WrapBusinessError({ IllegalArgumentException.class, IllegalStateException.class })
@ApplicationScoped
public class QhorusMcpTools extends QhorusMcpToolsBase {

    private static final Logger LOG = Logger.getLogger(QhorusMcpTools.class);

    @Inject
    CurrentPrincipal currentPrincipal;

    @Inject
    InstanceService instanceService;

    @Inject
    MessageService messageService;

    @Inject
    io.casehub.qhorus.runtime.data.DataService dataService;

    @Inject
    io.casehub.qhorus.runtime.config.QhorusConfig qhorusConfig;

    @Inject
    MessageLedgerEntryRepository ledgerRepo;

    @Inject
    MessageStore messageStore;

    @Inject
    ChannelStore channelStore;

    @Inject
    DataStore dataStore;

    @Inject
    InstanceStore instanceStore;

    @Inject
    WatchdogStore watchdogStore;

    @Inject
    CommitmentStore commitmentStore;

    @Inject
    ChannelGateway channelGateway;

    @Inject
    jakarta.enterprise.inject.Instance<io.casehub.qhorus.api.gateway.ChannelBackend> availableBackends;

    @Inject
    InstanceActorIdProvider instanceActorIdProvider;

    @Inject
    ProjectionRegistry projectionRegistry;

    @Inject
    TopicService topicService;

    @Inject
    ReactionService reactionService;

    @Inject
    PresenceService presenceService;
    @jakarta.inject.Inject
    io.casehub.qhorus.runtime.channel.ChannelMembershipService membershipService;
    @jakarta.inject.Inject
    io.casehub.qhorus.api.store.ChannelMembershipStore         membershipStore;
    @Inject
    ChannelSummaryService                                      channelSummaryService;


    @Inject
    TopicStore topicStore;
    @Inject
    io.casehub.ledger.api.spi.LedgerEntryRepository ledgerEntryRepository;

    @Inject
    io.casehub.qhorus.runtime.ledger.PeerAttestationWriter peerAttestationWriter;

    @Inject
    io.casehub.qhorus.runtime.ledger.ReviewerResolver reviewerResolver;


    @Inject
    ReactionStore reactionStore;
    @Inject
    io.casehub.qhorus.runtime.message.protocol.ProtocolRegistry protocolRegistry;


    // ---------------------------------------------------------------------------
    // Instance management tools
    // ---------------------------------------------------------------------------

    @Tool(name = "register", description = "Register an agent instance with capability tags. "
            + "Set read_only=true for dashboard/observer instances that only read EVENT messages. "
            + "Returns active channels and online instances as immediate context.")
    @Transactional
    public RegisterResponse register(
            @ToolArg(name = "instance_id", description = "Unique human-readable identifier for this agent") String instanceId,
            @ToolArg(name = "description", description = "Description of this agent's role") String description,
            @ToolArg(name = "capabilities", description = "Capability tags for peer discovery", required = false) List<String> capabilities,
            @ToolArg(name = "claudony_session_id", description = "Optional Claudony session ID for managed workers", required = false) String claudonySessionId,
            @ToolArg(name = "read_only", description = "If true, instance is read-only: cannot send messages, and check_messages with include_events=true returns EVENT messages. Default false.", required = false) Boolean readOnly) {
        List<String> caps = capabilities != null ? capabilities : List.of();
        boolean        ro       = readOnly != null && readOnly;
        Instance instance = instanceService.register(instanceId, description, caps, claudonySessionId, ro);

        List<ChannelInfo> channels = channelService.listAll().stream()
                                                   .map(ch -> new ChannelInfo(ch.name(), ch.description(), ch.semantic().name()))
                                                   .toList();

        List<InstanceInfo> onlineInstances = buildInstanceInfoList(instanceService.listAll());

        return new RegisterResponse(instance.instanceId(), channels, onlineInstances);
    }

    /** Backward-compat overload — no read_only param. */
    @Transactional
    RegisterResponse register(String instanceId, String description, List<String> capabilities,
            String claudonySessionId) {
        return register(instanceId, description, capabilities, claudonySessionId, null);
    }

    /**
     * Convenience overload used by ledger-package tests that need a per-channel registration style.
     * In Qhorus, instance registration is global — the {@code channelName} parameter is accepted
     * for API symmetry but not used for scoping.
     */
    @Transactional
    /** Convenience overload — no role or extra. Backward compatibility for tests. */
    public RegisterResponse registerInstance(String channelName, String instanceId,
            String description, List<String> capabilities, String claudonySessionId) {
        return registerInstance(channelName, instanceId, description, capabilities, claudonySessionId, null, null);
    }

    public RegisterResponse registerInstance(
            String channelName,
            String instanceId,
            String description,
            List<String> capabilities,
            String claudonySessionId,
            String role,
            String extra) {
        List<String> caps = capabilities != null ? capabilities : List.of();
        Instance instance = instanceService.register(instanceId,
                description != null ? description : instanceId, caps, claudonySessionId);

        List<ChannelInfo> channels = channelService.listAll().stream()
                                                   .map(ch -> new ChannelInfo(ch.name(), ch.description(), ch.semantic().name()))
                                                   .toList();

        List<InstanceInfo> onlineInstances = buildInstanceInfoList(instanceService.listAll());
        return new RegisterResponse(instance.instanceId(), channels, onlineInstances);
    }

    @Tool(name = "list_instances", description = "List registered agent instances. "
            + "Optionally filter by capability tag.")
    public List<InstanceInfo> listInstances(
            @ToolArg(name = "capability", description = "Filter by capability tag (optional)", required = false) String capability) {
        List<Instance> instances = (capability != null && !capability.isBlank())
                ? instanceService.findByCapability(capability)
                : instanceService.listAll();
        return buildInstanceInfoList(instances);
    }

    @Tool(name = "get_instance", description = "Look up a registered instance by its ID. "
            + "Returns full instance details including capabilities and status. "
            + "Throws an error if the instance is not found.")
    @Transactional
    public InstanceInfo getInstance(
            @ToolArg(name = "instance_id", description = "Instance ID to look up") String instanceId) {
        Instance instance = instanceService.findByInstanceId(instanceId)
                                                 .orElseThrow(() -> new IllegalArgumentException(
                        "Instance not found: " + instanceId));
        return buildInstanceInfoList(java.util.List.of(instance)).get(0);
    }

    // ---------------------------------------------------------------------------
    // Channel management tools
    // ---------------------------------------------------------------------------

    @Tool(name = "create_channel", description = "Create a new communication channel for agents to exchange messages. " +
                                                 "Returns channel details including the generated UUID and configured properties.")
    public ChannelDetail createChannel(
            @ToolArg(name = "name", description = "Unique channel name. Each /-delimited segment must match " +
                                                  "[a-z][a-z0-9]*(-[a-z0-9]+)* — lowercase letters and digits, hyphens only between " +
                                                  "alphanumeric groups. No leading, trailing, or consecutive hyphens. Max 80 chars per " +
                                                  "segment, 200 chars total. UUID-shaped names are rejected. " +
                                                  "Examples: \"billing-output\", \"case-abc/work\".") String name,
            @ToolArg(name = "description", description = "Channel purpose description") String description,
            @ToolArg(name = "semantic", description = "Channel semantic: APPEND (default), COLLECT, BARRIER, EPHEMERAL, LAST_WRITE", required = false) String semantic,
            @ToolArg(name = "barrier_contributors", description = "Comma-separated contributor names (BARRIER channels only)", required = false) String barrierContributors,
            @ToolArg(name = "allowed_writers", description = "Comma-separated allowed writers: bare instance IDs and/or capability:tag / role:name patterns. Null = open to all.", required = false) String allowedWriters,
            @ToolArg(name = "admin_instances", description = "Comma-separated instance IDs permitted to manage this channel (pause/resume/force_release/clear). Null = open to any caller.", required = false) String adminInstances,
            @ToolArg(name = "rate_limit_per_channel", description = "Max messages per minute across all senders. Null = unlimited.", required = false) Integer rateLimitPerChannel,
            @ToolArg(name = "rate_limit_per_instance", description = "Max messages per minute from a single sender. Null = unlimited.", required = false) Integer rateLimitPerInstance,
            @ToolArg(name = "allowed_types", description = "Comma-separated MessageType names permitted on this channel. Null = all types permitted.", required = false) String allowedTypes,
            @ToolArg(name = "denied_types", description = "Comma-separated MessageType names explicitly denied on this channel. Denial wins if a type appears in both.", required = false) String deniedTypes,
            @ToolArg(name = "space_id", description = "Space UUID to place this channel in. Null = top-level channel.", required = false) String spaceId,
            @ToolArg(name = "reviewer_ids", description = "Comma-separated reviewer instance IDs for automatic peer review after DONE. Null = no auto-review.", required = false) String reviewerIds,
            @ToolArg(name = "protocols", description = "Comma-separated protocol names to enforce on this channel (e.g. ROUND_ROBIN,CONTRIBUTION_REQUIRED). Null = no protocols.", required = false) String protocols,
            @ToolArg(name = "protocol_participants", description = "Comma-separated ordered participant IDs for protocol enforcement. Required for ROUND_ROBIN.", required = false) String protocolParticipants,
            @ToolArg(name = "inbound_connector_id", description = "Inbound connector type identifier. All four connector fields must be set together or left null.", required = false) String inboundConnectorId,
            @ToolArg(name = "external_key", description = "Connector-specific lookup key.", required = false) String externalKey,
            @ToolArg(name = "outbound_connector_id", description = "Outbound connector type identifier.", required = false) String outboundConnectorId,
            @ToolArg(name = "outbound_destination", description = "Outbound destination address.", required = false) String outboundDestination,
            @ToolArg(name = "track_delivery", description = "Enable per-participant delivery tracking. Null = semantic default (on for BARRIER/COLLECT, off for others). true = explicit opt-in, false = explicit opt-out.", required = false) Boolean trackDelivery) {
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
        Channel ch = channelService.create(ChannelCreateRequest.builder(name)
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
                                                               .reviewerInstances(splitCsv(reviewerIds))
                                                               .protocols(splitCsv(protocols))
                                                               .protocolParticipants(splitCsv(protocolParticipants))
                                                               .inboundConnectorId(inboundConnectorId)
                                                               .externalKey(externalKey)
                                                               .outboundConnectorId(outboundConnectorId)
                                                               .outboundDestination(outboundDestination)
                                                               .trackDelivery(trackDelivery)
                                                               .build());
        return toChannelDetail(ch, 0L);
    }


    @Tool(name = "update_channel_binding", description = "Update the outbound connector fields of an existing channel binding. "
            + "Use this to rotate an outbound destination (e.g. a refreshed Slack webhook URL or a new phone number) "
            + "without a service restart. Fires ChannelInitialisedEvent to refresh in-memory caches.")
    @Transactional
    public ChannelDetail updateChannelBinding(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "outbound_connector_id", description = "New outbound connector identifier") String outboundConnectorId,
            @ToolArg(name = "outbound_destination", description = "New outbound destination (e.g. webhook URL, phone number)") String outboundDestination) {
        Channel ch = resolveChannel(channel);
        channelService.updateConnectorBinding(ch.id(), outboundConnectorId, outboundDestination);
        return toChannelDetail(ch, messageStore.countByChannel(ch.id()));
    }

    @Tool(name = "set_channel_rate_limits", description = "Update the rate limits on an existing channel. "
            + "Pass null to remove a limit (restores unrestricted behaviour). "
            + "Limits are enforced via an in-memory sliding 60-second window that resets on restart.")
    @Transactional
    public ChannelDetail setChannelRateLimits(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "rate_limit_per_channel", description = "Max messages per minute across all senders. Null = unlimited.", required = false) Integer rateLimitPerChannel,
            @ToolArg(name = "rate_limit_per_instance", description = "Max messages per minute from a single sender. Null = unlimited.", required = false) Integer rateLimitPerInstance) {
        Channel resolved = resolveChannel(channel);
        Channel ch       = channelService.setRateLimits(resolved.id(), rateLimitPerChannel, rateLimitPerInstance);
        return toChannelDetail(ch, messageStore.countByChannel(ch.id()));
    }

    @Tool(name = "set_delivery_tracking",
          description = "Enable, disable, or reset delivery tracking on a channel. "
                        + "true = explicit opt-in, false = explicit opt-out, null/omit = revert to semantic default "
                        + "(BARRIER/COLLECT = on, others = off). "
                        + "When enabling on a channel with existing messages, initializes member cursors to the latest message ID.")
    @Transactional
    public String setDeliveryTracking(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "tracking", description = "true/false/null — null reverts to semantic default", required = false) Boolean tracking) {
        Channel ch = resolveChannel(channel);
        channelService.setTrackDelivery(ch.id(), tracking);
        Channel updated   = ch.toBuilder().trackDelivery(tracking).build();
        boolean effective = io.casehub.qhorus.runtime.channel.ChannelService.isDeliveryTrackingEnabled(updated);
        return "Delivery tracking " + (effective ? "enabled" : "disabled")
               + " on channel '" + ch.name() + "'";
    }


    @Tool(name = "set_channel_writers", description = "Update the write ACL on an existing channel. "
            + "Pass null or blank to open the channel to all writers.")
    @Transactional
    public ChannelDetail setChannelWriters(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "allowed_writers", description = "Comma-separated allowed writers (instance IDs and/or capability:tag / role:name). Null = open to all.", required = false) String allowedWriters) {
        Channel resolved = resolveChannel(channel);
        Channel ch       = channelService.setAllowedWriters(resolved.id(), splitCsv(allowedWriters));
        return toChannelDetail(ch, messageStore.countByChannel(ch.id()));
    }

    @Tool(name = "set_channel_admins", description = "Update the admin instance list on an existing channel. "
            + "Admins may invoke pause_channel, resume_channel, force_release_channel, and clear_channel. "
            + "Pass null or blank to open management to any caller.")
    @Transactional
    public ChannelDetail setChannelAdmins(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "admin_instances", description = "Comma-separated instance IDs permitted to manage this channel. Null = open to any caller.", required = false) String adminInstances) {
        Channel resolved = resolveChannel(channel);
        Channel ch       = channelService.setAdminInstances(resolved.id(), splitCsv(adminInstances));
        return toChannelDetail(ch, messageStore.countByChannel(ch.id()));
    }

    @Tool(name = "set_channel_reviewers", description = "Update the reviewer list on an existing channel. "
                                                        + "Reviewers receive automatic peer review QUERYs after DONE messages. "
                                                        + "Pass null or blank to disable auto-review.")
    @Transactional
    public ChannelDetail setChannelReviewers(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "reviewer_ids", description = "Comma-separated reviewer instance IDs. Null = disable auto-review.", required = false) String reviewerIds) {
        Channel resolved = resolveChannel(channel);
        Channel ch       = channelService.setReviewerInstances(resolved.id(), splitCsv(reviewerIds));
        return toChannelDetail(ch, messageStore.countByChannel(ch.id()));
    }

    @Tool(name = "list_protocols", description = "List all registered channel protocol names")
    public List<String> listProtocols() {
        return new java.util.ArrayList<>(protocolRegistry.allNames());
    }

    @Tool(name = "set_channel_protocols", description = "Set the protocols for a channel (full replacement). " +
                                                        "ROUND_ROBIN requires protocol_participants to be set first.")
    public ChannelDetail setChannelProtocols(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "protocols", description = "Comma-separated protocol names (e.g. ROUND_ROBIN,CONTRIBUTION_REQUIRED). Empty string to clear.") String protocols) {
        Channel      ch           = resolveChannel(channel);
        List<String> protocolList = splitCsv(protocols);
        if (protocolList.contains("ROUND_ROBIN") && ch.protocolParticipants().isEmpty()) {
            throw new IllegalArgumentException(
                    "ROUND_ROBIN requires protocolParticipants — set them first with set_protocol_participants");
        }
        Channel updated = channelService.setProtocols(ch.id(), protocolList);
        return toChannelDetail(updated, messageStore.countByChannel(ch.id()));
    }

    @Tool(name = "set_protocol_participants", description = "Set the ordered protocol participants for a channel (full replacement). " +
                                                            "Defines turn order for ROUND_ROBIN and contribution tracking for CONTRIBUTION_REQUIRED.")
    public ChannelDetail setProtocolParticipants(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "participants", description = "Comma-separated ordered participant instance IDs. Empty string to clear.") String participants) {
        Channel ch      = resolveChannel(channel);
        Channel updated = channelService.setProtocolParticipants(ch.id(), splitCsv(participants));
        return toChannelDetail(updated, messageStore.countByChannel(ch.id()));
    }

    @Tool(name = "get_channel_protocols", description = "Get the protocols and protocol participants configured on a channel")
    public java.util.Map<String, Object> getChannelProtocols(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel) {
        Channel ch = resolveChannel(channel);
        return java.util.Map.of(
                "protocols", ch.protocols(),
                "protocol_participants", ch.protocolParticipants());
    }


    @Tool(name = "attest", description = "Record a peer attestation (ENDORSED or CHALLENGED) "
                                         + "on a COMMAND or HANDOFF ledger entry. Self-attestation is rejected.")
    @Transactional
    public Map<String, Object> attest(
            @ToolArg(name = "entry_id", description = "UUID of the COMMAND/HANDOFF ledger entry") String entryId,
            @ToolArg(name = "verdict", description = "ENDORSED or CHALLENGED") String verdict,
            @ToolArg(name = "evidence", description = "Free-text evidence for the attestation", required = false) String evidence) {
        UUID id = UUID.fromString(entryId);
        io.casehub.ledger.api.model.AttestationVerdict v =
                io.casehub.ledger.api.model.AttestationVerdict.valueOf(verdict.toUpperCase());
        String tenancyId   = currentPrincipal.tenancyId();
        String attestorId  = currentPrincipal.actorId();
        var    attestation = peerAttestationWriter.write(id, v, evidence, attestorId, tenancyId);
        return Map.of("attestation_id", attestation.id,
                      "entry_id", id, "verdict", v.name(), "attestor_id", attestorId);
    }

    @Tool(name = "list_attestations", description = "List all attestations (policy and peer) on a ledger entry.")
    public List<Map<String, Object>> listAttestations(
            @ToolArg(name = "entry_id", description = "UUID of the ledger entry") String entryId) {
        UUID   id        = UUID.fromString(entryId);
        String tenancyId = currentPrincipal.tenancyId();
        return ledgerEntryRepository.findAttestationsByEntryId(id, tenancyId).stream()
                                    .map(a -> {
                                        var map = new java.util.LinkedHashMap<String, Object>();
                                        map.put("attestation_id", a.id);
                                        map.put("verdict", a.verdict.name());
                                        map.put("attestor_id", a.attestorId);
                                        map.put("attestor_role", a.attestorRole != null ? a.attestorRole : "policy");
                                        map.put("evidence", a.evidence != null ? a.evidence : "");
                                        map.put("confidence", a.confidence);
                                        map.put("occurred_at", a.occurredAt != null ? a.occurredAt.toString() : "");
                                        return (Map<String, Object>) map;
                                    })
                                    .toList();
    }

    @Tool(name = "request_peer_review", description = "Send peer review QUERYs to reviewers for a COMMAND/HANDOFF entry. "
                                                      + "Reviewers resolved from explicit list, channel config, capability routing, or CDI event.")
    @Transactional
    public Map<String, Object> requestPeerReview(
            @ToolArg(name = "entry_id", description = "UUID of the COMMAND/HANDOFF ledger entry") String entryId,
            @ToolArg(name = "reviewer_ids", description = "Comma-separated reviewer instance IDs. Resolved automatically if omitted.", required = false) String reviewerIds,
            @ToolArg(name = "channel", description = "Channel for the review QUERYs. Defaults to the entry's channel.", required = false) String channel) {
        UUID   id        = UUID.fromString(entryId);
        String tenancyId = currentPrincipal.tenancyId();
        var entry = (io.casehub.qhorus.runtime.ledger.MessageLedgerEntry) ledgerEntryRepository
                                                                                  .findEntryById(id, tenancyId)
                                                                                  .orElseThrow(() -> new IllegalArgumentException("Ledger entry not found: " + id));
        if (!"COMMAND".equals(entry.messageType) && !"HANDOFF".equals(entry.messageType)) {
            throw new IllegalArgumentException("Entry must be COMMAND or HANDOFF, not " + entry.messageType);
        }

        UUID         channelId = channel != null ? resolveChannel(channel).id() : entry.channelId;
        List<String> reviewers = reviewerResolver.resolve(channelId, splitCsv(reviewerIds), id, tenancyId);
        if (reviewers.isEmpty()) {
            return Map.of("reviewers_sent", 0, "advisory", "No reviewers resolved — configure channel reviewers or register instances with peer-reviewer capability.");
        }

        String completionContent = null;
        if (entry.correlationId != null) {
            var terminalEntry = ledgerRepo.findLatestByCorrelationId(entry.channelId, entry.correlationId, tenancyId);
            if (terminalEntry.isPresent()) {
                completionContent = terminalEntry.get().content;
            }
        }

        var sentReviews = new java.util.ArrayList<Map<String, String>>();
        for (String reviewerId : reviewers) {
            try {
                var peerReview = mapper.createObjectNode();
                peerReview.put("ledger_entry_id", id.toString());
                peerReview.put("original_command", entry.content);
                peerReview.put("completion_content", completionContent);
                var content = mapper.createObjectNode();
                content.set("peer_review", peerReview);

                String corrId = UUID.randomUUID().toString();
                messageService.dispatch(MessageDispatch.builder()
                                                       .channelId(channelId)
                                                       .sender(currentPrincipal.actorId())
                                                       .type(MessageType.QUERY)
                                                       .content(mapper.writeValueAsString(content))
                                                       .correlationId(corrId)
                                                       .target(reviewerId)
                                                       .actorType(ActorType.SYSTEM)
                                                       .tenancyId(tenancyId)
                                                       .build());
                sentReviews.add(Map.of("reviewer_id", reviewerId, "correlation_id", corrId));
            } catch (Exception e) {
                LOG.warnf(e, "Failed to send peer review QUERY to %s for entry %s", reviewerId, id);
            }
        }
        return Map.of("reviewers_sent", sentReviews.size(), "reviews", sentReviews);
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
    public ChannelDetail setChannelTypeConstraints(
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
        Channel resolved = resolveChannel(channel);
        Channel ch = channelService.setTypeConstraints(resolved.id(),
                                                             MessageType.parseTypes(allowedTypes), MessageType.parseTypes(deniedTypes));
        return toChannelDetail(ch, messageStore.countByChannel(ch.id()));
    }

    @Tool(name = "list_channels", description = "List all channels with message count and last activity.")
    public List<ChannelDetail> listChannels() {
        List<Channel> channels = channelService.listAll();
        if (channels.isEmpty()) {
            return List.of();
        }
        Map<UUID, Long>                    countByChannel = messageStore.countAllByChannel();
        Map<UUID, ChannelConnectorBinding> allBindings    = bindingStore.findAll();
        Map<UUID, String>                  spaceNames     = buildSpaceNameMap(channels);
        return channels.stream()
                       .map(ch -> toChannelDetail(ch, countByChannel.getOrDefault(ch.id(), 0L), allBindings, spaceNames))
                       .toList();}

    @Tool(name = "find_channel", description = "Search channels by keyword in name or description.")
    public List<ChannelDetail> findChannel(
            @ToolArg(name = "keyword", description = "Search term (case-insensitive)") String keyword) {
        List<Channel> matches = channelStore.scan(ChannelQuery.byKeyword(keyword));
        return matches.stream()
                .map(ch -> toChannelDetail(ch, messageStore.countByChannel(ch.id())))
                .toList();
    }

    // ---------------------------------------------------------------------------
    // Human-in-the-loop — channel flow control
    // ---------------------------------------------------------------------------

    /** Convenience overload — no caller identity (open governance assumed). */
    ChannelDetail pauseChannel(String channel) {
        return pauseChannel(channel, null);
    }

    @Tool(name = "pause_channel", description = "Pause a channel — blocks send_message and returns empty on check_messages. "
            + "Idempotent. Use to stop agent work flowing through a channel for human review.")
    @Transactional
    public ChannelDetail pauseChannel(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "caller_instance_id", description = "Instance ID of the caller. Required when the channel has an admin_instances list.", required = false) String callerInstanceId) {
        Channel ch = resolveChannel(channel);
        checkAdminAccess(ch, callerInstanceId, "pause_channel");
        ch = channelService.pause(ch.id());
        return toChannelDetail(ch, messageStore.countByChannel(ch.id()));
    }

    /** Convenience overload — no caller identity (open governance assumed). */
    ChannelDetail resumeChannel(String channel) {
        return resumeChannel(channel, null);
    }

    @Tool(name = "resume_channel", description = "Resume a paused channel — re-enables send_message and check_messages. "
            + "Idempotent.")
    @Transactional
    public ChannelDetail resumeChannel(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "caller_instance_id", description = "Instance ID of the caller. Required when the channel has an admin_instances list.", required = false) String callerInstanceId) {
        Channel ch = resolveChannel(channel);
        checkAdminAccess(ch, callerInstanceId, "resume_channel");
        ch = channelService.resume(ch.id());
        return toChannelDetail(ch, messageStore.countByChannel(ch.id()));
    }

    /** Convenience overload — no caller identity (open governance assumed). */
    DeleteChannelResult deleteChannel(String channel, Boolean force) {
        return deleteChannel(channel, force, null);
    }

    @Tool(name = "delete_channel", description = "Delete a named channel. "
            + "Rejects with an error if the channel has messages unless force=true. "
            + "When force=true, all messages in the channel are deleted before the channel is removed. "
            + "Subject to admin_instances check if the channel has an admin list.")
    @Transactional
    public DeleteChannelResult deleteChannel(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "force", description = "When true, deletes all messages in the channel then "
                    + "deletes the channel. When false (default), rejects if messages exist.", required = false) Boolean force,
            @ToolArg(name = "caller_instance_id", description = "Instance ID of the caller. Required when the channel has an admin_instances list.", required = false) String callerInstanceId) {
        Channel ch = resolveChannel(channel);
        checkAdminAccess(ch, callerInstanceId, "delete_channel");
        membershipStore.deleteAll(ch.id());
        reactionStore.deleteByChannel(ch.id());
        commitmentStore.deleteAll(ch.id());
        topicStore.deleteAll(ch.id());
        long deleted = channelService.delete(ch.id(), Boolean.TRUE.equals(force));
        channelGateway.closeChannel(ch.id(), new ChannelRef(ch.id(), ch.name()));
        return new DeleteChannelResult(ch.name(), deleted, "deleted");
    }

    @Tool(name = "list_backends", description = "List all registered channel backends for a channel. "
            + "Always includes 'qhorus-internal' (the Qhorus agent backend). "
            + "External backends (human-participating, human-observer) appear after registration.")
    public List<BackendInfo> listBackends(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel) {
        Channel ch = resolveChannel(channel);
        return channelGateway.listBackends(ch.id()).stream()
                .map(r -> new BackendInfo(r.backendId(), r.backendType(),
                        r.actorType().name().toLowerCase()))
                .toList();
    }

    @Tool(name = "deregister_backend", description = "Remove a registered backend from a channel. "
            + "Cannot remove 'qhorus-internal'.")
    public DeregisterBackendResult deregisterBackend(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "backend_id", description = "ID of the backend to remove") String backendId) {
        Channel ch = resolveChannel(channel);
        channelGateway.deregisterBackend(ch.id(), backendId);
        return new DeregisterBackendResult(ch.name(), backendId, true,
                "Backend " + backendId + " deregistered from " + ch.name());
    }

    @Tool(name = "register_backend", description = "Associate a CDI-registered channel backend with a channel. "
            + "The backend must already exist as a CDI bean (identified by its backendId). "
            + "backend_type: 'human_participating' (at most one per channel) or 'human_observer' (unlimited). "
            + "Cannot register 'qhorus-internal' as a human backend — it is always the agent backend.")
    public RegisterBackendResult registerBackend(
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
        Channel ch = resolveChannel(channel);
        io.casehub.qhorus.api.gateway.ChannelBackend backend =
                java.util.stream.StreamSupport.stream(availableBackends.spliterator(), false)
                        .filter(b -> backendId.equals(b.backendId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "No CDI backend registered with backendId: " + backendId
                                + ". Ensure the backend bean is deployed and its backendId() returns '" + backendId + "'."));
        channelGateway.registerBackend(ch.id(), backend, backendType);
        return new RegisterBackendResult(ch.name(), backendId, backendType,
                "Backend " + backendId + " registered as " + backendType + " on channel " + ch.name());
    }

    /** Convenience overload — no caller identity (open governance assumed). */
    ForceReleaseResult forceReleaseChannel(String channel, String reason) {
        return forceReleaseChannel(channel, reason, null);
    }

    @Tool(name = "force_release_channel", description = "Force-deliver all accumulated messages and clear a BARRIER or COLLECT channel, "
            + "bypassing normal release conditions. Use when a BARRIER is stuck (missing contributors) "
            + "or to collect early from a COLLECT channel. Posts an audit event. "
            + "Only valid for BARRIER and COLLECT semantics.")
    @Transactional
    public ForceReleaseResult forceReleaseChannel(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "reason", description = "Reason for the force-release (recorded in audit event)", required = false) String reason,
            @ToolArg(name = "caller_instance_id", description = "Instance ID of the caller. Required when the channel has an admin_instances list.", required = false) String callerInstanceId) {
        Channel ch = resolveChannel(channel);
        checkAdminAccess(ch, callerInstanceId, "force_release_channel");

        if (ch.semantic() != ChannelSemantic.BARRIER && ch.semantic() != ChannelSemantic.COLLECT) {
            throw new IllegalArgumentException(
                    "force_release_channel only applies to BARRIER and COLLECT channels, not "
                    + ch.semantic().name());
        }

        List<Message> messages = messageStore.scan(MessageQuery.builder()
                                                               .channelId(ch.id()).excludeTypes(List.of(MessageType.EVENT)).build());
        messageStore.deleteNonEvent(ch.id());

        String auditTelemetry = (reason != null && !reason.isBlank())
                                ? telemetryJson("action", "force_release_channel", "reason", reason)
                                : telemetryJson("action", "force_release_channel");
        messageService.dispatch(MessageDispatch.builder()
                                               .channelId(ch.id()).sender("system").type(MessageType.EVENT)
                                               .telemetry(auditTelemetry).actorType(ActorType.SYSTEM).build());

        channelService.updateLastActivity(ch.id(), ch.tenancyId());

        List<MessageSummary> summaries = messages.stream().map(this::toMessageSummary).toList();
        return new ForceReleaseResult(ch.name(), ch.semantic().name(), messages.size(), summaries);}

    // ---------------------------------------------------------------------------
    // Topic tools
    // ---------------------------------------------------------------------------

    @Tool(name = "list_topics", description = "List all topics in a channel with message counts and activity timestamps")
    public List<TopicSummary> listTopics(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel) {
        Channel ch = resolveChannel(channel);
        return topicService.listTopics(ch.id());
    }

    @Tool(name = "resolve_topic", description = "Mark a topic as resolved (done). Messages remain queryable but visually distinct in UI.")
    @Transactional
    public Topic resolveTopic(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "topic_name", description = "Name of the topic to resolve") String topicName,
            @ToolArg(name = "caller_instance_id", description = "Instance ID of the caller", required = false) String callerInstanceId) {
        Channel ch      = resolveChannel(channel);
        String  actorId = callerInstanceId != null ? callerInstanceId : "anonymous";
        return topicService.resolve(ch.id(), topicName, actorId);}

    @Tool(name = "unresolve_topic", description = "Unresolve a previously resolved topic")
    @Transactional
    public Topic unresolveTopic(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "topic_name", description = "Name of the topic to unresolve") String topicName) {
        Channel ch = resolveChannel(channel);
        return topicService.unresolve(ch.id(), topicName);}

    @Tool(name = "rename_topic", description = "Rename a topic — updates all messages in the topic and emits an audit EVENT in the ledger")
    @Transactional
    public TopicService.RenameResult renameTopic(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "old_name", description = "Current topic name") String oldName,
            @ToolArg(name = "new_name", description = "New topic name") String newName,
            @ToolArg(name = "caller_instance_id", description = "Instance ID of the caller", required = false) String callerInstanceId) {
        Channel                   ch      = resolveChannel(channel);
        String                    actorId = callerInstanceId != null ? callerInstanceId : "anonymous";
        TopicService.RenameResult result  = topicService.rename(ch.id(), oldName, newName, actorId);
        messageService.dispatch(MessageDispatch.builder()
                                               .channelId(ch.id())
                                               .sender("system:topic-service")
                                               .type(MessageType.EVENT)
                                               .telemetry(telemetryJson("action", "topic-renamed",
                                                                        "old_name", result.oldName(),
                                                                        "new_name", result.newName(),
                                                                        "messages_updated", result.messagesUpdated()))
                                               .actorType(ActorType.SYSTEM)
                                               .build());
        return result;}

    @Tool(name = "merge_topics", description = "Merge a source topic into a target topic — moves all messages and deletes the source topic. Emits an audit EVENT.")
    @Transactional
    public TopicService.MergeResult mergeTopics(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "source_topic", description = "Topic to merge from (will be deleted)") String sourceTopic,
            @ToolArg(name = "target_topic", description = "Topic to merge into (will receive messages)") String targetTopic,
            @ToolArg(name = "caller_instance_id", description = "Instance ID of the caller", required = false) String callerInstanceId) {
        Channel                  ch      = resolveChannel(channel);
        String                   actorId = callerInstanceId != null ? callerInstanceId : "anonymous";
        TopicService.MergeResult result  = topicService.merge(ch.id(), sourceTopic, targetTopic, actorId);
        messageService.dispatch(MessageDispatch.builder()
                                               .channelId(ch.id())
                                               .sender("system:topic-service")
                                               .type(MessageType.EVENT)
                                               .telemetry(telemetryJson("action", "topics-merged",
                                                                        "source_topic", result.sourceTopic(),
                                                                        "target_topic", result.targetTopic(),
                                                                        "messages_updated", result.messagesUpdated()))
                                               .actorType(ActorType.SYSTEM)
                                               .build());
        return result;}

    @Tool(name = "move_topic", description = "Move all messages in a topic from one channel to another. Blocks if open commitments exist. Emits audit EVENTs in both channels.")
    @Transactional
    public TopicService.MoveResult moveTopic(
            @ToolArg(name = "source_channel", description = "Source channel name or UUID") String sourceChannel,
            @ToolArg(name = "topic_name", description = "Topic to move") String topicName,
            @ToolArg(name = "target_channel", description = "Target channel name or UUID") String targetChannel,
            @ToolArg(name = "caller_instance_id", description = "Instance ID of the caller", required = false) String callerInstanceId) {
        Channel src = resolveChannel(sourceChannel);
        Channel tgt = resolveChannel(targetChannel);
        if (src.id().equals(tgt.id())) {
            throw new IllegalArgumentException("Source and target channels must be different");
        }
        if (!java.util.Objects.equals(src.tenancyId(), tgt.tenancyId())) {
            throw new IllegalArgumentException("Source and target channels must share the same tenancy");
        }
        io.casehub.qhorus.api.channel.ChannelSemantic semantic = tgt.semantic();
        if (semantic != io.casehub.qhorus.api.channel.ChannelSemantic.APPEND
            && semantic != io.casehub.qhorus.api.channel.ChannelSemantic.COLLECT) {
            throw new IllegalArgumentException("Target channel semantic must be APPEND or COLLECT, not " + semantic);
        }
        String                  actorId = callerInstanceId != null ? callerInstanceId : "anonymous";
        TopicService.MoveResult result  = topicService.move(src.id(), topicName, tgt.id(), actorId);
        messageService.dispatch(MessageDispatch.builder()
                                               .channelId(src.id()).sender("system:topic-service").type(MessageType.EVENT)
                                               .telemetry(telemetryJson("action", "topic-moved-out",
                                                                        "topic", result.topicName(),
                                                                        "target_channel", tgt.name(),
                                                                        "messages_moved", result.messagesUpdated()))
                                               .actorType(ActorType.SYSTEM).build());
        messageService.dispatch(MessageDispatch.builder()
                                               .channelId(tgt.id()).sender("system:topic-service").type(MessageType.EVENT)
                                               .telemetry(telemetryJson("action", "topic-moved-in",
                                                                        "topic", result.topicName(),
                                                                        "source_channel", src.name(),
                                                                        "messages_moved", result.messagesUpdated()))
                                               .actorType(ActorType.SYSTEM).build());
        return result;}



    // ---------------------------------------------------------------------------
    // Messaging tools
    // ---------------------------------------------------------------------------


    @Tool(name = "send_message", description = "Post a typed message to a channel. "
            + "For QUERY and COMMAND types, correlation_id is auto-generated if not supplied.")
    @Transactional
    public DispatchResult sendMessage(
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
            @ToolArg(name = "caused_by_entry_id", description = "Optional UUID of the ledger entry that triggered this dispatch (for causal chain tracing).", required = false) String causedByEntryId,
            @ToolArg(name = "topic", description = "Topic name for this message. Groups messages into named sub-conversations within the channel. Defaults to 'general' if omitted.", required = false) String topic) {
        Channel ch = resolveChannel(channel);

        // Read-only instance check — read_only instances cannot send any messages (MCP-specific)
        instanceService.findByInstanceId(sender).ifPresent(inst -> {
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
                instanceService.findByInstanceId(sender).ifPresent(inst -> {
                    for (java.util.UUID uuid : uuidRefs) {
                        dataService.claim(uuid, inst.id());
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

        DispatchResult dispatchResult = messageService.dispatch(
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
                        .topic(topic)
                        .build());

        // Fetch the persisted entity to write deadline as a dirty-entity update in the same transaction.
        if (deadline != null && !deadline.isBlank() && msgType.requiresCorrelationId()) {
            Message msg = messageService.findById(dispatchResult.messageId()).orElseThrow();
            messageStore.put(msg.toBuilder()
                    .deadline(java.time.Instant.now().plus(java.time.Duration.parse(deadline))).build());
        }

        // Auto-release artefact claims when a commitment resolves (RESPONSE/DONE/DECLINE/FAILURE).
        // Find the original QUERY/COMMAND message by correlationId and release the requester's claims.
        // HANDOFF delegates obligation — claims stay until the delegate resolves.
        if (dispatchResult.correlationId() != null && (msgType == MessageType.RESPONSE || msgType == MessageType.DONE
                || msgType == MessageType.DECLINE || msgType == MessageType.FAILURE)) {
            try {
                messageService.findByCorrelationId(dispatchResult.correlationId()).ifPresent(original -> {
                    if (original.artefactRefs() != null && !original.artefactRefs().isEmpty()) {
                        instanceService.findByInstanceId(original.sender()).ifPresent(inst -> {
                            for (io.casehub.qhorus.api.message.ArtefactRef ref : original.artefactRefs()) {
                                try { dataService.release(UUID.fromString(ref.uri()), inst.id()); }
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

    /** Backward-compat overload — no reader_instance_id filter, no include_events. */
    CheckResult checkMessages(String channelName, Long afterId, Integer limit, String sender) {
        return checkMessages(channelName, afterId, limit, sender, null, null);
    }

    /** Backward-compat overload — no include_events. */
    CheckResult checkMessages(String channelName, Long afterId, Integer limit, String sender,
            String readerInstanceId) {
        return checkMessages(channelName, afterId, limit, sender, readerInstanceId, null);
    }

    @Tool(name = "check_messages", description = "Poll for messages on a channel after a given cursor ID. "
            + "Excludes EVENT type by default — set include_events=true to receive EVENT messages "
            + "(intended for read_only instances acting as dashboards/observers). "
            + "Returns messages and last_id for subsequent polling. "
            + "Behaviour varies by channel semantic: EPHEMERAL deletes on read, "
            + "COLLECT delivers all and clears, BARRIER blocks until all contributors have written.")
    @Transactional
    public CheckResult checkMessages(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "after_id", description = "Return messages with ID > after_id (use 0 for all)", required = false) Long afterId,
            @ToolArg(name = "limit", description = "Maximum messages to return (default 20)", required = false) Integer limit,
            @ToolArg(name = "sender", description = "Filter by sender (optional)", required = false) String sender,
            @ToolArg(name = "reader_instance_id", description = "Calling agent's instance ID for target filtering. "
                    + "When provided, only broadcast (null target) and instance:<reader> messages are returned.", required = false) String readerInstanceId,
            @ToolArg(name = "include_events", description = "If true, include EVENT messages in results (default false). "
                    + "Used by read_only instances to receive telemetry events.", required = false) Boolean includeEvents) {
        Channel ch = resolveChannel(channel);

        if (ch.paused()) {
            return new CheckResult(List.of(), afterId != null ? afterId : 0L, "Channel is paused");
        }

        long cursor = afterId != null ? afterId : 0L;
        int pageSize = limit != null ? limit : 20;
        boolean events = includeEvents != null && includeEvents;

        return switch (ch.semantic()) {
            case EPHEMERAL -> checkMessagesEphemeral(ch, cursor, pageSize, readerInstanceId);
            case COLLECT -> checkMessagesCollect(ch, readerInstanceId);
            case BARRIER -> checkMessagesBarrier(ch, readerInstanceId);
            default -> checkMessagesAppend(ch, cursor, pageSize, sender, readerInstanceId, events);
        };
    }


    private void advanceDeliveryCursorIfTracked(Channel ch, String readerInstanceId, Long lastId) {
        if (lastId == null || lastId <= 0) {return;}
        if (readerInstanceId == null || readerInstanceId.isBlank()) {return;}
        if (!io.casehub.qhorus.runtime.channel.ChannelService.isDeliveryTrackingEnabled(ch)) {return;}
        membershipStore.updateLastDeliveredMessageId(ch.id(), readerInstanceId, lastId);
    }

    /** EPHEMERAL: deliver messages visible to this reader then delete only those. */
    private CheckResult checkMessagesEphemeral(Channel ch, long cursor, int pageSize, String readerInstanceId) {
        List<Message> fetched = messageService.pollAfter(ch.id(), cursor, pageSize);
        List<Message> visible = fetched.stream()
                                       .filter(m -> isVisibleToReader(m, readerInstanceId,
                                                                      () -> instanceService.findCapabilityTagsForInstance(readerInstanceId)))
                                       .toList();
        List<MessageSummary> summaries = visible.stream().map(this::toMessageSummary).toList();
        Long                 lastId    = summaries.isEmpty() ? cursor : summaries.getLast().messageId();
        advanceDeliveryCursorIfTracked(ch, readerInstanceId, lastId);
        if (!visible.isEmpty()) {
            List<Long> ids = visible.stream().map(m -> m.id()).toList();
            ids.forEach(messageStore::delete);
        }
        return new CheckResult(summaries, lastId, null);}

    /** COLLECT: deliver ALL accumulated messages atomically and clear the channel; filter returned view. */
    private CheckResult checkMessagesCollect(Channel ch, String readerInstanceId) {
        List<Message> messages = messageStore.scan(MessageQuery.builder()
                                                               .channelId(ch.id()).excludeTypes(List.of(MessageType.EVENT)).build());
        List<MessageSummary> summaries = messages.stream()
                                                 .filter(m -> isVisibleToReader(m, readerInstanceId,
                                                                                () -> instanceService.findCapabilityTagsForInstance(readerInstanceId)))
                                                 .map(this::toMessageSummary).toList();
        Long lastId = summaries.isEmpty() ? 0L : summaries.getLast().messageId();
        advanceDeliveryCursorIfTracked(ch, readerInstanceId, lastId);
        if (!messages.isEmpty()) {
            messageStore.deleteNonEvent(ch.id());
        }
        return new CheckResult(summaries, lastId, null);}

    /** BARRIER: block until all declared contributors have written; then deliver and reset. */
    private CheckResult checkMessagesBarrier(Channel ch, String readerInstanceId) {
        Set<String> required = ch.barrierContributors() != null
                               ? new java.util.HashSet<>(ch.barrierContributors())
                               : Set.of();

        if (required.isEmpty()) {
            return new CheckResult(List.of(), 0L, "Waiting for: (no contributors declared — check channel configuration)");
        }

        List<String> written = messageStore.distinctSendersByChannel(ch.id(), MessageType.EVENT);

        Set<String> pending = required.stream()
                                      .filter(r -> !written.contains(r))
                                      .collect(Collectors.toSet());

        if (!pending.isEmpty()) {
            String status = "Waiting for: " + String.join(", ", pending.stream().sorted().toList());
            return new CheckResult(List.of(), 0L, status);
        }

        List<Message> messages = messageStore.scan(MessageQuery.builder()
                                                               .channelId(ch.id()).excludeTypes(List.of(MessageType.EVENT)).build());
        List<MessageSummary> summaries = messages.stream()
                                                 .filter(m -> isVisibleToReader(m, readerInstanceId,
                                                                                () -> instanceService.findCapabilityTagsForInstance(readerInstanceId)))
                                                 .map(this::toMessageSummary).toList();
        Long lastId = summaries.isEmpty() ? 0L : summaries.getLast().messageId();
        advanceDeliveryCursorIfTracked(ch, readerInstanceId, lastId);
        messageStore.deleteNonEvent(ch.id());
        return new CheckResult(summaries, lastId, null);}

    /** APPEND / LAST_WRITE: standard cursor-based polling with optional target filter. */
    private CheckResult checkMessagesAppend(Channel ch, long cursor, int pageSize, String sender,
                                            String readerInstanceId, boolean includeEvents) {
        List<Message> messages = (sender != null && !sender.isBlank())
                                 ? messageService.pollAfterBySender(ch.id(), cursor, pageSize, sender, includeEvents)
                                 : messageService.pollAfter(ch.id(), cursor, pageSize, includeEvents);
        List<MessageSummary> summaries = messages.stream()
                                                 .filter(m -> isVisibleToReader(m, readerInstanceId,
                                                                                () -> instanceService.findCapabilityTagsForInstance(readerInstanceId)))
                                                 .map(this::toMessageSummary).toList();
        Long lastId = summaries.isEmpty() ? cursor : summaries.getLast().messageId();
        advanceDeliveryCursorIfTracked(ch, readerInstanceId, lastId);
        return new CheckResult(summaries, lastId, null);}

    /** Backward-compat overload — no reader_instance_id filter. */
    List<MessageSummary> getReplies(Long messageId) {
        return getReplies(messageId, null, null, null);
    }

    List<MessageSummary> getReplies(Long messageId, String readerInstanceId) {
        return getReplies(messageId, readerInstanceId, null, null);
    }

    @Tool(name = "get_replies", description = "Retrieve direct replies to a specific message.")
    @Transactional
    public List<MessageSummary> getReplies(
            @ToolArg(name = "message_id", description = "ID of the parent message") Long messageId,
            @ToolArg(name = "reader_instance_id", description = "Calling agent's instance ID for target filtering", required = false) String readerInstanceId,
            @ToolArg(name = "after_id", description = "Return replies with id > after_id (cursor pagination)", required = false) Long afterId,
            @ToolArg(name = "limit", description = "Maximum replies to return (default 20, max 100)", required = false) Integer limit) {
        final int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 100) : 20;
        final String query = afterId != null
                ? "inReplyTo = ?1 AND id > ?2 ORDER BY id ASC"
                : "inReplyTo = ?1 ORDER BY id ASC";
        MessageQuery.Builder mqb = MessageQuery.builder().inReplyTo(messageId).limit(effectiveLimit);
        if (afterId != null) mqb.afterId(afterId);
        final List<Message> messages = messageStore.scan(mqb.build());
        return messages.stream()
                .filter(m -> isVisibleToReader(m, readerInstanceId,
                        () -> instanceService.findCapabilityTagsForInstance(readerInstanceId)))
                .map(this::toMessageSummary)
                .toList();
    }

    /** Backward-compat overload — no reader_instance_id filter. */
    List<MessageSummary> searchMessages(String query, String channel, Integer limit) {
        return searchMessages(query, channel, limit, null);
    }

    @Tool(name = "search_messages", description = "Full-text keyword search across messages. Excludes EVENT type.")
    public List<MessageSummary> searchMessages(
            @ToolArg(name = "query", description = "Keyword to search for (case-insensitive)") String query,
            @ToolArg(name = "channel", description = "Channel name or UUID", required = false) String channel,
            @ToolArg(name = "limit", description = "Maximum results (default 20)", required = false) Integer limit,
            @ToolArg(name = "reader_instance_id", description = "Calling agent's instance ID for target filtering (optional)", required = false) String readerInstanceId) {
        String pattern = "%" + query.toLowerCase() + "%";
        int pageSize = limit != null ? limit : 20;

        Channel ch = null;
        if (channel != null && !channel.isBlank()) {
            ch = resolveChannel(channel);
        }
        UUID channelId = ch != null ? ch.id() : null;

        MessageQuery.Builder sqb = MessageQuery.builder()
                .contentPattern(query)
                .excludeTypes(List.of(MessageType.EVENT))
                .limit(pageSize);
        if (channelId != null) sqb.channelId(channelId);
        List<Message> results = messageStore.scan(sqb.build());

        return results.stream()
                .filter(m -> isVisibleToReader(m, readerInstanceId,
                        () -> instanceService.findCapabilityTagsForInstance(readerInstanceId)))
                .map(this::toMessageSummary).toList();
    }

    @Tool(name = "get_message", description = "Look up a message by its numeric ID. "
            + "Returns the message summary including content, type, sender, and metadata. "
            + "Throws an error if the message is not found.")
    public MessageSummary getMessage(
            @ToolArg(name = "message_id", description = "Numeric message ID") Long messageId) {
        Message message = messageService.findById(messageId)
                                              .orElseThrow(() -> new IllegalArgumentException(
                        "Message not found: " + messageId));
        return toMessageSummary(message);
    }

    // ---------------------------------------------------------------------------
    // Correlation / wait_for_reply
    // ---------------------------------------------------------------------------

    @Tool(name = "wait_for_reply", description = "Block until a RESPONSE message with the given correlation_id "
            + "arrives on the channel, or until timeout_seconds seconds elapse. "
            + "Returns immediately if a matching response already exists.")
    public WaitResult waitForReply(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "correlation_id", description = "UUID matching the correlation_id on the expected RESPONSE") String correlationId,
            @ToolArg(name = "timeout_seconds", description = "Seconds to wait before timing out (default 90)", required = false) Integer timeoutS,
            @ToolArg(name = "instance_id", description = "Waiting agent's instance ID for tracking (optional)", required = false) String instanceId) {
        Channel ch = resolveChannel(channel);

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
                Message response = messageService.findResponseByCorrelationId(ch.id(), correlationId)
                                                       .orElse(null);
                if (response != null) {
                    return new WaitResult(true, false, correlationId, toMessageSummary(response),
                            "Response received for correlation_id=" + correlationId);
                }
                Message done = messageService.findDoneByCorrelationId(ch.id(), correlationId)
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

    // ---------------------------------------------------------------------------
    // Human-in-the-loop — approval gate
    // ---------------------------------------------------------------------------

    @Tool(name = "request_approval", description = "Send an approval request to a channel and block until a human responds. "
            + "Returns the human's response or a timeout result. "
            + "Pair with list_pending_commitments (for human to discover) and respond_to_approval (for human to answer).")
    public WaitResult requestApproval(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "content", description = "The approval request content shown to the human") String content,
            @ToolArg(name = "timeout_seconds", description = "Seconds to wait for human response (default 300)", required = false) Integer timeoutS) {
        Channel ch            = resolveChannel(channel);
        String        correlationId = UUID.randomUUID().toString();
        return requestApprovalWithCorrelationId(ch.name(), content, correlationId, timeoutS);
    }

    /**
     * Testability overload — accepts a pre-supplied correlationId so tests can pre-seed the response.
     * Not exposed as an MCP tool.
     */
    public WaitResult requestApprovalWithCorrelationId(String channelName, String content, String correlationId,
                                                       Integer timeoutS) {
        int timeout = timeoutS != null ? timeoutS : 300;
        sendMessage(channelName, "agent", "query", content, correlationId, null, (List<String>) null, null, null, null, null, null);
        return waitForReply(channelName, correlationId, timeout, null);
    }

    @Tool(name = "respond_to_approval", description = "Human-callable: send a response to a pending approval request. "
            + "Use correlation_id from list_pending_commitments to identify which request to answer.")
    @Transactional
    public DispatchResult respondToApproval(
            @ToolArg(name = "correlation_id", description = "Correlation ID of the approval request (from list_pending_commitments)") String correlationId,
            @ToolArg(name = "response_text", description = "The approval decision or message to send back") String responseText,
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel) {
        Channel ch = resolveChannel(channel);
        // Look up the original request message to supply inReplyTo (required by RESPONSE type).
        // Use canonical MessageDispatch constructor to bypass builder validation when no prior message exists
        // (e.g., when commitment was opened directly without a corresponding channel message).
        Long inReplyTo = messageService.findByCorrelationId(correlationId)
                .map(m -> m.id())
                .orElse(null);
        // Use canonical constructor to bypass builder validation when inReplyTo is null —
        // respondToApproval is a human tool and must not fail even on unusual commitment states.
        io.casehub.qhorus.api.message.MessageDispatch dispatch = new io.casehub.qhorus.api.message.MessageDispatch(
                ch.id(), Senders.HUMAN, io.casehub.qhorus.api.message.MessageType.RESPONSE,
                responseText, correlationId, inReplyTo, null, null, null, null,
                io.casehub.platform.api.identity.ActorType.HUMAN, null, null, null, null);
        return messageService.dispatch(dispatch);
    }

    // ---------------------------------------------------------------------------
    // Human-in-the-loop — wait management
    // ---------------------------------------------------------------------------

    @Tool(name = "cancel_wait", description = "Cancel a pending wait_for_reply by its correlation_id. "
            + "The waiting agent receives status='cancelled' instead of timing out. "
            + "Use list_pending_commitments to discover what is blocked.")
    @Transactional
    public CancelWaitResult cancelWait(
            @ToolArg(name = "correlation_id", description = "Correlation ID of the pending wait to cancel") String correlationId) {
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

    @Tool(name = "list_pending_commitments", description = "List non-terminal commitments across all channels. "
            + "Returns oldest first. Use cancel_wait to unblock a specific wait, "
            + "or respond_to_approval to answer an approval request.")
    @Transactional
    public List<CommitmentDetail> listPendingCommitments() {
        return commitmentStore.findAllOpen().stream()
                .map(CommitmentDetail::from)
                .toList();
    }

    // ---------------------------------------------------------------------------
    // Commitment observability
    // ---------------------------------------------------------------------------

    @Tool(name = "list_my_commitments", description = "List non-terminal commitments on a channel involving this agent. "
            + "role=obligor: obligations you owe (must respond or decline). "
            + "role=requester: obligations others owe you. "
            + "role=both (default): all non-terminal commitments involving you.")
    @Transactional
    public List<CommitmentDetail> listMyCommitments(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "sender", description = "Your agent identity") String sender,
            @ToolArg(name = "role", description = "Filter: 'obligor', 'requester', or 'both' (default: both)", required = false) String role) {
        Channel ch = resolveChannel(channel);
        String        r  = role == null ? "both" : role.toLowerCase();
        List<Commitment> results = switch (r) {
            case "obligor" -> commitmentStore.findOpenByObligor(sender, ch.id());
            case "requester" -> commitmentStore.findOpenByRequester(sender, ch.id());
            default -> {
                var list = new java.util.ArrayList<>(
                        commitmentStore.findOpenByObligor(sender, ch.id()));
                list.addAll(commitmentStore.findOpenByRequester(sender, ch.id()));
                list.sort(java.util.Comparator.comparing(c -> c.createdAt()));
                yield list;
            }
        };
        return results.stream().map(CommitmentDetail::from).toList();
    }

    @Tool(name = "get_commitment", description = "Get the current state of a specific commitment by correlationId. "
            + "Shows full lifecycle: state, acknowledgedAt, resolvedAt, delegatedTo, parentCommitmentId.")
    @Transactional
    public CommitmentDetail getCommitment(
            @ToolArg(name = "correlation_id", description = "The correlation_id of the QUERY or COMMAND") String correlationId) {
        return commitmentStore.findByCorrelationId(correlationId)
                .map(CommitmentDetail::from)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No commitment found for correlation_id=" + correlationId));
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private List<InstanceInfo> buildInstanceInfoList(List<Instance> instances) {
        if (instances.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = instances.stream().map(i -> i.id()).toList();
        Map<UUID, List<String>> capsByInstanceId = CapabilityEntity
                .<CapabilityEntity> find("instanceId IN ?1", ids)
                .list()
                .stream()
                .collect(Collectors.groupingBy(
                        c -> c.instanceId,
                        Collectors.mapping(c -> c.tag, Collectors.toList())));

        return instances.stream()
                .map(i -> new InstanceInfo(
                        i.instanceId(),
                        i.description(),
                        i.status(),
                        capsByInstanceId.getOrDefault(i.id(), List.of()),
                        i.lastSeen().toString(),
                        i.readOnly()))
                .toList();
    }

    // ---------------------------------------------------------------------------
    // Shared data tools
    // ---------------------------------------------------------------------------

    @Tool(name = "share_artefact", description = "Store a large artefact by key. "
            + "Supports chunked upload via append=true; last_chunk=true marks the artefact complete. "
            + "Returns the artefact UUID for use in message artefact_refs.")
    @Transactional
    public ArtefactDetail shareArtefact(
            @ToolArg(name = "key", description = "Unique key for this artefact") String key,
            @ToolArg(name = "description", description = "Human-readable description", required = false) String description,
            @ToolArg(name = "created_by", description = "Owner instance identifier") String createdBy,
            @ToolArg(name = "content", description = "Content to store or append") String content,
            @ToolArg(name = "append", description = "Append to existing content (default false)", required = false) Boolean append,
            @ToolArg(name = "last_chunk", description = "Mark artefact complete (default true)", required = false) Boolean lastChunk) {
        boolean doAppend = append != null && append;
        boolean isLastChunk = lastChunk == null || lastChunk;
        var data = dataService.store(key, description, createdBy, content, doAppend, isLastChunk);
        return toArtefactDetail(data);
    }

    @Tool(name = "begin_artefact", description = "Begin a chunked artefact upload. "
            + "Creates the artefact in incomplete state with the first chunk of content. "
            + "Follow with append_chunk for additional chunks and finalize_artefact to complete.")
    @Transactional
    public ArtefactDetail beginArtefact(
            @ToolArg(name = "key", description = "Unique key for this artefact") String key,
            @ToolArg(name = "description", description = "Human-readable description", required = false) String description,
            @ToolArg(name = "created_by", description = "Owner instance identifier") String createdBy,
            @ToolArg(name = "content", description = "First chunk of content") String content) {
        var data = dataService.store(key, description, createdBy, content, false, false);
        return toArtefactDetail(data);
    }

    @Tool(name = "append_chunk", description = "Append a chunk to an in-progress artefact upload. "
            + "The artefact must have been created with begin_artefact and not yet finalized.")
    @Transactional
    public ArtefactDetail appendChunk(
            @ToolArg(name = "key", description = "Artefact key (from begin_artefact)") String key,
            @ToolArg(name = "content", description = "Content chunk to append") String content) {
        var data = dataService.store(key, null, null, content, true, false);
        return toArtefactDetail(data);
    }

    @Tool(name = "finalize_artefact", description = "Finalize a chunked artefact upload, optionally appending a last chunk. "
            + "Marks the artefact complete. Returns the final artefact UUID for use in message artefact_refs.")
    @Transactional
    public ArtefactDetail finalizeArtefact(
            @ToolArg(name = "key", description = "Artefact key (from begin_artefact)") String key,
            @ToolArg(name = "content", description = "Optional final chunk of content to append", required = false) String content) {
        String chunk = content != null ? content : "";
        var data = dataService.store(key, null, null, chunk, true, true);
        return toArtefactDetail(data);
    }

    @Tool(name = "get_artefact", description = "Retrieve a shared artefact by key or UUID. Exactly one of key or id must be provided.")
    public ArtefactDetail getArtefact(
            @ToolArg(name = "key", description = "Artefact key", required = false) String key,
            @ToolArg(name = "id", description = "Artefact UUID", required = false) String id) {
        boolean hasKey = key != null && !key.isBlank();
        boolean hasId = id != null && !id.isBlank();
        if (!hasKey && !hasId) {
            throw new IllegalArgumentException("Either 'key' or 'id' must be provided");
        }
        var data = hasKey
                ? dataService.getByKey(key)
                        .orElseThrow(() -> new IllegalArgumentException("Artefact not found: key=" + key))
                : dataService.getByUuid(java.util.UUID.fromString(id))
                        .orElseThrow(() -> new IllegalArgumentException("Artefact not found: id=" + id));
        return toArtefactDetail(data);
    }

    @Tool(name = "get_artefact_refs", description = "Get artefact references attached to a message.")
    public java.util.List<io.casehub.qhorus.api.message.ArtefactRef> getArtefactRefs(
            @ToolArg(name = "message_id", description = "Message ID") Long messageId) {
        io.casehub.qhorus.api.message.Message msg = messageStore.find(messageId)
                                                                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + messageId));
        return msg.artefactRefs() != null ? msg.artefactRefs() : java.util.List.of();
    }


    @Tool(name = "list_artefacts", description = "List all artefacts with metadata.")
    public List<ArtefactDetail> listArtefacts() {
        return dataService.listAll().stream().map(this::toArtefactDetail).toList();
    }

    @Tool(name = "claim_artefact", description = "Manually claim an artefact reference. Prevents GC. "
            + "Usually not needed — send_message with artefact_refs auto-claims for the sender.")
    @Transactional
    public String claimArtefact(
            @ToolArg(name = "artefact_id", description = "Artefact UUID") String artefactId,
            @ToolArg(name = "instance_id", description = "Claiming instance UUID") String instanceId) {
        try {
            dataService.claim(java.util.UUID.fromString(artefactId), java.util.UUID.fromString(instanceId));
            return "claimed";
        } catch (final IllegalArgumentException | IllegalStateException e) {
            return toolError(e);
        }
    }

    @Tool(name = "release_artefact", description = "Manually release an artefact reference. GC-eligible when all claims released. "
            + "Usually not needed — commitment resolution (RESPONSE/DONE/DECLINE/FAILURE) auto-releases.")
    @Transactional
    public String releaseArtefact(
            @ToolArg(name = "artefact_id", description = "Artefact UUID") String artefactId,
            @ToolArg(name = "instance_id", description = "Releasing instance UUID") String instanceId) {
        try {
            dataService.release(java.util.UUID.fromString(artefactId), java.util.UUID.fromString(instanceId));
            return "released";
        } catch (final IllegalArgumentException | IllegalStateException e) {
            return toolError(e);
        }
    }

    /** Not a @Tool — helper for tests and internal GC logic. */
    public boolean isGcEligible(String artefactId) {
        return dataService.isGcEligible(java.util.UUID.fromString(artefactId));
    }

    @Tool(name = "revoke_artefact", description = "Force-delete a shared artefact and release all its claims. "
            + "Use for data breaches, PII removal, or invalid data. "
            + "get_shared_data will fail after revocation. Does not cascade to messages that reference this artefact.")
    @Transactional
    public RevokeResult revokeArtefact(
            @ToolArg(name = "artefact_id", description = "UUID of the artefact to revoke") String artefactId) {
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

    // ---------------------------------------------------------------------------
    // Human-in-the-loop — message and instance management
    // ---------------------------------------------------------------------------

    @Tool(name = "delete_message", description = "Delete a single message by its sequence ID. "
            + "Use for PII removal, bad data, or agent mistakes. Does not cascade to replies.")
    @Transactional
    public DeleteMessageResult deleteMessage(
            @ToolArg(name = "message_id", description = "Sequence ID of the message to delete") Long messageId) {
        Message msg = messageStore.find(messageId).orElse(null);
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
        messageStore.scan(MessageQuery.builder().inReplyTo(messageId).build())
                .forEach(reply -> messageStore.put(reply.toBuilder().inReplyTo(null).build()));
        // Post audit event to the channel
        messageService.dispatch(MessageDispatch.builder()
                .channelId(msg.channelId()).sender("system").type(MessageType.EVENT)
                .actorType(ActorType.SYSTEM).build());
        messageStore.delete(msg.id());
        return new DeleteMessageResult(messageId, true, sender, type, preview,
                "Message " + messageId + " deleted");
    }

    /** Convenience overload — no caller identity (open governance assumed). */
    ClearChannelResult clearChannel(String channel) {
        return clearChannel(channel, null);
    }

    @Tool(name = "clear_channel", description = "Delete ALL non-event messages from a channel. "
            + "Does not delete the channel itself or event messages. "
            + "Returns count of messages deleted.")
    @Transactional
    public ClearChannelResult clearChannel(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "caller_instance_id", description = "Instance ID of the caller. Required when the channel has an admin_instances list.", required = false) String callerInstanceId) {
        Channel ch = resolveChannel(channel);
        checkAdminAccess(ch, callerInstanceId, "clear_channel");
        long deleted = messageStore.scan(MessageQuery.builder()
                .channelId(ch.id()).excludeTypes(List.of(MessageType.EVENT)).build()).size();
        messageStore.deleteNonEvent(ch.id());
        // Post audit event (survives the clear)
        messageService.dispatch(MessageDispatch.builder()
                .channelId(ch.id()).sender("system").type(MessageType.EVENT)
                .actorType(ActorType.SYSTEM).build());
        channelService.updateLastActivity(ch.id(), ch.tenancyId());
        return new ClearChannelResult(ch.name(), (int) deleted, true);
    }

    @Tool(name = "deregister_instance", description = "Force-remove an agent instance and its capability tags from the registry. "
            + "Use for misbehaving agents that won't self-deregister. Does not delete past messages.")
    @Transactional
    public DeregisterResult deregisterInstance(
            @ToolArg(name = "instance_id", description = "Human-readable instance ID of the agent to remove") String instanceId) {
        Instance instance = instanceStore.findByInstanceId(instanceId).orElse(null);
        if (instance == null) {
            return new DeregisterResult(instanceId, false,
                    "Instance not found: " + instanceId);
        }
        instanceStore.delete(instance.id());
        return new DeregisterResult(instanceId, true,
                "Instance '" + instanceId + "' deregistered");
    }

    @Tool(name = "get_channel_digest", description = "Return a structured human-readable summary of a channel's recent activity. "
            + "Useful for human dashboards to understand state before intervening. "
            + "Includes message count, sender/type breakdowns, artefact refs, recent messages (truncated), and timestamps.")
    @Transactional
    public ChannelDigest channelDigest(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "limit", description = "Max recent messages to include (default 10)", required = false) Integer limit) {
        Channel ch = resolveChannel(channel);

        int pageSize = limit != null ? limit : 10;
        List<Message> allMessages = messageStore.scan(MessageQuery.builder()
                                                                  .channelId(ch.id()).excludeTypes(List.of(MessageType.EVENT)).build());

        // Topic resolved status from Topic records
        Map<String, io.casehub.qhorus.api.message.TopicSummary> topicSummaries =
                topicService.listTopics(ch.id()).stream()
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

        // Per-topic counts from non-EVENT messages
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

        // Sender and type breakdowns
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

    // ---------------------------------------------------------------------------
    // Ledger audit trail tools
    // ---------------------------------------------------------------------------

    /** Backward-compat overload — no correlation_id or sort. Used by existing tests. */
    List<Map<String, Object>> listLedgerEntries(String channel, String typeFilter,
            String agentId, String since, Long afterId, int limit) {
        return listLedgerEntries(channel, typeFilter, agentId, since, afterId,
                null, null, limit);
    }

    @Tool(name = "list_ledger_entries", description = "Query the immutable audit ledger for a channel. "
            + "Returns all ledger entries in chronological order — every speech act, every tool invocation. "
            + "Use type_filter to narrow by message type: 'COMMAND,DONE,FAILURE' for obligation lifecycle, "
            + "'EVENT' for telemetry only, omit for the full channel history. "
            + "Supports optional filters for sender, since (ISO-8601), correlation_id, sort (asc/desc), "
            + "and cursor-based pagination via after_id.")
    @Transactional
    public List<Map<String, Object>> listLedgerEntries(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "type_filter", description = "Comma-separated MessageType names to include "
                    + "(e.g. 'COMMAND,DONE,FAILURE'). Omit to return all types.", required = false) String typeFilter,
            @ToolArg(name = "sender", description = "Filter by sender — returns only entries from this agent", required = false) String agentId,
            @ToolArg(name = "since", description = "ISO-8601 timestamp — return only entries at or after this time", required = false) String since,
            @ToolArg(name = "after_id", description = "Return entries with sequence_number > after_id (cursor pagination)", required = false) Long afterId,
            @ToolArg(name = "correlation_id", description = "Filter by correlation ID — returns only entries for this obligation", required = false) String correlationId,
            @ToolArg(name = "sort", description = "Sort order: 'asc' (default, oldest first) or 'desc' (newest first)", required = false) String sort,
            @ToolArg(name = "limit", description = "Maximum entries to return (default 20, max 100)", required = false) Integer limit) {

        final Channel ch = resolveChannel(channel);

        java.util.Set<String> types = null;
        if (typeFilter != null && !typeFilter.isBlank()) {
            types = java.util.Arrays.stream(typeFilter.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.toSet());
        }

        final int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 100) : 20;

        java.time.Instant sinceInstant = null;
        if (since != null && !since.isBlank()) {
            try {
                sinceInstant = java.time.Instant.parse(since);
            } catch (final java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "Invalid 'since' timestamp '" + since + "' — use ISO-8601 format, e.g. 2026-04-15T10:00:00Z");
            }
        }

        final boolean sortDesc;
        if (sort == null || sort.isBlank() || "asc".equalsIgnoreCase(sort)) {
            sortDesc = false;
        } else if ("desc".equalsIgnoreCase(sort)) {
            sortDesc = true;
        } else {
            throw new IllegalArgumentException(
                    "Invalid sort value '" + sort + "' — use 'asc' or 'desc'");
        }

        final List<MessageLedgerEntry> entries = ledgerRepo.listEntries(
                ch.id(), types, afterId, agentId, sinceInstant, correlationId, sortDesc, effectiveLimit,
                currentPrincipal.tenancyId());

        return entries.stream().map(this::toLedgerEntryMap).toList();
    }

    @Tool(name = "get_obligation_chain", description = "Return computed enrichment for an obligation identified by correlation_id: "
            + "initiator, participants, handoff count, elapsed time, resolution, and live commitment state. "
            + "For raw ledger entries use list_ledger_entries(correlation_id=X). "
            + "Returns null fields (not an error) for unknown correlation IDs.")
    @Transactional
    public ObligationChainSummary getObligationChain(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "correlation_id", description = "Correlation ID of the obligation to inspect") String correlationId) {

        final Channel ch = resolveChannel(channel);

        final List<MessageLedgerEntry> chain = ledgerRepo.findAllByCorrelationId(ch.id(), correlationId, currentPrincipal.tenancyId());

        if (chain.isEmpty()) {
            return new ObligationChainSummary(correlationId, null, null, null, null, null,
                    List.of(), 0, null);
        }

        final MessageLedgerEntry first = chain.get(0);
        final String initiator = first.actorId;
        final String createdAt = first.occurredAt != null ? first.occurredAt.toString() : null;

        // Terminal entry: first DONE / FAILURE / DECLINE (not HANDOFF — that is delegated, not resolved)
        final java.util.Set<String> terminal = java.util.Set.of("DONE", "FAILURE", "DECLINE");
        final MessageLedgerEntry terminalEntry = chain.stream()
                .filter(e -> terminal.contains(e.messageType))
                .findFirst()
                .orElse(null);

        final String resolution = terminalEntry != null ? terminalEntry.messageType : null;
        final String resolvedAt = (terminalEntry != null && terminalEntry.occurredAt != null)
                ? terminalEntry.occurredAt.toString()
                : null;
        final Long elapsedSeconds = (terminalEntry != null && first.occurredAt != null
                && terminalEntry.occurredAt != null)
                        ? terminalEntry.occurredAt.getEpochSecond() - first.occurredAt.getEpochSecond()
                        : null;

        // Participants — unique actorIds in encounter order
        final List<String> participants = chain.stream()
                .map(e -> e.actorId)
                .distinct()
                .collect(java.util.stream.Collectors.toList());

        final int handoffCount = (int) chain.stream()
                .filter(e -> "HANDOFF".equals(e.messageType))
                .count();

        final CommitmentDetail commitment = commitmentStore.findByCorrelationId(correlationId)
                .map(CommitmentDetail::from)
                .orElse(null);

        return new ObligationChainSummary(correlationId, initiator, createdAt, resolvedAt,
                elapsedSeconds, resolution, participants, handoffCount, commitment);
    }

    @Tool(name = "get_causal_chain", description = "Compliance and audit tool. Takes a ledger_entry_id (UUID from list_ledger_entries) "
            + "and walks causedByEntryId links upward to the root. "
            + "Returns the chain ordered oldest-first. "
            + "Returns empty list for unknown entry IDs (never throws on missing chain).")
    @Transactional
    public List<CausalChainEntry> getCausalChain(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "ledger_entry_id", description = "UUID of the ledger entry (from list_ledger_entries entry_id field)") String ledgerEntryId) {

        final Channel ch = resolveChannel(channel);

        final UUID entryUuid;
        try {
            entryUuid = UUID.fromString(ledgerEntryId);
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid ledger_entry_id '" + ledgerEntryId + "' — must be a UUID");
        }

        return ledgerRepo.findAncestorChain(ch.id(), entryUuid, currentPrincipal.tenancyId()).stream()
                .map(e -> new CausalChainEntry(
                        e.id != null ? e.id.toString() : null,
                        e.messageType,
                        e.actorId,
                        e.correlationId,
                        e.occurredAt != null ? e.occurredAt.toString() : null,
                        e.causedByEntryId != null ? e.causedByEntryId.toString() : null))
                .toList();
    }

    @Tool(name = "list_stalled_obligations", description = "Return COMMAND entries with no terminal sibling "
            + "(DONE / FAILURE / DECLINE / HANDOFF) sharing the same correlation_id, "
            + "whose timestamp is older than the given threshold. "
            + "Useful for detecting obligations that an obligor has not responded to.")
    @Transactional
    public List<StalledObligation> listStalledObligations(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "older_than_seconds", description = "Minimum age in seconds to consider stalled (default 30)", required = false) Integer olderThanSeconds) {

        final Channel ch = resolveChannel(channel);

        final int threshold = olderThanSeconds != null ? olderThanSeconds : 30;
        final java.time.Instant cutoff = java.time.Instant.now().minusSeconds(threshold);
        final java.time.Instant now = java.time.Instant.now();

        return ledgerRepo.findStalledCommands(ch.id(), cutoff, currentPrincipal.tenancyId()).stream()
                .map(e -> {
                    final long stalledFor = e.occurredAt != null
                            ? now.getEpochSecond() - e.occurredAt.getEpochSecond()
                            : 0L;
                    return new StalledObligation(
                            e.correlationId,
                            e.actorId,
                            e.content,
                            e.occurredAt != null ? e.occurredAt.toString() : null,
                            stalledFor);
                })
                .toList();
    }

    @Tool(name = "get_obligation_stats", description = "Return obligation outcome statistics for a channel: "
            + "total commands, fulfilled, failed, declined, delegated, still open, stalled, and fulfillment rate. "
            + "'Still open' = commands with no terminal outcome. "
            + "'Stalled' = subset of still-open whose timestamp is older than 30 seconds.")
    @Transactional
    public ObligationStats getObligationStats(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel) {

        final Channel ch = resolveChannel(channel);

        final Map<String, Long> counts = ledgerRepo.countByOutcome(ch.id(), currentPrincipal.tenancyId());
        final long total = counts.getOrDefault("COMMAND", 0L);
        final long fulfilled = counts.getOrDefault("DONE", 0L);
        final long failed = counts.getOrDefault("FAILURE", 0L);
        final long declined = counts.getOrDefault("DECLINE", 0L);
        final long delegated = counts.getOrDefault("HANDOFF", 0L);
        final long stillOpen = Math.max(0L, total - fulfilled - failed - declined - delegated);
        final long stalled = ledgerRepo
                .findStalledCommands(ch.id(), java.time.Instant.now().minusSeconds(30), currentPrincipal.tenancyId())
                .size();
        final double rate = total > 0 ? (double) fulfilled / total : 0.0;

        return new ObligationStats((int) total, (int) fulfilled, (int) failed, (int) declined,
                (int) delegated, (int) stillOpen, (int) stalled, rate);
    }

    @Tool(name = "get_telemetry_summary", description = "Aggregate EVENT telemetry for a channel, grouped by tool name. "
            + "Returns total event count, per-tool counts with average duration and total tokens, "
            + "and channel-wide totals. EVENT entries with no tool_name are counted under a null key. "
            + "Optional since parameter (ISO-8601) to restrict the time window.")
    @Transactional
    public TelemetrySummary getTelemetrySummary(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "since", description = "ISO-8601 timestamp — include only events at or after this time", required = false) String since) {

        final Channel ch = resolveChannel(channel);

        java.time.Instant sinceInstant = null;
        if (since != null && !since.isBlank()) {
            try {
                sinceInstant = java.time.Instant.parse(since);
            } catch (final java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "Invalid 'since' timestamp '" + since + "' — use ISO-8601 format");
            }
        }

        final List<MessageLedgerEntry> events = ledgerRepo.findEventsSince(ch.id(), sinceInstant, currentPrincipal.tenancyId());

        if (events.isEmpty()) {
            return new TelemetrySummary(0, Map.of(), 0L, 0L);
        }

        // Aggregate per tool (null toolName is a valid key)
        final java.util.LinkedHashMap<String, long[]> agg = new java.util.LinkedHashMap<>();
        for (final MessageLedgerEntry e : events) {
            final long[] acc = agg.computeIfAbsent(e.toolName, k -> new long[3]);
            acc[0]++; // count
            acc[1] += e.durationMs != null ? e.durationMs : 0; // total duration
            acc[2] += e.tokenCount != null ? e.tokenCount : 0; // total tokens
        }

        final Map<String, ToolTelemetry> byTool = new java.util.LinkedHashMap<>();
        for (final var entry : agg.entrySet()) {
            final long[] acc = entry.getValue();
            byTool.put(entry.getKey(),
                    new ToolTelemetry((int) acc[0], acc[0] > 0 ? acc[1] / acc[0] : 0L, acc[2]));
        }

        final long totalTokens = events.stream()
                .mapToLong(e -> e.tokenCount != null ? e.tokenCount : 0L).sum();
        final long totalDuration = events.stream()
                .mapToLong(e -> e.durationMs != null ? e.durationMs : 0L).sum();

        return new TelemetrySummary(events.size(), byTool, totalTokens, totalDuration);
    }

    @Tool(name = "get_channel_timeline", description = "Return all messages for a channel in chronological order, "
            + "interleaving regular messages and EVENT telemetry entries. "
            + "Each entry has a 'type' discriminator: 'MESSAGE' or 'EVENT'. "
            + "Supports cursor-based pagination via after_id (message.id() cursor).")
    @Transactional
    public List<Map<String, Object>> getChannelTimeline(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "after_id", description = "Return messages with id > after_id (cursor pagination)", required = false) Long afterId,
            @ToolArg(name = "limit", description = "Maximum messages to return (default 50, max 200)", required = false) Integer limit) {

        Channel ch = resolveChannel(channel);

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 200) : 50;

        List<Message> messages = messageStore.scan(
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

    @Tool(name = "get_obligation_activity", description = "Return all ledger entries across ALL channels that share a given correlation_id, "
            + "ordered chronologically. Each entry includes a 'channel' field showing which channel it was sent on. "
            + "Use this to reconstruct the full cross-channel picture of an obligation: the COMMAND on work, "
            + "the tool-call EVENTs on observe (when agents pass the correlationId on EVENT messages), "
            + "any oversight escalation, and the terminal DONE/FAILURE/DECLINE. "
            + "Tip: agents should pass correlation_id when sending EVENT messages to the observe channel "
            + "so those entries appear here alongside the obligation they relate to.")
    @Transactional
    public List<Map<String, Object>> getObligationActivity(
            @ToolArg(name = "correlation_id", description = "Correlation ID of the obligation to trace across channels") String correlationId,
            @ToolArg(name = "include_content_search", description = "Deprecated — reserved for future use. Has no effect.", required = false) Boolean includeContentSearch,
            @ToolArg(name = "limit", description = "Maximum entries to return (default 100, max 500)", required = false) Integer limit) {

        final int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 500) : 100;

        final List<io.casehub.qhorus.runtime.ledger.MessageLedgerEntry> entries =
                ledgerRepo.findByCorrelationIdAcrossChannels(correlationId, effectiveLimit, currentPrincipal.tenancyId());

        if (entries.isEmpty()) {
            return List.of();
        }

        // Batch-load channel names from the unique channel IDs in the results
        final java.util.Set<java.util.UUID> channelIds = entries.stream()
                .map(e -> e.channelId)
                .collect(java.util.stream.Collectors.toSet());
        final java.util.Map<java.util.UUID, String> channelNameById = channelService.listAll().stream()
                .filter(ch -> channelIds.contains(ch.id()))
                .collect(java.util.stream.Collectors.toMap(ch -> ch.id(), ch -> ch.name()));

        return entries.stream()
                .map(e -> toLedgerEntryMapWithChannel(e,
                        channelNameById.getOrDefault(e.channelId, "unknown")))
                .toList();
    }

    // ---------------------------------------------------------------------------
    // Human-in-the-loop — watchdogs and alerts (optional module)
    // ---------------------------------------------------------------------------

    private void requireWatchdogEnabled() {
        if (!qhorusConfig.watchdog().enabled()) {
            throw new IllegalStateException(
                    "Watchdog module is disabled. Set casehub.qhorus.watchdog.enabled=true to activate.");
        }
    }

    @Tool(name = "register_watchdog", description = "Register a watchdog condition that fires alert events to a notification channel "
                                                    + "when the condition is met. Condition types: BARRIER_STUCK, APPROVAL_PENDING, AGENT_STALE, CHANNEL_IDLE, QUEUE_DEPTH, "
                                                    + "CONTEXT_PRESSURE, LOOP_DETECTED, OBLIGATION_FAN_OUT, CONVERSATION_STALL, ECHO_CHAMBER. "
                                                    + "Requires casehub.qhorus.watchdog.enabled=true.")
    @Transactional
    public WatchdogSummary registerWatchdog(
            @ToolArg(name = "condition_type", description = "BARRIER_STUCK | APPROVAL_PENDING | AGENT_STALE | CHANNEL_IDLE | QUEUE_DEPTH | CONTEXT_PRESSURE | LOOP_DETECTED | OBLIGATION_FAN_OUT | CONVERSATION_STALL | ECHO_CHAMBER") String conditionType,
            @ToolArg(name = "target_name", description = "Channel name, instance_id, or '*' for all") String targetName,
            @ToolArg(name = "threshold_seconds", description = "Time threshold in seconds (for time-based conditions)", required = false) Integer thresholdSeconds,
            @ToolArg(name = "threshold_count", description = "Count threshold (for QUEUE_DEPTH, LOOP_DETECTED repetitions, ECHO_CHAMBER min agents)", required = false) Integer thresholdCount,
            @ToolArg(name = "similarity_pct", description = "Content similarity percentage threshold 0-100 (for LOOP_DETECTED, ECHO_CHAMBER)", required = false) Integer similarityPct,
            @ToolArg(name = "notification_channel", description = "Channel to post alert events to") String notificationChannel,
            @ToolArg(name = "created_by", description = "Who is registering this watchdog") String createdBy) {
        requireWatchdogEnabled();
        io.casehub.qhorus.api.watchdog.WatchdogConditionType type = io.casehub.qhorus.api.watchdog.WatchdogConditionType.fromString(conditionType)
                                                                                                                        .orElseThrow(() -> new IllegalArgumentException("Unknown condition_type '" + conditionType + "'. Valid: " + java.util.Arrays.toString(io.casehub.qhorus.api.watchdog.WatchdogConditionType.values())));
        Watchdog w = watchdogStore.put(Watchdog.builder(type, targetName)
                                               .thresholdSeconds(thresholdSeconds).thresholdCount(thresholdCount)
                                               .similarityPct(similarityPct)
                                               .notificationChannel(notificationChannel).createdBy(createdBy)
                                               .tenancyId(currentPrincipal.tenancyId()).build());
        return toWatchdogSummary(w);
    }

    @Tool(name = "list_watchdogs", description = "List all registered watchdog conditions. "
            + "Requires casehub.qhorus.watchdog.enabled=true.")
    @Transactional
    public List<WatchdogSummary> listWatchdogs() {
        requireWatchdogEnabled();
        return watchdogStore.scan(io.casehub.qhorus.api.store.query.WatchdogQuery.all()).stream()
                .map(this::toWatchdogSummary)
                .toList();
    }

    @Tool(name = "delete_watchdog", description = "Remove a registered watchdog by its ID. "
            + "Requires casehub.qhorus.watchdog.enabled=true.")
    @Transactional
    public DeleteWatchdogResult deleteWatchdog(
            @ToolArg(name = "watchdog_id", description = "UUID of the watchdog to delete") String watchdogId) {
        requireWatchdogEnabled();
        final UUID watchdogUuid;
        try {
            watchdogUuid = UUID.fromString(watchdogId);
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid watchdog_id '" + watchdogId + "' — must be a UUID (e.g. 550e8400-e29b-41d4-a716-446655440000)");
        }
        boolean found = watchdogStore.find(watchdogUuid).isPresent();
        if (found) watchdogStore.delete(watchdogUuid);
        long deleted = found ? 1 : 0;
        if (deleted > 0) {
            return new DeleteWatchdogResult(watchdogId, true, "Watchdog " + watchdogId + " deleted");
        }
        return new DeleteWatchdogResult(watchdogId, false,
                "Watchdog not found: " + watchdogId);
    }

    // ---------------------------------------------------------------------------
    // Reaction tools
    // ---------------------------------------------------------------------------

    @Tool(name = "react", description = "Add an emoji reaction to a message. Idempotent — reacting twice is a no-op.")
    @Transactional
    public Reaction react(
            @ToolArg(name = "message_id", description = "ID of the message to react to") Long messageId,
            @ToolArg(name = "emoji", description = "Emoji character or shortcode") String emoji,
            @ToolArg(name = "actor_id", description = "Who is reacting. Defaults to caller identity.", required = false) String actorId) {
        String actor = actorId != null ? actorId : currentPrincipal.actorId();
        return reactionService.react(messageId, emoji, actor, currentPrincipal.tenancyId());
    }

    @Tool(name = "unreact", description = "Remove an emoji reaction from a message. Idempotent — unreacting when not reacted is a no-op.")
    @Transactional
    public ReactionResult unreact(
            @ToolArg(name = "message_id", description = "ID of the message") Long messageId,
            @ToolArg(name = "emoji", description = "Emoji character or shortcode") String emoji,
            @ToolArg(name = "actor_id", description = "Who is unreacting. Defaults to caller identity.", required = false) String actorId) {
        String actor = actorId != null ? actorId : currentPrincipal.actorId();
        boolean removed = reactionService.unreact(messageId, emoji, actor);
        return new ReactionResult(messageId, emoji, removed);
    }

    @Tool(name = "get_reactions", description = "Get all reactions for a message, grouped by emoji with actor lists")
    public List<ReactionGroup> getReactions(
            @ToolArg(name = "message_id", description = "ID of the message") Long messageId) {
        return reactionService.getReactions(messageId);
    }

    @Tool(name = "get_reactions_batch", description = "Get reactions for multiple messages in one call, grouped by emoji with actor lists per message")
    public Map<Long, List<ReactionGroup>> getReactionsBatch(
            @ToolArg(name = "message_ids", description = "List of message IDs to fetch reactions for (max 200)") List<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            throw new IllegalArgumentException("message_ids must be non-null and non-empty");
        }
        if (messageIds.size() > 200) {
            throw new IllegalArgumentException("message_ids cannot exceed 200 entries");
        }
        return reactionService.getReactionsBatch(messageIds);
    }



    @Tool(name = "set_presence", description = "Report presence status (heartbeat). Accepted statuses: ONLINE, AVAILABLE, BUSY. AWAY and OFFLINE are computed from heartbeat absence.")
    public Presence setPresence(
            @ToolArg(name = "status", description = "Presence status: ONLINE, AVAILABLE, or BUSY") String status,
            @ToolArg(name = "status_message", description = "Optional status message", required = false) String statusMessage,
            @ToolArg(name = "member_id", description = "Member ID. Defaults to caller identity.", required = false) String memberId) {
        String member = memberId != null ? memberId : currentPrincipal.actorId();
        PresenceStatus ps = PresenceStatus.valueOf(status.toUpperCase());
        presenceService.heartbeat(member, ps, statusMessage);
        return presenceService.getPresence(member);
    }

    @Tool(name = "get_presence", description = "Get presence status for a member")
    public Presence getPresenceTool(
            @ToolArg(name = "member_id", description = "Member ID to query") String memberId) {
        return presenceService.getPresence(memberId);
    }

    @Tool(name = "get_channel_presence", description = "Get presence status for all members of a channel")
    public java.util.List<Presence> getChannelPresence(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel) {
        Channel ch = resolveChannel(channel);
        return presenceService.getChannelPresence(ch.id());
    }

    @Tool(name = "join_channel", description = "Join a channel as a member. Creates or updates membership with the specified role.")
    @jakarta.transaction.Transactional
    public MembershipSummary joinChannel(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "sender", description = "Instance ID of the joining member") String sender,
            @ToolArg(name = "role", description = "Member role: PARTICIPANT, OBSERVER, or MODERATOR. Defaults to PARTICIPANT.", required = false) String role) {
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
    @jakarta.transaction.Transactional
    public String leaveChannel(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "sender", description = "Instance ID of the leaving member") String sender) {
        io.casehub.qhorus.api.channel.Channel ch = resolveChannel(channel);
        membershipService.leave(ch.id(), sender);
        return sender + " left " + ch.name();
    }

    @Tool(name = "list_members", description = "List all members of a channel with their roles.")
    public java.util.List<MembershipSummary> listMembers(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel) {
        io.casehub.qhorus.api.channel.Channel ch = resolveChannel(channel);
        return membershipService.listMembers(ch.id()).stream()
                                .map(m -> new MembershipSummary(ch.id().toString(), ch.name(), m.memberId(),
                                                                m.role().name(), m.joinedAt().toString(), m.lastReadMessageId()))
                                .toList();
    }

    @Tool(name = "mark_channel_read", description = "Mark a channel as read up to a specific message ID. Null message_id marks up to the latest message.")
    @jakarta.transaction.Transactional
    public String markChannelRead(
            @ToolArg(name = "channel", description = "Channel name or UUID") String channel,
            @ToolArg(name = "sender", description = "Instance ID of the member") String sender,
            @ToolArg(name = "message_id", description = "Message ID to mark read up to. Null = latest.", required = false) Long messageId) {
        io.casehub.qhorus.api.channel.Channel ch          = resolveChannel(channel);
        Long                                  effectiveId = messageId;
        if (effectiveId == null) {
            effectiveId = messageStore.findLastMessage(ch.id()).map(io.casehub.qhorus.api.message.Message::id).orElse(0L);
        }
        membershipService.markRead(ch.id(), sender, effectiveId);
        return "Marked " + ch.name() + " read up to message " + effectiveId;
    }

    @Tool(name = "get_unread_counts", description = "Get unread message counts across all channels for a member. Excludes own messages and EVENT messages.")
    public java.util.List<io.casehub.qhorus.api.channel.UnreadCount> getUnreadCounts(
            @ToolArg(name = "sender", description = "Instance ID of the member") String sender) {
        return new java.util.ArrayList<>(membershipService.getUnreadCounts(sender, currentPrincipal.tenancyId()).values());
    }

    @Tool(name = "create_space", description = "Create an organizational space to group related channels. "
                                               + "Spaces can nest recursively (project → case → channels). "
                                               + "Channels are assigned to spaces via create_channel(space_id) or move_channel_to_space.")
    @Transactional
    public Space createSpace(
            @ToolArg(name = "name", description = "Space name (free-form text, max 200 chars)") String name,
            @ToolArg(name = "description", description = "Space purpose description", required = false) String description,
            @ToolArg(name = "parent_space_id", description = "Parent space UUID for nesting. Null = root space.", required = false) String parentSpaceId) {
        UUID parentId = ChannelSlugValidator.tryParseUuid(parentSpaceId);
        return spaceService.create(new io.casehub.qhorus.api.channel.SpaceCreateRequest(name, description, parentId));
    }

    @Tool(name = "list_spaces", description = "List spaces. Without parent_space_id, returns root spaces. "
                                              + "With parent_space_id, returns direct children of that space.")
    public List<Space> listSpaces(
            @ToolArg(name = "parent_space_id", description = "Parent space UUID or name. Null = list root spaces.", required = false) String parentSpaceId) {
        if (parentSpaceId == null) {return spaceService.listRoots();}
        return spaceService.listChildren(resolveSpace(parentSpaceId).id());
    }

    @Tool(name = "list_space_channels", description = "List all channels belonging to a space.")
    public List<ChannelDetail> listSpaceChannels(
            @ToolArg(name = "space", description = "Space UUID or name") String space) {
        UUID          sid      = resolveSpace(space).id();
        List<Channel> channels = spaceService.listChannels(sid);
        return channels.stream().map(ch -> toChannelDetail(ch, messageStore.count(
                MessageQuery.builder().channelId(ch.id()).build()))).toList();
    }

    @Tool(name = "delete_space", description = "Delete a space. Fails if the space contains channels or child spaces.")
    @Transactional
    public DeleteSpaceResult deleteSpace(
            @ToolArg(name = "space", description = "Space UUID or name to delete") String space) {
        Space s = resolveSpace(space);
        spaceService.delete(s.id());
        return new DeleteSpaceResult(s.id().toString(), true);
    }

    @Tool(name = "get_space", description = "Get a space by UUID or name.")
    public Space getSpace(
            @ToolArg(name = "space", description = "Space UUID or name") String space) {
        return resolveSpace(space);
    }

    @Tool(name = "rename_space", description = "Rename a space.")
    @Transactional
    public Space renameSpace(
            @ToolArg(name = "space", description = "Space UUID or name") String space,
            @ToolArg(name = "new_name", description = "New space name") String newName) {
        return spaceService.rename(resolveSpace(space).id(), newName);
    }

    @Tool(name = "update_space_description", description = "Update a space's description. Null clears the description.")
    @Transactional
    public Space updateSpaceDescription(
            @ToolArg(name = "space", description = "Space UUID or name") String space,
            @ToolArg(name = "description", description = "New description. Null clears it.", required = false) String description) {
        return spaceService.updateDescription(resolveSpace(space).id(), description);
    }

    @Tool(name = "move_space", description = "Move a space to a new parent. Null parent_space_id makes it a root space. "
                                             + "Fails if the move would create a cycle or exceed the maximum nesting depth.")
    @Transactional
    public Space moveSpace(
            @ToolArg(name = "space", description = "Space UUID or name to move") String space,
            @ToolArg(name = "parent_space_id", description = "New parent space UUID or name. Null = make root.", required = false) String parentSpaceId) {
        UUID parentId = parentSpaceId != null ? resolveSpace(parentSpaceId).id() : null;
        return spaceService.moveSpace(resolveSpace(space).id(), parentId);
    }

    @Tool(name = "move_channel_to_space", description = "Move a channel into a space. Null space_id removes the channel from its space (makes it top-level). "
                                                        + "Channel and space must be in the same tenancy.")
    @Transactional
    public ChannelDetail moveChannelToSpace(
            @ToolArg(name = "channel", description = "Channel UUID or name") String channel,
            @ToolArg(name = "space_id", description = "Target space UUID or name. Null = remove from space.", required = false) String spaceId) {
        UUID    sid     = spaceId != null ? resolveSpace(spaceId).id() : null;
        Channel updated = spaceService.moveChannelToSpace(resolveChannel(channel).id(), sid);
        return toChannelDetail(updated, messageStore.count(
                MessageQuery.builder().channelId(updated.id()).build()));
    }


    // ---------------------------------------------------------------------------
    // Projection tools
    // ---------------------------------------------------------------------------


    @Tool(description = "Get the maintained summary for a channel")
    public ChannelSummaryResult get_channel_summary(String channel) {
        Channel ch = resolveChannel(channel);
        return channelSummaryService.getSummary(ch.id())
                                    .map(s -> new ChannelSummaryResult(ch.name(), s.content(),
                                                                       s.updatedAt() != null ? s.updatedAt().toString() : null,
                                                                       s.updatedBy(), s.updateAfterMessages(), s.updateAfterSeconds()))
                                    .orElse(new ChannelSummaryResult(ch.name(), null, null, null, null, null));
    }

    @Tool(description = "Set or update a channel's summary text (manual override, bypasses hook)")
    public ChannelSummaryResult update_channel_summary(String channel, String summary) {
        Channel ch = resolveChannel(channel);
        var     s  = channelSummaryService.setSummary(ch.id(), summary, currentPrincipal.actorId());
        return new ChannelSummaryResult(ch.name(), s.content(),
                                        s.updatedAt() != null ? s.updatedAt().toString() : null,
                                        s.updatedBy(), s.updateAfterMessages(), s.updateAfterSeconds());
    }

    @Tool(description = "Configure auto-update thresholds for a channel's summary")
    public ChannelSummaryResult configure_channel_summary(String channel,
                                                          Integer update_after_messages, Integer update_after_seconds) {
        Channel ch = resolveChannel(channel);
        var     s  = channelSummaryService.configureSummary(ch.id(), update_after_messages, update_after_seconds);
        return new ChannelSummaryResult(ch.name(), s.content(),
                                        s.updatedAt() != null ? s.updatedAt().toString() : null,
                                        s.updatedBy(), s.updateAfterMessages(), s.updateAfterSeconds());
    }

    @Tool(description = "Trigger an immediate summary update via the configured hook")
    public ChannelSummaryResult trigger_channel_summary_update(String channel) {
        Channel ch = resolveChannel(channel);
        return channelSummaryService.triggerUpdate(ch.id())
                                    .map(s -> new ChannelSummaryResult(ch.name(), s.content(),
                                                                       s.updatedAt() != null ? s.updatedAt().toString() : null,
                                                                       s.updatedBy(), s.updateAfterMessages(), s.updateAfterSeconds()))
                                    .orElseThrow(() -> new IllegalArgumentException(
                                            "No summary configured for channel '" + ch.name() + "'. Use configure_channel_summary first."));
    }

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
