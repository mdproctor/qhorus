package io.casehub.qhorus.persistence.memory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.casehub.qhorus.api.message.Reaction;
import io.casehub.qhorus.api.store.ReactiveReactionStore;
import io.smallrye.mutiny.Uni;

@ApplicationScoped
@Alternative
@Priority(1)
public class InMemoryReactiveReactionStore implements ReactiveReactionStore {

    private final InMemoryReactionStore delegate = new InMemoryReactionStore();

    @Override public Uni<Reaction> react(Long messageId, String emoji, String actorId, String tenancyId) { return Uni.createFrom().item(delegate.react(messageId, emoji, actorId, tenancyId)); }
    @Override public Uni<Boolean> unreact(Long messageId, String emoji, String actorId) { return Uni.createFrom().item(delegate.unreact(messageId, emoji, actorId)); }
    @Override public Uni<List<Reaction>> findByMessage(Long messageId) { return Uni.createFrom().item(delegate.findByMessage(messageId)); }
    @Override public Uni<Map<Long, List<Reaction>>> findByMessages(Collection<Long> messageIds) { return Uni.createFrom().item(delegate.findByMessages(messageIds)); }
    @Override public Uni<Void> deleteByMessage(Long messageId) { delegate.deleteByMessage(messageId); return Uni.createFrom().voidItem(); }
    @Override public Uni<Void> deleteByChannel(UUID channelId) { delegate.deleteByChannel(channelId); return Uni.createFrom().voidItem(); }
}
