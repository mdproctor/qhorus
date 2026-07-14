package io.casehub.qhorus.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;
import io.casehub.qhorus.api.store.CrossTenantMessageStore;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.HandshakeRequest;
import io.quarkus.websockets.next.WebSocketConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChannelWebSocketEndpointTest {

    private WebSocketConnectionRegistry registry;
    private CrossTenantChannelStore channelStore;
    private CrossTenantMessageStore messageStore;
    private ObjectMapper objectMapper;
    private ChannelWebSocketEndpoint endpoint;

    @BeforeEach
    void setUp() {
        registry = new WebSocketConnectionRegistry();
        channelStore = mock(CrossTenantChannelStore.class);
        messageStore = mock(CrossTenantMessageStore.class);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        endpoint = new ChannelWebSocketEndpoint();
        endpoint.registry = registry;
        endpoint.channelStore = channelStore;
        endpoint.messageStore = messageStore;
        endpoint.objectMapper = objectMapper;
        endpoint.config = () -> 500;
    }

    private Channel testChannel(UUID id, String name) {
        return Channel.builder(name).id(id).semantic(ChannelSemantic.APPEND).build();
    }

    private WebSocketConnection mockConnection(String query) {
        WebSocketConnection conn = mock(WebSocketConnection.class);
        HandshakeRequest req = mock(HandshakeRequest.class);
        when(conn.handshakeRequest()).thenReturn(req);
        when(req.query()).thenReturn(query);
        return conn;
    }

    private Message testMessage(Long id, UUID channelId, String content) {
        return Message.builder()
                .id(id).channelId(channelId).sender("agent-1")
                .messageType(MessageType.STATUS).content(content)
                .createdAt(Instant.now()).build();
    }

    // ── Live-only mode ────────────────────────────────────────────────

    @Test
    void noLastEventId_liveOnlyMode() {
        UUID channelId = UUID.randomUUID();
        when(channelStore.findById(channelId)).thenReturn(Optional.of(testChannel(channelId, "ops")));
        WebSocketConnection conn = mockConnection(null);

        endpoint.onOpen(channelId.toString(), conn);

        assertThat(registry.connections(channelId)).containsExactly(conn);
        verify(conn, never()).sendTextAndAwait(anyString());
        verifyNoInteractions(messageStore);
    }

    @Test
    void emptyQuery_liveOnlyMode() {
        UUID channelId = UUID.randomUUID();
        when(channelStore.findById(channelId)).thenReturn(Optional.of(testChannel(channelId, "ops")));
        WebSocketConnection conn = mockConnection("");

        endpoint.onOpen(channelId.toString(), conn);

        assertThat(registry.connections(channelId)).containsExactly(conn);
        verify(conn, never()).sendTextAndAwait(anyString());
    }

    // ── Catch-up replay ───────────────────────────────────────────────

    @Test
    void lastEventId_sendsCatchUpMessages() {
        UUID channelId = UUID.randomUUID();
        when(channelStore.findById(channelId)).thenReturn(Optional.of(testChannel(channelId, "ops")));
        when(messageStore.scan(any(MessageQuery.class))).thenReturn(List.of(
                testMessage(43L, channelId, "msg-43"),
                testMessage(44L, channelId, "msg-44")));

        WebSocketConnection conn = mockConnection("lastEventId=42");
        endpoint.onOpen(channelId.toString(), conn);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(conn, atLeast(3)).sendTextAndAwait(captor.capture());

        List<String> frames = captor.getAllValues();
        assertThat(frames.get(0)).contains("catchup_begin");
        assertThat(frames.get(1)).contains("msg-43");
        assertThat(frames.get(2)).contains("msg-44");
        assertThat(frames.get(frames.size() - 1)).contains("catchup_end");
    }

    @Test
    void catchUpEnd_includesHighestMessageId() {
        UUID channelId = UUID.randomUUID();
        when(channelStore.findById(channelId)).thenReturn(Optional.of(testChannel(channelId, "ops")));
        when(messageStore.scan(any(MessageQuery.class))).thenReturn(List.of(
                testMessage(43L, channelId, "msg-43")));

        WebSocketConnection conn = mockConnection("lastEventId=42");
        endpoint.onOpen(channelId.toString(), conn);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(conn, atLeast(1)).sendTextAndAwait(captor.capture());

        String lastFrame = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(lastFrame).contains("catchup_end");
        assertThat(lastFrame).contains("43");
    }

    @Test
    void emptyResult_sendsCatchUpBeginAndEnd() {
        UUID channelId = UUID.randomUUID();
        when(channelStore.findById(channelId)).thenReturn(Optional.of(testChannel(channelId, "ops")));
        when(messageStore.scan(any(MessageQuery.class))).thenReturn(List.of());

        WebSocketConnection conn = mockConnection("lastEventId=42");
        endpoint.onOpen(channelId.toString(), conn);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(conn, atLeast(2)).sendTextAndAwait(captor.capture());

        List<String> frames = captor.getAllValues();
        assertThat(frames.get(0)).contains("catchup_begin");
        assertThat(frames.get(frames.size() - 1)).contains("catchup_end");
    }

    // ── Truncation ────────────────────────────────────────────────────

    @Test
    void truncation_sendsCatchUpTruncated() {
        UUID channelId = UUID.randomUUID();
        when(channelStore.findById(channelId)).thenReturn(Optional.of(testChannel(channelId, "ops")));

        endpoint.config = () -> 2;

        when(messageStore.scan(any(MessageQuery.class))).thenReturn(List.of(
                testMessage(43L, channelId, "msg-43"),
                testMessage(44L, channelId, "msg-44"),
                testMessage(45L, channelId, "msg-45")));
        when(messageStore.findLastMessage(channelId)).thenReturn(
                Optional.of(testMessage(590L, channelId, "head")));

        WebSocketConnection conn = mockConnection("lastEventId=42");
        endpoint.onOpen(channelId.toString(), conn);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(conn, atLeast(1)).sendTextAndAwait(captor.capture());

        String lastFrame = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(lastFrame).contains("catchup_truncated");
        assertThat(lastFrame).contains("590");
    }

    // ── Error handling ────────────────────────────────────────────────

    @Test
    void invalidLastEventId_noStoreQuery() {
        UUID channelId = UUID.randomUUID();
        when(channelStore.findById(channelId)).thenReturn(Optional.of(testChannel(channelId, "ops")));
        WebSocketConnection conn = mockConnection("lastEventId=notanumber");

        endpoint.onOpen(channelId.toString(), conn);

        assertThat(registry.connections(channelId)).containsExactly(conn);
        verifyNoInteractions(messageStore);
    }

    @Test
    void unknownChannel_closesConnection() {
        UUID channelId = UUID.randomUUID();
        when(channelStore.findById(channelId)).thenReturn(Optional.empty());
        WebSocketConnection conn = mockConnection(null);

        endpoint.onOpen(channelId.toString(), conn);

        verify(conn).closeAndAwait(any(CloseReason.class));
        assertThat(registry.connections(channelId)).isEmpty();
    }

    @Test
    void invalidChannelId_closesConnection() {
        WebSocketConnection conn = mockConnection(null);

        endpoint.onOpen("not-a-uuid", conn);

        verify(conn).closeAndAwait(any(CloseReason.class));
    }

    @Test
    void dbErrorDuringCatchUp_bufferCleanedUp() {
        UUID channelId = UUID.randomUUID();
        when(channelStore.findById(channelId)).thenReturn(Optional.of(testChannel(channelId, "ops")));
        when(messageStore.scan(any(MessageQuery.class)))
                .thenThrow(new RuntimeException("DB error"));

        WebSocketConnection conn = mockConnection("lastEventId=42");
        endpoint.onOpen(channelId.toString(), conn);

        assertThat(registry.tryBufferForCatchUp(conn, 1L, "{}")).isFalse();
    }

    // ── Query parameter parsing ───────────────────────────────────────

    @Test
    void lastEventId_withOtherParams_parsedCorrectly() {
        UUID channelId = UUID.randomUUID();
        when(channelStore.findById(channelId)).thenReturn(Optional.of(testChannel(channelId, "ops")));
        when(messageStore.scan(any(MessageQuery.class))).thenReturn(List.of());

        WebSocketConnection conn = mockConnection("token=abc&lastEventId=42&format=json");
        endpoint.onOpen(channelId.toString(), conn);

        verify(messageStore).scan(any(MessageQuery.class));
    }

    // ── @OnClose ──────────────────────────────────────────────────────

    @Test
    void onClose_unsubscribes() {
        UUID channelId = UUID.randomUUID();
        WebSocketConnection conn = mock(WebSocketConnection.class);
        registry.subscribe(channelId, conn);

        endpoint.onClose(channelId.toString(), conn);

        assertThat(registry.connections(channelId)).isEmpty();
    }
}
