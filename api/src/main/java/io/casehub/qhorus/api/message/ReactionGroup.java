package io.casehub.qhorus.api.message;

import java.util.List;

public record ReactionGroup(
        String emoji,
        int count,
        List<String> actorIds) {

    public ReactionGroup {
        actorIds = actorIds != null ? List.copyOf(actorIds) : List.of();
    }
}
