package io.casehub.qhorus.runtime.message.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.ProtocolContext;

class RequestResponseProtocolTest {

    private final RequestResponseProtocol protocol = new RequestResponseProtocol();

    {
        protocol.maxOpenQueries = 3;
    }

    private ProtocolContext ctx(MessageType incoming, String sender, List<Commitment> commitments) {
        return new ProtocolContext(UUID.randomUUID(), "test-channel", incoming, sender,
                UUID.randomUUID().toString(), List.of(), List.of(), commitments);
    }

    private Commitment openQuery(String correlationId, String requester) {
        return Commitment.builder()
                .id(UUID.randomUUID()).correlationId(correlationId)
                .channelId(UUID.randomUUID()).messageType(MessageType.QUERY)
                .requester(requester).state(CommitmentState.OPEN)
                .createdAt(Instant.now()).build();
    }

    private Commitment openCommand(String correlationId, String requester) {
        return Commitment.builder()
                .id(UUID.randomUUID()).correlationId(correlationId)
                .channelId(UUID.randomUUID()).messageType(MessageType.COMMAND)
                .requester(requester).state(CommitmentState.OPEN)
                .createdAt(Instant.now()).build();
    }

    @Test
    void noActiveCommitments_noAdvisory() {
        assertThat(protocol.evaluate(ctx(MessageType.QUERY, "a", List.of()))).isEmpty();
    }

    @Test
    void openQueriesUnderThreshold_noAdvisory() {
        List<Commitment> commitments = List.of(openQuery("q1", "a"), openQuery("q2", "a"));
        assertThat(protocol.evaluate(ctx(MessageType.QUERY, "a", commitments))).isEmpty();
    }

    @Test
    void openQueriesAtThreshold_advisoryOnNewQuery() {
        List<Commitment> commitments = List.of(
                openQuery("q1", "a"), openQuery("q2", "a"), openQuery("q3", "a"));
        List<String> advisories = protocol.evaluate(ctx(MessageType.QUERY, "a", commitments));
        assertThat(advisories).hasSize(1);
        assertThat(advisories.get(0)).startsWith("[REQUEST_RESPONSE]");
        assertThat(advisories.get(0)).contains("3 unanswered QUERYs");
    }

    @Test
    void nonResponseWithOpenQueries_advisory() {
        List<Commitment> commitments = List.of(openQuery("q1", "a"));
        List<String> advisories = protocol.evaluate(ctx(MessageType.STATUS, "b", commitments));
        assertThat(advisories).hasSize(1);
        assertThat(advisories.get(0)).startsWith("[REQUEST_RESPONSE]");
        assertThat(advisories.get(0)).contains("open QUERYs awaiting RESPONSE");
    }

    @Test
    void responseWithOpenQueries_noAdvisory() {
        List<Commitment> commitments = List.of(openQuery("q1", "a"));
        assertThat(protocol.evaluate(ctx(MessageType.RESPONSE, "b", commitments))).isEmpty();
    }

    @Test
    void commandCommitmentsIgnored() {
        List<Commitment> commitments = List.of(
                openCommand("c1", "a"), openCommand("c2", "a"), openCommand("c3", "a"));
        assertThat(protocol.evaluate(ctx(MessageType.QUERY, "a", commitments))).isEmpty();
    }
}
