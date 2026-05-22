package io.casehub.qhorus.runtime.message;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArtefactRefParserTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test void null_raw_returns_empty() {
        assertThat(ArtefactRefParser.parse(null)).isEmpty();
    }

    @Test void blank_raw_returns_empty() {
        assertThat(ArtefactRefParser.parse("   ")).isEmpty();
    }

    @Test void single_uuid_parsed() {
        assertThat(ArtefactRefParser.parse(A.toString())).containsExactly(A);
    }

    @Test void two_uuids_parsed() {
        assertThat(ArtefactRefParser.parse(A + "," + B)).containsExactly(A, B);
    }

    @Test void whitespace_around_uuids_trimmed() {
        assertThat(ArtefactRefParser.parse("  " + A + " , " + B + "  ")).containsExactly(A, B);
    }
}
