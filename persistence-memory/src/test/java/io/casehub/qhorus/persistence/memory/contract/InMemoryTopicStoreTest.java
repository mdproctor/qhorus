package io.casehub.qhorus.persistence.memory.contract;

import io.casehub.qhorus.api.store.TopicStore;
import io.casehub.qhorus.persistence.memory.InMemoryTopicStore;

public class InMemoryTopicStoreTest extends TopicStoreContractTest {

    private final InMemoryTopicStore store = new InMemoryTopicStore();

    @Override
    protected TopicStore store() {
        return store;
    }
}
