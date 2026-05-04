package io.casehub.qhorus.gateway;

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
    void normalise_alwaysReturnsQuery() {
        var raw = new InboundHumanMessage("user-42", "Please analyse this", Instant.now(), Map.of());
        assertEquals(MessageType.QUERY, normaliser.normalise(channel, raw).type());
    }

    @Test
    void normalise_preservesContent() {
        var raw = new InboundHumanMessage("user-42", "Hello agent!", Instant.now(), Map.of());
        assertEquals("Hello agent!", normaliser.normalise(channel, raw).content());
    }

    @Test
    void normalise_senderIdPrefixedWithHuman() {
        var raw = new InboundHumanMessage("+447911123456", "stop", Instant.now(), Map.of());
        assertEquals("human:+447911123456", normaliser.normalise(channel, raw).senderInstanceId());
    }

    @Test
    void normalise_emptyContent_stillReturnsQuery() {
        var raw = new InboundHumanMessage("user-1", "", Instant.now(), Map.of());
        NormalisedMessage result = normaliser.normalise(channel, raw);
        assertEquals(MessageType.QUERY, result.type());
        assertEquals("human:user-1", result.senderInstanceId());
    }
}
