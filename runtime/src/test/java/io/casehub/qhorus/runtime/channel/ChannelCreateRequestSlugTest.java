package io.casehub.qhorus.runtime.channel;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Pure-Java tests — no CDI, no Quarkus. Tests the compact constructor slug gate. */
class ChannelCreateRequestSlugTest {

    @Test
    void simple_acceptsValidSlug() {
        assertThatNoException().isThrownBy(() ->
            io.casehub.qhorus.api.channel.ChannelCreateRequest.builder("billing-output").build());
    }

    @Test
    void simple_acceptsHierarchical() {
        assertThatNoException().isThrownBy(() ->
            io.casehub.qhorus.api.channel.ChannelCreateRequest.builder("case-abc/work").build());
    }

    @Test
    void compactConstructor_rejectsNameWithSpacesAndUppercase() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new io.casehub.qhorus.api.channel.ChannelCreateRequest(
                "Billing Output", null, ChannelSemantic.APPEND,
                null, null, null, null, null, null, null, null, null, null, null))
            .withMessageContaining("Billing Output");
    }

    @Test
    void compactConstructor_rejectsUuidShapedName() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new io.casehub.qhorus.api.channel.ChannelCreateRequest(
                "a81b4c6d-1234-5678-abcd-ef0123456789", null, ChannelSemantic.APPEND,
                null, null, null, null, null, null, null, null, null, null, null))
            .withMessageContaining("UUID-shaped");
    }

    @Test
    void compactConstructor_rejectsRawPhoneNumberInSegment() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new io.casehub.qhorus.api.channel.ChannelCreateRequest(
                "connector/twilio-sms-inbound/+14155552671", null, ChannelSemantic.APPEND,
                null, null, null, null, null, null, null, null, null, null, null))
            .withMessageContaining("+14155552671");
    }

    @Test
    void compactConstructor_acceptsValidHierarchicalSlug() {
        assertThatNoException().isThrownBy(() -> new io.casehub.qhorus.api.channel.ChannelCreateRequest(
            "connector/twilio-sms-inbound/id-14155552671-3fa2b100", null, ChannelSemantic.APPEND,
            null, null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void compactConstructor_slugValidationFiresBeforeOverlapValidation() {
        // Invalid slug AND overlapping types — slug error should come first
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new io.casehub.qhorus.api.channel.ChannelCreateRequest(
                "Invalid Name", null, ChannelSemantic.APPEND,
                null, null, null, null, null,
                java.util.Set.of(io.casehub.qhorus.api.message.MessageType.EVENT),
                java.util.Set.of(io.casehub.qhorus.api.message.MessageType.EVENT),
                null, null, null, null))
            .withMessageContaining("Invalid Name");
    }
}
