package io.casehub.qhorus.runtime.api.core;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.channel.MembershipManager;
import io.casehub.qhorus.api.channel.PresenceTracker;
import io.casehub.qhorus.api.channel.ReactionManager;
import io.casehub.qhorus.api.channel.Space;
import io.casehub.qhorus.api.channel.TopicManager;
import io.casehub.qhorus.api.event.ChannelMutationEvent;
import io.casehub.qhorus.api.message.ConsumerMessaging;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.Reaction;
import io.casehub.qhorus.api.store.CommitmentReader;
import io.casehub.qhorus.api.store.MembershipReader;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.ReactionReader;
import io.casehub.qhorus.api.store.SpaceStore;
import io.casehub.qhorus.api.store.TopicReader;
import io.casehub.qhorus.api.store.query.ChannelQuery;
import io.casehub.qhorus.runtime.api.ChannelResponse;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.dashboard.QhorusDashboardService;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ChannelCore {

    private final ChannelService channelService;
    private final MessageStore messageStore;
    private final SpaceStore spaceStore;
    private final QhorusDashboardService dashboard;
    private final ReactionManager reactionManager;
    private final ReactionReader reactionReader;
    private final TopicManager topicManager;
    private final TopicReader topicReader;
    private final MembershipManager membershipManager;
    private final MembershipReader membershipReader;
    private final PresenceTracker presenceTracker;
    private final CommitmentReader commitmentReader;
    private final ConsumerMessaging messaging;
    private final Consumer<ChannelMutationEvent> mutationEvent;

    public ChannelCore(ChannelService channelService,
                       MessageStore messageStore,
                       SpaceStore spaceStore,
                       QhorusDashboardService dashboard,
                       ReactionManager reactionManager,
                       ReactionReader reactionReader,
                       TopicManager topicManager,
                       TopicReader topicReader,
                       MembershipManager membershipManager,
                       MembershipReader membershipReader,
                       PresenceTracker presenceTracker,
                       CommitmentReader commitmentReader,
                       ConsumerMessaging messaging,
                       Consumer<ChannelMutationEvent> mutationEvent) {
        this.channelService = channelService;
        this.messageStore = messageStore;
        this.spaceStore = spaceStore;
        this.dashboard = dashboard;
        this.reactionManager = reactionManager;
        this.reactionReader = reactionReader;
        this.topicManager = topicManager;
        this.topicReader = topicReader;
        this.membershipManager = membershipManager;
        this.membershipReader = membershipReader;
        this.presenceTracker = presenceTracker;
        this.commitmentReader = commitmentReader;
        this.messaging = messaging;
        this.mutationEvent = mutationEvent;
    }

    public Optional<Channel> resolve(String idOrName) {
        UUID uuid = tryParseUuid(idOrName);
        if (uuid != null) {
            return channelService.findById(uuid);
        }
        return channelService.findByName(idOrName);
    }

    public ChannelResponse create(CreateChannelRequest req) {
        var builder = ChannelCreateRequest.builder(req.name());
        if (req.description() != null) builder.description(req.description());
        if (req.semantic() != null) builder.semantic(ChannelSemantic.valueOf(req.semantic()));
        if (req.barrierContributors() != null) builder.barrierContributors(req.barrierContributors());
        if (req.allowedWriters() != null) builder.allowedWriters(req.allowedWriters());
        if (req.adminInstances() != null) builder.adminInstances(req.adminInstances());
        if (req.reviewerInstances() != null) builder.reviewerInstances(req.reviewerInstances());
        if (req.allowedTypes() != null) builder.allowedTypes(parseTypes(req.allowedTypes()));
        if (req.deniedTypes() != null) builder.deniedTypes(parseTypes(req.deniedTypes()));
        if (req.protocols() != null) builder.protocols(req.protocols());
        if (req.protocolParticipants() != null) builder.protocolParticipants(req.protocolParticipants());
        if (req.spaceId() != null) builder.spaceId(req.spaceId());
        if (req.trackDelivery() != null) builder.trackDelivery(req.trackDelivery());
        if (req.rateLimitPerChannel() != null) builder.rateLimitPerChannel(req.rateLimitPerChannel());
        if (req.rateLimitPerInstance() != null) builder.rateLimitPerInstance(req.rateLimitPerInstance());
        return toResponse(channelService.create(builder.build()));
    }

    public List<ChannelResponse> list(String prefix, UUID spaceId, Boolean paused) {
        return list(prefix, spaceId, paused, null, null);
    }

    public List<ChannelResponse> list(String prefix, UUID spaceId, Boolean paused, String metadataKey, String metadataValue) {
        List<Channel> channels;
        if (prefix != null || spaceId != null || paused != null || metadataKey != null) {
            var qb = ChannelQuery.builder();
            if (prefix != null) qb.namePrefix(prefix);
            if (spaceId != null) qb.spaceId(spaceId);
            if (paused != null) qb.paused(paused);
            if (metadataKey != null) qb.metadataKey(metadataKey).metadataValue(metadataValue);
            channels = channelService.scan(qb.build());
        } else {
            channels = channelService.listAll();
        }
        return toResponseList(channels);
    }

    public ChannelResponse getById(String id) {
        return toResponse(requireChannel(id));
    }

    public void delete(String id, boolean force) {
        channelService.delete(requireChannel(id).id(), force);
    }

    public List<Map<String, Object>> feed(int limit) {
        return dashboard.getFeed(limit);
    }

    public Object timeline(String id, Long after, int limit) {
        Channel ch = requireChannel(id);
        return dashboard.getTimeline(ch.name(), after != null ? after : 0, limit);
    }

    // -- Reactions --

    public void addReaction(String id, String messageId, ReactionRequest request) {
        requireChannel(id);
        long msgId = parseLongParam(messageId, "messageId");
        reactionManager.react(msgId, request.emoji());
        mutationEvent.accept(new ChannelMutationEvent.ReactionAdded(msgId, request.emoji()));
    }

    public void removeReaction(String id, String messageId, String emoji) {
        requireChannel(id);
        long msgId = parseLongParam(messageId, "messageId");
        reactionManager.unreact(msgId, emoji);
        mutationEvent.accept(new ChannelMutationEvent.ReactionRemoved(msgId, emoji));
    }

    public List<String> listReactions(String id, String messageId) {
        requireChannel(id);
        return reactionReader.findByMessage(parseLongParam(messageId, "messageId")).stream()
                .map(Reaction::emoji).toList();
    }

    // -- Topics --

    public Map<String, String> createTopic(String id, CreateTopicRequest request) {
        Channel ch = requireChannel(id);
        String name = request.name() != null ? request.name().trim() : "";
        if (name.isEmpty()) throw new IllegalArgumentException("Topic name must not be empty");
        if (name.length() > 100) throw new IllegalArgumentException("Topic name must be 100 characters or less");
        if ("General".equals(name) || "general".equals(name)) throw new IllegalStateException("\"General\" is reserved");
        var existing = topicReader.find(ch.id(), name);
        if (existing.isPresent()) throw new IllegalStateException("Topic already exists");
        var topic = topicManager.create(ch.id(), name);
        mutationEvent.accept(new ChannelMutationEvent.TopicCreated(ch.id(), topic));
        return Map.of("id", String.valueOf(topic.id()), "name", topic.name());
    }

    public Object listTopics(String id) {
        return topicManager.listTopics(requireChannel(id).id());
    }

    public void updateTopic(String id, String topicId, UpdateTopicRequest request) {
        Channel ch = requireChannel(id);
        long topicLongId = parseLongParam(topicId, "topicId");
        var existing = topicReader.findById(topicLongId);
        if (existing.isEmpty()) throw new IllegalArgumentException("Topic not found");
        if (!ch.id().equals(existing.get().channelId())) throw new IllegalArgumentException("Topic does not belong to this channel");
        if (request.name() != null) {
            var trimmed = request.name().trim();
            if (trimmed.isEmpty() || trimmed.length() > 100) throw new IllegalArgumentException("Invalid topic name");
            topicManager.rename(ch.id(), existing.get().name(), trimmed);
        }
        if (request.state() != null) {
            if ("RESOLVED".equals(request.state())) topicManager.resolve(ch.id(), existing.get().name());
            else if ("ACTIVE".equals(request.state()) && existing.get().resolved()) topicManager.unresolve(ch.id(), existing.get().name());
        }
        var updated = topicReader.findById(topicLongId).orElse(existing.get());
        mutationEvent.accept(new ChannelMutationEvent.TopicUpdated(ch.id(), updated));
    }

    public void mergeTopic(String id, String topicId, MergeTopicRequest request) {
        Channel ch = requireChannel(id);
        long sourceTopicId = parseLongParam(topicId, "topicId");
        var source = topicReader.findById(sourceTopicId);
        if (source.isEmpty()) throw new IllegalArgumentException("Source topic not found");
        if (!ch.id().equals(source.get().channelId())) throw new IllegalArgumentException("Source topic does not belong to this channel");
        if ("general".equalsIgnoreCase(source.get().name())) throw new IllegalArgumentException("Cannot merge the default topic");
        long targetTopicId = parseLongParam(request.targetTopicId(), "targetTopicId");
        var target = topicReader.findById(targetTopicId);
        if (target.isEmpty()) throw new IllegalArgumentException("Target topic not found");
        if (!ch.id().equals(target.get().channelId())) throw new IllegalArgumentException("Target topic does not belong to this channel");
        topicManager.merge(ch.id(), source.get().name(), target.get().name());
        mutationEvent.accept(new ChannelMutationEvent.TopicRemoved(ch.id(), sourceTopicId));
        var updatedTarget = topicReader.findById(targetTopicId).orElse(target.get());
        mutationEvent.accept(new ChannelMutationEvent.TopicUpdated(ch.id(), updatedTarget));
    }

    // -- Members --

    public Object listMembers(String id) {
        return membershipReader.findByChannel(requireChannel(id).id());
    }

    public void addMember(String id, AddMemberRequest request) {
        Channel ch = requireChannel(id);
        var membership = membershipManager.join(ch.id(), request.memberId());
        mutationEvent.accept(new ChannelMutationEvent.MemberJoined(ch.id(), membership));
    }

    public void removeMember(String id, String memberId) {
        Channel ch = requireChannel(id);
        membershipManager.leave(ch.id(), memberId);
        mutationEvent.accept(new ChannelMutationEvent.MemberLeft(ch.id(), memberId));
    }

    // -- Presence --

    public Object listPresence(String id) {
        return presenceTracker.getChannelPresence(requireChannel(id).id());
    }

    // -- Commitments --

    public Object listCommitments(String id) {
        return commitmentReader.findByChannel(requireChannel(id).id());
    }

    // -- Correlation --

    public Object correlationChain(String id, String correlationId) {
        requireChannel(id);
        return messaging.findAllByCorrelationId(correlationId);
    }

    // -- Messages --

    public Map<String, Object> postMessage(String id, MessagePostRequest request) {
        Channel ch = requireChannel(id);
        var dispatch = MessageDispatch.builder()
                .channelId(ch.id())
                .sender(request.sender())
                .type(MessageType.valueOf(request.type()))
                .actorType(io.casehub.platform.api.identity.ActorType.valueOf(request.actorType()))
                .content(request.content())
                .build();
        var result = messaging.dispatch(dispatch);
        return Map.of("messageId", result.messageId());
    }

    // -- Lifecycle --

    public ChannelResponse pause(String id) {
        return toResponse(channelService.pause(requireChannel(id).id()));
    }

    public ChannelResponse resume(String id) {
        return toResponse(channelService.resume(requireChannel(id).id()));
    }

    // -- Settings --

    public ChannelResponse setAllowedWriters(String id, StringListRequest req) {
        return toResponse(channelService.setAllowedWriters(requireChannel(id).id(), req.values() != null ? req.values() : List.of()));
    }

    public ChannelResponse setAdminInstances(String id, StringListRequest req) {
        return toResponse(channelService.setAdminInstances(requireChannel(id).id(), req.values() != null ? req.values() : List.of()));
    }

    public ChannelResponse setReviewerInstances(String id, StringListRequest req) {
        return toResponse(channelService.setReviewerInstances(requireChannel(id).id(), req.values() != null ? req.values() : List.of()));
    }

    public ChannelResponse setTypeConstraints(String id, TypeConstraintsRequest req) {
        Set<MessageType> allowed = req.allowedTypes() != null ? parseTypes(req.allowedTypes()) : null;
        Set<MessageType> denied = req.deniedTypes() != null ? parseTypes(req.deniedTypes()) : null;
        return toResponse(channelService.setTypeConstraints(requireChannel(id).id(), allowed, denied));
    }

    public ChannelResponse setRateLimits(String id, RateLimitsRequest req) {
        return toResponse(channelService.setRateLimits(requireChannel(id).id(), req.perChannel(), req.perInstance()));
    }

    public ChannelResponse setProtocols(String id, StringListRequest req) {
        return toResponse(channelService.setProtocols(requireChannel(id).id(), req.values() != null ? req.values() : List.of()));
    }

    public ChannelResponse setProtocolParticipants(String id, StringListRequest req) {
        return toResponse(channelService.setProtocolParticipants(requireChannel(id).id(), req.values() != null ? req.values() : List.of()));
    }

    public ChannelResponse setDeliveryTracking(String id, DeliveryTrackingRequest req) {
        UUID channelId = requireChannel(id).id();
        channelService.setTrackDelivery(channelId, req.enabled());
        return toResponse(channelService.findById(channelId).orElseThrow());
    }

    public ChannelResponse setEnforcementMode(String id, EnforcementModeRequest req) {
        Channel ch;
        UUID channelId = requireChannel(id).id();
        if (req.mode() != null) {
            io.casehub.qhorus.api.channel.EnforcementMode mode =
                    io.casehub.qhorus.api.channel.EnforcementMode.valueOf(req.mode().toUpperCase());
            ch = channelService.setEnforcementMode(channelId, mode);
        } else {
            ch = channelService.findById(channelId).orElseThrow();
        }
        if (req.exclusions() != null) {
            ch = channelService.setEnforcementExclusions(channelId, req.exclusions());
        }
        return toResponse(ch);
    }

    public ChannelResponse setMetadata(String id, java.util.Map<String, String> metadata) {
        return toResponse(channelService.setMetadata(requireChannel(id).id(), metadata));
    }

    public ChannelResponse setRoutingConfig(String id, RoutingConfigRequest req) {
        if (req.trustThreshold() != null && (req.trustThreshold() < 0.0 || req.trustThreshold() > 1.0)) {
            throw new IllegalArgumentException("trustThreshold must be between 0.0 and 1.0");
        }
        return toResponse(channelService.setRoutingTrustThreshold(requireChannel(id).id(), req.trustThreshold()));
    }

    // -- Mapping --

    public ChannelResponse toResponse(Channel ch) {
        long count = messageStore.countByChannel(ch.id());
        String spaceName = null;
        if (ch.spaceId() != null) {
            var spaces = spaceStore.findByIds(List.of(ch.spaceId()));
            if (!spaces.isEmpty()) spaceName = spaces.get(0).name();
        }
        return ChannelResponse.from(ch, count, spaceName);
    }

    public List<ChannelResponse> toResponseList(List<Channel> channels) {
        var spaceIds = channels.stream()
                .map(Channel::spaceId).filter(Objects::nonNull)
                .distinct().collect(Collectors.toList());
        Map<UUID, String> spaceNames = spaceIds.isEmpty()
                ? Map.of()
                : spaceStore.findByIds(spaceIds).stream()
                        .collect(Collectors.toMap(Space::id, Space::name));
        return channels.stream()
                .map(ch -> ChannelResponse.from(ch,
                        messageStore.countByChannel(ch.id()),
                        ch.spaceId() != null ? spaceNames.get(ch.spaceId()) : null))
                .collect(Collectors.toList());
    }

    // -- Internal --

    private Channel requireChannel(String idOrName) {
        return resolve(idOrName).orElseThrow(() ->
                new NoSuchElementException("Channel not found: " + idOrName));
    }

    static Set<MessageType> parseTypes(Set<String> names) {
        return names.stream().map(MessageType::valueOf).collect(Collectors.toSet());
    }

    public static long parseLongParam(String value, String name) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
        }
    }

    private static UUID tryParseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
