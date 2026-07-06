package io.casehub.qhorus.persistence.memory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.CrossTenantMessageStore;
import io.casehub.qhorus.api.store.query.MessageQuery;

/**
 * In-memory implementation of {@link CrossTenantMessageStore} for use in {@code @QuarkusTest} contexts.
 * Returns all messages with no tenant filter — delegates to {@link InMemoryMessageStore}.
 *
 * <p>Refs #260.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryCrossTenantMessageStore implements CrossTenantMessageStore {

    @Inject
    InMemoryMessageStore delegate;

    @Override
    public List<Message> scan(MessageQuery query) {
        return delegate.scan(query);
    }

    @Override
    public long count(MessageQuery query) {
        return delegate.count(query);
    }

    @Override
    public int countByChannel(UUID channelId) {
        return delegate.countByChannel(channelId);
    }

    @Override
    public List<String> distinctSendersByChannel(UUID channelId, MessageType excludedType) {
        return delegate.distinctSendersByChannel(channelId, excludedType);
    }

    @Override
    public Optional<Message> findLastMessage(UUID channelId) {
        return delegate.findLastMessage(channelId);
    }

    @Override
    public Optional<Message> find(Long id) {
        return delegate.find(id);
    }
}
