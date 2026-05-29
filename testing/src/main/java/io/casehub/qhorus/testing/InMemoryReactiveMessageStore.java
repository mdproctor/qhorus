package io.casehub.qhorus.testing;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.store.ReactiveMessageStore;
import io.casehub.qhorus.runtime.store.query.MessageQuery;
import io.smallrye.mutiny.Uni;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryReactiveMessageStore implements ReactiveMessageStore {

    /** Injected by CDI in @QuarkusTest; may be set directly in plain unit tests. */
    @Inject
    InMemoryMessageStore blocking = new InMemoryMessageStore();

    @Override
    public Uni<Message> put(Message message) {
        return Uni.createFrom().item(() -> {
            if (message.createdAt == null) {
                message.createdAt = Instant.now();
            }
            return blocking.put(message);
        });
    }

    @Override
    public Uni<Optional<Message>> find(Long id) {
        return Uni.createFrom().item(() -> blocking.find(id));
    }

    @Override
    public Uni<List<Message>> scan(MessageQuery query) {
        return Uni.createFrom().item(() -> blocking.scan(query));
    }

    @Override
    public Uni<Void> deleteAll(UUID channelId) {
        return Uni.createFrom().voidItem().invoke(() -> blocking.deleteAll(channelId));
    }

    @Override
    public Uni<Void> delete(Long id) {
        return Uni.createFrom().voidItem().invoke(() -> blocking.delete(id));
    }

    @Override
    public Uni<Integer> countByChannel(UUID channelId) {
        return Uni.createFrom().item(() -> blocking.countByChannel(channelId));
    }

    @Override
    public Uni<Long> count(MessageQuery q) {
        return Uni.createFrom().item(() -> blocking.count(q));
    }

    @Override
    public Uni<Map<UUID, Long>> countAllByChannel() {
        return Uni.createFrom().item(() -> blocking.countAllByChannel());
    }

    @Override
    public Uni<List<String>> distinctSendersByChannel(UUID channelId, MessageType excludedType) {
        return Uni.createFrom().item(() -> blocking.distinctSendersByChannel(channelId, excludedType));
    }

    @Override
    public Uni<Optional<Message>> findLastMessage(UUID channelId) {
        return Uni.createFrom().item(() -> blocking.findLastMessage(channelId));
    }

    public void clear() {
        blocking.clear();
    }
}
