package io.casehub.qhorus.persistence.memory;

import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.query.MessageQuery;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryMessageStore implements MessageStore {

    private final Map<Long, Message> store     = new ConcurrentHashMap<>();
    private final AtomicLong         idCounter = new AtomicLong(1);

    @Override
    public Message put(Message message) {
        Message.Builder b = message.toBuilder();
        if (message.id() == null) {
            b.id(idCounter.getAndIncrement());
        }
        if (message.createdAt() == null) {
            b.createdAt(Instant.now());
        }
        message = b.build();
        store.put(message.id(), message);
        return message;
    }

    @Override
    public Optional<Message> find(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Message> scan(MessageQuery query) {
        Stream<Message> stream = store.values().stream()
                                      .filter(query::matches)
                                      .sorted(query.descending()
                                              ? Comparator.comparingLong(Message::id).reversed()
                                              : Comparator.comparingLong(Message::id));

        List<Message> results = stream.toList();

        if (query.limit() != null && results.size() > query.limit()) {
            return results.subList(0, query.limit());
        }
        return results;}

    @Override
    public void deleteAll(UUID channelId) {
        store.values().removeIf(m -> channelId.equals(m.channelId()));
    }

    @Override
    public void deleteNonEvent(UUID channelId) {
        store.values().removeIf(m -> channelId.equals(m.channelId())
                && m.messageType() != io.casehub.qhorus.api.message.MessageType.EVENT);
    }

    @Override
    public void delete(Long id) {
        store.remove(id);
    }

    @Override
    public int countByChannel(UUID channelId) {
        return (int) store.values().stream()
                .filter(m -> channelId.equals(m.channelId()))
                .count();
    }

    @Override
    public long count(MessageQuery q) {
        // Stream directly — scan() enforces query.limit(), which would truncate the count.
        return store.values().stream()
                .filter(q::matches)
                .count();
    }

    @Override
    public Map<UUID, Long> countAllByChannel() {
        return store.values().stream()
                .collect(Collectors.groupingBy(Message::channelId, Collectors.counting()));
    }

    @Override
    public List<String> distinctSendersByChannel(UUID channelId, MessageType excludedType) {
        return store.values().stream()
                .filter(m -> channelId.equals(m.channelId()) && m.messageType() != excludedType)
                .map(Message::sender)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    @Override
    public Optional<Message> findLastMessage(final UUID channelId) {
        return store.values().stream()
                .filter(m -> channelId.equals(m.channelId()))
                .max(Comparator.comparingLong(Message::id));
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

    @Override
    public int updateChannelId(UUID sourceChannelId, String topic, UUID targetChannelId) {
        int count = 0;
        for (Map.Entry<Long, Message> entry : store.entrySet()) {
            Message m = entry.getValue();
            if (sourceChannelId.equals(m.channelId()) && topic.equalsIgnoreCase(m.topic())) {
                store.put(entry.getKey(), m.toBuilder().channelId(targetChannelId).build());
                count++;
            }
        }
        return count;
    }

    @Override
    public List<MessageView> findRecent(UUID channelId, int limit) {
        List<Message> filtered = store.values().stream()
                                      .filter(m -> m.channelId().equals(channelId))
                                      .filter(m -> m.messageType() != MessageType.EVENT)
                                      .sorted(java.util.Comparator.comparingLong(Message::id).reversed())
                                      .limit(limit)
                                      .toList();
        java.util.ArrayList<MessageView> views = new java.util.ArrayList<>(filtered.size());
        for (int i = filtered.size() - 1; i >= 0; i--) {
            Message m = filtered.get(i);
            views.add(new MessageView(m.id(), m.channelId(), m.sender(), m.messageType(),
                                      m.content(), m.correlationId(), m.inReplyTo(), m.target(), m.topic(),
                                      m.artefactRefs(), m.actorType(), m.createdAt(), m.deadline(), m.replyCount()));
        }
        return views;
    }


    /** Call in @BeforeEach for test isolation. */
    public void clear() {
        store.clear();
        idCounter.set(1);
    }
}
