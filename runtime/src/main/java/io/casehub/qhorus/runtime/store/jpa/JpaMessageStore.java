package io.casehub.qhorus.runtime.store.jpa;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.MessageEntity;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.query.MessageQuery;

@ApplicationScoped
public class JpaMessageStore implements MessageStore {

    @Inject
    CurrentPrincipal currentPrincipal;

    @Override
    @Transactional
    public Message put(Message message) {
        MessageEntity entity = MessageEntity.fromDomain(message);
        if (entity.id != null) {
            entity = MessageEntity.getEntityManager().merge(entity);
            MessageEntity.flush();
        } else {
            entity.persistAndFlush();
        }
        return entity.toDomain();
    }

    @Override
    public Optional<Message> find(Long id) {
        return MessageEntity.<MessageEntity>find("id = ?1 AND tenancyId = ?2", id, currentPrincipal.tenancyId())
                            .<MessageEntity>firstResultOptional()
                            .map(MessageEntity::toDomain);
    }

    @Override
    public List<Message> scan(MessageQuery q) {
        MessageQueryJpql mq = MessageQueryJpql.from(q, currentPrincipal.tenancyId());
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
    @Transactional
    public void deleteAll(UUID channelId) {
        MessageEntity.delete("channelId = ?1 AND tenancyId = ?2", channelId, currentPrincipal.tenancyId());
    }

    @Override
    @Transactional
    public void deleteNonEvent(UUID channelId) {
        MessageEntity.delete("channelId = ?1 AND messageType != ?2 AND tenancyId = ?3",
                channelId, MessageType.EVENT, currentPrincipal.tenancyId());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        MessageEntity.delete("id = ?1 AND tenancyId = ?2", id, currentPrincipal.tenancyId());
    }

    @Override
    public int countByChannel(UUID channelId) {
        return (int) MessageEntity.count("channelId = ?1 AND tenancyId = ?2", channelId, currentPrincipal.tenancyId());
    }

    @Override
    public long count(MessageQuery q) {
        MessageQueryJpql mq = MessageQueryJpql.from(q, currentPrincipal.tenancyId());
        return MessageEntity.count(mq.where(), mq.params());
    }

    @Override
    public Map<UUID, Long> countAllByChannel() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = MessageEntity.getEntityManager()
                                           .createQuery("SELECT m.channelId, COUNT(m) FROM Message m WHERE m.tenancyId = ?1 GROUP BY m.channelId")
                                           .setParameter(1, currentPrincipal.tenancyId())
                                           .getResultList();
        return rows.stream().collect(Collectors.toMap(r -> (UUID) r[0], r -> (Long) r[1]));
    }

    @Override
    public List<String> distinctSendersByChannel(UUID channelId, MessageType excludedType) {
        @SuppressWarnings("unchecked")
        List<String> result = MessageEntity.getEntityManager()
                                           .createQuery("SELECT DISTINCT m.sender FROM Message m "
                        + "WHERE m.channelId = ?1 AND m.messageType != ?2 AND m.tenancyId = ?3 ORDER BY m.sender")
                                           .setParameter(1, channelId)
                                           .setParameter(2, excludedType)
                                           .setParameter(3, currentPrincipal.tenancyId())
                                           .getResultList();
        return result;
    }

    @Override
    public Optional<Message> findLastMessage(final UUID channelId) {
        return MessageEntity.<MessageEntity>find("channelId = ?1 AND tenancyId = ?2 ORDER BY id DESC",
                                                 channelId, currentPrincipal.tenancyId())
                            .page(0, 1)
                            .<MessageEntity>firstResultOptional()
                            .map(MessageEntity::toDomain);
    }
}
