package io.casehub.qhorus.api.channel;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.casehub.qhorus.api.message.MessageType;

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
        String tenancyId,
        Instant createdAt,
        Instant lastActivityAt) {

    public Channel {
        barrierContributors = barrierContributors != null ? List.copyOf(barrierContributors) : null;
        allowedWriters = allowedWriters != null ? List.copyOf(allowedWriters) : null;
        adminInstances = adminInstances != null ? List.copyOf(adminInstances) : null;
        allowedTypes = allowedTypes != null ? Set.copyOf(allowedTypes) : null;
        deniedTypes = deniedTypes != null ? Set.copyOf(deniedTypes) : null;
    }

    public static Channel fromRequest(ChannelCreateRequest req, String tenancyId) {
        Instant now = Instant.now();
        return new Channel(
                UUID.randomUUID(),
                req.name(),
                req.description(),
                req.semantic() != null ? req.semantic() : ChannelSemantic.APPEND,
                splitCsv(req.barrierContributors()),
                splitCsv(req.allowedWriters()),
                splitCsv(req.adminInstances()),
                req.rateLimitPerChannel(),
                req.rateLimitPerInstance(),
                req.allowedTypes(),
                req.deniedTypes(),
                false,
                false,
                tenancyId,
                now,
                now);
    }

    public static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return null;
        List<String> result = java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return result.isEmpty() ? null : result;
    }

    public Builder toBuilder() {
        return new Builder(name)
                .id(id).description(description).semantic(semantic)
                .barrierContributors(barrierContributors).allowedWriters(allowedWriters)
                .adminInstances(adminInstances).rateLimitPerChannel(rateLimitPerChannel)
                .rateLimitPerInstance(rateLimitPerInstance).allowedTypes(allowedTypes)
                .deniedTypes(deniedTypes).paused(paused).autoCreated(autoCreated)
                .tenancyId(tenancyId).createdAt(createdAt).lastActivityAt(lastActivityAt);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private UUID id;
        private String description;
        private ChannelSemantic semantic;
        private List<String> barrierContributors;
        private List<String> allowedWriters;
        private List<String> adminInstances;
        private Integer rateLimitPerChannel;
        private Integer rateLimitPerInstance;
        private Set<MessageType> allowedTypes;
        private Set<MessageType> deniedTypes;
        private boolean paused;
        private boolean autoCreated;
        private String tenancyId;
        private Instant createdAt;
        private Instant lastActivityAt;

        private Builder(String name) { this.name = name; }

        public Builder id(UUID v) { this.id = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder semantic(ChannelSemantic v) { this.semantic = v; return this; }
        public Builder barrierContributors(List<String> v) { this.barrierContributors = v; return this; }
        public Builder allowedWriters(List<String> v) { this.allowedWriters = v; return this; }
        public Builder adminInstances(List<String> v) { this.adminInstances = v; return this; }
        public Builder rateLimitPerChannel(Integer v) { this.rateLimitPerChannel = v; return this; }
        public Builder rateLimitPerInstance(Integer v) { this.rateLimitPerInstance = v; return this; }
        public Builder allowedTypes(Set<MessageType> v) { this.allowedTypes = v; return this; }
        public Builder deniedTypes(Set<MessageType> v) { this.deniedTypes = v; return this; }
        public Builder paused(boolean v) { this.paused = v; return this; }
        public Builder autoCreated(boolean v) { this.autoCreated = v; return this; }
        public Builder tenancyId(String v) { this.tenancyId = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }
        public Builder lastActivityAt(Instant v) { this.lastActivityAt = v; return this; }

        public Channel build() {
            return new Channel(id, name, description, semantic,
                    barrierContributors, allowedWriters, adminInstances,
                    rateLimitPerChannel, rateLimitPerInstance,
                    allowedTypes, deniedTypes,
                    paused, autoCreated, tenancyId, createdAt, lastActivityAt);
        }
    }
}
