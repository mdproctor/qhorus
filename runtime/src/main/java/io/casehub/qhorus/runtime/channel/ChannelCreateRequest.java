package io.casehub.qhorus.runtime.channel;

import io.casehub.qhorus.api.channel.ChannelSemantic;

/**
 * Request record for creating a Qhorus channel with an optional connector binding.
 * If {@code inboundConnectorId} is non-null, all four binding fields must be non-null.
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
        String allowedTypes,
        // Connector binding — all four non-null together, or all null
        String inboundConnectorId,
        String externalKey,
        String outboundConnectorId,
        String outboundDestination
) {
    public ChannelCreateRequest {
        boolean anySet = inboundConnectorId != null || externalKey != null
                || outboundConnectorId != null || outboundDestination != null;
        boolean allSet = inboundConnectorId != null && externalKey != null
                && outboundConnectorId != null && outboundDestination != null;
        if (anySet && !allSet) {
            throw new IllegalArgumentException(
                    "Connector binding requires all four fields: inboundConnectorId, " +
                    "externalKey, outboundConnectorId, outboundDestination");
        }
    }

    public boolean hasConnectorBinding() {
        return inboundConnectorId != null;
    }

    /** Convenience factory — no connector binding. */
    public static ChannelCreateRequest simple(String name, ChannelSemantic semantic) {
        return new ChannelCreateRequest(name, null, semantic, null, null, null,
                null, null, null, null, null, null, null);
    }
}
