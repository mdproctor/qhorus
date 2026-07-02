package io.casehub.qhorus.runtime.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;

class ChannelCreateRequestTest {

    // ------------------------------------------------------------------
    // Valid construction — typed Set<MessageType> fields
    // ------------------------------------------------------------------

    @Test
    void allowedAndDeniedWithNoOverlapConstructsSuccessfully() {
        final io.casehub.qhorus.api.channel.ChannelCreateRequest req = new io.casehub.qhorus.api.channel.ChannelCreateRequest(
                "ch", null, ChannelSemantic.APPEND, null, null, null, null, null,
                Set.of(MessageType.QUERY, MessageType.COMMAND), Set.of(MessageType.EVENT),
                null, null, null, null);
        assertThat(req.allowedTypes()).containsExactlyInAnyOrder(MessageType.QUERY, MessageType.COMMAND);
        assertThat(req.deniedTypes()).containsExactly(MessageType.EVENT);
    }

    @Test
    void deniedOnlyWithNullAllowedConstructsSuccessfully() {
        final io.casehub.qhorus.api.channel.ChannelCreateRequest req = new io.casehub.qhorus.api.channel.ChannelCreateRequest(
                "ch", null, ChannelSemantic.APPEND, null, null, null, null, null,
                null, Set.of(MessageType.EVENT),
                null, null, null, null);
        assertThat(req.deniedTypes()).containsExactly(MessageType.EVENT);
    }

    @Test
    void allowedOnlyWithNullDeniedConstructsSuccessfully() {
        final io.casehub.qhorus.api.channel.ChannelCreateRequest req = new io.casehub.qhorus.api.channel.ChannelCreateRequest(
                "ch", null, ChannelSemantic.APPEND, null, null, null, null, null,
                Set.of(MessageType.QUERY), null,
                null, null, null, null);
        assertThat(req.allowedTypes()).containsExactly(MessageType.QUERY);
    }

    @Test
    void bothNullConstructsSuccessfully() {
        final io.casehub.qhorus.api.channel.ChannelCreateRequest req = new io.casehub.qhorus.api.channel.ChannelCreateRequest(
                "ch", null, ChannelSemantic.APPEND, null, null, null, null, null,
                null, null,
                null, null, null, null);
        assertThat(req.allowedTypes()).isNull();
        assertThat(req.deniedTypes()).isNull();
    }

    @Test
    void simpleFactoryHasNullDeniedTypes() {
        final io.casehub.qhorus.api.channel.ChannelCreateRequest req = io.casehub.qhorus.api.channel.ChannelCreateRequest.builder("ch").build();
        assertThat(req.deniedTypes()).isNull();
    }

    @Test
    void defensiveCopy_callerMutationDoesNotAffectRecord() {
        final Set<MessageType> mutable = new HashSet<>();
        mutable.add(MessageType.QUERY);
        final io.casehub.qhorus.api.channel.ChannelCreateRequest req = new io.casehub.qhorus.api.channel.ChannelCreateRequest(
                "ch", null, ChannelSemantic.APPEND, null, null, null, null, null,
                mutable, null, null, null, null, null);

        mutable.add(MessageType.COMMAND); // mutate after construction

        // Record must still reflect the state at construction time
        assertThat(req.allowedTypes()).containsExactly(MessageType.QUERY);
    }

    // ------------------------------------------------------------------
    // Overlap rejection
    // ------------------------------------------------------------------

    @Test
    void overlapBetweenAllowedAndDeniedThrows() {
        assertThatThrownBy(() -> new io.casehub.qhorus.api.channel.ChannelCreateRequest(
                "ch", null, ChannelSemantic.APPEND, null, null, null, null, null,
                Set.of(MessageType.QUERY, MessageType.COMMAND), Set.of(MessageType.QUERY),
                null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QUERY");
    }

    @Test
    void sameTypeBothSidesThrows() {
        assertThatThrownBy(() -> new io.casehub.qhorus.api.channel.ChannelCreateRequest(
                "ch", null, ChannelSemantic.APPEND, null, null, null, null, null,
                Set.of(MessageType.EVENT), Set.of(MessageType.EVENT),
                null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EVENT");
    }

    // ------------------------------------------------------------------
    // Connector binding invariant (pre-existing, must still pass)
    // ------------------------------------------------------------------

    @Test
    void partialConnectorBindingThrows() {
        assertThatThrownBy(() -> new io.casehub.qhorus.api.channel.ChannelCreateRequest(
                "ch", null, ChannelSemantic.APPEND, null, null, null, null, null,
                null, null,
                "connector-id", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Connector binding");
    }
}
