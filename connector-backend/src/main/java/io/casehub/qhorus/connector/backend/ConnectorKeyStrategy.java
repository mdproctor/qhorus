package io.casehub.qhorus.connector.backend;

import java.util.Set;

import io.casehub.connectors.InboundMessage;

/**
 * Derives the per-conversation lookup key from an InboundMessage.
 *
 * <p>For Slack, the externalChannelRef IS the conversation space (Slack channel ID).
 * For SMS/WhatsApp/Email, externalChannelRef is our own endpoint; the conversation
 * is with the sender — so externalSenderId is the correct key.
 */
final class ConnectorKeyStrategy {

    private static final Set<String> SENDER_KEYED = Set.of(
            "twilio-sms-inbound",
            "whatsapp-inbound",
            "email-inbound"
    );

    private ConnectorKeyStrategy() {}

    static String deriveKey(final InboundMessage msg) {
        if (SENDER_KEYED.contains(msg.connectorId())) {
            return msg.externalSenderId();
        }
        return msg.externalChannelRef();
    }
}
