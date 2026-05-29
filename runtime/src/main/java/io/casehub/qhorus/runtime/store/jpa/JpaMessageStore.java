package io.casehub.qhorus.runtime.store.jpa;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.store.MessageStore;
import io.casehub.qhorus.runtime.store.query.MessageQuery;

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
        MessageQueryJpql mq = MessageQueryJpql.from(q);
        String jpql = "FROM Message WHERE " + mq.where()
                + (q.descending() ? " ORDER BY id DESC" : " ORDER BY id ASC");

        if (q.limit() != null) {
            return Message.find(jpql, mq.params()).page(0, q.limit()).list();
        }
        return Message.list(jpql, mq.params());
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
    public long count(MessageQuery q) {
        MessageQueryJpql mq = MessageQueryJpql.from(q);
        return Message.count(mq.where(), mq.params());
    }

    @Override
    public Map<UUID, Long> countAllByChannel() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = Message.getEntityManager()
                .createQuery("SELECT m.channelId, COUNT(m) FROM Message m GROUP BY m.channelId")
                .getResultList();
        return rows.stream().collect(Collectors.toMap(r -> (UUID) r[0], r -> (Long) r[1]));
    }

    @Override
    public List<String> distinctSendersByChannel(UUID channelId, MessageType excludedType) {
        @SuppressWarnings("unchecked")
        List<String> result = Message.getEntityManager()
                .createQuery("SELECT DISTINCT m.sender FROM Message m "
                        + "WHERE m.channelId = ?1 AND m.messageType != ?2 ORDER BY m.sender")
                .setParameter(1, channelId)
                .setParameter(2, excludedType)
                .getResultList();
        return result;
    }

    @Override
    public Optional<Message> findLastMessage(final UUID channelId) {
        return Message.<Message>find("channelId = ?1 ORDER BY id DESC", channelId)
                .page(0, 1)
                .firstResultOptional();
    }
}
