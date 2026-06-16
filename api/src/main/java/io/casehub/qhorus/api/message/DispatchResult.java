package io.casehub.qhorus.api.message;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DispatchResult(
        Long messageId,
        UUID channelId,
        String sender,
        MessageType type,
        String correlationId,
        Long inReplyTo,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<UUID> artefactRefs,
        String target,
        UUID ledgerEntryId,
        UUID subjectId,
        UUID causedByEntryId,
        int parentReplyCount,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> advisories
) {
    public DispatchResult {
        artefactRefs = artefactRefs == null ? List.of() : List.copyOf(artefactRefs);
        advisories   = advisories   == null ? List.of() : List.copyOf(advisories);
    }
}
