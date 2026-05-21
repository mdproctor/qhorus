package io.casehub.qhorus.api.channel;

import java.util.UUID;

public record ChannelDetail(
        UUID channelId,
        String name,
        String description,
        String semantic,
        String barrierContributors,
        long messageCount,
        String lastActivityAt,
        boolean paused,
        /** Comma-separated allowed-writer entries, or null if the channel is open to all writers. */
        String allowedWriters,
        /** Comma-separated admin instance IDs, or null if management is open to any caller. */
        String adminInstances,
        /** Max messages per minute across all senders. Null = unlimited. */
        Integer rateLimitPerChannel,
        /** Max messages per minute from a single sender. Null = unlimited. */
        Integer rateLimitPerInstance,
        /** Comma-separated permitted MessageType names, or null if open to all types. */
        String allowedTypes) {
}
