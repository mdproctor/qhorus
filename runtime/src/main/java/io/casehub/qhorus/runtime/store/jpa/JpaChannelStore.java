package io.casehub.qhorus.runtime.store.jpa;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.query.ChannelQuery;
import io.casehub.qhorus.runtime.channel.ChannelEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

    @Override
    @Transactional
    public Channel put(Channel channel) {
        ChannelEntity entity = ChannelEntity.fromDomain(channel);
        if (entity.id != null) {
            entity = ChannelEntity.getEntityManager().merge(entity);
            ChannelEntity.flush();
        } else {
            entity.persistAndFlush();
        }
        return entity.toDomain();
    }

    @Override
    public Optional<Channel> find(UUID id) {
        return ChannelEntity.<ChannelEntity>find("id = ?1 AND tenancyId = ?2", id, currentPrincipal.tenancyId())
                            .<ChannelEntity>firstResultOptional()
                            .map(ChannelEntity::toDomain);
    }

    @Override
    public Optional<Channel> findByName(String name) {
        return ChannelEntity.<ChannelEntity>find("name = ?1 AND tenancyId = ?2", name, currentPrincipal.tenancyId())
                            .<ChannelEntity>firstResultOptional()
                            .map(ChannelEntity::toDomain);
    }

    @Override
    public List<Channel> scan(ChannelQuery q) {
        StringBuilder jpql   = new StringBuilder("FROM Channel WHERE tenancyId = ?1");
        List<Object>  params = new ArrayList<>();
        params.add(currentPrincipal.tenancyId());
        int idx = 2;

        if (q.paused() != null) {
            jpql.append(" AND paused = ?").append(idx++);
            params.add(q.paused());
        }
        if (q.semantic() != null) {
            jpql.append(" AND semantic = ?").append(idx++);
            params.add(q.semantic());
        }
        if (q.namePattern() != null) {
            jpql.append(" AND name LIKE ?").append(idx++);
            params.add(q.namePattern().replace("*", "%"));
        }
        if (q.namePrefix() != null) {
            jpql.append(" AND name LIKE ?").append(idx++).append(" ESCAPE '!'");
            params.add(escapeLikePrefix(q.namePrefix()) + "%");
        }
        if (q.keyword() != null) {
            jpql.append(" AND (LOWER(name) LIKE ?").append(idx).append(" OR LOWER(description) LIKE ?").append(idx).append(")");
            params.add("%" + q.keyword().toLowerCase() + "%");
            idx++;
        }
        if (q.spaceId() != null) {
            jpql.append(" AND spaceId = ?").append(idx++);
            params.add(q.spaceId());
        }
        if (q.topLevelOnly()) {
            jpql.append(" AND spaceId IS NULL");
        }

        List<ChannelEntity> entities = ChannelEntity.list(jpql.toString(), params.toArray());
        return entities.stream().map(ChannelEntity::toDomain).toList();}

    @Override
    @Transactional
    public void delete(UUID id) {
        ChannelEntity.delete("id = ?1 AND tenancyId = ?2", id, currentPrincipal.tenancyId());
    }

    @Override
    @Transactional
    public void updateLastActivity(UUID channelId, String tenancyId) {
        ChannelEntity.update("lastActivityAt = ?1 WHERE id = ?2 AND tenancyId = ?3",
                             Instant.now(), channelId, tenancyId);
        ChannelEntity.getEntityManager().flush();
        ChannelEntity.<ChannelEntity>findByIdOptional(channelId)
                .ifPresent(e -> ChannelEntity.getEntityManager().refresh(e));
    }

    @Override
    public void updateTrackDelivery(UUID channelId, Boolean trackDelivery) {
        ChannelEntity.<ChannelEntity>find("id", channelId)
                     .firstResultOptional()
                     .ifPresent(e -> e.trackDelivery = trackDelivery);
    }


    @Override
    public List<Channel> findByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<ChannelEntity> entities = ChannelEntity.list("id IN ?1 AND tenancyId = ?2", new ArrayList<>(ids), currentPrincipal.tenancyId());
        return entities.stream().map(ChannelEntity::toDomain).toList();
    }

    @Override
    public boolean hasChannelsInSpace(UUID spaceId) {
        if (spaceId == null) {return false;}
        return ChannelEntity.count("spaceId = ?1 AND tenancyId = ?2",
                                   spaceId, currentPrincipal.tenancyId()) > 0;
    }


    private static String escapeLikePrefix(String prefix) {
        return prefix.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
