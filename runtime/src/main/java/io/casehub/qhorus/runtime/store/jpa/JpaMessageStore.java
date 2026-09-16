package io.casehub.qhorus.runtime.store.jpa;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.MessageStore;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.casehub.qhorus.runtime.message.MessageEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class JpaMessageStore implements MessageStore {

    @Inject
    CurrentPrincipal currentPrincipal;

    @Inject
    EntityManager em;

    @Override
    @Transactional
    public Message put(Message message) {
        MessageEntity entity = MessageEntity.fromDomain(message);
        if (entity.id != null) {
            entity = em.merge(entity);
            em.flush();
        } else {
            em.persist(entity);
            em.flush();
        }
        return entity.toDomain();
    }

    @Override
    public Optional<Message> find(Long id) {
        return em.createQuery("SELECT e FROM Message e WHERE e.id = ?1 AND e.tenancyId = ?2", MessageEntity.class)
                            .setParameter(1, id).setParameter(2, currentPrincipal.tenancyId())
                            .getResultStream().findFirst()
                            .map(MessageEntity::toDomain);
    }

    @Override
    public List<Message> scan(MessageQuery q) {
        MessageQueryJpql mq = MessageQueryJpql.from(q, currentPrincipal.tenancyId());
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
    @Transactional
    public void deleteAll(UUID channelId) {
        em.createQuery("DELETE FROM Message e WHERE e.channelId = ?1 AND e.tenancyId = ?2")
                .setParameter(1, channelId).setParameter(2, currentPrincipal.tenancyId()).executeUpdate();
    }

    @Override
    @Transactional
    public void deleteNonEvent(UUID channelId) {
        em.createQuery("DELETE FROM Message e WHERE e.channelId = ?1 AND e.messageType != ?2 AND e.tenancyId = ?3")
                .setParameter(1, channelId).setParameter(2, MessageType.EVENT).setParameter(3, currentPrincipal.tenancyId()).executeUpdate();
    }

    @Override
    @Transactional
    public void delete(Long id) {
        em.createQuery("DELETE FROM Message e WHERE e.id = ?1 AND e.tenancyId = ?2")
                .setParameter(1, id).setParameter(2, currentPrincipal.tenancyId()).executeUpdate();
    }

    @Override
    public int countByChannel(UUID channelId) {
        return em.createQuery("SELECT COUNT(e) FROM Message e WHERE e.channelId = ?1 AND e.tenancyId = ?2", Long.class)
                .setParameter(1, channelId).setParameter(2, currentPrincipal.tenancyId()).getSingleResult().intValue();
    }

    @Override
    public long count(MessageQuery q) {
        MessageQueryJpql mq = MessageQueryJpql.from(q, currentPrincipal.tenancyId());
        var query = em.createQuery("SELECT COUNT(e) FROM Message e WHERE " + mq.where(), Long.class);
        Object[] ps = mq.params(); for (int i = 0; i < ps.length; i++) query.setParameter(i + 1, ps[i]);
        return query.getSingleResult();
    }

    @Override
    public Map<UUID, Long> countAllByChannel() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em
                                           .createQuery("SELECT m.channelId, COUNT(m) FROM Message m WHERE m.tenancyId = ?1 GROUP BY m.channelId")
                                           .setParameter(1, currentPrincipal.tenancyId())
                                           .getResultList();
        return rows.stream().collect(Collectors.toMap(r -> (UUID) r[0], r -> (Long) r[1]));
    }

    @Override
    public List<String> distinctSendersByChannel(UUID channelId, MessageType excludedType) {
        @SuppressWarnings("unchecked")
        List<String> result = em
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
        return em.createQuery("SELECT e FROM Message e WHERE e.channelId = ?1 AND e.tenancyId = ?2 ORDER BY e.id DESC", MessageEntity.class)
                            .setParameter(1, channelId).setParameter(2, currentPrincipal.tenancyId())
                            .setMaxResults(1)
                            .getResultStream().findFirst()
                            .map(MessageEntity::toDomain);
    }

    @Override
    public int updateTopicName(UUID channelId, String oldTopic, String newTopic) {
        return em.createQuery("UPDATE Message e SET e.topic = ?1 WHERE e.channelId = ?2 AND LOWER(e.topic) = LOWER(?3) AND e.tenancyId = ?4")
                .setParameter(1, newTopic).setParameter(2, channelId).setParameter(3, oldTopic).setParameter(4, currentPrincipal.tenancyId())
                .executeUpdate();
    }

    @Override
    public int updateChannelId(UUID sourceChannelId, String topic, UUID targetChannelId) {
        return em.createQuery("UPDATE Message e SET e.channelId = ?1 WHERE e.channelId = ?2 AND LOWER(e.topic) = LOWER(?3) AND e.tenancyId = ?4")
                .setParameter(1, targetChannelId).setParameter(2, sourceChannelId).setParameter(3, topic).setParameter(4, currentPrincipal.tenancyId())
                .executeUpdate();
    }

    @Override
    public List<io.casehub.qhorus.api.message.MessageView> findRecent(UUID channelId, int limit) {
        List<MessageEntity> entities = em.createQuery("SELECT e FROM Message e WHERE e.channelId = ?1 AND e.messageType != ?2 AND e.tenancyId = ?3 ORDER BY e.id DESC", MessageEntity.class)
                                                    .setParameter(1, channelId).setParameter(2, MessageType.EVENT).setParameter(3, currentPrincipal.tenancyId())
                                                    .setMaxResults(limit)
                                                    .getResultList();
        java.util.ArrayList<io.casehub.qhorus.api.message.MessageView> views = new java.util.ArrayList<>(entities.size());
        for (int i = entities.size() - 1; i >= 0; i--) {
            Message m = entities.get(i).toDomain();
            views.add(new io.casehub.qhorus.api.message.MessageView(
                    m.id(), m.channelId(), m.sender(), m.messageType(), m.content(), m.payload(),
                    m.correlationId(), m.inReplyTo(), m.target(), m.topic(),
                    m.artefactRefs(), m.actorType(), m.createdAt(), m.deadline(), m.replyCount(),
                    m.correctsMessageId(), m.retraction()));
        }
        return views;
    }

    @Override
    public int countByCorrectsMessageId(Long messageId) {
        return ((Number) em.createQuery("SELECT COUNT(e) FROM Message e WHERE e.correctsMessageId = ?1 AND e.tenancyId = ?2")
                .setParameter(1, messageId).setParameter(2, currentPrincipal.tenancyId())
                .getSingleResult()).intValue();
    }

}
