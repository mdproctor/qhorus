package io.casehub.qhorus.runtime.store.jpa;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import io.casehub.qhorus.runtime.channel.ChannelConnectorBinding;
import io.casehub.qhorus.runtime.store.ChannelBindingStore;

@ApplicationScoped
public class JpaChannelBindingStore implements ChannelBindingStore {

    @Override
    public Optional<ChannelConnectorBinding> findByChannelId(UUID channelId) {
        return ChannelConnectorBinding.findByIdOptional(channelId);
    }

    @Override
    public Optional<ChannelConnectorBinding> findByKey(String inboundConnectorId, String externalKey) {
        return ChannelConnectorBinding
                .find("inboundConnectorId = ?1 AND externalKey = ?2",
                        inboundConnectorId, externalKey)
                .firstResultOptional();
    }

    @Override
    @Transactional
    public void put(ChannelConnectorBinding binding) {
        binding.persistAndFlush();
    }

    @Override
    @Transactional
    public void delete(UUID channelId) {
        ChannelConnectorBinding.deleteById(channelId);
    }

    @Override
    public Map<UUID, ChannelConnectorBinding> findAll() {
        return ChannelConnectorBinding.<ChannelConnectorBinding>listAll().stream()
                .collect(Collectors.toMap(b -> b.channelId, b -> b));
    }
}
