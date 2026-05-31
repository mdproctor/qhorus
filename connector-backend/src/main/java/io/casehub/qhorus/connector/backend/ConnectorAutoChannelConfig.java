package io.casehub.qhorus.connector.backend;

import java.util.Map;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "casehub.qhorus.connector.auto-channel")
interface ConnectorAutoChannelConfig {

    /** Keyed by inbound connector ID, e.g. {@code "twilio-sms-inbound"}. */
    Map<String, ConnectorAutoChannelEntry> entries();

    interface ConnectorAutoChannelEntry {
        /**
         * Whether auto-channel creation is active for this connector.
         * Default: false — absent key means disabled, not a config error.
         */
        @WithDefault("false") boolean enabled();

        /**
         * Outbound connector ID for replies.
         * Required for email, Slack; optional for SMS and WhatsApp (convention mapping applies).
         */
        Optional<String> outboundConnectorId();

        /**
         * Channel name pattern. Tokens: {@code {connectorId}}, {@code {lookupKey}}.
         * Default: {@code connector/{connectorId}/{lookupKey}}.
         */
        Optional<String> channelNamePattern();

        /** Channel semantic for auto-created channels. Default: APPEND. */
        Optional<String> semantic();
    }
}
