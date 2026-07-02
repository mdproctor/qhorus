package io.casehub.qhorus.api.channel;

import java.util.HashSet;
import java.util.Set;

import io.casehub.qhorus.api.message.MessageType;

/**
 * Request record for creating a Qhorus channel with an optional connector binding.
 * If {@code inboundConnectorId} is non-null, all four binding fields must be non-null.
 *
 * <p>The compact constructor validates:
 * <ul>
 *   <li>Channel name is a well-formed slug path</li>
 *   <li>Connector binding completeness (all four fields present or all null)</li>
 *   <li>{@code allowedTypes} and {@code deniedTypes} do not intersect (denial wins by construction)</li>
 * </ul>
 *
 * <p>{@code null} for {@code allowedTypes} or {@code deniedTypes} means "open" (no restriction).
 * The record preserves null as-is — null and an empty set are distinct at the API level but
 * both serialize to {@code null} at the persistence boundary via {@link MessageType#serializeTypes}.
 */
public record ChannelCreateRequest(
        String name,
        String description,
        ChannelSemantic semantic,
        String barrierContributors,
        String allowedWriters,
        String adminInstances,
        Integer rateLimitPerChannel,
        Integer rateLimitPerInstance,
        Set<MessageType> allowedTypes,
        Set<MessageType> deniedTypes,
        // Connector binding — all four non-null together, or all null
        String inboundConnectorId,
        String externalKey,
        String outboundConnectorId,
        String outboundDestination
) {
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

        // Defensive copy — record fields must be immutable; caller mutation after construction
        // must not alter the validated state. Set.copyOf(null) throws NPE, hence the null guard.
        // Null is preserved (not normalized to Set.of()) — null means "open" and is a meaningful
        // contract distinct from "empty allowed set (nothing permitted)".
        allowedTypes = allowedTypes != null ? Set.copyOf(allowedTypes) : null;
        deniedTypes  = deniedTypes  != null ? Set.copyOf(deniedTypes)  : null;

        // Validate overlap — both fields may be null (null means "open", not "block all")
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

    public boolean hasConnectorBinding() {
        return inboundConnectorId != null;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private String description;
        private ChannelSemantic semantic = ChannelSemantic.APPEND;
        private String barrierContributors;
        private String allowedWriters;
        private String adminInstances;
        private Integer rateLimitPerChannel;
        private Integer rateLimitPerInstance;
        private Set<MessageType> allowedTypes;
        private Set<MessageType> deniedTypes;
        private String inboundConnectorId;
        private String externalKey;
        private String outboundConnectorId;
        private String outboundDestination;

        private Builder(String name) {
            this.name = name;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder semantic(ChannelSemantic semantic) {
            this.semantic = semantic;
            return this;
        }

        public Builder barrierContributors(String barrierContributors) {
            this.barrierContributors = barrierContributors;
            return this;
        }

        public Builder allowedWriters(String allowedWriters) {
            this.allowedWriters = allowedWriters;
            return this;
        }

        public Builder adminInstances(String adminInstances) {
            this.adminInstances = adminInstances;
            return this;
        }

        public Builder rateLimitPerChannel(Integer rateLimitPerChannel) {
            this.rateLimitPerChannel = rateLimitPerChannel;
            return this;
        }

        public Builder rateLimitPerInstance(Integer rateLimitPerInstance) {
            this.rateLimitPerInstance = rateLimitPerInstance;
            return this;
        }

        public Builder allowedTypes(Set<MessageType> allowedTypes) {
            this.allowedTypes = allowedTypes;
            return this;
        }

        public Builder deniedTypes(Set<MessageType> deniedTypes) {
            this.deniedTypes = deniedTypes;
            return this;
        }

        public Builder inboundConnectorId(String inboundConnectorId) {
            this.inboundConnectorId = inboundConnectorId;
            return this;
        }

        public Builder externalKey(String externalKey) {
            this.externalKey = externalKey;
            return this;
        }

        public Builder outboundConnectorId(String outboundConnectorId) {
            this.outboundConnectorId = outboundConnectorId;
            return this;
        }

        public Builder outboundDestination(String outboundDestination) {
            this.outboundDestination = outboundDestination;
            return this;
        }

        public ChannelCreateRequest build() {
            return new ChannelCreateRequest(name, description, semantic,
                    barrierContributors, allowedWriters, adminInstances,
                    rateLimitPerChannel, rateLimitPerInstance,
                    allowedTypes, deniedTypes,
                    inboundConnectorId, externalKey,
                    outboundConnectorId, outboundDestination);
        }
    }
}
