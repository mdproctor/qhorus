package io.casehub.qhorus.api.gateway;

import java.util.Set;

public record PostResult(Set<String> failedParticipantIds) {
    public static final PostResult ALL_DELIVERED = new PostResult(Set.of());

    public boolean hasFailures() {
        return !failedParticipantIds.isEmpty();
    }
}
