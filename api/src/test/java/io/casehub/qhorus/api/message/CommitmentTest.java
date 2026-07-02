package io.casehub.qhorus.api.message;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommitmentTest {

    @Test
    void builder_createsRecordWithAllFields() {
        UUID id = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(600);
        Instant ackAt = now.plusSeconds(5);
        Instant resolvedAt = now.plusSeconds(120);

        Commitment c = Commitment.builder()
                .id(id)
                .correlationId("corr-456")
                .channelId(channelId)
                .messageType(MessageType.COMMAND)
                .requester("agent-alpha")
                .obligor("agent-beta")
                .state(CommitmentState.FULFILLED)
                .expiresAt(expiresAt)
                .acknowledgedAt(ackAt)
                .resolvedAt(resolvedAt)
                .delegatedTo("agent-gamma")
                .parentCommitmentId(parentId)
                .tenancyId("tenant-2")
                .createdAt(now)
                .build();

        assertThat(c.id()).isEqualTo(id);
        assertThat(c.correlationId()).isEqualTo("corr-456");
        assertThat(c.channelId()).isEqualTo(channelId);
        assertThat(c.messageType()).isEqualTo(MessageType.COMMAND);
        assertThat(c.requester()).isEqualTo("agent-alpha");
        assertThat(c.obligor()).isEqualTo("agent-beta");
        assertThat(c.state()).isEqualTo(CommitmentState.FULFILLED);
        assertThat(c.expiresAt()).isEqualTo(expiresAt);
        assertThat(c.acknowledgedAt()).isEqualTo(ackAt);
        assertThat(c.resolvedAt()).isEqualTo(resolvedAt);
        assertThat(c.delegatedTo()).isEqualTo("agent-gamma");
        assertThat(c.parentCommitmentId()).isEqualTo(parentId);
        assertThat(c.tenancyId()).isEqualTo("tenant-2");
        assertThat(c.createdAt()).isEqualTo(now);
    }

    @Test
    void toBuilder_roundTripsAllFields() {
        UUID id = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(600);
        Instant ackAt = now.plusSeconds(5);
        Instant resolvedAt = now.plusSeconds(120);

        Commitment original = Commitment.builder()
                .id(id)
                .correlationId("corr-456")
                .channelId(channelId)
                .messageType(MessageType.COMMAND)
                .requester("agent-alpha")
                .obligor("agent-beta")
                .state(CommitmentState.FULFILLED)
                .expiresAt(expiresAt)
                .acknowledgedAt(ackAt)
                .resolvedAt(resolvedAt)
                .delegatedTo("agent-gamma")
                .parentCommitmentId(parentId)
                .tenancyId("tenant-2")
                .createdAt(now)
                .build();

        Commitment copy = original.toBuilder().build();

        assertThat(copy.id()).isEqualTo(original.id());
        assertThat(copy.correlationId()).isEqualTo(original.correlationId());
        assertThat(copy.channelId()).isEqualTo(original.channelId());
        assertThat(copy.messageType()).isEqualTo(original.messageType());
        assertThat(copy.requester()).isEqualTo(original.requester());
        assertThat(copy.obligor()).isEqualTo(original.obligor());
        assertThat(copy.state()).isEqualTo(original.state());
        assertThat(copy.expiresAt()).isEqualTo(original.expiresAt());
        assertThat(copy.acknowledgedAt()).isEqualTo(original.acknowledgedAt());
        assertThat(copy.resolvedAt()).isEqualTo(original.resolvedAt());
        assertThat(copy.delegatedTo()).isEqualTo(original.delegatedTo());
        assertThat(copy.parentCommitmentId()).isEqualTo(original.parentCommitmentId());
        assertThat(copy.tenancyId()).isEqualTo(original.tenancyId());
        assertThat(copy.createdAt()).isEqualTo(original.createdAt());
    }
}
