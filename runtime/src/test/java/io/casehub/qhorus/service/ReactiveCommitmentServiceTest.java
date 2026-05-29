package io.casehub.qhorus.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.message.ReactiveCommitmentService;
import io.casehub.qhorus.runtime.store.ReactiveChannelStore;
import io.casehub.qhorus.runtime.store.ReactiveCommitmentStore;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Integration tests for {@link ReactiveCommitmentService} state transitions.
 *
 * <p>Disabled: {@link ReactiveCommitmentService} calls {@code Panache.withTransaction()} which
 * requires a native reactive datasource driver. H2 has no reactive driver — enable when
 * Docker/PostgreSQL Dev Services is available. Refs #193.
 */
@Disabled("ReactiveCommitmentService calls Panache.withTransaction() — requires reactive datasource.")
@QuarkusTest
@TestProfile(ReactiveTestProfile.class)
class ReactiveCommitmentServiceTest {

    @Inject
    ReactiveCommitmentService svc;

    @Inject
    ReactiveCommitmentStore store;

    @Inject
    ReactiveChannelStore channelStore;

    private UUID persistChannel() {
        final Channel ch = new Channel();
        ch.name = "test-ch-" + UUID.randomUUID();
        ch.semantic = ChannelSemantic.APPEND;
        return Panache.withTransaction("qhorus", () -> channelStore.put(ch))
                .await().indefinitely().id;
    }

    private Commitment openCommitment(final String correlationId, final String obligor) {
        final UUID channelId = persistChannel();
        final Commitment c = new Commitment();
        c.correlationId = correlationId;
        c.channelId = channelId;
        c.messageType = MessageType.COMMAND;
        c.requester = "requester";
        c.obligor = obligor;
        c.state = CommitmentState.OPEN;
        return Panache.withTransaction("qhorus", () -> store.save(c)).await().indefinitely();
    }

    // --- Happy path ---

    @Test
    void acknowledge_transitions_OPEN_to_ACKNOWLEDGED_and_sets_acknowledgedAt() {
        final String correlationId = "corr-ack-" + UUID.randomUUID();
        openCommitment(correlationId, "agent-a");

        final Optional<Commitment> result = svc.acknowledge(correlationId).await().indefinitely();

        assertThat(result).isPresent();
        assertThat(result.get().state).isEqualTo(CommitmentState.ACKNOWLEDGED);
        assertThat(result.get().acknowledgedAt).isNotNull();
    }

    @Test
    void acknowledge_sets_acknowledgedAt_only_once() {
        final String correlationId = "corr-ack-once-" + UUID.randomUUID();
        openCommitment(correlationId, "agent-a");

        svc.acknowledge(correlationId).await().indefinitely();
        final Instant first = store.findByCorrelationId(correlationId).await().indefinitely()
                .orElseThrow().acknowledgedAt;

        svc.acknowledge(correlationId).await().indefinitely();
        final Instant second = store.findByCorrelationId(correlationId).await().indefinitely()
                .orElseThrow().acknowledgedAt;

        assertThat(second).isEqualTo(first);
    }

    @Test
    void fulfill_transitions_to_FULFILLED_and_sets_resolvedAt() {
        final String correlationId = "corr-fulfill-" + UUID.randomUUID();
        openCommitment(correlationId, "agent-a");

        svc.fulfill(correlationId).await().indefinitely();

        final Commitment updated = store.findByCorrelationId(correlationId).await().indefinitely()
                .orElseThrow();
        assertThat(updated.state).isEqualTo(CommitmentState.FULFILLED);
        assertThat(updated.resolvedAt).isNotNull();
    }

    @Test
    void decline_transitions_to_DECLINED_and_sets_resolvedAt() {
        final String correlationId = "corr-decline-" + UUID.randomUUID();
        openCommitment(correlationId, "agent-a");

        svc.decline(correlationId).await().indefinitely();

        final Commitment updated = store.findByCorrelationId(correlationId).await().indefinitely()
                .orElseThrow();
        assertThat(updated.state).isEqualTo(CommitmentState.DECLINED);
        assertThat(updated.resolvedAt).isNotNull();
    }

    @Test
    void fail_transitions_to_FAILED_and_sets_resolvedAt() {
        final String correlationId = "corr-fail-" + UUID.randomUUID();
        openCommitment(correlationId, "agent-a");

        svc.fail(correlationId).await().indefinitely();

        final Commitment updated = store.findByCorrelationId(correlationId).await().indefinitely()
                .orElseThrow();
        assertThat(updated.state).isEqualTo(CommitmentState.FAILED);
        assertThat(updated.resolvedAt).isNotNull();
    }

    @Test
    void delegate_transitions_parent_to_DELEGATED_and_creates_OPEN_child() {
        final String correlationId = "corr-delegate-" + UUID.randomUUID();
        final Commitment parent = openCommitment(correlationId, "agent-a");

        svc.delegate(correlationId, "agent-b").await().indefinitely();

        final Commitment updatedParent = store.findById(parent.id).await().indefinitely()
                .orElseThrow();
        assertThat(updatedParent.state).isEqualTo(CommitmentState.DELEGATED);
        assertThat(updatedParent.delegatedTo).isEqualTo("agent-b");
        assertThat(updatedParent.resolvedAt).isNotNull();

        // Child commitment takes over the correlationId
        final Commitment child = store.findByCorrelationId(correlationId).await().indefinitely()
                .orElseThrow();
        assertThat(child.id).isNotEqualTo(parent.id);
        assertThat(child.state).isEqualTo(CommitmentState.OPEN);
        assertThat(child.obligor).isEqualTo("agent-b");
        assertThat(child.parentCommitmentId).isEqualTo(parent.id);
    }

    @Test
    void expireOverdue_expires_overdue_commitments_only() {
        final String overdue1 = "corr-exp-1-" + UUID.randomUUID();
        final String overdue2 = "corr-exp-2-" + UUID.randomUUID();
        final String notDue = "corr-exp-no-" + UUID.randomUUID();

        final UUID channelId = persistChannel();

        Panache.withTransaction("qhorus", () -> {
            final Commitment c1 = new Commitment();
            c1.correlationId = overdue1; c1.channelId = channelId;
            c1.messageType = MessageType.COMMAND; c1.requester = "r"; c1.obligor = "o";
            c1.state = CommitmentState.OPEN;
            c1.expiresAt = Instant.now().minusSeconds(10);

            final Commitment c2 = new Commitment();
            c2.correlationId = overdue2; c2.channelId = channelId;
            c2.messageType = MessageType.COMMAND; c2.requester = "r"; c2.obligor = "o";
            c2.state = CommitmentState.ACKNOWLEDGED;
            c2.expiresAt = Instant.now().minusSeconds(5);

            final Commitment c3 = new Commitment();
            c3.correlationId = notDue; c3.channelId = channelId;
            c3.messageType = MessageType.COMMAND; c3.requester = "r"; c3.obligor = "o";
            c3.state = CommitmentState.OPEN;
            c3.expiresAt = Instant.now().plusSeconds(60);

            return store.save(c1).flatMap(x -> store.save(c2)).flatMap(x -> store.save(c3));
        }).await().indefinitely();

        final int count = svc.expireOverdue().await().indefinitely();

        assertThat(count).isEqualTo(2);
        assertThat(store.findByCorrelationId(overdue1).await().indefinitely()
                .orElseThrow().state).isEqualTo(CommitmentState.EXPIRED);
        assertThat(store.findByCorrelationId(overdue2).await().indefinitely()
                .orElseThrow().state).isEqualTo(CommitmentState.EXPIRED);
        assertThat(store.findByCorrelationId(notDue).await().indefinitely()
                .orElseThrow().state).isEqualTo(CommitmentState.OPEN);
    }

    // --- Terminal idempotency ---

    @Test
    void fulfill_after_decline_is_noOp() {
        final String correlationId = "corr-idem-1-" + UUID.randomUUID();
        openCommitment(correlationId, "agent-a");
        svc.decline(correlationId).await().indefinitely();

        final Optional<Commitment> result = svc.fulfill(correlationId).await().indefinitely();

        assertThat(result).isEmpty();
        assertThat(store.findByCorrelationId(correlationId).await().indefinitely()
                .orElseThrow().state).isEqualTo(CommitmentState.DECLINED);
    }

    @Test
    void acknowledge_after_fulfill_is_noOp() {
        final String correlationId = "corr-idem-2-" + UUID.randomUUID();
        openCommitment(correlationId, "agent-a");
        svc.fulfill(correlationId).await().indefinitely();

        final Optional<Commitment> result = svc.acknowledge(correlationId).await().indefinitely();

        assertThat(result).isEmpty();
        assertThat(store.findByCorrelationId(correlationId).await().indefinitely()
                .orElseThrow().state).isEqualTo(CommitmentState.FULFILLED);
    }

    @Test
    void delegate_is_noOp_when_commitment_already_terminal() {
        final String correlationId = "corr-delegate-terminal-" + UUID.randomUUID();
        openCommitment(correlationId, "agent-a");
        svc.fulfill(correlationId).await().indefinitely();

        final Optional<Commitment> result = svc.delegate(correlationId, "agent-b")
                .await().indefinitely();

        assertThat(result).isEmpty();
    }

    // --- Robustness ---

    @Test
    void all_transitions_with_null_correlationId_are_noOps() {
        assertThat(svc.acknowledge(null).await().indefinitely()).isEmpty();
        assertThat(svc.fulfill(null).await().indefinitely()).isEmpty();
        assertThat(svc.decline(null).await().indefinitely()).isEmpty();
        assertThat(svc.fail(null).await().indefinitely()).isEmpty();
        assertThat(svc.delegate(null, "agent").await().indefinitely()).isEmpty();
    }

    @Test
    void all_transitions_with_unknown_correlationId_are_noOps() {
        final String unknown = "no-such-corr-" + UUID.randomUUID();
        assertThat(svc.acknowledge(unknown).await().indefinitely()).isEmpty();
        assertThat(svc.fulfill(unknown).await().indefinitely()).isEmpty();
    }

    @Test
    void findByCorrelationId_returns_empty_when_absent() {
        assertThat(svc.findByCorrelationId("no-such-" + UUID.randomUUID()).await().indefinitely())
                .isEmpty();
    }

    @Test
    void findByCorrelationId_returns_commitment_when_present() {
        final String correlationId = "corr-find-" + UUID.randomUUID();
        openCommitment(correlationId, "agent-a");

        final Optional<Commitment> result = svc.findByCorrelationId(correlationId)
                .await().indefinitely();

        assertThat(result).isPresent();
        assertThat(result.get().correlationId).isEqualTo(correlationId);
    }
}
