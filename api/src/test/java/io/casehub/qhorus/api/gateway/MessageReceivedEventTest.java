package io.casehub.qhorus.api.gateway;

import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MessageReceivedEventTest {

    @Test
    void fromMessage_mapsAllFields() {
        UUID    channelId = UUID.randomUUID();
        Instant now       = Instant.now();
        Message msg = Message.builder()
                             .id(42L).channelId(channelId).sender("agent-1")
                             .messageType(MessageType.COMMAND).tenancyId("t1")
                             .content("do it").correlationId("corr-1")
                             .target("role:worker").topic("general")
                             .createdAt(now).build();

        MessageReceivedEvent event = MessageReceivedEvent.fromMessage(msg, "ops-channel");

        assertEquals(42L, event.messageId());
        assertEquals("ops-channel", event.channelName());
        assertEquals(channelId, event.channelId());
        assertEquals("t1", event.tenancyId());
        assertEquals(MessageType.COMMAND, event.messageType());
        assertEquals("agent-1", event.senderId());
        assertEquals("corr-1", event.correlationId());
        assertEquals(now, event.occurredAt());
        assertEquals("do it", event.content());
        assertEquals("general", event.topic());
    }

    @Test
    void fromMessage_eventType_contentIsNull() {
        Message msg = Message.builder()
                             .id(10L).channelId(UUID.randomUUID()).sender("agent-1")
                             .messageType(MessageType.EVENT).content("{\"tool\":\"search\"}")
                             .build();

        MessageReceivedEvent event = MessageReceivedEvent.fromMessage(msg, "ch");

        assertNull(event.content());
    }

    @Test
    void fromMessage_nullCreatedAt_defaultsToNow() {
        Message msg = Message.builder()
                             .id(10L).channelId(UUID.randomUUID()).sender("agent-1")
                             .messageType(MessageType.STATUS).content("working")
                             .build();

        MessageReceivedEvent event = MessageReceivedEvent.fromMessage(msg, "ch");

        assertNotNull(event.occurredAt());
    }
}
