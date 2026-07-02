package io.casehub.qhorus.persistence.memory.contract;

import io.casehub.qhorus.api.store.DeliveryCursorStore;
import io.casehub.qhorus.persistence.memory.InMemoryDeliveryCursorStore;
import org.junit.jupiter.api.BeforeEach;

class InMemoryDeliveryCursorStoreTest extends DeliveryCursorStoreContractTest {

    private final InMemoryDeliveryCursorStore store = new InMemoryDeliveryCursorStore();

    @Override
    protected DeliveryCursorStore store() { return store; }

    @Override
    @BeforeEach
    void clearStore() { store.clear(); }
}
