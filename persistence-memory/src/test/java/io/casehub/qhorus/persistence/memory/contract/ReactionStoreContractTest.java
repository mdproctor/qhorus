package io.casehub.qhorus.persistence.memory.contract;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.Reaction;
import io.casehub.qhorus.api.store.ReactionStore;

import static org.assertj.core.api.Assertions.*;

public abstract class ReactionStoreContractTest {

    protected abstract ReactionStore store();

    private static final String TENANCY = "test-tenant";

    @Test
    void react_creates_reaction() {
        Reaction r = store().react(1L, "👍", "agent-1", TENANCY);
        assertThat(r.id()).isNotNull();
        assertThat(r.messageId()).isEqualTo(1L);
        assertThat(r.emoji()).isEqualTo("👍");
        assertThat(r.actorId()).isEqualTo("agent-1");
    }

    @Test
    void react_is_idempotent() {
        store().react(1L, "👍", "agent-1", TENANCY);
        store().react(1L, "👍", "agent-1", TENANCY);

        List<Reaction> reactions = store().findByMessage(1L);
        assertThat(reactions).hasSize(1);
    }

    @Test
    void different_actors_same_emoji() {
        store().react(1L, "👍", "agent-1", TENANCY);
        store().react(1L, "👍", "agent-2", TENANCY);

        List<Reaction> reactions = store().findByMessage(1L);
        assertThat(reactions).hasSize(2);
    }

    @Test
    void multiple_emojis_same_message() {
        store().react(1L, "👍", "agent-1", TENANCY);
        store().react(1L, "❤️", "agent-1", TENANCY);

        List<Reaction> reactions = store().findByMessage(1L);
        assertThat(reactions).hasSize(2);
    }

    @Test
    void unreact_removes_reaction() {
        store().react(1L, "👍", "agent-1", TENANCY);
        boolean removed = store().unreact(1L, "👍", "agent-1");
        assertThat(removed).isTrue();
        assertThat(store().findByMessage(1L)).isEmpty();
    }

    @Test
    void unreact_is_idempotent() {
        boolean removed = store().unreact(1L, "👍", "agent-1");
        assertThat(removed).isFalse();
    }

    @Test
    void findByMessages_batch() {
        store().react(1L, "👍", "agent-1", TENANCY);
        store().react(1L, "❤️", "agent-2", TENANCY);
        store().react(2L, "🎉", "agent-1", TENANCY);

        Map<Long, List<Reaction>> batch = store().findByMessages(List.of(1L, 2L, 3L));
        assertThat(batch.get(1L)).hasSize(2);
        assertThat(batch.get(2L)).hasSize(1);
        assertThat(batch.getOrDefault(3L, List.of())).isEmpty();
    }

    @Test
    void deleteByMessage_removes_all_for_message() {
        store().react(1L, "👍", "agent-1", TENANCY);
        store().react(1L, "❤️", "agent-2", TENANCY);
        store().react(2L, "👍", "agent-1", TENANCY);

        store().deleteByMessage(1L);
        assertThat(store().findByMessage(1L)).isEmpty();
        assertThat(store().findByMessage(2L)).hasSize(1);
    }
}
