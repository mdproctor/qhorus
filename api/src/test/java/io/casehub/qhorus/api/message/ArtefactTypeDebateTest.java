package io.casehub.qhorus.api.message;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ArtefactTypeDebateTest {

    @Test
    void debateEnumExists() {
        ArtefactType debate = ArtefactType.valueOf("DEBATE");
        assertThat(debate).isNotNull();
        assertThat(debate.name()).isEqualTo("DEBATE");
    }

    @Test
    void allExpectedValuesPresent() {
        assertThat(ArtefactType.values()).extracting(ArtefactType::name)
                .contains("DOCUMENT", "CODE", "CASE", "WORK_ITEM", "CHANNEL", "MESSAGE", "EXTERNAL", "DEBATE");
    }
}
