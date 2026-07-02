package io.casehub.qhorus.runtime.message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentDeclinedEvent;
import io.casehub.qhorus.api.message.CommitmentExpiredEvent;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.CommitmentStore;

@ApplicationScoped
public class CommitmentService {

    private static final Logger LOG = Logger.getLogger(CommitmentService.class);

    @Inject
    CommitmentStore store;

    @Inject
    Event<CommitmentDeclinedEvent> declinedEvents;

    @Inject
    Event<CommitmentExpiredEvent> expiredEvents;

    @Transactional
    public Commitment open(UUID commitmentId, String correlationId, UUID channelId,
                           MessageType type, String requester, String obligor, Instant expiresAt) {
        Commitment c = Commitment.builder()
                .id(commitmentId)
                .correlationId(correlationId)
                .channelId(channelId)
                .messageType(type)
                .requester(requester)
                .obligor(obligor)
                .expiresAt(expiresAt)
                .state(CommitmentState.OPEN)
                .build();
        return store.save(c);
    }

    @Transactional
    public Optional<Commitment> acknowledge(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) return Optional.empty();
        return store.findByCorrelationId(correlationId)
                .filter(c -> c.state().isActive())
                .map(c -> store.save(c.toBuilder()
                        .state(CommitmentState.ACKNOWLEDGED)
                        .acknowledgedAt(c.acknowledgedAt() == null ? Instant.now() : c.acknowledgedAt())
                        .build()));
    }

    @Transactional
    public Optional<Commitment> fulfill(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) return Optional.empty();
        return store.findByCorrelationId(correlationId)
                .filter(c -> c.state().isActive())
                .map(c -> store.save(c.toBuilder()
                        .state(CommitmentState.FULFILLED)
                        .resolvedAt(Instant.now())
                        .build()));
    }

    @Transactional
    public Optional<Commitment> decline(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) return Optional.empty();
        return store.findByCorrelationId(correlationId)
                .filter(c -> c.state().isActive())
                .map(c -> {
                    Commitment saved = store.save(c.toBuilder()
                            .state(CommitmentState.DECLINED)
                            .resolvedAt(Instant.now())
                            .build());
                    declinedEvents.fire(new CommitmentDeclinedEvent(
                            saved.id(), saved.correlationId(), saved.channelId(),
                            saved.obligor(), saved.requester()));
                    return saved;
                });
    }

    @Transactional
    public Optional<Commitment> fail(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) return Optional.empty();
        return store.findByCorrelationId(correlationId)
                .filter(c -> c.state().isActive())
                .map(c -> store.save(c.toBuilder()
                        .state(CommitmentState.FAILED)
                        .resolvedAt(Instant.now())
                        .build()));
    }

    @Transactional
    public Optional<Commitment> delegate(String correlationId, String delegatedTo) {
        if (correlationId == null || correlationId.isBlank()) return Optional.empty();
        return store.findByCorrelationId(correlationId)
                .filter(c -> c.state().isActive())
                .map(c -> {
                    Commitment delegated = store.save(c.toBuilder()
                            .state(CommitmentState.DELEGATED)
                            .delegatedTo(delegatedTo)
                            .resolvedAt(Instant.now())
                            .build());
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
                    store.save(child);
                    return delegated;
                });
    }

    @Transactional
    public int expireOverdue() {
        List<Commitment> overdue = store.findExpiredBefore(Instant.now());
        List<CommitmentExpiredEvent> toFire = new ArrayList<>(overdue.size());
        overdue.forEach(c -> {
            store.save(c.toBuilder()
                    .state(CommitmentState.EXPIRED)
                    .resolvedAt(Instant.now())
                    .build());
            toFire.add(new CommitmentExpiredEvent(
                    c.id(), c.correlationId(), c.channelId(), c.obligor(), c.requester(), c.expiresAt()));
        });
        toFire.forEach(event -> {
            try {
                expiredEvents.fire(event);
            } catch (Exception e) {
                LOG.warnf(e, "CommitmentExpiredEvent observer failed for commitment %s — continuing", event.commitmentId());
            }
        });
        return overdue.size();
    }

    @Transactional
    public Optional<Commitment> extendDeadline(String correlationId, Instant newDeadline) {
        if (correlationId == null || correlationId.isBlank()) return Optional.empty();
        return store.findByCorrelationId(correlationId)
                .filter(c -> c.state().isActive())
                .map(c -> store.save(c.toBuilder().expiresAt(newDeadline).build()));
    }

    public Optional<Commitment> findByCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) return Optional.empty();
        return store.findByCorrelationId(correlationId);
    }
}
