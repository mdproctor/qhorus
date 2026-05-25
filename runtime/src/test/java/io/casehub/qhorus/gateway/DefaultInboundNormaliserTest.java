package io.casehub.qhorus.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.InboundHumanMessage;
import io.casehub.qhorus.api.gateway.NormalisedMessage;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.gateway.DefaultInboundNormaliser;

class DefaultInboundNormaliserTest {

    DefaultInboundNormaliser normaliser = new DefaultInboundNormaliser();
    ChannelRef channel = new ChannelRef(UUID.randomUUID(), "test-ch");

    @Test
    void normalise_returns_QUERY_when_no_metadata_key() {
        var raw = new InboundHumanMessage("user-42", "Please analyse this", Instant.now(), Map.of(), null, null);
        assertEquals(MessageType.QUERY, normaliser.normalise(channel, raw).type());
    }

    @Test
    void normalise_preservesContent() {
        var raw = new InboundHumanMessage("user-42", "Hello agent!", Instant.now(), Map.of(), null, null);
        assertEquals("Hello agent!", normaliser.normalise(channel, raw).content());
    }

    @Test
    void normalise_senderIdPrefixedWithHuman() {
        var raw = new InboundHumanMessage("+447911123456", "stop", Instant.now(), Map.of(), null, null);
        assertEquals("human:+447911123456", normaliser.normalise(channel, raw).senderInstanceId());
    }

    @Test
    void normalise_emptyContent_stillReturnsQuery() {
        var raw = new InboundHumanMessage("user-1", "", Instant.now(), Map.of(), null, null);
        NormalisedMessage result = normaliser.normalise(channel, raw);
        assertEquals(MessageType.QUERY, result.type());
        assertEquals("human:user-1", result.senderInstanceId());
    }

    @Test
    void normalise_withCorrelationId_passesThrough() {
        var raw = new InboundHumanMessage("user-42", "approved", Instant.now(), Map.of(), "corr-99", null);
        assertEquals("corr-99", normaliser.normalise(channel, raw).correlationId());
    }

    @Test
    void normalise_nullCorrelationId_propagatesNull() {
        var raw = new InboundHumanMessage("user-42", "hello", Instant.now(), Map.of(), null, null);
        assertNull(normaliser.normalise(channel, raw).correlationId());
    }

    @Test
    void normalise_null_inReplyTo_propagates_null() {
        var raw = new InboundHumanMessage("user-42", "hello", Instant.now(), Map.of(), null, null);
        NormalisedMessage result = normaliser.normalise(channel, raw);
        assertNull(result.inReplyTo());
        assertNull(result.artefactRefs());
        assertNull(result.target());
    }

    @Test
    void normalise_passes_inReplyTo_from_InboundHumanMessage() {
        var raw = new InboundHumanMessage("user-42", "done", Instant.now(), Map.of(), "corr-1", 99L);
        assertThat(normaliser.normalise(channel, raw).inReplyTo()).isEqualTo(99L);
    }

    @Test
    void normalise_uses_message_type_from_metadata() {
        var raw = new InboundHumanMessage("user-42", "ok done",
                Instant.now(), Map.of("message-type", "RESPONSE"), "corr-1", null);
        assertThat(normaliser.normalise(channel, raw).type()).isEqualTo(MessageType.RESPONSE);
    }

    @Test
    void normalise_ignores_invalid_message_type_key_falls_back_to_QUERY() {
        var raw = new InboundHumanMessage("user-42", "ok",
                Instant.now(), Map.of("message-type", "NOT_A_TYPE"), null, null);
        assertThat(normaliser.normalise(channel, raw).type()).isEqualTo(MessageType.QUERY);
    }

    @Test
    void normalise_blank_message_type_metadata_falls_back_to_QUERY() {
        var raw = new InboundHumanMessage("user-42", "hello",
                Instant.now(), Map.of("message-type", "   "), null, null);
        assertThat(normaliser.normalise(channel, raw).type()).isEqualTo(MessageType.QUERY);
    }

    @Test
    void normalise_message_type_metadata_is_case_insensitive() {
        var raw = new InboundHumanMessage("user-42", "ok",
                Instant.now(), Map.of("message-type", "response"), "corr-1", null);
        assertThat(normaliser.normalise(channel, raw).type()).isEqualTo(MessageType.RESPONSE);
    }

    @Test
    void normalise_HANDOFF_metadata_falls_back_to_QUERY_no_target_available() {
        var raw = new InboundHumanMessage("user-42", "hand off",
                Instant.now(), Map.of("message-type", "HANDOFF"), null, null);
        assertThat(normaliser.normalise(channel, raw).type()).isEqualTo(MessageType.QUERY);
    }
}
