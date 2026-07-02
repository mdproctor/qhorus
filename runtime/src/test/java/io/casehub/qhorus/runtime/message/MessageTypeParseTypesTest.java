package io.casehub.qhorus.runtime.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.MessageType;

class MessageTypeParseTypesTest {

    @Test
    void parseTypesNullReturnsNull() {
        assertThat(MessageType.parseTypes(null)).isNull();
    }

    @Test
    void parseTypesBlankReturnsNull() {
        assertThat(MessageType.parseTypes("")).isNull();
        assertThat(MessageType.parseTypes("   ")).isNull();
    }

    @Test
    void parseTypesSingleType() {
        assertThat(MessageType.parseTypes("EVENT")).isEqualTo(Set.of(MessageType.EVENT));
    }

    @Test
    void parseTypesMultipleTypes() {
        assertThat(MessageType.parseTypes("QUERY,COMMAND"))
                .containsExactlyInAnyOrder(MessageType.QUERY, MessageType.COMMAND);
    }

    @Test
    void parseTypesTrimsWhitespace() {
        assertThat(MessageType.parseTypes(" EVENT , COMMAND "))
                .containsExactlyInAnyOrder(MessageType.EVENT, MessageType.COMMAND);
    }

    @Test
    void parseTypesThrowsOnInvalidName() {
        assertThatThrownBy(() -> MessageType.parseTypes("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseTypesThrowsOnMixedValidAndInvalid() {
        assertThatThrownBy(() -> MessageType.parseTypes("EVENT,INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseTypesResultIsImmutable() {
        Set<MessageType> result = MessageType.parseTypes("EVENT");
        assertThatThrownBy(() -> result.add(MessageType.COMMAND))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── serializeTypes ────────────────────────────────────────────────────────

    @Test
    void serializeTypes_null_returnsNull() {
        assertThat(MessageType.serializeTypes(null)).isNull();
    }

    @Test
    void serializeTypes_emptySet_returnsNull() {
        assertThat(MessageType.serializeTypes(Set.of())).isNull();
    }

    @Test
    void serializeTypes_singleType_returnsName() {
        assertThat(MessageType.serializeTypes(Set.of(MessageType.EVENT))).isEqualTo("EVENT");
    }

    @Test
    void serializeTypes_multipleTypes_returnsSortedCommaSeparated() {
        // COMMAND < QUERY alphabetically
        assertThat(MessageType.serializeTypes(Set.of(MessageType.QUERY, MessageType.COMMAND)))
                .isEqualTo("COMMAND,QUERY");
    }

    @Test
    void serializeTypes_unsortedInput_producesCanonicalSortedOutput() {
        // Regardless of iteration order, output is always sorted
        assertThat(MessageType.serializeTypes(Set.of(MessageType.RESPONSE, MessageType.COMMAND)))
                .isEqualTo("COMMAND,RESPONSE");
    }

    @Test
    void serializeTypes_isInverseOfParseTypes() {
        Set<MessageType> types = Set.of(MessageType.QUERY, MessageType.EVENT, MessageType.COMMAND);
        assertThat(MessageType.parseTypes(MessageType.serializeTypes(types)))
                .isEqualTo(types);
    }
}
