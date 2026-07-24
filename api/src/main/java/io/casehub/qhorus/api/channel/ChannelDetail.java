package io.casehub.qhorus.api.channel;

import java.util.UUID;

public record ChannelDetail(
        UUID channelId, String name, String description, String semantic,
        String barrierContributors, long messageCount, String lastActivityAt,
        boolean paused, String allowedWriters, String adminInstances,
        Integer rateLimitPerChannel, Integer rateLimitPerInstance,
        String allowedTypes, String deniedTypes, UUID spaceId, String spaceName,
        String reviewerInstances, String protocols, String protocolParticipants,
        Boolean trackDelivery,
        ConnectorBinding connectorBinding) {

    public ChannelDetail(UUID channelId, String name, String description, String semantic,
                         String barrierContributors, long messageCount, String lastActivityAt,
                         boolean paused, String allowedWriters, String adminInstances,
                         Integer rateLimitPerChannel, Integer rateLimitPerInstance,
                         String allowedTypes, String deniedTypes, UUID spaceId, String spaceName,
                         String reviewerInstances, String protocols, String protocolParticipants,
                         ConnectorBinding connectorBinding) {
        this(channelId, name, description, semantic, barrierContributors, messageCount,
             lastActivityAt, paused, allowedWriters, adminInstances, rateLimitPerChannel,
             rateLimitPerInstance, allowedTypes, deniedTypes, spaceId, spaceName,
             reviewerInstances, protocols, protocolParticipants, null, connectorBinding);
    }

    public ChannelDetail(UUID channelId, String name, String description, String semantic,
                         String barrierContributors, long messageCount, String lastActivityAt,
                         boolean paused, String allowedWriters, String adminInstances,
                         Integer rateLimitPerChannel, Integer rateLimitPerInstance,
                         String allowedTypes, String deniedTypes, UUID spaceId, String spaceName,
                         String reviewerInstances,
                         ConnectorBinding connectorBinding) {
        this(channelId, name, description, semantic, barrierContributors, messageCount,
             lastActivityAt, paused, allowedWriters, adminInstances, rateLimitPerChannel,
             rateLimitPerInstance, allowedTypes, deniedTypes, spaceId, spaceName,
             reviewerInstances, null, null, null, connectorBinding);
    }

    public record ConnectorBinding(String inboundConnectorId, String externalKey,
                                   String outboundConnectorId, String outboundDestination) {}
}
