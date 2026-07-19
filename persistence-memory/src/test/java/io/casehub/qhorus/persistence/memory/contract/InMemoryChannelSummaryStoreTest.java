package io.casehub.qhorus.persistence.memory.contract;

import io.casehub.qhorus.api.channel.ChannelSummary;
import io.casehub.qhorus.persistence.memory.InMemoryChannelSummaryStore;

import java.util.Optional;
import java.util.UUID;

class InMemoryChannelSummaryStoreTest extends ChannelSummaryStoreContractTest {

    private final InMemoryChannelSummaryStore store = new InMemoryChannelSummaryStore();

    @Override protected ChannelSummary save(ChannelSummary s) { return store.save(s); }
    @Override protected Optional<ChannelSummary> findByChannelId(UUID chId) { return store.findByChannelId(chId); }
    @Override protected void deleteByChannelId(UUID chId) { store.deleteByChannelId(chId); }
    @Override protected void reset() { store.clear(); }
}
