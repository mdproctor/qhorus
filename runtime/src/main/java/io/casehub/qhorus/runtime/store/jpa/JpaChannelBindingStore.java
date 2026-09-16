package io.casehub.qhorus.runtime.store.jpa;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;

import io.casehub.qhorus.api.channel.ChannelConnectorBinding;
import io.casehub.qhorus.runtime.channel.ChannelConnectorBindingEntity;
import io.casehub.qhorus.api.store.ChannelBindingStore;

@ApplicationScoped
public class JpaChannelBindingStore implements ChannelBindingStore {

    @Inject
    @io.quarkus.hibernate.orm.PersistenceUnit("qhorus")
    EntityManager em;

    @Override
    public Optional<ChannelConnectorBinding> findByChannelId(UUID channelId) {
        return Optional.ofNullable(em.find(ChannelConnectorBindingEntity.class, channelId))
                .map(ChannelConnectorBindingEntity::toDomain);
    }

    @Override
    public Optional<ChannelConnectorBinding> findByKey(String inboundConnectorId, String externalKey) {
        return em.createQuery("SELECT e FROM ChannelConnectorBindingEntity e WHERE e.inboundConnectorId = ?1 AND e.externalKey = ?2", ChannelConnectorBindingEntity.class)
                .setParameter(1, inboundConnectorId).setParameter(2, externalKey)
                .getResultStream().findFirst()
                .map(ChannelConnectorBindingEntity::toDomain);
    }

    @Override
    @Transactional
    public void put(ChannelConnectorBinding binding) {
        ChannelConnectorBindingEntity entity = ChannelConnectorBindingEntity.fromDomain(binding);
        if (entity.channelId != null) {
            entity = em.merge(entity);
            em.flush();
        } else {
            em.persist(entity);
            em.flush();
        }
    }

    @Override
    @Transactional
    public Optional<ChannelConnectorBinding> putIfAbsent(ChannelConnectorBinding binding) {
        Optional<ChannelConnectorBinding> existing = findByKey(binding.inboundConnectorId(), binding.externalKey());
        if (existing.isPresent()) {
            return existing;
        }
        try {
            ChannelConnectorBindingEntity entity = ChannelConnectorBindingEntity.fromDomain(binding);
            em.persist(entity);
            em.flush();
            return Optional.empty();
        } catch (PersistenceException ex) {
            em.clear();
            return findByKey(binding.inboundConnectorId(), binding.externalKey());
        }
    }

    @Override
    @Transactional
    public void delete(UUID channelId) {
        em.createQuery("DELETE FROM ChannelConnectorBindingEntity e WHERE e.channelId = ?1").setParameter(1, channelId).executeUpdate();
    }

    @Override
    public Map<UUID, ChannelConnectorBinding> findAll() {
        return em.createQuery("SELECT e FROM ChannelConnectorBindingEntity e", ChannelConnectorBindingEntity.class)
                .getResultList().stream()
                .collect(Collectors.toUnmodifiableMap(b -> b.channelId, b -> b.toDomain()));
    }
}
