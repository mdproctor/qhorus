package io.casehub.qhorus.websocket;

import io.quarkus.websockets.next.WebSocketConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WebSocketConnectionRegistryTest {

    private WebSocketConnectionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WebSocketConnectionRegistry();
    }

    @Test
    void subscribeAndLookup() {
        UUID channelId = UUID.randomUUID();
        WebSocketConnection conn = mock(WebSocketConnection.class);

        registry.subscribe(channelId, conn);

        assertThat(registry.connections(channelId)).containsExactly(conn);
    }

    @Test
    void unsubscribeRemoves() {
        UUID channelId = UUID.randomUUID();
        WebSocketConnection conn = mock(WebSocketConnection.class);

        registry.subscribe(channelId, conn);
        registry.unsubscribe(channelId, conn);

        assertThat(registry.connections(channelId)).isEmpty();
    }

    @Test
    void unsubscribeLastConnectionCleansUpMap() {
        UUID channelId = UUID.randomUUID();
        WebSocketConnection conn = mock(WebSocketConnection.class);

        registry.subscribe(channelId, conn);
        registry.unsubscribe(channelId, conn);

        assertThat(registry.channelCount()).isZero();
    }

    @Test
    void multipleConnectionsPerChannel() {
        UUID channelId = UUID.randomUUID();
        WebSocketConnection c1 = mock(WebSocketConnection.class);
        WebSocketConnection c2 = mock(WebSocketConnection.class);

        registry.subscribe(channelId, c1);
        registry.subscribe(channelId, c2);

        assertThat(registry.connections(channelId)).containsExactlyInAnyOrder(c1, c2);
    }

    @Test
    void unknownChannelReturnsEmptySet() {
        assertThat(registry.connections(UUID.randomUUID())).isEmpty();
    }
// ── Catch-up buffering ────────────────────────────────────────────

    @Test
    void subscribeCatchingUp_buffersViaTryBuffer() {
        UUID                channelId = UUID.randomUUID();
        WebSocketConnection conn      = mock(WebSocketConnection.class);

        registry.subscribeCatchingUp(channelId, conn);
        boolean buffered = registry.tryBufferForCatchUp(conn, 1L, "{\"msg\":1}");

        assertThat(buffered).isTrue();
        assertThat(registry.connections(channelId)).containsExactly(conn);
    }

    @Test
    void completeCatchUp_returnsBufferedMessages() {
        UUID                channelId = UUID.randomUUID();
        WebSocketConnection conn      = mock(WebSocketConnection.class);

        registry.subscribeCatchingUp(channelId, conn);
        registry.tryBufferForCatchUp(conn, 1L, "{\"msg\":1}");
        registry.tryBufferForCatchUp(conn, 2L, "{\"msg\":2}");

        var buffered = registry.completeCatchUp(channelId, conn);

        assertThat(buffered).hasSize(2);
        assertThat(buffered.get(0).messageId()).isEqualTo(1L);
        assertThat(buffered.get(0).json()).isEqualTo("{\"msg\":1}");
        assertThat(buffered.get(1).messageId()).isEqualTo(2L);
    }

    @Test
    void completeCatchUp_subsequentTryBufferReturnsFalse() {
        UUID                channelId = UUID.randomUUID();
        WebSocketConnection conn      = mock(WebSocketConnection.class);

        registry.subscribeCatchingUp(channelId, conn);
        registry.completeCatchUp(channelId, conn);

        assertThat(registry.tryBufferForCatchUp(conn, 3L, "{}")).isFalse();
    }

    @Test
    void cancelCatchUp_discardsBuffer() {
        UUID                channelId = UUID.randomUUID();
        WebSocketConnection conn      = mock(WebSocketConnection.class);

        registry.subscribeCatchingUp(channelId, conn);
        registry.tryBufferForCatchUp(conn, 1L, "{}");
        registry.cancelCatchUp(channelId, conn);

        assertThat(registry.tryBufferForCatchUp(conn, 2L, "{}")).isFalse();
    }

    @Test
    void unsubscribeDuringCatchUp_cleansUpBuffer() {
        UUID                channelId = UUID.randomUUID();
        WebSocketConnection conn      = mock(WebSocketConnection.class);

        registry.subscribeCatchingUp(channelId, conn);
        registry.tryBufferForCatchUp(conn, 1L, "{}");
        registry.unsubscribe(channelId, conn);

        assertThat(registry.tryBufferForCatchUp(conn, 2L, "{}")).isFalse();
        assertThat(registry.connections(channelId)).isEmpty();
    }

    @Test
    void liveSubscribe_tryBufferReturnsFalse() {
        UUID                channelId = UUID.randomUUID();
        WebSocketConnection conn      = mock(WebSocketConnection.class);

        registry.subscribe(channelId, conn);

        assertThat(registry.tryBufferForCatchUp(conn, 1L, "{}")).isFalse();
    }

    @Test
    void completeCatchUp_emptyBuffer_returnsEmptyList() {
        UUID                channelId = UUID.randomUUID();
        WebSocketConnection conn      = mock(WebSocketConnection.class);

        registry.subscribeCatchingUp(channelId, conn);
        var buffered = registry.completeCatchUp(channelId, conn);

        assertThat(buffered).isEmpty();
    }

}
