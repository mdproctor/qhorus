package io.casehub.qhorus.api.channel;

import io.casehub.qhorus.api.message.MessageType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record ChannelCreateRequest(
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
        UUID spaceId,
        List<String> reviewerInstances,
        List<String> protocols,
        List<String> protocolParticipants,
        Boolean trackDelivery,
        String inboundConnectorId,
        String externalKey,
        String outboundConnectorId,
        String outboundDestination) {

    public ChannelCreateRequest {
        io.casehub.qhorus.api.channel.ChannelSlugValidator.validateSlugPath(name);
        boolean anySet = inboundConnectorId != null || externalKey != null
                         || outboundConnectorId != null || outboundDestination != null;
        boolean allSet = inboundConnectorId != null && externalKey != null
                         && outboundConnectorId != null && outboundDestination != null;
        if (anySet && !allSet) {
            throw new IllegalArgumentException(
                    "Connector binding requires all four fields: inboundConnectorId, " +
                    "externalKey, outboundConnectorId, outboundDestination");
        }

        barrierContributors  = barrierContributors != null ? List.copyOf(barrierContributors) : List.of();
        allowedWriters       = allowedWriters != null ? List.copyOf(allowedWriters) : List.of();
        adminInstances       = adminInstances != null ? List.copyOf(adminInstances) : List.of();
        reviewerInstances    = reviewerInstances != null ? List.copyOf(reviewerInstances) : List.of();
        protocols            = protocols != null ? List.copyOf(protocols) : List.of();
        protocolParticipants = protocolParticipants != null ? List.copyOf(protocolParticipants) : List.of();
        allowedTypes         = allowedTypes != null ? Set.copyOf(allowedTypes) : null;
        deniedTypes          = deniedTypes != null ? Set.copyOf(deniedTypes) : null;

        if (allowedTypes != null && !allowedTypes.isEmpty()
            && deniedTypes != null && !deniedTypes.isEmpty()) {
            final Set<MessageType> overlap = new HashSet<>(allowedTypes);
            overlap.retainAll(deniedTypes);
            if (!overlap.isEmpty()) {
                throw new IllegalArgumentException(
                        "allowedTypes and deniedTypes must not intersect. Overlap: " + overlap);
            }
        }
    }

    public ChannelCreateRequest(
            String name, String description, ChannelSemantic semantic,
            List<String> barrierContributors, List<String> allowedWriters,
            List<String> adminInstances, Integer rateLimitPerChannel,
            Integer rateLimitPerInstance, Set<MessageType> allowedTypes,
            Set<MessageType> deniedTypes, UUID spaceId, List<String> reviewerInstances,
            List<String> protocols, List<String> protocolParticipants,
            String inboundConnectorId, String externalKey,
            String outboundConnectorId, String outboundDestination) {
        this(name, description, semantic, barrierContributors, allowedWriters, adminInstances,
             rateLimitPerChannel, rateLimitPerInstance, allowedTypes, deniedTypes,
             spaceId, reviewerInstances, protocols, protocolParticipants, null,
             inboundConnectorId, externalKey, outboundConnectorId, outboundDestination);
    }

    public ChannelCreateRequest(
            String name, String description, ChannelSemantic semantic,
            List<String> barrierContributors, List<String> allowedWriters,
            List<String> adminInstances, Integer rateLimitPerChannel,
            Integer rateLimitPerInstance, Set<MessageType> allowedTypes,
            Set<MessageType> deniedTypes, UUID spaceId, List<String> reviewerInstances,
            String inboundConnectorId, String externalKey,
            String outboundConnectorId, String outboundDestination) {
        this(name, description, semantic, barrierContributors, allowedWriters, adminInstances,
             rateLimitPerChannel, rateLimitPerInstance, allowedTypes, deniedTypes,
             spaceId, reviewerInstances, null, null, null,
             inboundConnectorId, externalKey, outboundConnectorId, outboundDestination);
    }

    public ChannelCreateRequest(
            String name, String description, ChannelSemantic semantic,
            List<String> barrierContributors, List<String> allowedWriters,
            List<String> adminInstances, Integer rateLimitPerChannel,
            Integer rateLimitPerInstance, Set<MessageType> allowedTypes,
            Set<MessageType> deniedTypes, UUID spaceId,
            String inboundConnectorId, String externalKey,
            String outboundConnectorId, String outboundDestination) {
        this(name, description, semantic, barrierContributors, allowedWriters, adminInstances,
             rateLimitPerChannel, rateLimitPerInstance, allowedTypes, deniedTypes,
             spaceId, null, null, null, null,
             inboundConnectorId, externalKey, outboundConnectorId, outboundDestination);
    }

    public ChannelCreateRequest(
            String name, String description, ChannelSemantic semantic,
            List<String> barrierContributors, List<String> allowedWriters,
            List<String> adminInstances, Integer rateLimitPerChannel,
            Integer rateLimitPerInstance, Set<MessageType> allowedTypes,
            Set<MessageType> deniedTypes,
            String inboundConnectorId, String externalKey,
            String outboundConnectorId, String outboundDestination) {
        this(name, description, semantic, barrierContributors, allowedWriters, adminInstances,
             rateLimitPerChannel, rateLimitPerInstance, allowedTypes, deniedTypes,
             null, null, null, null, null,
             inboundConnectorId, externalKey, outboundConnectorId, outboundDestination);
    }

    public boolean hasConnectorBinding() {
        return inboundConnectorId != null;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String           name;
        private       String           description;
        private       ChannelSemantic  semantic = ChannelSemantic.APPEND;
        private       List<String>     barrierContributors;
        private       List<String>     allowedWriters;
        private       List<String>     adminInstances;
        private       Integer          rateLimitPerChannel;
        private       Integer          rateLimitPerInstance;
        private       Set<MessageType> allowedTypes;
        private       Set<MessageType> deniedTypes;
        private       UUID             spaceId;
        private       List<String>     reviewerInstances;
        private       List<String>     protocols;
        private       List<String>     protocolParticipants;
        private       Boolean          trackDelivery;
        private       String           inboundConnectorId;
        private       String           externalKey;
        private       String           outboundConnectorId;
        private       String           outboundDestination;

        private Builder(String name) {this.name = name;}

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

        public Builder inboundConnectorId(String v) {
            this.inboundConnectorId = v;
            return this;
        }

        public Builder externalKey(String v) {
            this.externalKey = v;
            return this;
        }

        public Builder outboundConnectorId(String v) {
            this.outboundConnectorId = v;
            return this;
        }

        public Builder outboundDestination(String v) {
            this.outboundDestination = v;
            return this;
        }

        public ChannelCreateRequest build() {
            return new ChannelCreateRequest(name, description, semantic,
                                            barrierContributors, allowedWriters, adminInstances,
                                            rateLimitPerChannel, rateLimitPerInstance,
                                            allowedTypes, deniedTypes,
                                            spaceId, reviewerInstances,
                                            protocols, protocolParticipants, trackDelivery,
                                            inboundConnectorId, externalKey,
                                            outboundConnectorId, outboundDestination);
        }
    }
}
