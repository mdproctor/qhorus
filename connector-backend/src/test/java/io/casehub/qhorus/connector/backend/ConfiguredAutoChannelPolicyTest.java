package io.casehub.qhorus.connector.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.connectors.InboundConnectorIds;
import io.casehub.connectors.InboundConnectorTypes;
import io.casehub.connectors.InboundMessage;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.channel.ChannelSlugValidator;

class ConfiguredAutoChannelPolicyTest {

    private ConnectorAutoChannelConfig config;
    private ConnectorAutoChannelConfig.ConnectorAutoChannelEntry smsEntry;
    private ConfiguredAutoChannelPolicy policy;

    private InboundMessage smsMsg(String sender) {
        return new InboundMessage(InboundConnectorIds.TWILIO_SMS, InboundConnectorTypes.SMS, sender,
                "+14155550000", "hello", List.of(), Instant.now(), Map.of(), null);
    }

    private InboundMessage emailMsg(String sender) {
        return new InboundMessage(InboundConnectorIds.EMAIL, InboundConnectorTypes.EMAIL, sender,
                "support@company.com", "hello", List.of(), Instant.now(), Map.of(), null);
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
        // Connector ID unchanged (already a valid slug); phone sanitised with hash
        String name = result.get().channelName();
        assertThat(name).startsWith("connector/" + InboundConnectorIds.TWILIO_SMS + "/id-447911000001-");
        assertThat(name).matches("connector/" + InboundConnectorIds.TWILIO_SMS + "/id-447911000001-[0-9a-f]{8}");
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
        // {lookupKey} is sanitised; +447911000001 → id-447911000001-<hash>
        String name = result.get().channelName();
        assertThat(name).startsWith("sms/id-447911000001-");
        assertThat(name).matches("sms/id-447911000001-[0-9a-f]{8}");
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
        InboundMessage slackMsg = new InboundMessage(InboundConnectorIds.SLACK_INBOUND, InboundConnectorTypes.SLACK, "U12345",
                "C67890", "hi", List.of(), Instant.now(), Map.of(), null);
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

        InboundMessage waMsg = new InboundMessage(InboundConnectorIds.WHATSAPP, InboundConnectorTypes.WHATSAPP,
                "+44791100001", "+14155550000", "hi", List.of(), Instant.now(), Map.of(), null);

        Optional<AutoChannelSpec> result = policy.onFirstContact(waMsg, "+44791100001");

        assertThat(result).isPresent();
        assertThat(result.get().outboundConnectorId()).isEqualTo("whatsapp");
    }

    // ── sanitiseSegment ──

    @Test void sanitiseSegment_phoneGetsIdPrefix() {
        String result = ConfiguredAutoChannelPolicy.sanitiseSegment("+14155552671");
        assertThat(result).startsWith("id-14155552671-");
        assertThat(result).matches("id-14155552671-[0-9a-f]{8}");
    }

    @Test void sanitiseSegment_emailNormalised() {
        String result = ConfiguredAutoChannelPolicy.sanitiseSegment("user@example.com");
        assertThat(result).startsWith("user-example-com-");
        assertThat(result).matches("user-example-com-[0-9a-f]{8}");
    }

    @Test void sanitiseSegment_caseVariantsProduceSameResult() {
        String lower = ConfiguredAutoChannelPolicy.sanitiseSegment("user@example.com");
        String upper = ConfiguredAutoChannelPolicy.sanitiseSegment("User@Example.COM");
        assertThat(lower).isEqualTo(upper); // hash computed on lowercased form
    }

    @Test void sanitiseSegment_validSlugGetsHashAppended() {
        String result = ConfiguredAutoChannelPolicy.sanitiseSegment("twilio-sms-inbound");
        assertThat(result).startsWith("twilio-sms-inbound-");
        assertThat(result).matches("twilio-sms-inbound-[0-9a-f]{8}");
    }

    @Test void sanitiseSegment_alwaysProducesValidSlug() {
        // Any sanitised output must pass the segment validator
        String result = ConfiguredAutoChannelPolicy.sanitiseSegment("+14155552671");
        assertThat(ChannelSlugValidator.isValidSegment(result)).isTrue();
    }

    @Test void sanitiseSegment_uuidInputPreservesFullContent() {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        String result = ConfiguredAutoChannelPolicy.sanitiseSegment(uuid);
        // UUID starts with digit → id- prefix; full UUID content preserved (39 chars < 71)
        assertThat(result).startsWith("id-550e8400-e29b-41d4-a716-446655440000-");
    }

    @Test void sanitiseSegment_longInputTruncatedToMaxLength() {
        // Input is 100 'a' chars — exceeds 71-char prefix limit
        String longInput = "a".repeat(100);
        String result = ConfiguredAutoChannelPolicy.sanitiseSegment(longInput);
        // Total must be <= 80 chars and pass the segment validator
        assertThat(result).hasSizeLessThanOrEqualTo(80);
        assertThat(ChannelSlugValidator.isValidSegment(result)).isTrue();
    }

    // ── slugifyConnectorId ──

    @Test void slugifyConnectorId_validSlugUnchanged() {
        assertThat(ConfiguredAutoChannelPolicy.slugifyConnectorId("twilio-sms-inbound"))
            .isEqualTo("twilio-sms-inbound"); // NO hash appended
    }

    @Test void slugifyConnectorId_spacesNormalised() {
        assertThat(ConfiguredAutoChannelPolicy.slugifyConnectorId("My Connector"))
            .isEqualTo("my-connector"); // NO hash appended
    }

    @Test void slugifyConnectorId_digitStartGetsIdPrefix() {
        assertThat(ConfiguredAutoChannelPolicy.slugifyConnectorId("123connector"))
            .isEqualTo("id-123connector");
    }

    @Test void slugifyConnectorId_alwaysProducesValidSlug() {
        assertThat(ChannelSlugValidator.isValidSegment(
            ConfiguredAutoChannelPolicy.slugifyConnectorId("My Connector"))).isTrue();
    }

    // ── validatePattern ──

    @Test void validatePattern_acceptsAllPlaceholders() {
        assertThatNoException().isThrownBy(() ->
            ConfiguredAutoChannelPolicy.validatePattern("connector/{connectorId}/{lookupKey}"));
    }

    @Test void validatePattern_acceptsValidLiterals() {
        assertThatNoException().isThrownBy(() ->
            ConfiguredAutoChannelPolicy.validatePattern("sms/{lookupKey}"));
    }

    @Test void validatePattern_rejectsUppercaseLiteral() {
        assertThatIllegalStateException()
            .isThrownBy(() -> ConfiguredAutoChannelPolicy.validatePattern("Support/{lookupKey}"))
            .withMessageContaining("Support");
    }

    @Test void validatePattern_rejectsMixedLiteralWithUppercase() {
        assertThatIllegalStateException()
            .isThrownBy(() ->
                ConfiguredAutoChannelPolicy.validatePattern("Billing-{lookupKey}/work"))
            .withMessageContaining("Billing");
    }

    @Test void validatePattern_purePlaceholderSegmentPasses() {
        // {lookupKey} alone → substituted to "a" → valid
        assertThatNoException().isThrownBy(() ->
            ConfiguredAutoChannelPolicy.validatePattern("{lookupKey}/sub-path"));
    }

    @Test void validatePattern_rejectsDigitStartingLiteralSegment() {
        // A literal segment starting with a digit is invalid — must start with [a-z]
        assertThatIllegalStateException()
            .isThrownBy(() -> ConfiguredAutoChannelPolicy.validatePattern("123/{lookupKey}"))
            .withMessageContaining("123");
    }
}
