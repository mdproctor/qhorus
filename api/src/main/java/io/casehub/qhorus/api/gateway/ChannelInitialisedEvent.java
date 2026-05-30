package io.casehub.qhorus.api.gateway;

import java.util.UUID;

/**
 * Fired by {@link io.casehub.qhorus.runtime.gateway.ChannelGateway} when a channel's
 * gateway registry entry is initialised — either on channel creation or on application
 * startup (recovery of all persisted channels).
 *
 * <p>External backends observe this event to register themselves without needing their
 * own startup recovery logic. The {@link #recovered} flag distinguishes startup recovery
 * ({@code true}) from first-time channel creation or binding update ({@code false}).
 *
 * <p>Refs #183.
 */
public record ChannelInitialisedEvent(UUID channelId, String channelName, boolean recovered) {}
