package io.casehub.qhorus.runtime.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.MessageType;

class MessageTypeParseTypesTest {

    @Test
    void parseTypesNullReturnsEmpty() {
        assertThat(MessageType.parseTypes(null)).isEmpty();
    }

    @Test
    void parseTypesBlankReturnsEmpty() {
        assertThat(MessageType.parseTypes("")).isEmpty();
        assertThat(MessageType.parseTypes("   ")).isEmpty();
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
}
