package io.casehub.qhorus.runtime.message;

import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.Topic;
import io.casehub.qhorus.api.message.TopicSummary;
import io.casehub.qhorus.api.store.CommitmentStore;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.TopicStore;
import io.casehub.qhorus.api.store.query.MessageQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class TopicService {

    static final String DEFAULT_TOPIC = "general";

    @Inject
    public TopicStore topicStore;

    @Inject
    public MessageStore messageStore;

    @Inject
    public CommitmentStore commitmentStore;

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

    public Topic resolve(UUID channelId, String topicName, String actorId) {
        String name = normalise(topicName);
        if (DEFAULT_TOPIC.equalsIgnoreCase(name)) {
            throw new IllegalArgumentException("Cannot resolve the default topic 'general'");
        }
        Topic existing = topicStore.find(channelId, name)
                                   .orElseThrow(() -> new IllegalArgumentException("Topic '" + name + "' not found"));
        Topic resolved = new Topic(existing.id(), existing.channelId(), existing.name(),
                                   true, Instant.now(), actorId, existing.createdAt(), existing.tenancyId());
        return topicStore.put(resolved);
    }

    public Topic unresolve(UUID channelId, String topicName) {
        String name = normalise(topicName);
        Topic existing = topicStore.find(channelId, name)
                                   .orElseThrow(() -> new IllegalArgumentException("Topic '" + name + "' not found"));
        Topic unresolved = new Topic(existing.id(), existing.channelId(), existing.name(),
                                     false, null, null, existing.createdAt(), existing.tenancyId());
        return topicStore.put(unresolved);
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

    public MergeResult merge(UUID channelId, String sourceTopic, String targetTopic, String actorId) {
        String normalSource = normalise(sourceTopic);
        String normalTarget = normalise(targetTopic);
        if (DEFAULT_TOPIC.equalsIgnoreCase(normalSource)) {
            throw new IllegalArgumentException("Cannot merge from the default topic 'general'");
        }
        if (normalSource.equalsIgnoreCase(normalTarget)) {
            throw new IllegalArgumentException("Source and target topics must be different");
        }
        if (topicStore.find(channelId, normalSource).isEmpty()) {
            throw new IllegalArgumentException("Source topic '" + normalSource + "' not found");
        }
        if (topicStore.find(channelId, normalTarget).isEmpty()) {
            throw new IllegalArgumentException("Target topic '" + normalTarget + "' not found");
        }
        int messagesUpdated = messageStore.updateTopicName(channelId, normalSource, normalTarget);
        topicStore.delete(channelId, normalSource);
        return new MergeResult(normalSource, normalTarget, messagesUpdated);
    }

    public MoveResult move(UUID sourceChannelId, String topicName, UUID targetChannelId, String actorId) {
        String normalTopic = normalise(topicName);
        if (DEFAULT_TOPIC.equalsIgnoreCase(normalTopic)) {
            throw new IllegalArgumentException("Cannot move the default topic 'general'");
        }
        if (topicStore.find(sourceChannelId, normalTopic).isEmpty()) {
            throw new IllegalArgumentException("Topic '" + normalTopic + "' not found in source channel");
        }

        var messages = messageStore.scan(
                MessageQuery.builder().channelId(sourceChannelId).topic(normalTopic).build());
        var commitmentIds = messages.stream()
                .map(Message::commitmentId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (!commitmentIds.isEmpty()) {
            var commitments = commitmentStore.findByIds(commitmentIds);
            var openCommitments = commitments.stream()
                    .filter(c -> c.state().isActive())
                    .toList();
            if (!openCommitments.isEmpty()) {
                String blocking = openCommitments.stream()
                        .map(io.casehub.qhorus.api.message.Commitment::correlationId)
                        .collect(Collectors.joining(", "));
                throw new IllegalStateException(
                        "Cannot move topic — open commitments exist: " + blocking);
            }
        }

        int moved = messageStore.updateChannelId(sourceChannelId, normalTopic, targetChannelId);

        if (topicStore.find(targetChannelId, normalTopic).isEmpty()) {
            topicStore.put(new Topic(null, targetChannelId, normalTopic, false, null, null, Instant.now(), null));
        }
        topicStore.delete(sourceChannelId, normalTopic);

        return new MoveResult(normalTopic, sourceChannelId, targetChannelId, moved);
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

    public record MergeResult(String sourceTopic, String targetTopic, int messagesUpdated) {}

    public record MoveResult(String topicName, UUID sourceChannelId, UUID targetChannelId, int messagesUpdated) {}

}
