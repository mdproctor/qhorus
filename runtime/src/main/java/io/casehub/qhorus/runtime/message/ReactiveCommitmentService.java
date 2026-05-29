package io.casehub.qhorus.runtime.message;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.runtime.store.ReactiveCommitmentStore;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;

/**
 * Reactive mirror of {@link CommitmentService} for state-transition operations.
 *
 * <p>The {@code open()} operation for COMMAND/QUERY is handled inline in
 * {@link ReactiveMessageService#dispatch} within the same {@code withTransaction} as the
 * message insert — ensuring atomicity without a second transaction boundary. This service
 * handles state transitions only (acknowledge / fulfill / decline / fail / delegate /
 * expireOverdue).
 *
 * <p>Each public method opens its own {@code Panache.withTransaction("qhorus", ...)}
 * — equivalent semantics to {@code REQUIRES_NEW} in the blocking service.
 *
 * <p>The {@link #delegate} method uses a two-save flatMap chain within a single transaction:
 * parent is saved as DELEGATED first, then the child OPEN commitment is saved.
 */
@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
public class ReactiveCommitmentService {

    @Inject
    ReactiveCommitmentStore store;

    /**
     * Transitions OPEN or ACKNOWLEDGED → ACKNOWLEDGED.
     * Sets {@code acknowledgedAt} only on the first STATUS.
     */
    public Uni<Optional<Commitment>> acknowledge(final String correlationId) {
        return transition(correlationId, CommitmentState.ACKNOWLEDGED, c -> {
            if (c.acknowledgedAt == null) {
                c.acknowledgedAt = Instant.now();
            }
        });
    }

    /** Called when RESPONSE (for QUERY) or DONE (for COMMAND) is received. */
    public Uni<Optional<Commitment>> fulfill(final String correlationId) {
        return transition(correlationId, CommitmentState.FULFILLED,
                c -> c.resolvedAt = Instant.now());
    }

    /** Called when DECLINE is received. */
    public Uni<Optional<Commitment>> decline(final String correlationId) {
        return transition(correlationId, CommitmentState.DECLINED,
                c -> c.resolvedAt = Instant.now());
    }

    /** Called when FAILURE is received. */
    public Uni<Optional<Commitment>> fail(final String correlationId) {
        return transition(correlationId, CommitmentState.FAILED,
                c -> c.resolvedAt = Instant.now());
    }

    /**
     * Called when HANDOFF is received.
     *
     * <p>Transitions the non-terminal commitment to DELEGATED and creates a child OPEN
     * commitment for {@code delegatedTo} — two saves in sequence within one transaction.
     * The child carries the same {@code correlationId} so {@code wait_for_reply} polling
     * continues transparently.
     */
    public Uni<Optional<Commitment>> delegate(final String correlationId,
            final String delegatedTo) {
        if (correlationId == null || correlationId.isBlank()) {
            return Uni.createFrom().item(Optional.empty());
        }
        return Panache.withTransaction("qhorus", () ->
            store.findByCorrelationId(correlationId).flatMap(opt -> {
                if (opt.isEmpty() || opt.get().state.isTerminal()) {
                    return Uni.createFrom().item(Optional.empty());
                }
                final Commitment c = opt.get();
                final UUID parentId = c.id;
                c.state = CommitmentState.DELEGATED;
                c.delegatedTo = delegatedTo;
                c.resolvedAt = Instant.now();
                return store.save(c).flatMap(saved -> {
                    final Commitment child = new Commitment();
                    child.correlationId = correlationId;
                    child.channelId = c.channelId;
                    child.messageType = c.messageType;
                    child.requester = c.requester;
                    child.obligor = delegatedTo;
                    child.expiresAt = c.expiresAt;
                    child.state = CommitmentState.OPEN;
                    child.parentCommitmentId = parentId;
                    return store.save(child).map(ignored -> Optional.of(saved));
                });
            })
        );
    }

    /**
     * Called by the expiry scheduler. Transitions all overdue OPEN/ACKNOWLEDGED
     * commitments to EXPIRED. Returns the number expired.
     */
    public Uni<Integer> expireOverdue() {
        return Panache.withTransaction("qhorus", () ->
            store.findExpiredBefore(Instant.now()).flatMap(overdue -> {
                if (overdue.isEmpty()) {
                    return Uni.createFrom().item(0);
                }
                final List<Uni<Commitment>> saves = overdue.stream().map(c -> {
                    c.state = CommitmentState.EXPIRED;
                    c.resolvedAt = Instant.now();
                    return store.save(c);
                }).toList();
                return Uni.join().all(saves).andFailFast().map(List::size);
            })
        );
    }

    /**
     * Returns the Commitment for the given correlation ID, if any.
     * Used by ReactiveA2AResource to derive task state from commitment lifecycle.
     */
    public Uni<Optional<Commitment>> findByCorrelationId(final String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return Uni.createFrom().item(Optional.empty());
        }
        return store.findByCorrelationId(correlationId);
    }

    /**
     * Dispatches the appropriate state transition for the given dispatch's message type.
     * Called from ReactiveMessageService after the message insert commits.
     *
     * <p>COMMAND/QUERY are skipped — the {@code open()} commitment is created inline in
     * {@link ReactiveMessageService#dispatch} Phase 2. EVENT is skipped — no commitment
     * is created or modified for events.
     */
    @SuppressWarnings("unused")
    Uni<Void> updateState(final MessageDispatch dispatch, final UUID commitmentId) {
        final String correlationId = dispatch.correlationId();
        if (correlationId == null) {
            return Uni.createFrom().voidItem();
        }
        return switch (dispatch.type()) {
            case STATUS -> acknowledge(correlationId).replaceWithVoid();
            case RESPONSE, DONE -> fulfill(correlationId).replaceWithVoid();
            case DECLINE -> decline(correlationId).replaceWithVoid();
            case FAILURE -> fail(correlationId).replaceWithVoid();
            case HANDOFF -> delegate(correlationId, dispatch.target()).replaceWithVoid();
            default -> Uni.createFrom().voidItem();
        };
    }

    private Uni<Optional<Commitment>> transition(final String correlationId,
            final CommitmentState target, final Consumer<Commitment> update) {
        if (correlationId == null || correlationId.isBlank()) {
            return Uni.createFrom().item(Optional.empty());
        }
        return Panache.withTransaction("qhorus", () ->
            store.findByCorrelationId(correlationId).flatMap(opt -> {
                if (opt.isEmpty() || opt.get().state.isTerminal()) {
                    return Uni.createFrom().item(Optional.empty());
                }
                final Commitment c = opt.get();
                update.accept(c);
                c.state = target;
                return store.save(c).map(Optional::of);
            })
        );
    }
}
