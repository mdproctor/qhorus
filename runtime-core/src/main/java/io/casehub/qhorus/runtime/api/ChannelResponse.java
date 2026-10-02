package io.casehub.qhorus.runtime.api;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.message.MessageType;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public record ChannelResponse(
        UUID channelId,
        String name,
        String description,
        String semantic,
        long messageCount,
        String lastActivityAt,
        boolean paused,
        List<String> barrierContributors,
        List<String> allowedWriters,
        List<String> adminInstances,
        List<String> reviewerInstances,
        Set<String> allowedTypes,
        Set<String> deniedTypes,
        Integer rateLimitPerChannel,
        Integer rateLimitPerInstance,
        UUID spaceId,
        String spaceName,
        List<String> protocols,
        List<String> protocolParticipants,
        Boolean trackDelivery,
        String enforcementMode,
        List<String> enforcementExclusions,
        java.util.Map<String, String> metadata) {

    public static ChannelResponse from(final Channel ch, final long messageCount, final String spaceName) {
        return new ChannelResponse(
                ch.id(), ch.name(), ch.description(),
                ch.semantic() != null ? ch.semantic().name() : null,
                messageCount,
                ch.lastActivityAt() != null ? ch.lastActivityAt().toString() : null,
                ch.paused(),
                ch.barrierContributors(),
                ch.allowedWriters(),
                ch.adminInstances(),
                ch.reviewerInstances(),
                ch.allowedTypes() != null
                        ? ch.allowedTypes().stream().map(MessageType::name).collect(Collectors.toSet())
                        : null,
                ch.deniedTypes() != null
                        ? ch.deniedTypes().stream().map(MessageType::name).collect(Collectors.toSet())
                        : null,
                ch.rateLimitPerChannel(),
                ch.rateLimitPerInstance(),
                ch.spaceId(), spaceName,
                ch.protocols(),
                ch.protocolParticipants(),
                ch.trackDelivery(),
                ch.enforcementMode() != null && ch.enforcementMode() != io.casehub.qhorus.api.channel.EnforcementMode.ADVISORY
                        ? ch.enforcementMode().name() : null,
                ch.enforcementExclusions(),
                ch.metadata());
    }
}
