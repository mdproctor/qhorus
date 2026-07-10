package io.casehub.qhorus.api.store;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.casehub.qhorus.api.message.Reaction;
import io.smallrye.mutiny.Uni;

public interface ReactiveReactionStore {
    Uni<Reaction> react(Long messageId, String emoji, String actorId, String tenancyId);
    Uni<Boolean> unreact(Long messageId, String emoji, String actorId);
    Uni<List<Reaction>> findByMessage(Long messageId);
    Uni<Map<Long, List<Reaction>>> findByMessages(Collection<Long> messageIds);
    Uni<Void> deleteByMessage(Long messageId);
    Uni<Void> deleteByChannel(UUID channelId);
}
