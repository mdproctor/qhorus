package io.casehub.qhorus.testing;

import io.casehub.qhorus.runtime.store.ChannelBindingStore;
import io.casehub.qhorus.testing.contract.ChannelBindingStoreContractTest;

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
