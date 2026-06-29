package io.casehub.qhorus.testing.contract;

import io.casehub.qhorus.runtime.store.DeliveryCursorStore;
import io.casehub.qhorus.testing.InMemoryDeliveryCursorStore;
import org.junit.jupiter.api.BeforeEach;

class InMemoryDeliveryCursorStoreTest extends DeliveryCursorStoreContractTest {

    private final InMemoryDeliveryCursorStore store = new InMemoryDeliveryCursorStore();

    @Override
    protected DeliveryCursorStore store() { return store; }

    @Override
    @BeforeEach
    void clearStore() { store.clear(); }
}
