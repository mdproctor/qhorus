package io.casehub.qhorus.persistence.memory;

import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.store.CommitmentStore;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryCommitmentStore implements CommitmentStore {

    private final Map<UUID, Commitment> byId = new ConcurrentHashMap<>();

    @Override
    public Commitment save(Commitment c) {
        Commitment.Builder b = c.toBuilder();
        if (c.id() == null) {
            b.id(UUID.randomUUID());
        }
        if (c.createdAt() == null) {
            b.createdAt(Instant.now());
        }
        c = b.build();
        byId.put(c.id(), c);
        return c;
    }

    @Override
    public Optional<Commitment> findById(UUID commitmentId) {
        return Optional.ofNullable(byId.get(commitmentId));
    }

    @Override
    public Optional<Commitment> findByCorrelationId(String correlationId) {
        return byId.values().stream()
                   .filter(c -> correlationId.equals(c.correlationId()))
                   .filter(c -> c.state().isActive())
                   .findFirst()
                   .or(() -> byId.values().stream()
                                 .filter(c -> correlationId.equals(c.correlationId()))
                                 .findFirst());
    }

    @Override
    public List<Commitment> findAllByCorrelationId(String correlationId) {
        return byId.values().stream()
                   .filter(c -> correlationId.equals(c.correlationId()))
                   .sorted(java.util.Comparator.comparing(c -> c.createdAt() != null ? c.createdAt() : java.time.Instant.MIN))
                   .toList();
    }


    @Override
    public List<Commitment> findByIds(Collection<UUID> ids) {
        return ids.stream()
                  .map(byId::get)
                  .filter(Objects::nonNull)
                  .toList();
    }

    @Override
    public List<Commitment> findOpenByObligor(String obligor, UUID channelId) {
        return byId.values().stream()
                   .filter(c -> c.state().isActive())
                   .filter(c -> channelId.equals(c.channelId()))
                   .filter(c -> obligor != null && obligor.equals(c.obligor()))
                   .toList();
    }

    @Override
    public List<Commitment> findOpenByObligor(String obligor) {
        if (obligor == null) {return List.of();}
        return byId.values().stream()
                   .filter(c -> c.state().isActive())
                   .filter(c -> obligor.equals(c.obligor()))
                   .toList();
    }

    @Override
    public List<Commitment> findOpenByRequester(String requester, UUID channelId) {
        return byId.values().stream()
                   .filter(c -> c.state().isActive())
                   .filter(c -> channelId.equals(c.channelId()))
                   .filter(c -> requester != null && requester.equals(c.requester()))
                   .toList();
    }

    @Override
    public List<Commitment> findByState(CommitmentState state, UUID channelId) {
        return byId.values().stream()
                   .filter(c -> state == c.state())
                   .filter(c -> channelId.equals(c.channelId()))
                   .toList();
    }

    @Override
    public List<Commitment> findExpiredBefore(Instant cutoff) {
        return byId.values().stream()
                   .filter(c -> c.state().isActive())
                   .filter(c -> c.expiresAt() != null && c.expiresAt().isBefore(cutoff))
                   .toList();
    }

    @Override
    public List<Commitment> findAllOpen() {
        return byId.values().stream()
                   .filter(c -> c.state() == CommitmentState.OPEN || c.state() == CommitmentState.ACKNOWLEDGED)
                   .sorted(java.util.Comparator.comparing(c -> c.expiresAt() != null ? c.expiresAt() : java.time.Instant.MAX))
                   .toList();
    }

    @Override
    public void deleteById(UUID commitmentId) {
        byId.remove(commitmentId);
    }

    @Override
    public long deleteAll(UUID channelId) {
        List<UUID> toRemove = byId.values().stream()
                                  .filter(c -> channelId.equals(c.channelId()))
                                  .map(Commitment::id)
                                  .toList();
        toRemove.forEach(byId::remove);
        return toRemove.size();
    }

    @Override
    public long deleteExpiredBefore(Instant cutoff) {
        List<Commitment> expired = findExpiredBefore(cutoff);
        expired.forEach(c -> deleteById(c.id()));
        return expired.size();
    }

    /**
     * Call in @BeforeEach for test isolation.
     */
    public void clear() {
        byId.clear();
    }
}
