package io.casehub.qhorus.persistence.memory;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.casehub.qhorus.api.message.Reaction;
import io.casehub.qhorus.api.store.ReactionStore;

@ApplicationScoped
@Alternative
@Priority(1)
public class InMemoryReactionStore implements ReactionStore {

    private final Map<Long, Reaction> store = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(1);

    @Override
    public Reaction react(Long messageId, String emoji, String actorId, String tenancyId) {
        for (Reaction r : store.values()) {
            if (r.messageId().equals(messageId) && r.emoji().equals(emoji) && r.actorId().equals(actorId)) {
                return r;
            }
        }
        Reaction r = new Reaction(idCounter.getAndIncrement(), messageId, emoji, actorId, Instant.now(), tenancyId);
        store.put(r.id(), r);
        return r;
    }

    @Override
    public boolean unreact(Long messageId, String emoji, String actorId) {
        return store.values().removeIf(r ->
                r.messageId().equals(messageId) && r.emoji().equals(emoji) && r.actorId().equals(actorId));
    }

    @Override
    public List<Reaction> findByMessage(Long messageId) {
        return store.values().stream()
                .filter(r -> r.messageId().equals(messageId))
                .toList();
    }

    @Override
    public Map<Long, List<Reaction>> findByMessages(Collection<Long> messageIds) {
        Map<Long, List<Reaction>> result = new HashMap<>();
        for (Long id : messageIds) {
            result.put(id, findByMessage(id));
        }
        return result;
    }

    @Override
    public void deleteByMessage(Long messageId) {
        store.values().removeIf(r -> r.messageId().equals(messageId));
    }

    @Override
    public void deleteByChannel(UUID channelId) {
        // InMemory: no channel→message relationship — noop in unit tests
    }

    public void clear() {
        store.clear();
    }
}
