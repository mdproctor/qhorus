package io.casehub.qhorus.runtime.store.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.MessageEntity;
import io.casehub.qhorus.api.store.CrossTenantMessageStore;
import io.casehub.qhorus.api.store.query.MessageQuery;

@ApplicationScoped
public class JpaCrossTenantMessageStore implements CrossTenantMessageStore {

    @Override
    public List<Message> scan(MessageQuery q) {
        MessageQueryJpql mq = MessageQueryJpql.from(q);
        String jpql = "FROM Message WHERE " + mq.where()
                + (q.descending() ? " ORDER BY id DESC" : " ORDER BY id ASC");

        List<MessageEntity> entities;
        if (q.limit() != null) {
            entities = MessageEntity.find(jpql, mq.params()).page(0, q.limit()).list();
        } else {
            entities = MessageEntity.list(jpql, mq.params());
        }
        return entities.stream().map(MessageEntity::toDomain).toList();
    }

    @Override
    public long count(MessageQuery q) {
        MessageQueryJpql mq = MessageQueryJpql.from(q);
        return MessageEntity.count(mq.where(), mq.params());
    }

    @Override
    public int countByChannel(UUID channelId) {
        return (int) MessageEntity.count("channelId", channelId);
    }

    @Override
    public List<String> distinctSendersByChannel(UUID channelId, MessageType excludedType) {
        @SuppressWarnings("unchecked")
        List<String> result = MessageEntity.getEntityManager()
                                           .createQuery("SELECT DISTINCT m.sender FROM Message m "
                        + "WHERE m.channelId = ?1 AND m.messageType != ?2 ORDER BY m.sender")
                                           .setParameter(1, channelId)
                                           .setParameter(2, excludedType)
                                           .getResultList();
        return result;
    }

    @Override
    public Optional<Message> findLastMessage(UUID channelId) {
        return MessageEntity.<MessageEntity>find("channelId = ?1 ORDER BY id DESC", channelId)
                            .page(0, 1)
                            .<MessageEntity>firstResultOptional()
                            .map(MessageEntity::toDomain);
    }
}
