package io.casehub.qhorus.api.channel;

import java.util.UUID;

public record ChannelConnectorBinding(
        UUID channelId,
        String inboundConnectorId,
        String externalKey,
        String outboundConnectorId,
        String outboundDestination) {}
