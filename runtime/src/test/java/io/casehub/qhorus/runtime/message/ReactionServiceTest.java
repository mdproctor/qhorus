package io.casehub.qhorus.runtime.message;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.Reaction;
import io.casehub.qhorus.api.message.ReactionGroup;
import io.casehub.qhorus.api.store.ReactionStore;

import static org.assertj.core.api.Assertions.*;

class ReactionServiceTest {

    private ReactionService service;
    private StubReactionStore reactionStore;

    @BeforeEach
    void setUp() {
        reactionStore = new StubReactionStore();
        service = new ReactionService();
        service.reactionStore = reactionStore;
        service.reactionEvent = null;
    }

    @Test
    void react_creates_reaction() {
        Reaction r = service.react(1L, "👍", "agent-1", "tenant-1");
        assertThat(r.emoji()).isEqualTo("👍");
    }

    @Test
    void react_trims_emoji() {
        Reaction r = service.react(1L, "  👍  ", "agent-1", "tenant-1");
        assertThat(r.emoji()).isEqualTo("👍");
    }

    @Test
    void react_rejects_blank_emoji() {
        assertThatThrownBy(() -> service.react(1L, "", "agent-1", "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void react_rejects_null_emoji() {
        assertThatThrownBy(() -> service.react(1L, null, "agent-1", "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unreact_returns_true_when_removed() {
        service.react(1L, "👍", "agent-1", "tenant-1");
        assertThat(service.unreact(1L, "👍", "agent-1")).isTrue();
    }

    @Test
    void unreact_returns_false_when_not_present() {
        assertThat(service.unreact(1L, "👍", "agent-1")).isFalse();
    }

    @Test
    void getReactions_groups_by_emoji() {
        service.react(1L, "👍", "agent-1", "tenant-1");
        service.react(1L, "👍", "agent-2", "tenant-1");
        service.react(1L, "❤️", "agent-1", "tenant-1");

        List<ReactionGroup> groups = service.getReactions(1L);
        assertThat(groups).hasSize(2);

        ReactionGroup thumbsUp = groups.stream()
                .filter(g -> g.emoji().equals("👍")).findFirst().orElseThrow();
        assertThat(thumbsUp.count()).isEqualTo(2);
        assertThat(thumbsUp.actorIds()).containsExactlyInAnyOrder("agent-1", "agent-2");

        ReactionGroup heart = groups.stream()
                .filter(g -> g.emoji().equals("❤️")).findFirst().orElseThrow();
        assertThat(heart.count()).isEqualTo(1);
    }

    @Test
    void getReactionsBatch_returns_grouped_per_message() {
        service.react(1L, "👍", "agent-1", "tenant-1");
        service.react(2L, "❤️", "agent-2", "tenant-1");

        Map<Long, List<ReactionGroup>> batch = service.getReactionsBatch(List.of(1L, 2L));
        assertThat(batch.get(1L)).hasSize(1);
        assertThat(batch.get(2L)).hasSize(1);
    }

    // ── Inline test double ──────────────────────────────────────────────────

    static class StubReactionStore implements ReactionStore {
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
            return store.values().stream().filter(r -> r.messageId().equals(messageId)).toList();
        }

        @Override
        public Map<Long, List<Reaction>> findByMessages(Collection<Long> messageIds) {
            Map<Long, List<Reaction>> result = new HashMap<>();
            for (Long id : messageIds) { result.put(id, findByMessage(id)); }
            return result;
        }

        @Override public void deleteByMessage(Long messageId) {}
        @Override public void deleteByChannel(UUID channelId) {}
    }
}
