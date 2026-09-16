package io.casehub.qhorus.runtime.store.jpa;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.query.ChannelQuery;
import io.casehub.qhorus.runtime.channel.ChannelEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JpaChannelStore implements ChannelStore {

    @Inject
    CurrentPrincipal currentPrincipal;

    @Inject
    @io.quarkus.hibernate.orm.PersistenceUnit("qhorus")
    EntityManager em;

    @Override
    @Transactional
    public Channel put(Channel channel) {
        ChannelEntity entity = ChannelEntity.fromDomain(channel);
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
    public Optional<Channel> find(UUID id) {
        return em.createQuery("SELECT e FROM Channel e WHERE e.id = ?1 AND e.tenancyId = ?2", ChannelEntity.class)
                            .setParameter(1, id).setParameter(2, currentPrincipal.tenancyId())
                            .getResultStream().findFirst()
                            .map(ChannelEntity::toDomain);
    }

    @Override
    public Optional<Channel> findByName(String name) {
        return em.createQuery("SELECT e FROM Channel e WHERE e.name = ?1 AND e.tenancyId = ?2", ChannelEntity.class)
                            .setParameter(1, name).setParameter(2, currentPrincipal.tenancyId())
                            .getResultStream().findFirst()
                            .map(ChannelEntity::toDomain);
    }

    @Override
    public List<Channel> scan(ChannelQuery q) {
        StringBuilder jpql   = new StringBuilder("FROM Channel e WHERE e.tenancyId = ?1");
        List<Object>  params = new ArrayList<>();
        params.add(currentPrincipal.tenancyId());
        int idx = 2;

        if (q.paused() != null) {
            jpql.append(" AND e.paused = ?").append(idx++);
            params.add(q.paused());
        }
        if (q.semantic() != null) {
            jpql.append(" AND e.semantic = ?").append(idx++);
            params.add(q.semantic());
        }
        if (q.namePattern() != null) {
            jpql.append(" AND e.name LIKE ?").append(idx++);
            params.add(q.namePattern().replace("*", "%"));
        }
        if (q.namePrefix() != null) {
            jpql.append(" AND e.name LIKE ?").append(idx++).append(" ESCAPE '!'");
            params.add(escapeLikePrefix(q.namePrefix()) + "%");
        }
        if (q.keyword() != null) {
            jpql.append(" AND (LOWER(e.name) LIKE ?").append(idx).append(" OR LOWER(e.description) LIKE ?").append(idx).append(")");
            params.add("%" + q.keyword().toLowerCase() + "%");
            idx++;
        }
        if (q.spaceId() != null) {
            jpql.append(" AND e.spaceId = ?").append(idx++);
            params.add(q.spaceId());
        }
        if (q.topLevelOnly()) {
            jpql.append(" AND e.spaceId IS NULL");
        }

        var query = em.createQuery("SELECT e " + jpql.toString(), ChannelEntity.class);
        for (int i = 0; i < params.size(); i++) query.setParameter(i + 1, params.get(i));
        List<ChannelEntity> entities = query.getResultList();
        return entities.stream().map(ChannelEntity::toDomain).toList();}

    @Override
    @Transactional
    public void delete(UUID id) {
        em.createQuery("DELETE FROM Channel e WHERE e.id = ?1 AND e.tenancyId = ?2")
                .setParameter(1, id).setParameter(2, currentPrincipal.tenancyId()).executeUpdate();
    }

    @Override
    @Transactional
    public void updateLastActivity(UUID channelId, String tenancyId) {
        em.createQuery("UPDATE Channel e SET e.lastActivityAt = ?1 WHERE e.id = ?2 AND e.tenancyId = ?3")
                             .setParameter(1, Instant.now()).setParameter(2, channelId).setParameter(3, tenancyId).executeUpdate();
        em.flush();
        ChannelEntity found = em.find(ChannelEntity.class, channelId);
        if (found != null) em.refresh(found);
    }

    @Override
    public void updateTrackDelivery(UUID channelId, Boolean trackDelivery) {
        em.createQuery("SELECT e FROM Channel e WHERE e.id = ?1", ChannelEntity.class)
                     .setParameter(1, channelId)
                     .getResultStream().findFirst()
                     .ifPresent(e -> e.trackDelivery = trackDelivery);
    }


    @Override
    public List<Channel> findByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<ChannelEntity> entities = em.createQuery("SELECT e FROM Channel e WHERE e.id IN ?1 AND e.tenancyId = ?2", ChannelEntity.class)
                .setParameter(1, new ArrayList<>(ids)).setParameter(2, currentPrincipal.tenancyId()).getResultList();
        return entities.stream().map(ChannelEntity::toDomain).toList();
    }

    @Override
    public boolean hasChannelsInSpace(UUID spaceId) {
        if (spaceId == null) {return false;}
        return em.createQuery("SELECT COUNT(e) FROM Channel e WHERE e.spaceId = ?1 AND e.tenancyId = ?2", Long.class)
                                   .setParameter(1, spaceId).setParameter(2, currentPrincipal.tenancyId()).getSingleResult() > 0;
    }


    private static String escapeLikePrefix(String prefix) {
        return prefix.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
