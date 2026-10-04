package io.casehub.qhorus.testing;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelConnectorBinding;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.channel.ChannelDetail;
import io.casehub.qhorus.api.channel.ChannelManager;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.data.SharedData;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.Senders;
import io.casehub.qhorus.api.instance.Instance;
import io.casehub.qhorus.api.message.ArtefactRef;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageDispatcher;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.Topic;
import io.casehub.qhorus.api.store.ChannelBindingStore;
import io.casehub.qhorus.api.store.ChannelMembershipStore;
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
import io.casehub.qhorus.api.store.query.WatchdogQuery;
import io.casehub.qhorus.api.watchdog.Watchdog;
import io.casehub.qhorus.api.watchdog.WatchdogAction;
import io.casehub.qhorus.api.watchdog.WatchdogConditionType;
import io.casehub.qhorus.runtime.QhorusEntityMapper;
import io.casehub.qhorus.runtime.data.DataService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.ledger.CausalGraphService;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntry;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntryRepository;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.ProjectionRegistry;
import io.casehub.qhorus.runtime.message.ProjectionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class QhorusTestHelper {

    @Inject ChannelManager channelManager;
    @Inject ChannelStore channelStore;
    @Inject MessageDispatcher messageDispatcher;
    @Inject MessageStore messageStore;
    @Inject InstanceStore instanceStore;
    @Inject DataStore dataStore;
    @Inject WatchdogStore watchdogStore;
    @Inject CommitmentStore commitmentStore;
    @Inject ChannelMembershipStore membershipStore;
    @Inject ReactionStore reactionStore;
    @Inject TopicStore topicStore;
    @Inject ChannelBindingStore bindingStore;
    @Inject MessageLedgerEntryRepository ledgerRepo;
    @Inject QhorusEntityMapper entityMapper;
    @Inject CurrentPrincipal currentPrincipal;
    @Inject CausalGraphService causalGraphService;
    @Inject ChannelGateway channelGateway;
    @Inject DataService dataService;
    @Inject MessageService messageService;
    @Inject ProjectionRegistry projectionRegistry;
    @Inject ProjectionService projectionService;
    @Inject io.casehub.qhorus.runtime.instance.InstanceService instanceService;
    @Inject io.casehub.qhorus.runtime.config.QhorusConfig qhorusConfig;
    @Inject io.casehub.qhorus.runtime.message.ReactionService reactionService;
    @Inject io.casehub.qhorus.runtime.message.protocol.ProtocolRegistry protocolRegistry;

    // ── Inner record types ──────────────────────────────────────────────────────

    public record MessageSummary(
            Long messageId, String sender, String messageType, String content,
            String payload, String correlationId, Long inReplyTo, String createdAt,
            List<ArtefactRef> artefactRefs, String target, String topic) {}

    public record CheckResult(List<MessageSummary> messages, Long lastId, String barrierStatus) {
        public boolean isEmpty() { return messages == null || messages.isEmpty(); }
        public int size() { return messages != null ? messages.size() : 0; }
        public MessageSummary get(int index) { return messages.get(index); }
        public java.util.stream.Stream<MessageSummary> stream() { return messages != null ? messages.stream() : java.util.stream.Stream.empty(); }
    }

    public record ArtefactDetail(
            UUID artefactId, String key, String description, String createdBy,
            String content, boolean complete, long sizeBytes, String updatedAt) {}

    public record WaitResult(
            boolean found, boolean timedOut, String correlationId,
            MessageSummary message, String status) {}

    public record CancelWaitResult(String correlationId, boolean cancelled, String message) {}

    public record CommitmentDetail(
            String commitmentId, String correlationId, String channelId,
            String messageType, String requester, String obligor, String state,
            String expiresAt, String acknowledgedAt, String resolvedAt,
            String delegatedTo, String parentCommitmentId, String createdAt) {
        public static CommitmentDetail from(Commitment c) {
            return new CommitmentDetail(
                    c.id() != null ? c.id().toString() : null,
                    c.correlationId(),
                    c.channelId() != null ? c.channelId().toString() : null,
                    c.messageType() != null ? c.messageType().name() : null,
                    c.requester(), c.obligor(),
                    c.state() != null ? c.state().name() : null,
                    c.expiresAt() != null ? c.expiresAt().toString() : null,
                    c.acknowledgedAt() != null ? c.acknowledgedAt().toString() : null,
                    c.resolvedAt() != null ? c.resolvedAt().toString() : null,
                    c.delegatedTo(),
                    c.parentCommitmentId() != null ? c.parentCommitmentId().toString() : null,
                    c.createdAt() != null ? c.createdAt().toString() : null);
        }
    }

    public record DeleteChannelResult(String channelName, long messagesDeleted, String status) {}

    public record ObligationChainSummary(
            String correlationId, String initiator, String createdAt, String resolvedAt,
            Long elapsedSeconds, String resolution, List<String> participants,
            int handoffCount, CommitmentDetail commitment) {}

    public record CausalChainEntry(
            String entryId, String channelId, String channelName, String messageType,
            String actorId, String correlationId, String occurredAt, String content,
            String causedByEntryId) {}

    public record StalledObligation(
            String correlationId, String actorId, String content,
            String occurredAt, long stalledForSeconds) {}

    public record ObligationStats(
            int totalCommands, int fulfilled, int failed, int declined,
            int delegated, int stillOpen, int stalled, double fulfillmentRate) {}

    public record ToolTelemetry(int count, long avgDurationMs, long totalTokens) {}

    public record TelemetrySummary(
            int totalEvents, Map<String, ToolTelemetry> byTool,
            long totalTokens, long totalDurationMs) {}

    public record WatchdogSummary(
            String id, String conditionType, String targetName,
            Integer thresholdSeconds, Integer thresholdCount, Integer similarityPct,
            String notificationChannel, String createdBy, String createdAt,
            String lastFiredAt, String action) {}

    public record DeleteWatchdogResult(String watchdogId, boolean deleted, String message) {}

    public record RevokeResult(
            String artefactId, String key, String createdBy, long sizeBytes,
            int claimsReleased, boolean revoked, String message) {}

    public record ClearChannelResult(String channelName, int messagesDeleted, boolean cleared) {}

    public record ChannelDigest(
            String channelName, String semantic, boolean paused, long messageCount,
            Map<String, Integer> senderBreakdown, Map<String, Integer> typeBreakdown,
            int artefactRefCount, List<String> activeAgents,
            List<MessagePreview> recentMessages,
            String oldestMessageAt, String newestMessageAt,
            List<TopicDigest> topicBreakdown) {}

    public record TopicDigest(String name, long messageCount, String lastActivityAt,
                               boolean resolved, String resolvedAt) {}

    public record MessagePreview(Long messageId, String sender, String messageType,
                                  String contentPreview, String createdAt) {}

    public record DeleteMessageResult(Long messageId, boolean deleted, String sender,
                                       String messageType, String contentPreview, String message) {}

    public record ForceReleaseResult(String channelName, String semantic, int messageCount,
                                      List<MessageSummary> messages) {
        public boolean isEmpty() { return messages == null || messages.isEmpty(); }
        public int size() { return messages != null ? messages.size() : 0; }
        public MessageSummary get(int index) { return messages.get(index); }
        public java.util.stream.Stream<MessageSummary> stream() { return messages != null ? messages.stream() : java.util.stream.Stream.empty(); }
    }

    // ── Channel creation ────────────────────────────────────────────────────────

    public Channel createChannel(String name) {
        return channelManager.create(ChannelCreateRequest.builder(name).build());
    }

    public Channel createChannel(String name, ChannelSemantic semantic) {
        return channelManager.create(ChannelCreateRequest.builder(name)
                .semantic(semantic).build());
    }

    public Channel createChannel(String name, ChannelSemantic semantic,
                                 List<String> barrierContributors) {
        return channelManager.create(ChannelCreateRequest.builder(name)
                .semantic(semantic).barrierContributors(barrierContributors).build());
    }

    public Channel createChannel(ChannelCreateRequest request) {
        return channelManager.create(request);
    }

    public ChannelDetail createChannel(
            String name, String description, String semantic,
            String barrierContributors, String allowedWriters, String adminInstances,
            Integer rateLimitPerChannel, Integer rateLimitPerInstance,
            String allowedTypes, String deniedTypes, String spaceId,
            String reviewerIds, String protocols, String protocolParticipants,
            String inboundConnectorId, String externalKey,
            String outboundConnectorId, String outboundDestination,
            Boolean trackDelivery) {
        ChannelSemantic sem = ChannelSemantic.APPEND;
        if (semantic != null && !semantic.isBlank()) {
            try {
                sem = ChannelSemantic.valueOf(semantic.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid semantic '" + semantic
                        + "'. Valid values: " + Arrays.toString(ChannelSemantic.values()), e);
            }
        }
        Channel ch = channelManager.create(ChannelCreateRequest.builder(name)
                .description(description).semantic(sem)
                .barrierContributors(splitCsv(barrierContributors))
                .allowedWriters(splitCsv(allowedWriters))
                .adminInstances(splitCsv(adminInstances))
                .rateLimitPerChannel(rateLimitPerChannel)
                .rateLimitPerInstance(rateLimitPerInstance)
                .allowedTypes(MessageType.parseTypes(allowedTypes))
                .deniedTypes(MessageType.parseTypes(deniedTypes))
                .spaceId(spaceId != null ? UUID.fromString(spaceId) : null)
                .reviewerInstances(splitCsv(reviewerIds))
                .protocols(splitCsv(protocols))
                .protocolParticipants(splitCsv(protocolParticipants))
                .inboundConnectorId(inboundConnectorId)
                .externalKey(externalKey)
                .outboundConnectorId(outboundConnectorId)
                .outboundDestination(outboundDestination)
                .trackDelivery(trackDelivery)
                .build());
        return toChannelDetail(ch);
    }

    // ── Channel listing and configuration ───────────────────────────────────────

    public List<ChannelDetail> listChannels() {
        List<Channel> channels = channelStore.scan(ChannelQuery.all());
        if (channels.isEmpty()) return List.of();
        Map<UUID, Long> countByChannel = messageStore.countAllByChannel();
        Map<UUID, ChannelConnectorBinding> allBindings = bindingStore.findAll();
        return channels.stream()
                .map(ch -> entityMapper.toChannelDetail(ch,
                        countByChannel.getOrDefault(ch.id(), 0L),
                        Optional.ofNullable(allBindings.get(ch.id()))))
                .toList();
    }

    public ChannelDetail setChannelAdmins(String channelName, String adminsCsv) {
        Channel ch = findChannel(channelName);
        Channel updated = channelManager.setAdminInstances(ch.id(), splitCsv(adminsCsv));
        return toChannelDetail(updated);
    }

    public ChannelDetail setChannelWriters(String channelName, String writersCsv) {
        Channel ch = findChannel(channelName);
        Channel updated = channelManager.setAllowedWriters(ch.id(), splitCsv(writersCsv));
        return toChannelDetail(updated);
    }

    public ChannelDetail setChannelRateLimits(String channelName, Integer perChannel, Integer perInstance) {
        Channel ch = findChannel(channelName);
        Channel updated = channelManager.setRateLimits(ch.id(), perChannel, perInstance);
        return toChannelDetail(updated);
    }

    public ChannelDetail setChannelTypeConstraints(String channelName, String allowedTypes, String deniedTypes) {
        Channel ch = findChannel(channelName);
        Channel updated = channelManager.setTypeConstraints(ch.id(),
                MessageType.parseTypes(allowedTypes), MessageType.parseTypes(deniedTypes));
        return toChannelDetail(updated);
    }

    public ChannelDetail setChannelReviewers(String channelName, String reviewersCsv) {
        Channel ch = findChannel(channelName);
        Channel updated = channelManager.setReviewerInstances(ch.id(), splitCsv(reviewersCsv));
        return toChannelDetail(updated);
    }

    public ChannelDetail setChannelProtocols(String channelName, String protocolsCsv) {
        Channel ch = findChannel(channelName);
        Channel updated = channelManager.setProtocols(ch.id(), splitCsv(protocolsCsv));
        return toChannelDetail(updated);
    }

    public ChannelDetail setProtocolParticipants(String channelName, String participantsCsv) {
        Channel ch = findChannel(channelName);
        Channel updated = channelManager.setProtocolParticipants(ch.id(), splitCsv(participantsCsv));
        return toChannelDetail(updated);
    }

    public ChannelDetail updateChannelBinding(String channelName, String outboundConnectorId, String outboundDestination) {
        Channel ch = findChannel(channelName);
        io.casehub.qhorus.runtime.channel.ChannelService channelService = jakarta.enterprise.inject.spi.CDI.current().select(io.casehub.qhorus.runtime.channel.ChannelService.class).get();
        channelService.updateConnectorBinding(ch.id(), outboundConnectorId, outboundDestination);
        return toChannelDetail(ch);
    }

    public ChannelDetail setEnforcementMode(String channelName, String mode) {
        Channel ch = findChannel(channelName);
        io.casehub.qhorus.api.channel.EnforcementMode enforcementMode;
        try {
            enforcementMode = io.casehub.qhorus.api.channel.EnforcementMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid enforcement mode '" + mode
                    + "'. Valid: " + Arrays.toString(io.casehub.qhorus.api.channel.EnforcementMode.values()));
        }
        Channel updated = channelManager.setEnforcementMode(ch.id(), enforcementMode);
        return toChannelDetail(updated);
    }

    public ChannelDetail setEnforcementExclusions(String channelName, String exclusions) {
        Channel ch = findChannel(channelName);
        Channel updated = channelManager.setEnforcementExclusions(ch.id(), splitCsv(exclusions));
        return toChannelDetail(updated);
    }

    public Map<String, Object> getChannelEnforcement(String channelName) {
        Channel      ch      = findChannel(channelName);
        List<String> sources = new java.util.ArrayList<>();
        sources.add("TYPE_POLICY");
        sources.add("CORRELATION_INTEGRITY");
        sources.addAll(protocolRegistry.allNames().stream().sorted().toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enforcement_mode", ch.enforcementMode() != null ? ch.enforcementMode().name() : "ADVISORY");
        result.put("enforcement_exclusions", ch.enforcementExclusions());
        result.put("available_sources", sources);
        return result;
    }

    public io.casehub.qhorus.api.instance.InstanceInfo getInstance(String instanceId) {
        Instance inst = instanceStore.findByInstanceId(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Instance not found: " + instanceId));
        return new io.casehub.qhorus.api.instance.InstanceInfo(
                inst.instanceId(), inst.description(), inst.status(),
                instanceStore.findCapabilities(inst.id()),
                inst.lastSeen() != null ? inst.lastSeen().toString() : null,
                inst.readOnly());
    }

    // ── Channel operations ──────────────────────────────────────────────────────

    public void deleteChannel(String channelName, boolean force) {
        Channel ch = findChannel(channelName);
        channelManager.delete(ch.id(), force);
    }

    public DeleteChannelResult deleteChannel(String channelName, Boolean force, String callerInstanceId) {
        Channel ch = findChannel(channelName);
        checkAdminAccess(ch, callerInstanceId, "delete_channel");
        membershipStore.deleteAll(ch.id());
        reactionStore.deleteByChannel(ch.id());
        commitmentStore.deleteAll(ch.id());
        topicStore.deleteAll(ch.id());
        long deleted = channelManager.delete(ch.id(), Boolean.TRUE.equals(force));
        channelGateway.closeChannel(ch.id(), new ChannelRef(ch.id(), ch.name()));
        return new DeleteChannelResult(ch.name(), deleted, "deleted");
    }

    public void pauseChannel(String channelName) {
        channelManager.pause(resolveChannelId(channelName));
    }

    public ChannelDetail pauseChannel(String channelName, String callerInstanceId) {
        Channel ch = findChannel(channelName);
        checkAdminAccess(ch, callerInstanceId, "pause_channel");
        Channel updated = channelManager.pause(ch.id());
        return toChannelDetail(updated);
    }

    public void resumeChannel(String channelName) {
        channelManager.resume(resolveChannelId(channelName));
    }

    public ChannelDetail resumeChannel(String channelName, String callerInstanceId) {
        Channel ch = findChannel(channelName);
        checkAdminAccess(ch, callerInstanceId, "resume_channel");
        Channel updated = channelManager.resume(ch.id());
        return toChannelDetail(updated);
    }

    public ClearChannelResult clearChannel(String channelName, String callerInstanceId) {
        Channel ch = findChannel(channelName);
        checkAdminAccess(ch, callerInstanceId, "clear_channel");
        List<Message> nonEvent = messageStore.scan(MessageQuery.builder()
                .channelId(ch.id()).excludeTypes(List.of(MessageType.EVENT)).build());
        int count = nonEvent.size();
        messageStore.deleteNonEvent(ch.id());
        return new ClearChannelResult(ch.name(), count, true);
    }

    public ChannelDigest channelDigest(String channelName, Integer limit) {
        Channel ch = findChannel(channelName);
        int pageSize = limit != null ? limit : 10;
        List<Message> allMessages = messageStore.scan(MessageQuery.builder()
                .channelId(ch.id()).excludeTypes(List.of(MessageType.EVENT)).build());
        if (allMessages.isEmpty()) {
            return new ChannelDigest(ch.name(), ch.semantic().name(), ch.paused(),
                    0L, Map.of(), Map.of(), 0, List.of(), List.of(), null, null, List.of());
        }
        Map<String, Integer> senderBreakdown = new LinkedHashMap<>();
        Map<String, Integer> typeBreakdown = new LinkedHashMap<>();
        java.util.Set<String> artefactUuids = new java.util.LinkedHashSet<>();
        for (Message m : allMessages) {
            senderBreakdown.merge(m.sender(), 1, Integer::sum);
            typeBreakdown.merge(m.messageType().name(), 1, Integer::sum);
            if (m.artefactRefs() != null && !m.artefactRefs().isEmpty()) {
                m.artefactRefs().forEach(ref -> artefactUuids.add(ref.uri()));
            }
        }
        Instant cutoff = Instant.now().minusSeconds(300);
        List<String> activeAgents = allMessages.stream()
                .filter(m -> m.createdAt() != null && m.createdAt().isAfter(cutoff))
                .map(Message::sender).distinct().toList();
        List<MessagePreview> recent = allMessages.stream()
                .skip(Math.max(0, allMessages.size() - pageSize))
                .map(m -> {
                    String content = m.content() != null ? m.content() : "";
                    String preview = content.length() > 120 ? content.substring(0, 120) + "…" : content;
                    return new MessagePreview(m.id(), m.sender(), m.messageType().name(),
                            preview, m.createdAt() != null ? m.createdAt().toString() : null);
                }).toList();
        String oldest = allMessages.get(0).createdAt() != null
                ? allMessages.get(0).createdAt().toString() : null;
        String newest = allMessages.get(allMessages.size() - 1).createdAt() != null
                ? allMessages.get(allMessages.size() - 1).createdAt().toString() : null;
        List<Topic> topics = topicStore.findByChannel(ch.id());
        List<TopicDigest> topicDigests = topics.stream().map(t -> {
            long count = allMessages.stream()
                    .filter(m -> t.name().equalsIgnoreCase(m.topic())).count();
            String lastActivity = allMessages.stream()
                    .filter(m -> t.name().equalsIgnoreCase(m.topic()))
                    .reduce((a, b) -> b)
                    .map(m -> m.createdAt() != null ? m.createdAt().toString() : null)
                    .orElse(null);
            return new TopicDigest(t.name(), count, lastActivity,
                    t.resolved(), t.resolvedAt() != null ? t.resolvedAt().toString() : null);
        }).toList();
        return new ChannelDigest(ch.name(), ch.semantic().name(), ch.paused(),
                allMessages.size(), senderBreakdown, typeBreakdown,
                artefactUuids.size(), activeAgents, recent, oldest, newest, topicDigests);
    }

    // ── Message sending ─────────────────────────────────────────────────────────

    public DispatchResult sendMessage(String channelName, String sender,
                                      String type, String content) {
        return sendMessage(channelName, sender, type, content, null, null, null,
                null, null, null, null, null, null);
    }

    public DispatchResult sendMessage(String channelName, String sender,
                                      String type, String content,
                                      String correlationId) {
        return sendMessage(channelName, sender, type, content, null,
                correlationId, null, null, null, null, null, null, null);
    }

    public DispatchResult sendMessage(String channelName, String sender,
                                      String type, String content,
                                      String correlationId, Long inReplyTo) {
        return sendMessage(channelName, sender, type, content, null,
                correlationId, inReplyTo, null, null, null, null, null, null);
    }

    public DispatchResult sendMessage(String channelName, String sender,
                                      String type, String content,
                                      String payload, String correlationId,
                                      Long inReplyTo, List<String> artefactRefs,
                                      String target, String deadline,
                                      String subjectId, String causedByEntryId,
                                      String topic) {
        UUID channelId = resolveChannelId(channelName);
        MessageType msgType = MessageType.valueOf(type.toUpperCase());

        instanceService.findByInstanceId(sender).ifPresent(inst -> {
            if (inst.readOnly()) {
                throw new IllegalStateException(
                        "Instance '" + sender + "' is read-only and cannot send messages. "
                                + "Use check_messages with include_events=true to receive EVENT messages.");
            }
        });

        if (msgType.requiresContent() && (content == null || content.isBlank())) {
            throw new IllegalArgumentException(msgType.name() + " requires non-empty content explaining the reason.");
        }
        if (msgType.requiresTarget() && (target == null || target.isBlank())) {
            throw new IllegalArgumentException("HANDOFF requires a non-null target (instance:id, capability:tag, or role:name).");
        }

        String corrId = correlationId;
        if (corrId == null && msgType.requiresCorrelationId()) {
            corrId = UUID.randomUUID().toString();
        }

        String normalisedTarget = (target == null || target.isBlank()) ? null : target.strip();
        if (normalisedTarget != null) {
            if (!normalisedTarget.startsWith("instance:") && !normalisedTarget.startsWith("capability:") && !normalisedTarget.startsWith("role:")) {
                throw new IllegalArgumentException("Invalid target format: '" + normalisedTarget + "'. Must be instance:<id>, capability:<tag>, or role:<name>.");
            }
            String valuePart = normalisedTarget.substring(normalisedTarget.indexOf(':') + 1);
            if (valuePart.isBlank()) {
                throw new IllegalArgumentException("Invalid target format: '" + normalisedTarget + "'. Value after prefix cannot be empty.");
            }
        }

        List<ArtefactRef> refsList = null;
        List<UUID> uuidRefs = new java.util.ArrayList<>();
        if (artefactRefs != null && !artefactRefs.isEmpty()) {
            List<ArtefactRef> built = new java.util.ArrayList<>(artefactRefs.size());
            for (String ref : artefactRefs) {
                try {
                    UUID parsed = UUID.fromString(ref);
                    uuidRefs.add(parsed);
                    built.add(new ArtefactRef(ref, io.casehub.qhorus.api.message.ArtefactType.DOCUMENT, null, null));
                } catch (IllegalArgumentException e) {
                    built.add(new ArtefactRef(ref, io.casehub.qhorus.api.message.ArtefactType.EXTERNAL, null, null));
                }
            }
            if (!uuidRefs.isEmpty()) {
                List<UUID> found = dataStore.findByIds(uuidRefs).stream().map(SharedData::id).toList();
                List<UUID> unknown = uuidRefs.stream().filter(u -> !found.contains(u)).toList();
                if (!unknown.isEmpty()) {
                    throw new IllegalArgumentException("Unknown artefact ref(s): " + unknown.stream().map(UUID::toString).collect(Collectors.joining(", ")));
                }
                instanceService.findByInstanceId(sender).ifPresent(inst -> {
                    for (UUID uuid : uuidRefs) { dataService.claim(uuid, inst.id()); }
                });
            }
            refsList = List.copyOf(built);
        }

        var builder = MessageDispatch.builder()
                .channelId(channelId)
                .sender(sender)
                .type(msgType)
                .actorType(ActorType.AGENT);

        if (content != null) builder.content(content);
        if (payload != null) builder.payload(payload);
        if (corrId != null) builder.correlationId(corrId);
        if (inReplyTo != null) builder.inReplyTo(inReplyTo);
        if (refsList != null) builder.artefactRefs(refsList);
        if (normalisedTarget != null) builder.target(normalisedTarget);
        if (topic != null) builder.topic(topic);
        if (subjectId != null) builder.subjectId(UUID.fromString(subjectId));
        if (causedByEntryId != null) builder.causedByEntryId(UUID.fromString(causedByEntryId));

        DispatchResult dispatchResult = messageDispatcher.dispatch(builder.build());

        if (deadline != null && !deadline.isBlank() && msgType.requiresCorrelationId()) {
            messageStore.find(dispatchResult.messageId()).ifPresent(msg ->
                    messageStore.put(msg.toBuilder()
                            .deadline(java.time.Instant.now().plus(java.time.Duration.parse(deadline))).build()));
        }

        boolean isCommitmentResolving = msgType == MessageType.DONE || msgType == MessageType.DECLINE || msgType == MessageType.FAILURE;
        if (!isCommitmentResolving && msgType == MessageType.RESPONSE && dispatchResult.correlationId() != null) {
            var commitment = commitmentStore.findByCorrelationId(dispatchResult.correlationId());
            isCommitmentResolving = commitment.isEmpty() || commitment.get().messageType() != MessageType.PROPOSE;
        }
        if (dispatchResult.correlationId() != null && isCommitmentResolving) {
            try {
                messageService.findByCorrelationId(dispatchResult.correlationId()).ifPresent(original -> {
                    if (original.artefactRefs() != null && !original.artefactRefs().isEmpty()) {
                        instanceService.findByInstanceId(original.sender()).ifPresent(inst -> {
                            for (ArtefactRef ref : original.artefactRefs()) {
                                try { dataService.release(UUID.fromString(ref.uri()), inst.id()); }
                                catch (IllegalArgumentException ignored) {}
                            }
                        });
                    }
                });
            } catch (Exception e) { /* auto-release failed — non-fatal */ }
        }

        return dispatchResult;
    }

    public DispatchResult dispatch(MessageDispatch dispatch) {
        return messageDispatcher.dispatch(dispatch);
    }

    // ── Message queries ─────────────────────────────────────────────────────────

    public CheckResult checkMessages(String channelName, Long afterId, Integer limit) {
        return checkMessages(channelName, afterId, limit, null, null, null);
    }

    public CheckResult checkMessages(String channelName, Long afterId, Integer limit,
                                      String sender, String readerInstanceId,
                                      Boolean includeEvents) {
        Channel ch = findChannel(channelName);
        int effectiveLimit = (limit != null && limit > 0) ? limit : 50;

        if (ch.paused()) {
            return new CheckResult(List.of(), afterId != null ? afterId : 0L, "Channel is paused");
        }

        long cursor = afterId != null ? afterId : 0L;
        boolean events = includeEvents != null && includeEvents;

        return switch (ch.semantic()) {
            case EPHEMERAL -> checkMessagesEphemeral(ch, cursor, effectiveLimit, readerInstanceId);
            case COLLECT -> checkMessagesCollect(ch, readerInstanceId);
            case BARRIER -> checkMessagesBarrier(ch, readerInstanceId);
            default -> checkMessagesAppend(ch, cursor, effectiveLimit, sender, readerInstanceId, events);
        };
    }

    private CheckResult checkMessagesAppend(Channel ch, long cursor, int pageSize, String sender,
                                             String readerInstanceId, boolean includeEvents) {
        List<Message> messages = (sender != null && !sender.isBlank())
                ? messageService.pollAfterBySender(ch.id(), cursor, pageSize, sender, includeEvents)
                : messageService.pollAfter(ch.id(), cursor, pageSize, includeEvents);
        List<MessageSummary> summaries = messages.stream()
                .filter(m -> isVisibleToReader(m, readerInstanceId))
                .map(this::toMessageSummary).toList();
        Long lastId = summaries.isEmpty() ? cursor : summaries.getLast().messageId();
        advanceDeliveryCursorIfTracked(ch, readerInstanceId, lastId);
        return new CheckResult(summaries, lastId, null);
    }

    private CheckResult checkMessagesEphemeral(Channel ch, long cursor, int pageSize, String readerInstanceId) {
        List<Message> fetched = messageService.pollAfter(ch.id(), cursor, pageSize);
        List<Message> visible = fetched.stream()
                .filter(m -> isVisibleToReader(m, readerInstanceId)).toList();
        List<MessageSummary> summaries = visible.stream().map(this::toMessageSummary).toList();
        Long lastId = summaries.isEmpty() ? cursor : summaries.getLast().messageId();
        advanceDeliveryCursorIfTracked(ch, readerInstanceId, lastId);
        if (!visible.isEmpty()) {
            visible.stream().map(Message::id).forEach(messageStore::delete);
        }
        return new CheckResult(summaries, lastId, null);
    }

    private CheckResult checkMessagesCollect(Channel ch, String readerInstanceId) {
        List<Message> messages = messageStore.scan(MessageQuery.builder()
                .channelId(ch.id()).excludeTypes(List.of(MessageType.EVENT)).build());
        List<MessageSummary> summaries = messages.stream()
                .filter(m -> isVisibleToReader(m, readerInstanceId))
                .map(this::toMessageSummary).toList();
        Long lastId = summaries.isEmpty() ? 0L : summaries.getLast().messageId();
        advanceDeliveryCursorIfTracked(ch, readerInstanceId, lastId);
        if (!messages.isEmpty()) {
            messageStore.deleteNonEvent(ch.id());
        }
        return new CheckResult(summaries, lastId, null);
    }

    private CheckResult checkMessagesBarrier(Channel ch, String readerInstanceId) {
        java.util.Set<String> required = ch.barrierContributors() != null
                ? new java.util.HashSet<>(ch.barrierContributors()) : java.util.Set.of();
        if (required.isEmpty()) {
            return new CheckResult(List.of(), 0L, "Waiting for: (no contributors declared — check channel configuration)");
        }
        List<String> written = messageStore.distinctSendersByChannel(ch.id(), MessageType.EVENT);
        java.util.Set<String> pending = required.stream()
                .filter(r -> !written.contains(r)).collect(java.util.stream.Collectors.toSet());
        if (!pending.isEmpty()) {
            String status = "Waiting for: " + String.join(", ", pending.stream().sorted().toList());
            return new CheckResult(List.of(), 0L, status);
        }
        List<Message> messages = messageStore.scan(MessageQuery.builder()
                .channelId(ch.id()).excludeTypes(List.of(MessageType.EVENT)).build());
        List<MessageSummary> summaries = messages.stream()
                .filter(m -> isVisibleToReader(m, readerInstanceId))
                .map(this::toMessageSummary).toList();
        Long lastId = summaries.isEmpty() ? 0L : summaries.getLast().messageId();
        advanceDeliveryCursorIfTracked(ch, readerInstanceId, lastId);
        messageStore.deleteNonEvent(ch.id());
        return new CheckResult(summaries, lastId, null);
    }

    private void advanceDeliveryCursorIfTracked(Channel ch, String readerInstanceId, Long lastId) {
        if (lastId == null || lastId <= 0) return;
        if (readerInstanceId == null || readerInstanceId.isBlank()) return;
        if (!io.casehub.qhorus.runtime.channel.ChannelService.isDeliveryTrackingEnabled(ch)) return;
        membershipStore.updateLastDeliveredMessageId(ch.id(), readerInstanceId, lastId);
    }

    private void requireWatchdogEnabled() {
        if (!qhorusConfig.watchdog().enabled()) {
            throw new IllegalStateException(
                    "Watchdog module is disabled. Set casehub.qhorus.watchdog.enabled=true to activate.");
        }
    }

    private boolean isVisibleToReader(Message m, String readerInstanceId) {
        if (readerInstanceId == null || readerInstanceId.isBlank()) return true;
        if (m.messageType() == MessageType.EVENT) return true;
        if (m.target() == null) return true;
        if (m.target().equals("instance:" + readerInstanceId)) return true;
        if (m.target().startsWith("capability:") || m.target().startsWith("role:")) {
            return instanceService.findCapabilityTagsForInstance(readerInstanceId).contains(m.target());
        }
        return false;
    }

    public List<MessageSummary> getReplies(Long messageId) {
        return getReplies(messageId, null, null, null);
    }

    public List<MessageSummary> getReplies(Long messageId, String readerInstanceId) {
        return getReplies(messageId, readerInstanceId, null, null);
    }

    public List<MessageSummary> getReplies(Long messageId, String readerInstanceId,
                                           Long afterId, Integer limit) {
        int                  effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 100) : 20;
        MessageQuery.Builder mqb            = MessageQuery.builder().inReplyTo(messageId).limit(effectiveLimit);
        if (afterId != null) {mqb.afterId(afterId);}
        List<Message> messages = messageStore.scan(mqb.build());
        return messages.stream()
                       .filter(m -> isVisibleToReader(m, readerInstanceId))
                       .map(this::toMessageSummary).toList();
    }

    // ── Instance management ─────────────────────────────────────────────────────

    public Instance registerInstance(String instanceId, String description,
                                     String... capabilities) {
        List<String> caps = (capabilities != null)
                ? Arrays.stream(capabilities).filter(java.util.Objects::nonNull).toList()
                : List.of();
        return instanceService.register(instanceId, description, caps, null, false);
    }

    public Instance register(String instanceId, String description,
                             List<String> capabilities, String claudonySessionId,
                             Boolean readOnly) {
        List<String> caps = capabilities != null ? capabilities : List.of();
        boolean ro = readOnly != null && readOnly;
        return instanceService.register(instanceId, description, caps, claudonySessionId, ro);
    }

    public List<io.casehub.qhorus.api.instance.InstanceInfo> listInstances(String capability) {
        List<Instance> all = (capability != null && !capability.isBlank())
                ? instanceStore.scan(io.casehub.qhorus.api.store.query.InstanceQuery.byCapability(capability))
                : instanceStore.scan(io.casehub.qhorus.api.store.query.InstanceQuery.all());
        return all.stream()
                .map(i -> new io.casehub.qhorus.api.instance.InstanceInfo(
                        i.instanceId(), i.description(), i.status(),
                        instanceStore.findCapabilities(i.id()),
                        i.lastSeen() != null ? i.lastSeen().toString() : null,
                        i.readOnly()))
                .toList();
    }

    public List<MessageSummary> searchMessages(String query, String channel, Integer limit,
                                               String readerInstanceId) {
        int  pageSize  = limit != null ? limit : 20;
        UUID channelId = null;
        if (channel != null && !channel.isBlank()) {
            channelId = findChannel(channel).id();
        }
        MessageQuery.Builder sqb = MessageQuery.builder()
                                               .contentPattern(query)
                                               .excludeTypes(List.of(MessageType.EVENT))
                                               .limit(pageSize);
        if (channelId != null) {sqb.channelId(channelId);}
        List<Message> results = messageStore.scan(sqb.build());
        return results.stream()
                      .filter(m -> isVisibleToReader(m, readerInstanceId))
                      .map(this::toMessageSummary).toList();
    }

    public List<MessageSummary> searchMessages(String query, String channel, Integer limit) {
        int pageSize = limit != null ? limit : 20;
        UUID channelId = null;
        if (channel != null && !channel.isBlank()) {
            channelId = findChannel(channel).id();
        }
        MessageQuery.Builder sqb = MessageQuery.builder()
                .contentPattern(query)
                .excludeTypes(List.of(MessageType.EVENT))
                .limit(pageSize);
        if (channelId != null) sqb.channelId(channelId);
        List<Message> results = messageStore.scan(sqb.build());
        return results.stream().map(this::toMessageSummary).toList();
    }

    public MessageSummary getMessage(Long messageId) {
        Message message = messageStore.find(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + messageId));
        return toMessageSummary(message);
    }

    public void deregisterInstance(String instanceId) {
        Instance inst = instanceStore.findByInstanceId(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Instance not found: " + instanceId));
        instanceStore.delete(inst.id());
    }

    public Map<Long, List<io.casehub.qhorus.api.message.ReactionGroup>> getReactionsBatch(List<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            throw new IllegalArgumentException("message_ids must be non-null and non-empty");
        }
        if (messageIds.size() > 200) {
            throw new IllegalArgumentException("message_ids exceeds max batch size of 200 (got " + messageIds.size() + ")");
        }
        return reactionService.getReactionsBatch(messageIds);
    }

    // ── Artefact management ─────────────────────────────────────────────────────

    public SharedData shareArtefact(String key, String description,
                                    String content, String createdBy) {
        var data = SharedData.builder(key)
                .description(description)
                .content(content)
                .createdBy(createdBy)
                .complete(true)
                .sizeBytes(content != null ? content.length() : 0)
                .build();
        return dataStore.put(data);
    }

    public ArtefactDetail shareArtefact(String key, String description,
                                        String createdBy, String content,
                                        Boolean append, Boolean lastChunk) {
        boolean doAppend = append != null && append;
        boolean isLastChunk = lastChunk == null || lastChunk;
        var data = dataService.store(key, description, createdBy, content, doAppend, isLastChunk);
        return toArtefactDetail(data);
    }

    public RevokeResult revokeArtefact(String artefactId) {
        UUID uuid = UUID.fromString(artefactId);
        SharedData data = dataStore.find(uuid).orElse(null);
        if (data == null) {
            return new RevokeResult(artefactId, null, null, 0, 0, false,
                    "Artefact not found: " + artefactId);
        }
        int claimsReleased = dataStore.countClaims(uuid);
        dataStore.delete(uuid);
        return new RevokeResult(artefactId, data.key(), data.createdBy(), data.sizeBytes(),
                claimsReleased, true,
                "Artefact '" + data.key() + "' revoked — " + claimsReleased + " claim(s) released");
    }

    public ArtefactDetail beginArtefact(String key, String description,
                                         String createdBy, String content) {
        var data = dataService.store(key, description, createdBy, content, false, false);
        return toArtefactDetail(data);
    }

    public ArtefactDetail appendChunk(String key, String content) {
        var data = dataService.store(key, null, null, content, true, false);
        return toArtefactDetail(data);
    }

    public ArtefactDetail finalizeArtefact(String key, String content) {
        String chunk = content != null ? content : "";
        var data = dataService.store(key, null, null, chunk, true, true);
        return toArtefactDetail(data);
    }

    public ArtefactDetail getArtefact(String key) {
        return getArtefact(key, null);
    }

    public ArtefactDetail getArtefact(String key, String id) {
        boolean hasKey = key != null && !key.isBlank();
        boolean hasId = id != null && !id.isBlank();
        if (!hasKey && !hasId) {
            throw new IllegalArgumentException("Either 'key' or 'id' must be provided");
        }
        var data = hasKey
                ? dataService.getByKey(key)
                        .orElseThrow(() -> new IllegalArgumentException("Artefact not found: key=" + key))
                : dataService.getByUuid(UUID.fromString(id))
                        .orElseThrow(() -> new IllegalArgumentException("Artefact not found: id=" + id));
        return toArtefactDetail(data);
    }

    public List<ArtefactDetail> listArtefacts() {
        return dataService.listAll().stream().map(this::toArtefactDetail).toList();
    }

    public String claimArtefact(String artefactId, String instanceId) {
        dataService.claim(UUID.fromString(artefactId), UUID.fromString(instanceId));
        return "claimed";
    }

    public String releaseArtefact(String artefactId, String instanceId) {
        dataService.release(UUID.fromString(artefactId), UUID.fromString(instanceId));
        return "released";
    }

    public boolean isGcEligible(String artefactId) {
        return dataService.isGcEligible(UUID.fromString(artefactId));
    }

    // ── Watchdog ────────────────────────────────────────────────────────────────

    public WatchdogSummary registerWatchdog(String conditionType, String targetName,
                                     Integer thresholdSeconds, Integer thresholdCount,
                                     Integer similarityPct, String notificationChannel,
                                     String createdBy, String action) {
        requireWatchdogEnabled();
        var watchdog = Watchdog.builder(
                        WatchdogConditionType.valueOf(conditionType), targetName)
                .thresholdSeconds(thresholdSeconds)
                .thresholdCount(thresholdCount)
                .similarityPct(similarityPct)
                .notificationChannel(notificationChannel)
                .createdBy(createdBy)
                .action(action != null ? WatchdogAction.valueOf(action) : WatchdogAction.ALERT)
                .build();
        Watchdog saved = watchdogStore.put(watchdog);
        return toWatchdogSummary(saved);
    }

    public List<WatchdogSummary> listWatchdogs() {
        requireWatchdogEnabled();
        return watchdogStore.scan(WatchdogQuery.all()).stream()
                .map(this::toWatchdogSummary).toList();
    }

    public DeleteWatchdogResult deleteWatchdog(String watchdogId) {
        requireWatchdogEnabled();
        UUID uuid = UUID.fromString(watchdogId);
        boolean found = watchdogStore.find(uuid).isPresent();
        if (found) watchdogStore.delete(uuid);
        return found
                ? new DeleteWatchdogResult(watchdogId, true, "Watchdog " + watchdogId + " deleted")
                : new DeleteWatchdogResult(watchdogId, false, "Watchdog not found: " + watchdogId);
    }

    // ── Commitment / wait operations ────────────────────────────────────────────

    public List<CommitmentDetail> listPendingCommitments() {
        return commitmentStore.findAllOpen().stream()
                .map(CommitmentDetail::from).toList();
    }

    public List<CommitmentDetail> listMyCommitments(String channel, String sender, String role) {
        Channel ch = findChannel(channel);
        String r = role == null ? "both" : role.toLowerCase();
        List<Commitment> results = switch (r) {
            case "obligor" -> commitmentStore.findOpenByObligor(sender, ch.id());
            case "requester" -> commitmentStore.findOpenByRequester(sender, ch.id());
            default -> {
                var list = new java.util.ArrayList<>(commitmentStore.findOpenByObligor(sender, ch.id()));
                list.addAll(commitmentStore.findOpenByRequester(sender, ch.id()));
                list.sort(java.util.Comparator.comparing(Commitment::createdAt));
                yield list;
            }
        };
        return results.stream().map(CommitmentDetail::from).toList();
    }

    public CommitmentDetail getCommitment(String correlationId) {
        return commitmentStore.findByCorrelationId(correlationId)
                .map(CommitmentDetail::from)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No commitment found for correlation_id=" + correlationId));
    }

    public CancelWaitResult cancelWait(String correlationId) {
        Optional<Commitment> opt = commitmentStore.findByCorrelationId(correlationId);
        if (opt.isPresent()) {
            commitmentStore.deleteById(opt.get().id());
            return new CancelWaitResult(correlationId, true,
                    "Cancelled pending wait for correlation_id=" + correlationId);
        }
        return new CancelWaitResult(correlationId, false,
                "No pending wait found for correlation_id=" + correlationId);
    }

    public WaitResult waitForReply(String channelName, String correlationId,
                                    Integer timeoutS, String instanceId) {
        findChannel(channelName);
        int timeout = timeoutS != null ? timeoutS : 90;
        Instant expiresAt = Instant.now().plusSeconds(timeout);
        long pollMs = 100;
        while (Instant.now().isBefore(expiresAt)) {
            Optional<Commitment> opt = commitmentStore.findByCorrelationId(correlationId);
            if (opt.isEmpty()) {
                return new WaitResult(false, false, correlationId, null,
                        "Wait cancelled for correlation_id=" + correlationId);
            }
            Commitment commitment = opt.get();
            if (commitment.state() == CommitmentState.FULFILLED
                    || commitment.state() == CommitmentState.OPEN
                    || commitment.state() == CommitmentState.ACKNOWLEDGED
                    || commitment.state() == CommitmentState.DELEGATED) {
                UUID chId = commitment.channelId();
                Message response = messageService.findResponseByCorrelationId(chId, correlationId).orElse(null);
                if (response != null) {
                    return new WaitResult(true, false, correlationId, toMessageSummary(response),
                            "Response received for correlation_id=" + correlationId);
                }
                Message done = messageService.findDoneByCorrelationId(chId, correlationId).orElse(null);
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
            try {
                Thread.sleep(pollMs);
                pollMs = Math.min(pollMs * 2, 500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new WaitResult(false, true, correlationId, null,
                "Timed out after " + timeout + "s waiting for response to correlation_id=" + correlationId);
    }

    public WaitResult requestApprovalWithCorrelationId(String channelName, String content,
                                                        String correlationId, Integer timeoutS) {
        int timeout = timeoutS != null ? timeoutS : 300;
        sendMessage(channelName, "agent", "query", content, null,
                correlationId, null, null, null, null, null, null, null);
        return waitForReply(channelName, correlationId, timeout, null);
    }

    public DispatchResult respondToApproval(String correlationId, String responseText,
                                             String channelName) {
        Channel ch = findChannel(channelName);
        Long inReplyTo = messageService.findByCorrelationId(correlationId)
                .map(Message::id).orElse(null);
        MessageDispatch dispatch = new MessageDispatch(
                ch.id(), Senders.HUMAN, MessageType.RESPONSE,
                responseText, null, correlationId, inReplyTo, null, null, null, null,
                ActorType.HUMAN, null, null, null, null, null);
        return messageDispatcher.dispatch(dispatch);
    }

    // ── Message management ───────────────────────────────────────────────────────

    public DeleteMessageResult deleteMessage(Long messageId) {
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
        messageStore.delete(msg.id());
        return new DeleteMessageResult(messageId, true, sender, type, preview,
                "Message " + messageId + " deleted");
    }

    public ForceReleaseResult forceRelease(String channelName, String callerInstanceId) {
        Channel ch = findChannel(channelName);
        checkAdminAccess(ch, callerInstanceId, "force_release_channel");
        if (ch.semantic() != ChannelSemantic.BARRIER && ch.semantic() != ChannelSemantic.COLLECT) {
            throw new IllegalArgumentException(
                    "force_release only applies to BARRIER and COLLECT channels, not " + ch.semantic().name());
        }
        List<Message> messages = messageStore.scan(MessageQuery.builder()
                .channelId(ch.id()).excludeTypes(List.of(MessageType.EVENT)).build());
        List<MessageSummary> summaries = messages.stream().map(this::toMessageSummary).toList();
        messageStore.deleteNonEvent(ch.id());
        return new ForceReleaseResult(ch.name(), ch.semantic().name(), summaries.size(), summaries);
    }

    public List<String> listProjections() {
        return projectionRegistry.registeredNames().stream().sorted().toList();
    }

    // ── Peer attestation ─────────────────────────────────────────────────────────

    @Inject io.casehub.qhorus.runtime.ledger.PeerAttestationWriter peerAttestationWriter;
    @Inject io.casehub.qhorus.runtime.ledger.ReviewerResolver reviewerResolver;
    @Inject io.casehub.ledger.api.spi.LedgerEntryRepository ledgerEntryRepository;

    public Map<String, Object> attest(String entryId, String verdict, String evidence) {
        UUID entryUuid = UUID.fromString(entryId);
        io.casehub.ledger.api.model.AttestationVerdict v =
                io.casehub.ledger.api.model.AttestationVerdict.valueOf(verdict);
        peerAttestationWriter.write(entryUuid, v, evidence,
                currentPrincipal.actorId(), currentPrincipal.tenancyId());
        return Map.of("entry_id", entryId, "verdict", verdict, "status", "recorded");
    }

    public List<Map<String, Object>> listAttestations(String entryId) {
        UUID entryUuid = UUID.fromString(entryId);
        return ledgerEntryRepository.findAttestationsByEntryId(entryUuid,
                currentPrincipal.tenancyId()).stream()
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("attestor_id", a.attestorId);
                    m.put("verdict", a.verdict != null ? a.verdict.name() : null);
                    m.put("confidence", a.confidence);
                    m.put("evidence", a.evidence);
                    m.put("occurred_at", a.occurredAt != null ? a.occurredAt.toString() : null);
                    String role = (a.verdict != null
                            && (a.verdict == io.casehub.ledger.api.model.AttestationVerdict.ENDORSED
                                || a.verdict == io.casehub.ledger.api.model.AttestationVerdict.CHALLENGED))
                            ? "peer-reviewer" : "policy";
                    m.put("attestor_role", role);
                    return m;
                })
                .toList();
    }

    public Map<String, Object> requestPeerReview(String entryId, String reviewerIds, String channel) {
        UUID         entryUuid = UUID.fromString(entryId);
        List<String> reviewers = splitCsv(reviewerIds);
        Channel      ch        = (channel != null && !channel.isBlank()) ? findChannel(channel) : null;
        if (ch == null) {
            var entry = ledgerEntryRepository.findEntryById(entryUuid, currentPrincipal.tenancyId())
                                             .orElseThrow(() -> new IllegalArgumentException("Ledger entry not found: " + entryId));
            if (entry instanceof MessageLedgerEntry mle && mle.channelId != null) {
                ch = channelStore.find(mle.channelId).orElse(null);
            }
        }
        UUID   channelId   = ch != null ? ch.id() : null;
        String channelName = ch != null ? ch.name() : null;
        if (reviewers.isEmpty()) {
            reviewers = reviewerResolver.resolve(channelId, List.of(), entryUuid, currentPrincipal.tenancyId());
        }
        int sent = 0;
        for (String reviewer : reviewers) {
            String corrId  = UUID.randomUUID().toString();
            String content = "{\"peer_review\": {\"ledger_entry_id\": \"" + entryId + "\", \"reviewer\": \"" + reviewer + "\"}}";
            if (channelName != null) {
                sendMessage(channelName, currentPrincipal.actorId(), "query", content, corrId);
                sent++;
            }
        }
        return Map.of("entry_id", entryId, "reviewers_sent", sent, "reviewers", reviewers);
    }

    // ── Ledger queries ──────────────────────────────────────────────────────────

    public List<Map<String, Object>> listLedgerEntries(String channel, String typeFilter,
                                                        String agentId, String since,
                                                        Long afterId, int limit) {
        return listLedgerEntries(channel, typeFilter, agentId, since, afterId, null, null, limit);
    }

    public List<Map<String, Object>> listLedgerEntries(
            String channel, String typeFilter, String agentId, String since,
            Long afterId, String correlationId, String sort, Integer limit) {
        Channel ch = findChannel(channel);
        Set<String> types = null;
        if (typeFilter != null && !typeFilter.isBlank()) {
            types = Arrays.stream(typeFilter.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
        }
        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 100) : 20;
        Instant sinceInstant = null;
        if (since != null && !since.isBlank()) {
            try {
                sinceInstant = Instant.parse(since);
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid 'since' timestamp: " + since, e);
            }
        }
        if (sort != null && !sort.isBlank()
                && !"asc".equalsIgnoreCase(sort) && !"desc".equalsIgnoreCase(sort)) {
            throw new IllegalArgumentException("Invalid sort value: '" + sort + "'. Must be 'asc' or 'desc'.");
        }
        boolean sortDesc = sort != null && "desc".equalsIgnoreCase(sort);
        List<MessageLedgerEntry> entries = ledgerRepo.listEntries(
                ch.id(), types, afterId, agentId, sinceInstant, correlationId,
                sortDesc, effectiveLimit, currentPrincipal.tenancyId());
        return entries.stream().map(this::toLedgerEntryMap).toList();
    }

    public ObligationChainSummary getObligationChain(String channel, String correlationId) {
        Channel ch = findChannel(channel);
        List<MessageLedgerEntry> chain = ledgerRepo.findAllByCorrelationId(
                ch.id(), correlationId, currentPrincipal.tenancyId());
        if (chain.isEmpty()) {
            return new ObligationChainSummary(correlationId, null, null, null, null, null,
                    List.of(), 0, null);
        }
        MessageLedgerEntry first = chain.get(0);
        String initiator = first.actorId;
        String createdAt = first.occurredAt != null ? first.occurredAt.toString() : null;
        Set<String> terminal = Set.of("DONE", "FAILURE", "DECLINE");
        MessageLedgerEntry terminalEntry = chain.stream()
                .filter(e -> terminal.contains(e.messageType))
                .findFirst().orElse(null);
        String resolution = terminalEntry != null ? terminalEntry.messageType : null;
        String resolvedAt = (terminalEntry != null && terminalEntry.occurredAt != null)
                ? terminalEntry.occurredAt.toString() : null;
        Long elapsedSeconds = (terminalEntry != null && first.occurredAt != null
                && terminalEntry.occurredAt != null)
                ? terminalEntry.occurredAt.getEpochSecond() - first.occurredAt.getEpochSecond()
                : null;
        List<String> participants = chain.stream()
                .map(e -> e.actorId).distinct().collect(Collectors.toList());
        int handoffCount = (int) chain.stream()
                .filter(e -> "HANDOFF".equals(e.messageType)).count();
        CommitmentDetail commitment = commitmentStore.findByCorrelationId(correlationId)
                .map(CommitmentDetail::from).orElse(null);
        return new ObligationChainSummary(correlationId, initiator, createdAt, resolvedAt,
                elapsedSeconds, resolution, participants, handoffCount, commitment);
    }

    public List<CausalChainEntry> getCausalChain(String channel, String ledgerEntryId) {
        UUID entryUuid = UUID.fromString(ledgerEntryId);
        String tenancyId = currentPrincipal.tenancyId();
        if (channel != null && !channel.isBlank()) {
            Channel ch = findChannel(channel);
            return ledgerRepo.findAncestorChain(ch.id(), entryUuid, tenancyId).stream()
                    .map(e -> new CausalChainEntry(
                            e.id != null ? e.id.toString() : null,
                            e.channelId != null ? e.channelId.toString() : null,
                            ch.name(), e.messageType, e.actorId, e.correlationId,
                            e.occurredAt != null ? e.occurredAt.toString() : null,
                            e.content,
                            e.causedByEntryId != null ? e.causedByEntryId.toString() : null))
                    .toList();
        }
        List<MessageLedgerEntry> chain = ledgerRepo.findAncestorChainCrossChannel(entryUuid, tenancyId);
        Set<UUID> channelIds = chain.stream().map(e -> e.channelId).collect(Collectors.toSet());
        Map<UUID, String> channelNames = channelStore.findByIds(channelIds).stream()
                .collect(Collectors.toMap(Channel::id, Channel::name, (a, b) -> a));
        return chain.stream()
                .map(e -> new CausalChainEntry(
                        e.id != null ? e.id.toString() : null,
                        e.channelId != null ? e.channelId.toString() : null,
                        channelNames.getOrDefault(e.channelId, "unknown"),
                        e.messageType, e.actorId, e.correlationId,
                        e.occurredAt != null ? e.occurredAt.toString() : null,
                        e.content,
                        e.causedByEntryId != null ? e.causedByEntryId.toString() : null))
                .toList();
    }

    public List<StalledObligation> listStalledObligations(String channel, Integer olderThanSeconds) {
        Channel ch = findChannel(channel);
        int threshold = olderThanSeconds != null ? olderThanSeconds : 30;
        Instant cutoff = Instant.now().minusSeconds(threshold);
        Instant now = Instant.now();
        return ledgerRepo.findStalledCommands(ch.id(), cutoff, currentPrincipal.tenancyId()).stream()
                .map(e -> new StalledObligation(
                        e.correlationId, e.actorId, e.content,
                        e.occurredAt != null ? e.occurredAt.toString() : null,
                        e.occurredAt != null ? now.getEpochSecond() - e.occurredAt.getEpochSecond() : 0L))
                .toList();
    }

    public ObligationStats getObligationStats(String channel) {
        Channel ch = findChannel(channel);
        Map<String, Long> counts = ledgerRepo.countByOutcome(ch.id(), currentPrincipal.tenancyId());
        long total = counts.getOrDefault("COMMAND", 0L);
        long fulfilled = counts.getOrDefault("DONE", 0L);
        long failed = counts.getOrDefault("FAILURE", 0L);
        long declined = counts.getOrDefault("DECLINE", 0L);
        long delegated = counts.getOrDefault("HANDOFF", 0L);
        long stillOpen = Math.max(0L, total - fulfilled - failed - declined - delegated);
        long stalled = ledgerRepo
                .findStalledCommands(ch.id(), Instant.now().minusSeconds(30), currentPrincipal.tenancyId())
                .size();
        double rate = total > 0 ? (double) fulfilled / total : 0.0;
        return new ObligationStats((int) total, (int) fulfilled, (int) failed, (int) declined,
                (int) delegated, (int) stillOpen, (int) stalled, rate);
    }

    public TelemetrySummary getTelemetrySummary(String channel, String since) {
        Channel ch = findChannel(channel);
        Instant sinceInstant = null;
        if (since != null && !since.isBlank()) {
            try {
                sinceInstant = Instant.parse(since);
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid 'since' timestamp: " + since, e);
            }
        }
        List<MessageLedgerEntry> events = ledgerRepo.findEventsSince(
                ch.id(), sinceInstant, currentPrincipal.tenancyId());
        if (events.isEmpty()) {
            return new TelemetrySummary(0, Map.of(), 0L, 0L);
        }
        LinkedHashMap<String, long[]> agg = new LinkedHashMap<>();
        for (MessageLedgerEntry e : events) {
            long[] acc = agg.computeIfAbsent(e.toolName, k -> new long[3]);
            acc[0]++;
            acc[1] += e.durationMs != null ? e.durationMs : 0;
            acc[2] += e.tokenCount != null ? e.tokenCount : 0;
        }
        Map<String, ToolTelemetry> byTool = new LinkedHashMap<>();
        for (var entry : agg.entrySet()) {
            long[] acc = entry.getValue();
            byTool.put(entry.getKey(),
                    new ToolTelemetry((int) acc[0], acc[0] > 0 ? acc[1] / acc[0] : 0L, acc[2]));
        }
        long totalTokens = events.stream()
                .mapToLong(e -> e.tokenCount != null ? e.tokenCount : 0L).sum();
        long totalDuration = events.stream()
                .mapToLong(e -> e.durationMs != null ? e.durationMs : 0L).sum();
        return new TelemetrySummary(events.size(), byTool, totalTokens, totalDuration);
    }

    public List<Map<String, Object>> getChannelTimeline(String channel, Long afterId, Integer limit) {
        Channel ch = findChannel(channel);
        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 200) : 50;
        List<Message> messages = messageStore.scan(
                MessageQuery.poll(ch.id(), afterId, effectiveLimit));
        List<Long> eventIds = messages.stream()
                .filter(m -> m.messageType() == MessageType.EVENT)
                .map(Message::id).toList();
        Map<Long, MessageLedgerEntry> ledgerByMessageId = eventIds.isEmpty()
                ? Map.of()
                : ledgerRepo.findByMessageIds(eventIds).stream()
                        .collect(Collectors.toMap(e -> e.messageId, e -> e));
        return messages.stream()
                .map(m -> entityMapper.toTimelineEntry(m,
                        m.messageType() == MessageType.EVENT ? ledgerByMessageId.get(m.id()) : null))
                .toList();
    }

    public List<Map<String, Object>> getObligationActivity(String correlationId,
                                                            Boolean includeContentSearch,
                                                            Integer limit) {
        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 500) : 100;
        List<MessageLedgerEntry> entries = ledgerRepo.findByCorrelationIdAcrossChannels(
                correlationId, effectiveLimit, currentPrincipal.tenancyId());
        if (entries.isEmpty()) return List.of();
        Set<UUID> channelIds = entries.stream().map(e -> e.channelId).collect(Collectors.toSet());
        Map<UUID, String> channelNameById = channelStore.findByIds(channelIds).stream()
                .collect(Collectors.toMap(Channel::id, Channel::name, (a, b) -> a));
        return entries.stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("channel", channelNameById.getOrDefault(e.channelId, "unknown"));
                    m.putAll(toLedgerEntryMap(e));
                    return m;
                })
                .toList();
    }

    public CausalGraphService.CausalGraph getCausalGraph(String correlationId, Integer limit) {
        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 500) : 100;
        return causalGraphService.buildGraph(correlationId, effectiveLimit, currentPrincipal.tenancyId());
    }

    public String renderCausalGraph(String correlationId, Integer limit) {
        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 500) : 200;
        var graph = causalGraphService.buildGraph(correlationId, effectiveLimit, currentPrincipal.tenancyId());
        return io.casehub.qhorus.runtime.ledger.CausalGraphRenderer.render(graph);
    }

    // ── Projection ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public String projectChannel(String channel, String projectionName,
                                  Integer maxMessages, String topic) {
        Channel ch = findChannel(channel);
        var projection = projectionRegistry.get(projectionName);
        return projectAndRender(ch.id(), projection, maxMessages, topic);
    }

    private <S> String projectAndRender(UUID channelId, io.casehub.qhorus.api.spi.RenderableProjection<S> projection,
                                         Integer maxMessages, String topic) {
        String normalizedTopic = (topic != null && !topic.isBlank()) ? topic : null;
        boolean hasLimit = maxMessages != null && maxMessages > 0;
        if (normalizedTopic != null || hasLimit) {
            var qb = MessageQuery.builder();
            if (normalizedTopic != null) qb.topic(normalizedTopic);
            if (hasLimit) qb.limit(maxMessages);
            var result = projectionService.project(channelId, qb.build(), projection);
            return projection.render(result);
        }
        var result = projectionService.project(channelId, projection);
        return projection.render(result);
    }

    // ── Utilities ───────────────────────────────────────────────────────────────

    public UUID resolveChannelId(String channelName) {
        return findChannel(channelName).id();
    }

    public List<ChannelDetail> findChannelByKeyword(String keyword) {
        List<Channel> matches = channelStore.scan(ChannelQuery.byKeyword(keyword));
        return matches.stream()
                .map(ch -> entityMapper.toChannelDetail(ch, messageStore.countByChannel(ch.id()),
                        bindingStore.findByChannelId(ch.id())))
                .toList();
    }

    public Channel findChannel(String channelName) {
        UUID uuid = tryParseUuid(channelName);
        if (uuid != null) {
            return channelStore.find(uuid)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Channel not found: " + channelName));
        }
        return channelStore.findByName(channelName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Channel not found: " + channelName));
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    private ChannelDetail toChannelDetail(Channel ch) {
        return entityMapper.toChannelDetail(ch, messageStore.countByChannel(ch.id()),
                bindingStore.findByChannelId(ch.id()));
    }

    private static void checkAdminAccess(Channel ch, String callerInstanceId, String toolName) {
        if (ch.adminInstances() == null || ch.adminInstances().isEmpty()) return;
        if (callerInstanceId == null || callerInstanceId.isBlank()) {
            throw new IllegalStateException(
                    "Channel '" + ch.name() + "' requires a caller_instance_id for " + toolName
                            + " — it has an admin_instances list.");
        }
        if (!ch.adminInstances().contains(callerInstanceId)) {
            throw new IllegalStateException(
                    "Caller '" + callerInstanceId + "' is not permitted to invoke " + toolName
                            + " on channel '" + ch.name() + "'. Not in admin_instances list.");
        }
    }

    private MessageSummary toMessageSummary(Message m) {
        List<ArtefactRef> refs = m.artefactRefs() != null ? m.artefactRefs() : List.of();
        return new MessageSummary(m.id(), m.sender(), m.messageType().name(), m.content(),
                m.payload(), m.correlationId(), m.inReplyTo(),
                m.createdAt() != null ? m.createdAt().toString() : null,
                refs, m.target(), m.topic());
    }

    private ArtefactDetail toArtefactDetail(SharedData d) {
        return new ArtefactDetail(d.id(), d.key(), d.description(), d.createdBy(),
                d.content(), d.complete(), d.sizeBytes(),
                d.updatedAt() != null ? d.updatedAt().toString() : null);
    }

    private WatchdogSummary toWatchdogSummary(Watchdog w) {
        return new WatchdogSummary(
                w.id().toString(), w.conditionType().name(), w.targetName(),
                w.thresholdSeconds(), w.thresholdCount(), w.similarityPct(),
                w.notificationChannel(), w.createdBy(),
                w.createdAt() != null ? w.createdAt().toString() : null,
                w.lastFiredAt() != null ? w.lastFiredAt().toString() : null,
                w.action() != null ? w.action().name() : "ALERT");
    }

    private Map<String, Object> toLedgerEntryMap(MessageLedgerEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entry_id", e.id != null ? e.id.toString() : null);
        m.put("sequence_number", (long) e.sequenceNumber);
        m.put("message_type", e.messageType);
        m.put("entry_type", e.entryType != null ? e.entryType.name() : null);
        m.put("actor_id", e.actorId);
        m.put("target", e.target);
        m.put("content", e.content);
        m.put("correlation_id", e.correlationId);
        m.put("commitment_id", e.commitmentId != null ? e.commitmentId.toString() : null);
        m.put("caused_by_entry_id", e.causedByEntryId != null ? e.causedByEntryId.toString() : null);
        m.put("occurred_at", e.occurredAt != null ? e.occurredAt.toString() : null);
        m.put("message_id", e.messageId);
        if (e.toolName != null) m.put("tool_name", e.toolName);
        if (e.durationMs != null) m.put("duration_ms", e.durationMs);
        if (e.tokenCount != null) m.put("token_count", e.tokenCount);
        if (e.contextRefs != null) m.put("context_refs", e.contextRefs);
        if (e.sourceEntity != null) m.put("source_entity", e.sourceEntity);
        return m;
    }

    private static UUID tryParseUuid(String value) {
        if (value == null || value.length() != 36) return null;
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    public String telemetryJson(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("telemetryJson requires key-value pairs");
        }
        var map = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
