package io.casehub.qhorus.persistence.memory.contract;

import io.casehub.qhorus.api.store.ReactionStore;
import io.casehub.qhorus.persistence.memory.InMemoryReactionStore;

public class InMemoryReactionStoreTest extends ReactionStoreContractTest {

    private final InMemoryReactionStore store = new InMemoryReactionStore();

    @Override
    protected ReactionStore store() {
        return store;
    }
}
