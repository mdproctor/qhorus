package io.casehub.qhorus.connector.backend;

import io.casehub.qhorus.api.channel.ChannelSemantic;

/**
 * Specification returned by {@link AutoChannelPolicy} describing the channel to create
 * on first contact from an unknown sender.
 *
 * <p>{@code allowedTypes} null = open channel (no message type restriction).
 * {@code description} should be human-readable,
 * e.g. "Auto-created on first contact via twilio-sms-inbound".
 */
public record AutoChannelSpec(
        String channelName,
        String description,
        ChannelSemantic semantic,
        String allowedTypes,
        String outboundConnectorId,
        String outboundDestination
) {}
