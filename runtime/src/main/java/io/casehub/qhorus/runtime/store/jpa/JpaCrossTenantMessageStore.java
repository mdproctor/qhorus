package io.casehub.qhorus.runtime.store.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.MessageEntity;
import io.casehub.qhorus.api.store.CrossTenantMessageStore;
import io.casehub.qhorus.api.store.query.MessageQuery;

@ApplicationScoped
public class JpaCrossTenantMessageStore implements CrossTenantMessageStore {

    @Inject
    @io.quarkus.hibernate.orm.PersistenceUnit("qhorus")
    EntityManager em;

    @Override
    public List<Message> scan(MessageQuery q) {
        MessageQueryJpql mq = MessageQueryJpql.from(q);
        String jpql = "FROM Message e WHERE " + mq.where()
                + (q.descending() ? " ORDER BY e.id DESC" : " ORDER BY e.id ASC");

        List<MessageEntity> entities;
        if (q.limit() != null) {
            var query = em.createQuery("SELECT e " + jpql, MessageEntity.class);
            Object[] ps = mq.params(); for (int i = 0; i < ps.length; i++) query.setParameter(i + 1, ps[i]);
            entities = query.setMaxResults(q.limit()).getResultList();
        } else {
            var query = em.createQuery("SELECT e " + jpql, MessageEntity.class);
            Object[] ps = mq.params(); for (int i = 0; i < ps.length; i++) query.setParameter(i + 1, ps[i]);
            entities = query.getResultList();
        }
        return entities.stream().map(MessageEntity::toDomain).toList();
    }

    @Override
    public long count(MessageQuery q) {
        MessageQueryJpql mq = MessageQueryJpql.from(q);
        var query = em.createQuery("SELECT COUNT(e) FROM Message e WHERE " + mq.where(), Long.class);
        Object[] ps = mq.params(); for (int i = 0; i < ps.length; i++) query.setParameter(i + 1, ps[i]);
        return query.getSingleResult();
    }

    @Override
    public int countByChannel(UUID channelId) {
        return em.createQuery("SELECT COUNT(e) FROM Message e WHERE e.channelId = ?1", Long.class)
                .setParameter(1, channelId).getSingleResult().intValue();
    }

    @Override
    public List<String> distinctSendersByChannel(UUID channelId, MessageType excludedType) {
        @SuppressWarnings("unchecked")
        List<String> result = em
                                           .createQuery("SELECT DISTINCT m.sender FROM Message m "
                        + "WHERE m.channelId = ?1 AND m.messageType != ?2 ORDER BY m.sender")
                                           .setParameter(1, channelId)
                                           .setParameter(2, excludedType)
                                           .getResultList();
        return result;
    }

    @Override
    public Optional<Message> findLastMessage(UUID channelId) {
        return em.createQuery("SELECT e FROM Message e WHERE e.channelId = ?1 ORDER BY e.id DESC", MessageEntity.class)
                            .setParameter(1, channelId).setMaxResults(1)
                            .getResultStream().findFirst()
                            .map(MessageEntity::toDomain);
    }

    @Override
    public Optional<Message> find(Long id) {
        return Optional.ofNullable(em.find(MessageEntity.class, id))
                .map(MessageEntity::toDomain);
    }
}
