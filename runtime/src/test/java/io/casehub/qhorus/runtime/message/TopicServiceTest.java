package io.casehub.qhorus.runtime.message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.Topic;
import io.casehub.qhorus.api.message.TopicSummary;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.TopicStore;
import io.casehub.qhorus.api.store.query.MessageQuery;

import static org.assertj.core.api.Assertions.*;

class TopicServiceTest {

    private TopicService service;
    private StubTopicStore topicStore;
    private StubMessageStore messageStore;
    private UUID channelId;

    @BeforeEach
    void setUp() {
        topicStore = new StubTopicStore();
        messageStore = new StubMessageStore();
        service = new TopicService();
        service.topicStore = topicStore;
        service.messageStore = messageStore;
        channelId = UUID.randomUUID();
    }

    @Test
    void ensureExists_creates_topic_on_first_call() {
        Topic topic = service.ensureExists(channelId, "auth-analysis", "tenant-1");
        assertThat(topic).isNotNull();
        assertThat(topic.name()).isEqualTo("auth-analysis");
        assertThat(topic.resolved()).isFalse();
    }

    @Test
    void ensureExists_returns_existing_on_second_call() {
        Topic first = service.ensureExists(channelId, "auth-analysis", "tenant-1");
        Topic second = service.ensureExists(channelId, "auth-analysis", "tenant-1");
        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void ensureExists_case_insensitive() {
        Topic first = service.ensureExists(channelId, "Auth-Analysis", "tenant-1");
        Topic second = service.ensureExists(channelId, "auth-analysis", "tenant-1");
        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void ensureExists_null_defaults_to_general() {
        Topic topic = service.ensureExists(channelId, null, "tenant-1");
        assertThat(topic.name()).isEqualTo("general");
    }

    @Test
    void ensureExists_blank_defaults_to_general() {
        Topic topic = service.ensureExists(channelId, "  ", "tenant-1");
        assertThat(topic.name()).isEqualTo("general");
    }

    @Test
    void resolve_sets_resolved_state() {
        service.ensureExists(channelId, "done-topic", "tenant-1");
        service.resolve(channelId, "done-topic", "actor-1");

        Topic found = topicStore.find(channelId, "done-topic").orElseThrow();
        assertThat(found.resolved()).isTrue();
        assertThat(found.resolvedBy()).isEqualTo("actor-1");
        assertThat(found.resolvedAt()).isNotNull();
    }

    @Test
    void resolve_rejects_general() {
        service.ensureExists(channelId, "general", "tenant-1");
        assertThatThrownBy(() -> service.resolve(channelId, "general", "actor-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("general");
    }

    @Test
    void resolve_rejects_nonexistent() {
        assertThatThrownBy(() -> service.resolve(channelId, "nonexistent", "actor-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void unresolve_clears_resolved_state() {
        service.ensureExists(channelId, "topic-1", "tenant-1");
        service.resolve(channelId, "topic-1", "actor-1");
        service.unresolve(channelId, "topic-1");

        Topic found = topicStore.find(channelId, "topic-1").orElseThrow();
        assertThat(found.resolved()).isFalse();
    }

    @Test
    void rename_rejects_general_as_source() {
        service.ensureExists(channelId, "general", "tenant-1");
        assertThatThrownBy(() -> service.rename(channelId, "general", "new-name", "actor-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rename_rejects_if_target_exists() {
        service.ensureExists(channelId, "topic-a", "tenant-1");
        service.ensureExists(channelId, "topic-b", "tenant-1");
        assertThatThrownBy(() -> service.rename(channelId, "topic-a", "topic-b", "actor-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void rename_rejects_nonexistent() {
        assertThatThrownBy(() -> service.rename(channelId, "nonexistent", "new-name", "actor-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void rename_updates_topic_and_messages() {
        service.ensureExists(channelId, "old-name", "tenant-1");
        messageStore.put(Message.builder()
                .channelId(channelId).sender("a").messageType(MessageType.STATUS)
                .actorType(ActorType.AGENT).topic("old-name").tenancyId("tenant-1").build());
        messageStore.put(Message.builder()
                .channelId(channelId).sender("b").messageType(MessageType.STATUS)
                .actorType(ActorType.AGENT).topic("old-name").tenancyId("tenant-1").build());

        TopicService.RenameResult result = service.rename(channelId, "old-name", "new-name", "actor-1");
        assertThat(result.messagesUpdated()).isEqualTo(2);
        assertThat(topicStore.find(channelId, "new-name")).isPresent();
        assertThat(topicStore.find(channelId, "old-name")).isEmpty();
    }

    @Test
    void listTopics_returns_summaries_with_counts() {
        service.ensureExists(channelId, "topic-a", "tenant-1");
        service.ensureExists(channelId, "topic-b", "tenant-1");
        messageStore.put(Message.builder()
                .channelId(channelId).sender("a").messageType(MessageType.STATUS)
                .actorType(ActorType.AGENT).topic("topic-a").tenancyId("tenant-1").build());
        messageStore.put(Message.builder()
                .channelId(channelId).sender("a").messageType(MessageType.STATUS)
                .actorType(ActorType.AGENT).topic("topic-a").tenancyId("tenant-1").build());
        messageStore.put(Message.builder()
                .channelId(channelId).sender("b").messageType(MessageType.COMMAND)
                .actorType(ActorType.AGENT).topic("topic-b").tenancyId("tenant-1").build());

        List<TopicSummary> summaries = service.listTopics(channelId);
        assertThat(summaries).hasSize(2);
        TopicSummary a = summaries.stream().filter(s -> s.name().equals("topic-a")).findFirst().orElseThrow();
        assertThat(a.messageCount()).isEqualTo(2);
        TopicSummary b = summaries.stream().filter(s -> s.name().equals("topic-b")).findFirst().orElseThrow();
        assertThat(b.messageCount()).isEqualTo(1);
    }

    @Test
    void normalise_rejects_topic_over_200_chars() {
        String longTopic = "a".repeat(201);
        assertThatThrownBy(() -> service.ensureExists(channelId, longTopic, "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("200");
    }

    @Test
    void normalise_trims_whitespace() {
        Topic topic = service.ensureExists(channelId, "  auth-analysis  ", "tenant-1");
        assertThat(topic.name()).isEqualTo("auth-analysis");
    }

    // ── Inline test doubles ─────────────────────────────────────────────────

    static class StubTopicStore implements TopicStore {
        private final Map<Long, Topic> store = new ConcurrentHashMap<>();
        private final AtomicLong idCounter = new AtomicLong(1);

        @Override
        public Topic put(Topic topic) {
            Optional<Topic> existing = find(topic.channelId(), topic.name());
            if (existing.isPresent()) {
                Topic updated = new Topic(existing.get().id(), topic.channelId(), existing.get().name(),
                        topic.resolved(), topic.resolvedAt(), topic.resolvedBy(),
                        existing.get().createdAt(), topic.tenancyId());
                store.put(updated.id(), updated);
                return updated;
            }
            Topic saved = new Topic(idCounter.getAndIncrement(), topic.channelId(), topic.name(),
                    topic.resolved(), topic.resolvedAt(), topic.resolvedBy(),
                    topic.createdAt(), topic.tenancyId());
            store.put(saved.id(), saved);
            return saved;
        }

        @Override
        public Optional<Topic> find(UUID channelId, String name) {
            return store.values().stream()
                    .filter(t -> t.channelId().equals(channelId) && t.name().equalsIgnoreCase(name))
                    .findFirst();
        }

        @Override public Optional<Topic> findById(Long id) { return Optional.ofNullable(store.get(id)); }

        @Override
        public List<Topic> findByChannel(UUID channelId) {
            return store.values().stream()
                    .filter(t -> t.channelId().equals(channelId))
                    .sorted((a, b) -> a.createdAt().compareTo(b.createdAt()))
                    .toList();
        }

        @Override
        public int rename(UUID channelId, String oldName, String newName) {
            Optional<Topic> existing = find(channelId, oldName);
            if (existing.isEmpty()) return 0;
            Topic t = existing.get();
            store.put(t.id(), new Topic(t.id(), t.channelId(), newName, t.resolved(),
                    t.resolvedAt(), t.resolvedBy(), t.createdAt(), t.tenancyId()));
            return 1;
        }

        @Override public void delete(UUID channelId, String name) {
            store.values().removeIf(t -> t.channelId().equals(channelId) && t.name().equalsIgnoreCase(name));
        }

        @Override public void deleteAll(UUID channelId) {
            store.values().removeIf(t -> t.channelId().equals(channelId));
        }
    }

    static class StubMessageStore implements MessageStore {
        private final Map<Long, Message> store = new ConcurrentHashMap<>();
        private final AtomicLong idCounter = new AtomicLong(1);

        @Override
        public Message put(Message message) {
            Long id = message.id() != null ? message.id() : idCounter.getAndIncrement();
            Message saved = message.toBuilder().id(id).createdAt(
                    message.createdAt() != null ? message.createdAt() : Instant.now()).build();
            store.put(id, saved);
            return saved;
        }

        @Override
        public List<Message> scan(MessageQuery query) {
            return store.values().stream().filter(query::matches).toList();
        }

        @Override
        public int updateTopicName(UUID channelId, String oldTopic, String newTopic) {
            int count = 0;
            for (Map.Entry<Long, Message> entry : store.entrySet()) {
                Message m = entry.getValue();
                if (m.channelId().equals(channelId) && oldTopic.equalsIgnoreCase(m.topic())) {
                    store.put(entry.getKey(), m.toBuilder().topic(newTopic).build());
                    count++;
                }
            }
            return count;
        }

        @Override public Optional<Message> find(Long id) { return Optional.ofNullable(store.get(id)); }
        @Override public void deleteAll(UUID channelId) {}
        @Override public void deleteNonEvent(UUID channelId) {}
        @Override public void delete(Long id) {}
        @Override public int countByChannel(UUID channelId) { return 0; }
        @Override public long count(MessageQuery q) { return 0; }
        @Override public java.util.Map<UUID, Long> countAllByChannel() { return Map.of(); }
        @Override public List<String> distinctSendersByChannel(UUID channelId, MessageType excludedType) { return List.of(); }
        @Override public Optional<Message> findLastMessage(UUID channelId) { return Optional.empty(); }
    }
}
