package io.casehub.qhorus.runtime.store.jpa;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.message.Reaction;
import io.casehub.qhorus.api.store.ReactionStore;
import io.casehub.qhorus.runtime.message.ReactionEntity;

@ApplicationScoped
public class JpaReactionStore implements ReactionStore {

    @Inject
    CurrentPrincipal currentPrincipal;

    @Override
    public Reaction react(Long messageId, String emoji, String actorId, String tenancyId) {
        String tid = tenancyId != null ? tenancyId : currentPrincipal.tenancyId();
        Optional<ReactionEntity> existing = ReactionEntity.<ReactionEntity>find(
                "messageId = ?1 AND emoji = ?2 AND actorId = ?3",
                messageId, emoji, actorId).firstResultOptional();
        if (existing.isPresent()) {
            return existing.get().toDomain();
        }
        ReactionEntity e = new ReactionEntity();
        e.messageId = messageId;
        e.emoji = emoji;
        e.actorId = actorId;
        e.tenancyId = tid;
        e.persist();
        return e.toDomain();
    }

    @Override
    public boolean unreact(Long messageId, String emoji, String actorId) {
        return ReactionEntity.delete("messageId = ?1 AND emoji = ?2 AND actorId = ?3",
                messageId, emoji, actorId) > 0;
    }

    @Override
    public List<Reaction> findByMessage(Long messageId) {
        return ReactionEntity.<ReactionEntity>find("messageId = ?1", messageId)
                .list().stream().map(ReactionEntity::toDomain).toList();
    }

    @Override
    public Map<Long, List<Reaction>> findByMessages(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) return Map.of();
        List<ReactionEntity> entities = ReactionEntity.find("messageId IN ?1", List.copyOf(messageIds)).list();
        Map<Long, List<Reaction>> result = new HashMap<>();
        for (Long id : messageIds) {
            result.put(id, entities.stream()
                    .filter(e -> e.messageId.equals(id))
                    .map(ReactionEntity::toDomain)
                    .toList());
        }
        return result;
    }

    @Override
    public void deleteByMessage(Long messageId) {
        ReactionEntity.delete("messageId = ?1", messageId);
    }

    @Override
    public void deleteByChannel(UUID channelId) {
        ReactionEntity.delete("messageId IN (SELECT m.id FROM Message m WHERE m.channelId = ?1)", channelId);
    }
}
