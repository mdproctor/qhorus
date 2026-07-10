package io.casehub.qhorus.runtime.message;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.Topic;
import io.casehub.qhorus.api.message.TopicSummary;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.TopicStore;
import io.casehub.qhorus.api.store.query.MessageQuery;

@ApplicationScoped
public class TopicService {

    static final String DEFAULT_TOPIC = "general";

    @Inject
    public TopicStore topicStore;

    @Inject
    public MessageStore messageStore;

    public Topic ensureExists(UUID channelId, String topicName, String tenancyId) {
        String name = normalise(topicName);
        Optional<Topic> existing = topicStore.find(channelId, name);
        if (existing.isPresent()) {
            return existing.get();
        }
        return topicStore.put(new Topic(null, channelId, name, false, null, null, Instant.now(), tenancyId));
    }

    public List<TopicSummary> listTopics(UUID channelId) {
        List<Topic> topics = topicStore.findByChannel(channelId);
        return topics.stream().map(t -> {
            List<Message> messages = messageStore.scan(
                    MessageQuery.builder().channelId(channelId).topic(t.name()).build());
            Instant lastActivity = messages.stream()
                    .map(Message::createdAt)
                    .filter(java.util.Objects::nonNull)
                    .max(Instant::compareTo)
                    .orElse(t.createdAt());
            return new TopicSummary(t.name(), messages.size(), lastActivity, t.resolved(), t.resolvedAt());
        }).toList();
    }

    public void resolve(UUID channelId, String topicName, String actorId) {
        String name = normalise(topicName);
        if (DEFAULT_TOPIC.equalsIgnoreCase(name)) {
            throw new IllegalArgumentException("Cannot resolve the default topic 'general'");
        }
        Topic existing = topicStore.find(channelId, name)
                .orElseThrow(() -> new IllegalArgumentException("Topic '" + name + "' not found"));
        topicStore.put(new Topic(existing.id(), existing.channelId(), existing.name(),
                true, Instant.now(), actorId, existing.createdAt(), existing.tenancyId()));
    }

    public void unresolve(UUID channelId, String topicName) {
        String name = normalise(topicName);
        Topic existing = topicStore.find(channelId, name)
                .orElseThrow(() -> new IllegalArgumentException("Topic '" + name + "' not found"));
        topicStore.put(new Topic(existing.id(), existing.channelId(), existing.name(),
                false, null, null, existing.createdAt(), existing.tenancyId()));
    }

    public RenameResult rename(UUID channelId, String oldName, String newName, String actorId) {
        String normalOld = normalise(oldName);
        String normalNew = normalise(newName);
        if (DEFAULT_TOPIC.equalsIgnoreCase(normalOld)) {
            throw new IllegalArgumentException("Cannot rename the default topic 'general'");
        }
        if (topicStore.find(channelId, normalNew).isPresent()) {
            throw new IllegalArgumentException("Topic '" + normalNew + "' already exists in this channel");
        }
        int topicUpdated = topicStore.rename(channelId, normalOld, normalNew);
        if (topicUpdated == 0) {
            throw new IllegalArgumentException("Topic '" + normalOld + "' not found");
        }
        int messagesUpdated = messageStore.updateTopicName(channelId, normalOld, normalNew);
        return new RenameResult(normalOld, normalNew, messagesUpdated);
    }

    static String normalise(String topicName) {
        if (topicName == null || topicName.isBlank()) return DEFAULT_TOPIC;
        String trimmed = topicName.strip();
        if (trimmed.length() > 200) {
            throw new IllegalArgumentException("Topic name exceeds 200 characters");
        }
        return trimmed;
    }

    public record RenameResult(String oldName, String newName, int messagesUpdated) {}
}
