package io.casehub.qhorus.runtime.message;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Converts the comma-separated artefact ref string stored in Message entities to List<UUID>. */
final class ArtefactRefParser {

    private ArtefactRefParser() {}

    static List<UUID> parse(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(UUID::fromString)
                .toList();
    }
}
