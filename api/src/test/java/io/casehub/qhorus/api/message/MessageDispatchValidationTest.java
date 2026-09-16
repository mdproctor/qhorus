package io.casehub.qhorus.api.message;

import io.casehub.platform.api.identity.ActorType;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MessageDispatchValidationTest {

    @Test
    void retractionRequiresCorrectsMessageId() {
        assertThrows(IllegalArgumentException.class, () ->
            MessageDispatch.builder()
                .channelId(UUID.randomUUID())
                .sender("alice")
                .type(MessageType.RESPONSE)
                .content("corrected")
                .actorType(ActorType.HUMAN)
                .retraction(true)
                .build());
    }

    @Test
    void correctionBuildsSuccessfully() {
        MessageDispatch d = MessageDispatch.builder()
                .channelId(UUID.randomUUID())
                .sender("alice")
                .type(MessageType.RESPONSE)
                .content("corrected text")
                .actorType(ActorType.HUMAN)
                .correctsMessageId(42L)
                .build();
        assertEquals(42L, d.correctsMessageId());
        assertFalse(d.retraction());
    }

    @Test
    void retractionBuildsSuccessfully() {
        MessageDispatch d = MessageDispatch.builder()
                .channelId(UUID.randomUUID())
                .sender("alice")
                .type(MessageType.RESPONSE)
                .content("retraction reason")
                .actorType(ActorType.HUMAN)
                .correctsMessageId(42L)
                .retraction(true)
                .build();
        assertEquals(42L, d.correctsMessageId());
        assertTrue(d.retraction());
    }
}
