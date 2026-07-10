package io.casehub.qhorus.api.store;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.casehub.qhorus.api.message.Reaction;

public interface ReactionStore {
    Reaction react(Long messageId, String emoji, String actorId, String tenancyId);
    boolean unreact(Long messageId, String emoji, String actorId);
    List<Reaction> findByMessage(Long messageId);
    Map<Long, List<Reaction>> findByMessages(Collection<Long> messageIds);
    void deleteByMessage(Long messageId);
    void deleteByChannel(UUID channelId);
}
