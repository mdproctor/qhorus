package io.casehub.qhorus.persistence.memory;

import io.casehub.qhorus.api.store.ChannelBindingStore;
import io.casehub.qhorus.persistence.memory.contract.ChannelBindingStoreContractTest;

class InMemoryChannelBindingStoreTest extends ChannelBindingStoreContractTest {

    private final InMemoryChannelBindingStore store = new InMemoryChannelBindingStore();

    @Override
    protected ChannelBindingStore store() {
        return store;
    }

    @Override
    protected void reset() {
        store.clear();
    }
}
