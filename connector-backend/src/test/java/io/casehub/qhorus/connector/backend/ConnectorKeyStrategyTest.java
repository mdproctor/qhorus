package io.casehub.qhorus.connector.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.casehub.connectors.InboundMessage;

class ConnectorKeyStrategyTest {

    private InboundMessage msg(String connectorId, String senderId, String channelRef) {
        return new InboundMessage(connectorId, senderId, channelRef, "content", Instant.now(), Map.of());
    }

    @Test
    void slackInbound_usesExternalChannelRef() {
        assertThat(ConnectorKeyStrategy.deriveKey(msg("slack-inbound", "U123", "C456")))
                .isEqualTo("C456");
    }

    @Test
    void twilioSmsInbound_usesExternalSenderId() {
        assertThat(ConnectorKeyStrategy.deriveKey(msg("twilio-sms-inbound", "+15551110000", "+14155552671")))
                .isEqualTo("+15551110000");
    }

    @Test
    void whatsappInbound_usesExternalSenderId() {
        assertThat(ConnectorKeyStrategy.deriveKey(msg("whatsapp-inbound", "+44700000000", "phone-number-id")))
                .isEqualTo("+44700000000");
    }

    @Test
    void emailInbound_usesExternalSenderId() {
        assertThat(ConnectorKeyStrategy.deriveKey(msg("email-inbound", "alice@example.com", "inbox@casehub.io")))
                .isEqualTo("alice@example.com");
    }

    @Test
    void unknownConnector_fallsBackToExternalChannelRef() {
        assertThat(ConnectorKeyStrategy.deriveKey(msg("custom-inbound", "sender-id", "channel-ref")))
                .isEqualTo("channel-ref");
    }
}
