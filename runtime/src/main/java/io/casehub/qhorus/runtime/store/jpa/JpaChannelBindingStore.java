package io.casehub.qhorus.runtime.store.jpa;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import io.casehub.qhorus.api.channel.ChannelConnectorBinding;
import io.casehub.qhorus.runtime.channel.ChannelConnectorBindingEntity;
import io.casehub.qhorus.api.store.ChannelBindingStore;

@ApplicationScoped
public class JpaChannelBindingStore implements ChannelBindingStore {

    @Override
    public Optional<ChannelConnectorBinding> findByChannelId(UUID channelId) {
        return ChannelConnectorBindingEntity.<ChannelConnectorBindingEntity>findByIdOptional(channelId)
                .map(ChannelConnectorBindingEntity::toDomain);
    }

    @Override
    public Optional<ChannelConnectorBinding> findByKey(String inboundConnectorId, String externalKey) {
        return ChannelConnectorBindingEntity
                .<ChannelConnectorBindingEntity>find("inboundConnectorId = ?1 AND externalKey = ?2",
                        inboundConnectorId, externalKey)
                .<ChannelConnectorBindingEntity>firstResultOptional()
                .map(ChannelConnectorBindingEntity::toDomain);
    }

    @Override
    @Transactional
    public void put(ChannelConnectorBinding binding) {
        ChannelConnectorBindingEntity entity = ChannelConnectorBindingEntity.fromDomain(binding);
        if (entity.channelId != null) {
            entity = ChannelConnectorBindingEntity.getEntityManager().merge(entity);
            ChannelConnectorBindingEntity.flush();
        } else {
            entity.persistAndFlush();
        }
    }

    @Override
    @Transactional
    public void delete(UUID channelId) {
        ChannelConnectorBindingEntity.deleteById(channelId);
    }

    @Override
    public Map<UUID, ChannelConnectorBinding> findAll() {
        return ChannelConnectorBindingEntity.<ChannelConnectorBindingEntity>listAll().stream()
                .collect(Collectors.toUnmodifiableMap(b -> b.channelId, b -> b.toDomain()));
    }
}
