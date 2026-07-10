package io.casehub.qhorus.persistence.memory.contract;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.Topic;
import io.casehub.qhorus.api.store.TopicStore;

import static org.assertj.core.api.Assertions.*;

public abstract class TopicStoreContractTest {

    protected abstract TopicStore store();

    private UUID channelId;
    private static final String TENANCY = "test-tenant";

    @BeforeEach
    void setUp() {
        channelId = UUID.randomUUID();
    }

    protected Topic makeTopic(String name) {
        return new Topic(null, channelId, name, false, null, null, Instant.now(), TENANCY);
    }

    @Test
    void put_and_find_by_channel_and_name() {
        Topic saved = store().put(makeTopic("auth-analysis"));
        assertThat(saved.id()).isNotNull();
        assertThat(saved.name()).isEqualTo("auth-analysis");

        Optional<Topic> found = store().find(channelId, "auth-analysis");
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(saved.id());
    }

    @Test
    void find_is_case_insensitive() {
        store().put(makeTopic("Auth-Analysis"));

        assertThat(store().find(channelId, "auth-analysis")).isPresent();
        assertThat(store().find(channelId, "AUTH-ANALYSIS")).isPresent();
        assertThat(store().find(channelId, "Auth-Analysis")).isPresent();
    }

    @Test
    void find_returns_empty_for_unknown() {
        assertThat(store().find(channelId, "nonexistent")).isEmpty();
    }

    @Test
    void findById() {
        Topic saved = store().put(makeTopic("billing"));
        assertThat(store().findById(saved.id())).isPresent();
        assertThat(store().findById(99999L)).isEmpty();
    }

    @Test
    void findByChannel_returns_all_topics() {
        store().put(makeTopic("topic-a"));
        store().put(makeTopic("topic-b"));
        store().put(makeTopic("topic-c"));

        UUID otherChannel = UUID.randomUUID();
        store().put(new Topic(null, otherChannel, "other", false, null, null, Instant.now(), TENANCY));

        List<Topic> topics = store().findByChannel(channelId);
        assertThat(topics).hasSize(3);
        assertThat(topics).extracting(Topic::name)
                .containsExactlyInAnyOrder("topic-a", "topic-b", "topic-c");
    }

    @Test
    void rename_updates_topic_name() {
        store().put(makeTopic("old-name"));

        int updated = store().rename(channelId, "old-name", "new-name");
        assertThat(updated).isEqualTo(1);

        assertThat(store().find(channelId, "old-name")).isEmpty();
        assertThat(store().find(channelId, "new-name")).isPresent();
    }

    @Test
    void rename_nonexistent_returns_zero() {
        int updated = store().rename(channelId, "nonexistent", "new-name");
        assertThat(updated).isEqualTo(0);
    }

    @Test
    void delete_removes_topic() {
        store().put(makeTopic("to-delete"));
        assertThat(store().find(channelId, "to-delete")).isPresent();

        store().delete(channelId, "to-delete");
        assertThat(store().find(channelId, "to-delete")).isEmpty();
    }

    @Test
    void deleteAll_removes_all_for_channel() {
        store().put(makeTopic("a"));
        store().put(makeTopic("b"));
        UUID otherChannel = UUID.randomUUID();
        store().put(new Topic(null, otherChannel, "other", false, null, null, Instant.now(), TENANCY));

        store().deleteAll(channelId);
        assertThat(store().findByChannel(channelId)).isEmpty();
        assertThat(store().findByChannel(otherChannel)).hasSize(1);
    }

    @Test
    void put_is_upsert_by_channel_and_name() {
        store().put(makeTopic("upsert-test"));
        store().put(new Topic(null, channelId, "upsert-test", true,
                Instant.now(), "actor-1", Instant.now(), TENANCY));

        assertThat(store().findByChannel(channelId)).hasSize(1);
        Topic found = store().find(channelId, "upsert-test").orElseThrow();
        assertThat(found.resolved()).isTrue();
    }
}
