package io.casehub.qhorus.runtime.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.ChannelSemantic;

class ChannelCreateRequestTest {

    // ------------------------------------------------------------------
    // Valid construction
    // ------------------------------------------------------------------

    @Test
    void allowedAndDeniedWithNoOverlapConstructsSuccessfully() {
        ChannelCreateRequest req = new ChannelCreateRequest(
                "ch", null, ChannelSemantic.APPEND, null, null, null, null, null,
                "QUERY,COMMAND", "EVENT",
                null, null, null, null);
        assertThat(req.allowedTypes()).isEqualTo("QUERY,COMMAND");
        assertThat(req.deniedTypes()).isEqualTo("EVENT");
    }

    @Test
    void deniedOnlyWithNullAllowedConstructsSuccessfully() {
        ChannelCreateRequest req = new ChannelCreateRequest(
                "ch", null, ChannelSemantic.APPEND, null, null, null, null, null,
                null, "EVENT",
                null, null, null, null);
        assertThat(req.deniedTypes()).isEqualTo("EVENT");
    }

    @Test
    void allowedOnlyWithNullDeniedConstructsSuccessfully() {
        ChannelCreateRequest req = new ChannelCreateRequest(
                "ch", null, ChannelSemantic.APPEND, null, null, null, null, null,
                "QUERY", null,
                null, null, null, null);
        assertThat(req.allowedTypes()).isEqualTo("QUERY");
    }

    @Test
    void bothNullConstructsSuccessfully() {
        ChannelCreateRequest req = new ChannelCreateRequest(
                "ch", null, ChannelSemantic.APPEND, null, null, null, null, null,
                null, null,
                null, null, null, null);
        assertThat(req.allowedTypes()).isNull();
        assertThat(req.deniedTypes()).isNull();
    }

    @Test
    void simpleFactoryHasNullDeniedTypes() {
        ChannelCreateRequest req = ChannelCreateRequest.simple("ch", ChannelSemantic.APPEND);
        assertThat(req.deniedTypes()).isNull();
    }

    // ------------------------------------------------------------------
    // Overlap rejection
    // ------------------------------------------------------------------

    @Test
    void overlapBetweenAllowedAndDeniedThrows() {
        assertThatThrownBy(() -> new ChannelCreateRequest(
                "ch", null, ChannelSemantic.APPEND, null, null, null, null, null,
                "QUERY,COMMAND", "QUERY",
                null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QUERY");
    }

    @Test
    void sameTypeBothSidesThrows() {
        assertThatThrownBy(() -> new ChannelCreateRequest(
                "ch", null, ChannelSemantic.APPEND, null, null, null, null, null,
                "EVENT", "EVENT",
                null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EVENT");
    }

    // ------------------------------------------------------------------
    // Invalid type name rejection
    // ------------------------------------------------------------------

    @Test
    void invalidTypeNameInAllowedTypesThrows() {
        assertThatThrownBy(() -> new ChannelCreateRequest(
                "ch", null, ChannelSemantic.APPEND, null, null, null, null, null,
                "INVALID", null,
                null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidTypeNameInDeniedTypesThrows() {
        assertThatThrownBy(() -> new ChannelCreateRequest(
                "ch", null, ChannelSemantic.APPEND, null, null, null, null, null,
                null, "INVALID",
                null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // Connector binding invariant (pre-existing, must still pass)
    // ------------------------------------------------------------------

    @Test
    void partialConnectorBindingThrows() {
        assertThatThrownBy(() -> new ChannelCreateRequest(
                "ch", null, ChannelSemantic.APPEND, null, null, null, null, null,
                null, null,
                "connector-id", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Connector binding");
    }
}
