package io.casehub.qhorus.runtime.store.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelEntity;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;

@ApplicationScoped
public class JpaCrossTenantChannelStore implements CrossTenantChannelStore {

    @Override
    public List<Channel> listAll() {
        return ChannelEntity.<ChannelEntity>listAll()
                .stream().map(ChannelEntity::toDomain).toList();
    }

    @Override
    public Optional<Channel> findById(UUID id) {
        return ChannelEntity.<ChannelEntity>findByIdOptional(id)
                .map(ChannelEntity::toDomain);
    }

    @Override
    public Optional<Channel> findByNameAndTenancy(String name, String tenancyId) {
        return ChannelEntity.<ChannelEntity>find("name = ?1 AND tenancyId = ?2", name, tenancyId)
                .<ChannelEntity>firstResultOptional()
                .map(ChannelEntity::toDomain);
    }
}
