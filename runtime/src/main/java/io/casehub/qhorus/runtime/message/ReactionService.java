package io.casehub.qhorus.runtime.message;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.message.Reaction;
import io.casehub.qhorus.api.message.ReactionChangedEvent;
import io.casehub.qhorus.api.message.ReactionGroup;
import io.casehub.qhorus.api.store.ReactionStore;

@ApplicationScoped
public class ReactionService {

    @Inject
    public ReactionStore reactionStore;

    @Inject
    public Event<ReactionChangedEvent> reactionEvent;

    public Reaction react(Long messageId, String emoji, String actorId, String tenancyId) {
        String trimmed = validateEmoji(emoji);
        Reaction r = reactionStore.react(messageId, trimmed, actorId, tenancyId);
        if (reactionEvent != null) {
            reactionEvent.fireAsync(new ReactionChangedEvent(messageId, trimmed, actorId, true));
        }
        return r;
    }

    public boolean unreact(Long messageId, String emoji, String actorId) {
        String trimmed = validateEmoji(emoji);
        boolean removed = reactionStore.unreact(messageId, trimmed, actorId);
        if (removed && reactionEvent != null) {
            reactionEvent.fireAsync(new ReactionChangedEvent(messageId, trimmed, actorId, false));
        }
        return removed;
    }

    public List<ReactionGroup> getReactions(Long messageId) {
        return groupReactions(reactionStore.findByMessage(messageId));
    }

    public Map<Long, List<ReactionGroup>> getReactionsBatch(Collection<Long> messageIds) {
        Map<Long, List<Reaction>> raw = reactionStore.findByMessages(messageIds);
        return raw.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> groupReactions(e.getValue())));
    }

    private static List<ReactionGroup> groupReactions(List<Reaction> reactions) {
        return reactions.stream()
                .collect(Collectors.groupingBy(Reaction::emoji))
                .entrySet().stream()
                .map(e -> new ReactionGroup(
                        e.getKey(),
                        e.getValue().size(),
                        e.getValue().stream().map(Reaction::actorId).toList()))
                .toList();
    }

    private static String validateEmoji(String emoji) {
        if (emoji == null || emoji.isBlank()) {
            throw new IllegalArgumentException("emoji is required");
        }
        return emoji.strip();
    }
}
