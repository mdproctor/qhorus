package io.casehub.qhorus.api.channel;

import io.casehub.qhorus.api.message.MessageType;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record Channel(
        UUID id,
        String name,
        String description,
        ChannelSemantic semantic,
        List<String> barrierContributors,
        List<String> allowedWriters,
        List<String> adminInstances,
        Integer rateLimitPerChannel,
        Integer rateLimitPerInstance,
        Set<MessageType> allowedTypes,
        Set<MessageType> deniedTypes,
        boolean paused,
        boolean autoCreated,
        UUID spaceId,
        List<String> reviewerInstances,
        List<String> protocols,
        List<String> protocolParticipants,
        Boolean trackDelivery,
        String tenancyId,
        Instant createdAt,
        Instant lastActivityAt) {

    public Channel {
        barrierContributors  = barrierContributors != null ? List.copyOf(barrierContributors) : List.of();
        allowedWriters       = allowedWriters != null ? List.copyOf(allowedWriters) : List.of();
        adminInstances       = adminInstances != null ? List.copyOf(adminInstances) : List.of();
        reviewerInstances    = reviewerInstances != null ? List.copyOf(reviewerInstances) : List.of();
        protocols            = protocols != null ? List.copyOf(protocols) : List.of();
        protocolParticipants = protocolParticipants != null ? List.copyOf(protocolParticipants) : List.of();
        allowedTypes         = allowedTypes != null ? Set.copyOf(allowedTypes) : null;
        deniedTypes          = deniedTypes != null ? Set.copyOf(deniedTypes) : null;
    }

    public Channel(UUID id, String name, String description, ChannelSemantic semantic,
                   List<String> barrierContributors, List<String> allowedWriters,
                   List<String> adminInstances, Integer rateLimitPerChannel,
                   Integer rateLimitPerInstance, Set<MessageType> allowedTypes,
                   Set<MessageType> deniedTypes, boolean paused, boolean autoCreated,
                   UUID spaceId, List<String> reviewerInstances,
                   List<String> protocols, List<String> protocolParticipants,
                   String tenancyId, Instant createdAt, Instant lastActivityAt) {
        this(id, name, description, semantic, barrierContributors, allowedWriters,
             adminInstances, rateLimitPerChannel, rateLimitPerInstance, allowedTypes,
             deniedTypes, paused, autoCreated, spaceId, reviewerInstances,
             protocols, protocolParticipants, null, tenancyId, createdAt, lastActivityAt);
    }

    public Channel(UUID id, String name, String description, ChannelSemantic semantic,
                   List<String> barrierContributors, List<String> allowedWriters,
                   List<String> adminInstances, Integer rateLimitPerChannel,
                   Integer rateLimitPerInstance, Set<MessageType> allowedTypes,
                   Set<MessageType> deniedTypes, boolean paused, boolean autoCreated,
                   UUID spaceId, List<String> reviewerInstances,
                   String tenancyId, Instant createdAt, Instant lastActivityAt) {
        this(id, name, description, semantic, barrierContributors, allowedWriters,
             adminInstances, rateLimitPerChannel, rateLimitPerInstance, allowedTypes,
             deniedTypes, paused, autoCreated, spaceId, reviewerInstances,
             null, null, null, tenancyId, createdAt, lastActivityAt);
    }

    public Channel(UUID id, String name, String description, ChannelSemantic semantic,
                   List<String> barrierContributors, List<String> allowedWriters,
                   List<String> adminInstances, Integer rateLimitPerChannel,
                   Integer rateLimitPerInstance, Set<MessageType> allowedTypes,
                   Set<MessageType> deniedTypes, boolean paused, boolean autoCreated,
                   UUID spaceId, String tenancyId, Instant createdAt, Instant lastActivityAt) {
        this(id, name, description, semantic, barrierContributors, allowedWriters,
             adminInstances, rateLimitPerChannel, rateLimitPerInstance, allowedTypes,
             deniedTypes, paused, autoCreated, spaceId, null,
             null, null, null, tenancyId, createdAt, lastActivityAt);
    }

    public static Channel fromRequest(ChannelCreateRequest req, String tenancyId) {
        Instant now = Instant.now();
        return new Channel(
                UUID.randomUUID(),
                req.name(),
                req.description(),
                req.semantic() != null ? req.semantic() : ChannelSemantic.APPEND,
                req.barrierContributors(),
                req.allowedWriters(),
                req.adminInstances(),
                req.rateLimitPerChannel(),
                req.rateLimitPerInstance(),
                req.allowedTypes(),
                req.deniedTypes(),
                false,
                false,
                req.spaceId(),
                req.reviewerInstances(),
                req.protocols(),
                req.protocolParticipants(),
                req.trackDelivery(),
                tenancyId,
                now,
                now);
    }

    public Builder toBuilder() {
        return new Builder(name)
                       .id(id).description(description).semantic(semantic)
                       .barrierContributors(barrierContributors).allowedWriters(allowedWriters)
                       .adminInstances(adminInstances).rateLimitPerChannel(rateLimitPerChannel)
                       .rateLimitPerInstance(rateLimitPerInstance).allowedTypes(allowedTypes)
                       .deniedTypes(deniedTypes).paused(paused).autoCreated(autoCreated)
                       .spaceId(spaceId).reviewerInstances(reviewerInstances)
                       .protocols(protocols).protocolParticipants(protocolParticipants)
                       .trackDelivery(trackDelivery)
                       .tenancyId(tenancyId).createdAt(createdAt).lastActivityAt(lastActivityAt);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String           name;
        private       UUID             id;
        private       String           description;
        private       ChannelSemantic  semantic;
        private       List<String>     barrierContributors;
        private       List<String>     allowedWriters;
        private       List<String>     adminInstances;
        private       Integer          rateLimitPerChannel;
        private       Integer          rateLimitPerInstance;
        private       Set<MessageType> allowedTypes;
        private       Set<MessageType> deniedTypes;
        private       boolean          paused;
        private       boolean          autoCreated;
        private       UUID             spaceId;
        private       List<String>     reviewerInstances;
        private       List<String>     protocols;
        private       List<String>     protocolParticipants;
        private       Boolean          trackDelivery;
        private       String           tenancyId;
        private       Instant          createdAt;
        private       Instant          lastActivityAt;

        private Builder(String name) {this.name = name;}

        public Builder id(UUID v) {
            this.id = v;
            return this;
        }

        public Builder description(String v) {
            this.description = v;
            return this;
        }

        public Builder semantic(ChannelSemantic v) {
            this.semantic = v;
            return this;
        }

        public Builder barrierContributors(List<String> v) {
            this.barrierContributors = v;
            return this;
        }

        public Builder allowedWriters(List<String> v) {
            this.allowedWriters = v;
            return this;
        }

        public Builder adminInstances(List<String> v) {
            this.adminInstances = v;
            return this;
        }

        public Builder rateLimitPerChannel(Integer v) {
            this.rateLimitPerChannel = v;
            return this;
        }

        public Builder rateLimitPerInstance(Integer v) {
            this.rateLimitPerInstance = v;
            return this;
        }

        public Builder allowedTypes(Set<MessageType> v) {
            this.allowedTypes = v;
            return this;
        }

        public Builder deniedTypes(Set<MessageType> v) {
            this.deniedTypes = v;
            return this;
        }

        public Builder paused(boolean v) {
            this.paused = v;
            return this;
        }

        public Builder autoCreated(boolean v) {
            this.autoCreated = v;
            return this;
        }

        public Builder spaceId(UUID v) {
            this.spaceId = v;
            return this;
        }

        public Builder reviewerInstances(List<String> v) {
            this.reviewerInstances = v;
            return this;
        }

        public Builder protocols(List<String> v) {
            this.protocols = v;
            return this;
        }

        public Builder protocolParticipants(List<String> v) {
            this.protocolParticipants = v;
            return this;
        }

        public Builder trackDelivery(Boolean v) {
            this.trackDelivery = v;
            return this;
        }

        public Builder tenancyId(String v) {
            this.tenancyId = v;
            return this;
        }

        public Builder createdAt(Instant v) {
            this.createdAt = v;
            return this;
        }

        public Builder lastActivityAt(Instant v) {
            this.lastActivityAt = v;
            return this;
        }

        public Channel build() {
            return new Channel(id, name, description, semantic,
                               barrierContributors, allowedWriters, adminInstances,
                               rateLimitPerChannel, rateLimitPerInstance,
                               allowedTypes, deniedTypes,
                               paused, autoCreated, spaceId, reviewerInstances,
                               protocols, protocolParticipants, trackDelivery,
                               tenancyId, createdAt, lastActivityAt);
        }
    }
}
