package io.quarkiverse.qhorus.runtime.store.jpa;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.message.MessageType;
import io.quarkiverse.qhorus.runtime.store.MessageStore;
import io.quarkiverse.qhorus.runtime.store.query.MessageQuery;

@ApplicationScoped
public class JpaMessageStore implements MessageStore {

    @Override
    @Transactional
    public Message put(Message message) {
        message.persistAndFlush();
        return message;
    }

    @Override
    public Optional<Message> find(Long id) {
        return Optional.ofNullable(Message.findById(id));
    }

    @Override
    public List<Message> scan(MessageQuery q) {
        StringBuilder jpql = new StringBuilder("FROM Message WHERE 1=1");
        List<Object> params = new ArrayList<>();
        int idx = 1;

        if (q.channelId() != null) {
            jpql.append(" AND channelId = ?").append(idx++);
            params.add(q.channelId());
        }
        if (q.afterId() != null) {
            jpql.append(" AND id > ?").append(idx++);
            params.add(q.afterId());
        }
        if (q.sender() != null) {
            jpql.append(" AND sender = ?").append(idx++);
            params.add(q.sender());
        }
        if (q.target() != null) {
            jpql.append(" AND target = ?").append(idx++);
            params.add(q.target());
        }
        if (q.inReplyTo() != null) {
            jpql.append(" AND inReplyTo = ?").append(idx++);
            params.add(q.inReplyTo());
        }
        if (q.excludeTypes() != null && !q.excludeTypes().isEmpty()) {
            jpql.append(" AND messageType NOT IN ?").append(idx++);
            params.add(q.excludeTypes());
        }
        if (q.contentPattern() != null) {
            jpql.append(" AND LOWER(content) LIKE ?").append(idx++);
            params.add("%" + q.contentPattern().toLowerCase() + "%");
        }

        jpql.append(" ORDER BY id ASC");

        List<Message> results = Message.list(jpql.toString(), params.toArray());

        if (q.limit() != null && results.size() > q.limit()) {
            return results.subList(0, q.limit());
        }
        return results;
    }

    @Override
    @Transactional
    public void deleteAll(UUID channelId) {
        Message.delete("channelId", channelId);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Message.deleteById(id);
    }

    @Override
    public int countByChannel(UUID channelId) {
        return (int) Message.count("channelId", channelId);
    }

    @Override
    public Map<UUID, Long> countAllByChannel() {
        throw new UnsupportedOperationException("Not yet implemented — see task #7");
    }

    @Override
    public List<String> distinctSendersByChannel(UUID channelId, MessageType excludedType) {
        throw new UnsupportedOperationException("Not yet implemented — see task #7");
    }
}
