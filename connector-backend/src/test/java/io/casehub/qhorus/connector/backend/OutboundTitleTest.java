package io.casehub.qhorus.connector.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.gateway.ChannelRef;

class OutboundTitleTest {

    private final ChannelRef channel = new ChannelRef(UUID.randomUUID(), "support-channel");

    @Test
    void email_returnsReChannelName() {
        assertThat(OutboundTitle.forConnector("email", channel))
                .isEqualTo("Re: support-channel");
    }

    @Test
    void slack_returnsNull() {
        assertThat(OutboundTitle.forConnector("slack", channel)).isNull();
    }

    @Test
    void twilioSms_returnsNull() {
        assertThat(OutboundTitle.forConnector("twilio-sms", channel)).isNull();
    }

    @Test
    void whatsapp_returnsNull() {
        assertThat(OutboundTitle.forConnector("whatsapp", channel)).isNull();
    }

    @Test
    void teams_returnsNull() {
        assertThat(OutboundTitle.forConnector("teams", channel)).isNull();
    }

    @Test
    void unknownConnector_returnsNull() {
        assertThat(OutboundTitle.forConnector("custom-outbound", channel)).isNull();
    }
}
