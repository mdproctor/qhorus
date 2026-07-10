package io.casehub.qhorus.api.message;

public record ReactionChangedEvent(
        Long messageId,
        String emoji,
        String actorId,
        boolean added) {}
