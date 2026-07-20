package io.casehub.qhorus.runtime.message.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.casehub.platform.api.identity.ActorType;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ProtocolContext;

class RoundRobinProtocolTest {

    private final RoundRobinProtocol protocol = new RoundRobinProtocol();

    private static final UUID CH = UUID.randomUUID();

    private ProtocolContext ctx(String sender, List<String> participants, List<MessageView> messages) {
        return new ProtocolContext(CH, "test-channel", MessageType.STATUS, sender,
                null, participants, messages, List.of());
    }

    private MessageView mv(String sender) {
        return new MessageView(System.nanoTime(), CH, sender, MessageType.STATUS,
                "content", null, null, null, null, List.of(), ActorType.AGENT, Instant.now(), null, 0);
    }

    private MessageView event(String sender) {
        return new MessageView(System.nanoTime(), CH, sender, MessageType.EVENT,
                null, null, null, null, null, List.of(), ActorType.AGENT, Instant.now(), null, 0);
    }

    @Test
    void noParticipants_noAdvisory() {
        assertThat(protocol.evaluate(ctx("a", List.of(), List.of()))).isEmpty();
    }

    @Test
    void singleParticipant_noAdvisory() {
        assertThat(protocol.evaluate(ctx("a", List.of("a"), List.of(mv("a"))))).isEmpty();
    }

    @Test
    void correctTurn_noAdvisory() {
        List<String> participants = List.of("a", "b", "c");
        List<MessageView> messages = List.of(mv("a"));
        assertThat(protocol.evaluate(ctx("b", participants, messages))).isEmpty();
    }

    @Test
    void wrongTurn_advisory() {
        List<String> participants = List.of("a", "b", "c");
        List<MessageView> messages = List.of(mv("a"));
        List<String> advisories = protocol.evaluate(ctx("c", participants, messages));
        assertThat(advisories).hasSize(1);
        assertThat(advisories.get(0)).startsWith("[ROUND_ROBIN]");
        assertThat(advisories.get(0)).contains("expected 'b'");
        assertThat(advisories.get(0)).contains("got 'c'");
    }

    @Test
    void noMessageHistory_noAdvisory() {
        List<String> participants = List.of("a", "b");
        assertThat(protocol.evaluate(ctx("a", participants, List.of()))).isEmpty();
    }

    @Test
    void nonParticipantSender_noAdvisory_doesNotAdvanceTurn() {
        List<String> participants = List.of("a", "b");
        List<MessageView> messages = List.of(mv("a"));
        assertThat(protocol.evaluate(ctx("system:watchdog", participants, messages))).isEmpty();
    }

    @Test
    void wrapAround_afterLastParticipant_firstSpeaksAgain() {
        List<String> participants = List.of("a", "b", "c");
        List<MessageView> messages = List.of(mv("a"), mv("b"), mv("c"));
        assertThat(protocol.evaluate(ctx("a", participants, messages))).isEmpty();
    }

    @Test
    void eventMessages_ignoredForTurnDetermination() {
        List<String> participants = List.of("a", "b");
        List<MessageView> messages = List.of(mv("a"), event("b"));
        List<String> advisories = protocol.evaluate(ctx("a", participants, messages));
        assertThat(advisories).hasSize(1);
        assertThat(advisories.get(0)).contains("expected 'b'");
    }
}
