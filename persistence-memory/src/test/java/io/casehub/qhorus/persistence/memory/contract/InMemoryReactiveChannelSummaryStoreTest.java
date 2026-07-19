package io.casehub.qhorus.persistence.memory.contract;

import io.casehub.qhorus.api.channel.ChannelSummary;
import io.casehub.qhorus.persistence.memory.InMemoryReactiveChannelSummaryStore;

import java.util.Optional;
import java.util.UUID;

class InMemoryReactiveChannelSummaryStoreTest extends ChannelSummaryStoreContractTest {

    private final InMemoryReactiveChannelSummaryStore store = new InMemoryReactiveChannelSummaryStore();

    @Override
    protected ChannelSummary save(ChannelSummary s)               {return store.save(s).await().indefinitely();}

    @Override
    protected Optional<ChannelSummary> findByChannelId(UUID chId) {return store.findByChannelId(chId).await().indefinitely();}

    @Override
    protected void deleteByChannelId(UUID chId)                   {store.deleteByChannelId(chId).await().indefinitely();}

    @Override
    protected void reset()                                        { /* internal delegate resets per instance */ }
}
