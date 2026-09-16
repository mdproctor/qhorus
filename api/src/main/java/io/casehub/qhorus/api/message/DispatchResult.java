package io.casehub.qhorus.api.message;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DispatchResult(
        Long messageId,
        UUID channelId,
        String sender,
        MessageType type,
        String correlationId,
        Long inReplyTo,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ArtefactRef> artefactRefs,
        String target,
        UUID ledgerEntryId,
        UUID subjectId,
        UUID causedByEntryId,
        int parentReplyCount,
        Long correctsMessageId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> advisories
) {
    public DispatchResult {
        artefactRefs = artefactRefs == null ? List.of() : List.copyOf(artefactRefs);
        advisories   = advisories == null ? List.of() : List.copyOf(advisories);
    }

    public DispatchResult(Long messageId, UUID channelId, String sender, MessageType type,
                          String correlationId, Long inReplyTo, List<ArtefactRef> artefactRefs,
                          String target, UUID ledgerEntryId, UUID subjectId, UUID causedByEntryId,
                          int parentReplyCount, List<String> advisories) {
        this(messageId, channelId, sender, type, correlationId, inReplyTo, artefactRefs,
             target, ledgerEntryId, subjectId, causedByEntryId, parentReplyCount, null, advisories);
    }
}
