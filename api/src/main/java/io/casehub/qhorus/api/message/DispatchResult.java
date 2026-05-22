package io.casehub.qhorus.api.message;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public record DispatchResult(
        Long messageId,
        UUID channelId,
        String sender,
        MessageType type,
        String correlationId,
        Long inReplyTo,
        List<UUID> artefactRefs,
        String target,
        UUID ledgerEntryId,    // null when ledger writes suppressed
        UUID subjectId,        // resolved value actually written to ledger
        UUID causedByEntryId   // resolved value actually written to ledger
) {
    public DispatchResult {
        artefactRefs = (artefactRefs == null) ? List.of() : List.copyOf(artefactRefs);
    }

    /** Parse a comma-separated artefact refs string (from Message entity) into List<UUID>. */
    public static List<UUID> parseArtefactRefs(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(UUID::fromString)
                .toList();
    }
}
