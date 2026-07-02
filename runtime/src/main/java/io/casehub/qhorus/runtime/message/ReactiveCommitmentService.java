package io.casehub.qhorus.runtime.message;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.store.ReactiveCommitmentStore;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;

@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
public class ReactiveCommitmentService {

    @Inject
    ReactiveCommitmentStore store;

    public Uni<Optional<Commitment>> acknowledge(final String correlationId) {
        return transition(correlationId, CommitmentState.ACKNOWLEDGED, c ->
                c.toBuilder().state(CommitmentState.ACKNOWLEDGED)
                        .acknowledgedAt(c.acknowledgedAt() == null ? Instant.now() : c.acknowledgedAt())
                        .build());
    }

    public Uni<Optional<Commitment>> fulfill(final String correlationId) {
        return transition(correlationId, CommitmentState.FULFILLED, c ->
                c.toBuilder().state(CommitmentState.FULFILLED).resolvedAt(Instant.now()).build());
    }

    public Uni<Optional<Commitment>> decline(final String correlationId) {
        return transition(correlationId, CommitmentState.DECLINED, c ->
                c.toBuilder().state(CommitmentState.DECLINED).resolvedAt(Instant.now()).build());
    }

    public Uni<Optional<Commitment>> fail(final String correlationId) {
        return transition(correlationId, CommitmentState.FAILED, c ->
                c.toBuilder().state(CommitmentState.FAILED).resolvedAt(Instant.now()).build());
    }

    public Uni<Optional<Commitment>> delegate(final String correlationId,
                                              final String delegatedTo) {
        if (correlationId == null || correlationId.isBlank()) {
            return Uni.createFrom().item(Optional.empty());
        }
        return Panache.withTransaction("qhorus", () ->
            store.findByCorrelationId(correlationId).flatMap(opt -> {
                if (opt.isEmpty() || opt.get().state().isTerminal()) {
                    return Uni.createFrom().item(Optional.<Commitment>empty());
                }
                final Commitment c = opt.get();
                Commitment delegated = c.toBuilder()
                        .state(CommitmentState.DELEGATED)
                        .delegatedTo(delegatedTo)
                        .resolvedAt(Instant.now())
                        .build();
                return store.save(delegated).flatMap(saved -> {
                    Commitment child = Commitment.builder()
                            .correlationId(correlationId)
                            .channelId(c.channelId())
                            .messageType(c.messageType())
                            .requester(c.requester())
                            .obligor(delegatedTo)
                            .expiresAt(c.expiresAt())
                            .state(CommitmentState.OPEN)
                            .parentCommitmentId(c.id())
                            .build();
                    return store.save(child).map(ignored -> Optional.of(saved));
                });
            })
        );
    }

    public Uni<Integer> expireOverdue() {
        return Panache.withTransaction("qhorus", () ->
            store.findExpiredBefore(Instant.now()).flatMap(overdue -> {
                if (overdue.isEmpty()) {
                    return Uni.createFrom().item(0);
                }
                final List<Uni<Commitment>> saves = overdue.stream().map(c -> {
                    Commitment expired = c.toBuilder()
                            .state(CommitmentState.EXPIRED)
                            .resolvedAt(Instant.now())
                            .build();
                    return store.save(expired);
                }).toList();
                return Uni.join().all(saves).andFailFast().map(List::size);
            })
        );
    }

    public Uni<Optional<Commitment>> extendDeadline(final String correlationId,
                                                    final Instant newDeadline) {
        if (correlationId == null || correlationId.isBlank()) {
            return Uni.createFrom().item(Optional.empty());
        }
        return Panache.withTransaction("qhorus", () ->
            store.findByCorrelationId(correlationId).flatMap(opt -> {
                if (opt.isEmpty() || opt.get().state().isTerminal()) {
                    return Uni.createFrom().item(Optional.<Commitment>empty());
                }
                Commitment updated = opt.get().toBuilder().expiresAt(newDeadline).build();
                return store.save(updated).map(Optional::of);
            })
        );
    }

    public Uni<Optional<Commitment>> findByCorrelationId(final String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return Uni.createFrom().item(Optional.empty());
        }
        return store.findByCorrelationId(correlationId);
    }

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
                                                 final CommitmentState target,
                                                 final java.util.function.UnaryOperator<Commitment> update) {
        if (correlationId == null || correlationId.isBlank()) {
            return Uni.createFrom().item(Optional.empty());
        }
        return Panache.withTransaction("qhorus", () ->
            store.findByCorrelationId(correlationId).flatMap(opt -> {
                if (opt.isEmpty() || opt.get().state().isTerminal()) {
                    return Uni.createFrom().item(Optional.<Commitment>empty());
                }
                Commitment updated = update.apply(opt.get());
                return store.save(updated).map(Optional::of);
            })
        );
    }
}
