package io.casehub.qhorus.runtime.message.protocol;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ProtocolContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContributionRequiredProtocolTest {

    private final ContributionRequiredProtocol protocol = new ContributionRequiredProtocol();

    {
        protocol.maxConsecutive = 2;
    }

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
    void noRecentMessages_noAdvisory() {
        assertThat(protocol.evaluate(ctx("a", List.of("a", "b"), List.of()))).isEmpty();
    }

    @Test
    void senderBelowConsecutiveThreshold_noAdvisory() {
        // Only 1 consecutive from "a" in history, incoming makes 2 but last non-a was "b"
        // With threshold=2, b interleaved means consecutive=0 for "a" after the break
        List<MessageView> messages = List.of(mv("a"), mv("b"));
        assertThat(protocol.evaluate(ctx("a", List.of("a", "b"), messages))).isEmpty();
    }

    @Test
    void senderAtConsecutiveThreshold_advisoryWithMissingContributors() {
        List<MessageView> messages = List.of(mv("b"), mv("a"), mv("a"));
        List<String> advisories = protocol.evaluate(ctx("a", List.of("a", "b"), messages));
        assertThat(advisories).hasSize(1);
        assertThat(advisories.get(0)).startsWith("[CONTRIBUTION_REQUIRED]");
        assertThat(advisories.get(0)).contains("3 consecutive messages");
        assertThat(advisories.get(0)).contains("b");
    }

    @Test
    void allParticipantsContributing_noAdvisory() {
        // b spoke between a's messages — consecutive resets
        List<MessageView> messages = List.of(mv("a"), mv("b"));
        assertThat(protocol.evaluate(ctx("a", List.of("a", "b"), messages))).isEmpty();
    }

    @Test
    void eventMessagesExcludedFromCount() {
        // EVENT from a doesn't count as consecutive — only 1 non-EVENT from a, incoming makes 2 but b spoke before
        List<MessageView> messages = List.of(mv("a"), mv("b"), event("a"));
        assertThat(protocol.evaluate(ctx("a", List.of("a", "b"), messages))).isEmpty();
    }

    @Test
    void fallbackParticipants_derivedFromDistinctSenders() {
        List<MessageView> messages = List.of(mv("a"), mv("b"), mv("a"), mv("a"));
        List<String> advisories = protocol.evaluate(ctx("a", List.of(), messages));
        assertThat(advisories).hasSize(1);
        assertThat(advisories.get(0)).contains("b");
    }

    @Test
    void singleParticipant_noAdvisory() {
        List<MessageView> messages = List.of(mv("a"), mv("a"), mv("a"));
        assertThat(protocol.evaluate(ctx("a", List.of("a"), messages))).isEmpty();
    }
}
