package io.casehub.qhorus.api.gateway;

/**
 * SPI for broadcasting channel activity events.
 *
 * <p>Implementations can notify external systems (e.g., SSE streams,
 * WebSocket channels) when new messages are dispatched to a channel.
 *
 * <p>Default implementation is a no-op. Override by providing a CDI bean
 * with {@code @Alternative @Priority(100)} or higher.
 *
 * <p>Refs #162.
 */
@FunctionalInterface
public interface ChannelActivityBroadcaster {

    /**
     * Broadcast a channel activity event.
     *
     * @param event the activity event to broadcast
     */
    void broadcast(ChannelActivityEvent event);

    /**
     * Channel activity event.
     *
     * @param channelId   the channel UUID
     * @param channelName the channel name
     * @param messageId   the message primary key
     */
    record ChannelActivityEvent(
        java.util.UUID channelId,
        String channelName,
        Long messageId
    ) {}
}
