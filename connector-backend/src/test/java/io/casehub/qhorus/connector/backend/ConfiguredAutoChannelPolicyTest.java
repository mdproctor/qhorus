package io.casehub.qhorus.connector.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.connectors.InboundConnectorIds;
import io.casehub.connectors.InboundMessage;
import io.casehub.qhorus.api.channel.ChannelSemantic;

class ConfiguredAutoChannelPolicyTest {

    private ConnectorAutoChannelConfig config;
    private ConnectorAutoChannelConfig.ConnectorAutoChannelEntry smsEntry;
    private ConfiguredAutoChannelPolicy policy;

    private InboundMessage smsMsg(String sender) {
        return new InboundMessage(InboundConnectorIds.TWILIO_SMS, sender,
                "+14155550000", "hello", Instant.now(), Map.of());
    }

    private InboundMessage emailMsg(String sender) {
        return new InboundMessage(InboundConnectorIds.EMAIL, sender,
                "support@company.com", "hello", Instant.now(), Map.of());
    }

    @BeforeEach
    void setUp() {
        config = mock(ConnectorAutoChannelConfig.class);
        smsEntry = mock(ConnectorAutoChannelConfig.ConnectorAutoChannelEntry.class);
        when(smsEntry.enabled()).thenReturn(true);
        when(smsEntry.outboundConnectorId()).thenReturn(Optional.empty());
        when(smsEntry.channelNamePattern()).thenReturn(Optional.empty());
        when(smsEntry.semantic()).thenReturn(Optional.empty());
        when(config.entries()).thenReturn(Map.of(InboundConnectorIds.TWILIO_SMS, smsEntry));
        policy = new ConfiguredAutoChannelPolicy(config);
    }

    @Test
    void sms_enabled_conventionResolvesOutbound() {
        Optional<AutoChannelSpec> result = policy.onFirstContact(smsMsg("+447911000001"), "+447911000001");

        assertThat(result).isPresent();
        AutoChannelSpec spec = result.get();
        assertThat(spec.outboundConnectorId()).isEqualTo("twilio-sms");
        assertThat(spec.outboundDestination()).isEqualTo("+447911000001");
        assertThat(spec.semantic()).isEqualTo(ChannelSemantic.APPEND);
        assertThat(spec.allowedTypes()).isNull();
    }

    @Test
    void sms_enabled_defaultChannelName() {
        Optional<AutoChannelSpec> result = policy.onFirstContact(smsMsg("+447911000001"), "+447911000001");

        assertThat(result).isPresent();
        assertThat(result.get().channelName())
                .isEqualTo("connector/" + InboundConnectorIds.TWILIO_SMS + "/+447911000001");
    }

    @Test
    void sms_enabled_descriptionMentionsConnectorAndSender() {
        Optional<AutoChannelSpec> result = policy.onFirstContact(smsMsg("+447911000001"), "+447911000001");

        assertThat(result).isPresent();
        assertThat(result.get().description())
                .contains(InboundConnectorIds.TWILIO_SMS)
                .contains("+447911000001");
    }

    @Test
    void sms_customPattern_substitutesTokens() {
        when(smsEntry.channelNamePattern()).thenReturn(Optional.of("sms/{lookupKey}"));

        Optional<AutoChannelSpec> result = policy.onFirstContact(smsMsg("+447911000001"), "+447911000001");

        assertThat(result).isPresent();
        assertThat(result.get().channelName()).isEqualTo("sms/+447911000001");
    }

    @Test
    void sms_semanticOverride_appliesConfiguredSemantic() {
        when(smsEntry.semantic()).thenReturn(Optional.of("LAST_WRITE"));

        Optional<AutoChannelSpec> result = policy.onFirstContact(smsMsg("+447911"), "+447911");

        assertThat(result).isPresent();
        assertThat(result.get().semantic()).isEqualTo(ChannelSemantic.LAST_WRITE);
    }

    @Test
    void sms_disabled_returnsEmpty() {
        when(smsEntry.enabled()).thenReturn(false);

        assertThat(policy.onFirstContact(smsMsg("+447911"), "+447911")).isEmpty();
    }

    @Test
    void unknownConnector_noEntry_returnsEmpty() {
        InboundMessage slackMsg = new InboundMessage("slack-inbound", "U12345",
                "C67890", "hi", Instant.now(), Map.of());
        assertThat(policy.onFirstContact(slackMsg, "C67890")).isEmpty();
    }

    @Test
    void email_enabled_explicitOutbound_returnsSpec() {
        ConnectorAutoChannelConfig.ConnectorAutoChannelEntry emailEntry =
                mock(ConnectorAutoChannelConfig.ConnectorAutoChannelEntry.class);
        when(emailEntry.enabled()).thenReturn(true);
        when(emailEntry.outboundConnectorId()).thenReturn(Optional.of("email"));
        when(emailEntry.channelNamePattern()).thenReturn(Optional.empty());
        when(emailEntry.semantic()).thenReturn(Optional.empty());
        when(config.entries()).thenReturn(Map.of(InboundConnectorIds.EMAIL, emailEntry));

        Optional<AutoChannelSpec> result = policy.onFirstContact(
                emailMsg("alice@example.com"), "alice@example.com");

        assertThat(result).isPresent();
        assertThat(result.get().outboundConnectorId()).isEqualTo("email");
        assertThat(result.get().outboundDestination()).isEqualTo("alice@example.com");
    }

    @Test
    void email_enabled_noOutboundConfig_noConvention_returnsEmpty() {
        ConnectorAutoChannelConfig.ConnectorAutoChannelEntry emailEntry =
                mock(ConnectorAutoChannelConfig.ConnectorAutoChannelEntry.class);
        when(emailEntry.enabled()).thenReturn(true);
        when(emailEntry.outboundConnectorId()).thenReturn(Optional.empty());
        when(emailEntry.channelNamePattern()).thenReturn(Optional.empty());
        when(emailEntry.semantic()).thenReturn(Optional.empty());
        when(config.entries()).thenReturn(Map.of(InboundConnectorIds.EMAIL, emailEntry));

        assertThat(policy.onFirstContact(emailMsg("alice@example.com"), "alice@example.com"))
                .isEmpty();
    }

    @Test
    void whatsapp_enabled_conventionResolvesOutbound() {
        ConnectorAutoChannelConfig.ConnectorAutoChannelEntry waEntry =
                mock(ConnectorAutoChannelConfig.ConnectorAutoChannelEntry.class);
        when(waEntry.enabled()).thenReturn(true);
        when(waEntry.outboundConnectorId()).thenReturn(Optional.empty());
        when(waEntry.channelNamePattern()).thenReturn(Optional.empty());
        when(waEntry.semantic()).thenReturn(Optional.empty());
        when(config.entries()).thenReturn(Map.of(InboundConnectorIds.WHATSAPP, waEntry));

        InboundMessage waMsg = new InboundMessage(InboundConnectorIds.WHATSAPP,
                "+44791100001", "+14155550000", "hi", Instant.now(), Map.of());

        Optional<AutoChannelSpec> result = policy.onFirstContact(waMsg, "+44791100001");

        assertThat(result).isPresent();
        assertThat(result.get().outboundConnectorId()).isEqualTo("whatsapp");
    }
}
