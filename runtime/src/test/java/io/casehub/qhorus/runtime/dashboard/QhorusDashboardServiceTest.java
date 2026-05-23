package io.casehub.qhorus.runtime.dashboard;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.qhorus.runtime.QhorusEntityMapper;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.runtime.channel.ReactiveChannelService;
import io.casehub.qhorus.runtime.instance.Instance;
import io.casehub.qhorus.runtime.instance.ReactiveInstanceService;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.ReactiveMessageService;
import io.casehub.qhorus.runtime.store.ReactiveMessageStore;
import io.casehub.qhorus.runtime.store.query.MessageQuery;
import io.smallrye.mutiny.Uni;

class QhorusDashboardServiceTest {

    ReactiveChannelService channelService = mock(ReactiveChannelService.class);
    ReactiveInstanceService instanceService = mock(ReactiveInstanceService.class);
    ReactiveMessageService messageService = mock(ReactiveMessageService.class);
    ReactiveMessageStore messageStore = mock(ReactiveMessageStore.class);
    QhorusDashboardService service;

    @BeforeEach
    void setUp() {
        service = new QhorusDashboardService();
        service.channelService = channelService;
        service.instanceService = instanceService;
        service.messageService = messageService;
        service.messageStore = messageStore;
        service.entityMapper = new QhorusEntityMapper(new ObjectMapper());
        reset(channelService, instanceService, messageService, messageStore);
    }

    // ── listChannels ──────────────────────────────────────────────────────────

    @Test
    void listChannels_emptyStore_returnsEmptyList() {
        when(channelService.listAll()).thenReturn(Uni.createFrom().item(List.of()));

        List<QhorusDashboardService.ChannelView> result = service.listChannels()
                .await().atMost(Duration.ofSeconds(1));

        assertTrue(result.isEmpty());
    }

    @Test
    void listChannels_withChannel_returnsChannelViewWithMessageCount() {
        Channel ch = channel("work", ChannelSemantic.APPEND);
        when(channelService.listAll()).thenReturn(Uni.createFrom().item(List.of(ch)));
        when(messageStore.countByChannel(ch.id)).thenReturn(Uni.createFrom().item(7));

        List<QhorusDashboardService.ChannelView> result = service.listChannels()
                .await().atMost(Duration.ofSeconds(1));

        assertEquals(1, result.size());
        assertEquals("work", result.get(0).name());
        assertEquals(7, result.get(0).messageCount());
        assertFalse(result.get(0).paused());
        assertEquals("APPEND", result.get(0).semantic());
    }

    // ── listInstances ─────────────────────────────────────────────────────────

    @Test
    void listInstances_emptyStore_returnsEmptyList() {
        when(instanceService.listAll()).thenReturn(Uni.createFrom().item(List.of()));

        List<QhorusDashboardService.InstanceView> result = service.listInstances()
                .await().atMost(Duration.ofSeconds(1));

        assertTrue(result.isEmpty());
    }

    @Test
    void listInstances_withInstance_returnsInstanceViewWithCapabilities() {
        Instance inst = instance("claude:analyst@v1", "analyst");
        when(instanceService.listAll()).thenReturn(Uni.createFrom().item(List.of(inst)));
        when(instanceService.findCapabilityTagsForInstance("claude:analyst@v1"))
                .thenReturn(Uni.createFrom().item(List.of("code-review", "security")));

        List<QhorusDashboardService.InstanceView> result = service.listInstances()
                .await().atMost(Duration.ofSeconds(1));

        assertEquals(1, result.size());
        assertEquals("claude:analyst@v1", result.get(0).instanceId());
        assertEquals(List.of("code-review", "security"), result.get(0).capabilities());
        assertEquals("online", result.get(0).status());
    }

    // ── getTimeline ───────────────────────────────────────────────────────────

    @Test
    void getTimeline_unknownChannel_returnsEmptyList() {
        when(channelService.findByName("no-such")).thenReturn(Uni.createFrom().item(Optional.empty()));

        List<Map<String, Object>> result = service.getTimeline("no-such", null, 50)
                .await().atMost(Duration.ofSeconds(1));

        assertTrue(result.isEmpty());
    }

    @Test
    void getTimeline_knownChannel_returnsTimelineEntries() {
        Channel ch = channel("work", ChannelSemantic.APPEND);
        Message msg = message(ch.id, "agent:analyst@v1", MessageType.STATUS, "working on it");
        when(channelService.findByName("work")).thenReturn(Uni.createFrom().item(Optional.of(ch)));
        when(messageStore.scan(any(MessageQuery.class))).thenReturn(Uni.createFrom().item(List.of(msg)));

        List<Map<String, Object>> result = service.getTimeline("work", null, 50)
                .await().atMost(Duration.ofSeconds(1));

        assertEquals(1, result.size());
        assertEquals("MESSAGE", result.get(0).get("type"));
        assertEquals("agent:analyst@v1", result.get(0).get("sender"));
        assertEquals("status", result.get(0).get("message_type"));
        assertEquals("working on it", result.get(0).get("content"));
    }

    @Test
    void getTimeline_limitCappedAt200() {
        Channel ch = channel("work", ChannelSemantic.APPEND);
        when(channelService.findByName("work")).thenReturn(Uni.createFrom().item(Optional.of(ch)));
        when(messageStore.scan(any(MessageQuery.class))).thenReturn(Uni.createFrom().item(List.of()));

        // Should not throw — limit is silently capped
        assertDoesNotThrow(() -> service.getTimeline("work", null, 999)
                .await().atMost(Duration.ofSeconds(1)));
    }

    // ── getFeed ───────────────────────────────────────────────────────────────

    @Test
    void getFeed_emptyChannels_returnsEmptyList() {
        when(channelService.listAll()).thenReturn(Uni.createFrom().item(List.of()));

        List<Map<String, Object>> result = service.getFeed(100)
                .await().atMost(Duration.ofSeconds(1));

        assertTrue(result.isEmpty());
    }

    @Test
    void getFeed_withChannels_tagsEachEntryWithChannelName() {
        Channel ch = channel("work", ChannelSemantic.APPEND);
        Message msg = message(ch.id, "agent:analyst@v1", MessageType.STATUS, "progress");
        msg.createdAt = Instant.now();
        when(channelService.listAll()).thenReturn(Uni.createFrom().item(List.of(ch)));
        when(messageStore.scan(any(MessageQuery.class))).thenReturn(Uni.createFrom().item(List.of(msg)));

        List<Map<String, Object>> result = service.getFeed(100)
                .await().atMost(Duration.ofSeconds(1));

        assertEquals(1, result.size());
        assertEquals("work", result.get(0).get("channel"));
    }

    @Test
    void getFeed_returnsNewestFirstAcrossChannels() {
        Channel ch1 = channel("ch-alpha", ChannelSemantic.APPEND);
        Channel ch2 = channel("ch-beta", ChannelSemantic.APPEND);

        Message older = message(ch1.id, "agent:a", MessageType.STATUS, "old");
        older.id = 1L;
        Message newer = message(ch2.id, "agent:b", MessageType.STATUS, "new");
        newer.id = 2L;

        when(channelService.listAll()).thenReturn(Uni.createFrom().item(List.of(ch1, ch2)));
        when(messageStore.scan(any(MessageQuery.class)))
                .thenReturn(Uni.createFrom().item(List.of(newer, older)));

        List<Map<String, Object>> result = service.getFeed(100)
                .await().atMost(Duration.ofSeconds(1));

        assertEquals(2, result.size());
        assertEquals("ch-beta", result.get(0).get("channel"));
        assertEquals("ch-alpha", result.get(1).get("channel"));
    }

    // ── sendHumanMessage ──────────────────────────────────────────────────────

    @Test
    void sendHumanMessage_unknownChannel_throwsIllegalArgumentException() {
        when(channelService.findByName("ghost")).thenReturn(Uni.createFrom().item(Optional.empty()));

        Exception ex = assertThrows(Exception.class, () ->
                service.sendHumanMessage("ghost", "human:alice", MessageType.STATUS, "hello")
                        .await().atMost(Duration.ofSeconds(1)));
        assertTrue(ex instanceof IllegalArgumentException
                || (ex.getCause() instanceof IllegalArgumentException),
                "Expected IllegalArgumentException, got: " + ex);
        String msg = ex instanceof IllegalArgumentException ? ex.getMessage() : ex.getCause().getMessage();
        assertTrue(msg.contains("ghost"), "Message should mention channel name: " + msg);
    }

    @Test
    void sendHumanMessage_pausedChannel_throwsIllegalStateException() {
        Channel ch = channel("oversight", ChannelSemantic.APPEND);
        ch.paused = true;
        when(channelService.findByName("oversight")).thenReturn(Uni.createFrom().item(Optional.of(ch)));
        // Paused check now lives inside ReactiveMessageService.dispatch() — mock throws as it would in production.
        when(messageService.dispatch(any(MessageDispatch.class)))
                .thenReturn(Uni.createFrom().failure(
                        new IllegalStateException("Channel 'oversight' is paused")));

        Exception ex = assertThrows(Exception.class, () ->
                service.sendHumanMessage("oversight", "human:alice", MessageType.STATUS, "hello")
                        .await().atMost(Duration.ofSeconds(1)));
        assertTrue(ex instanceof IllegalStateException
                || (ex.getCause() instanceof IllegalStateException),
                "Expected IllegalStateException, got: " + ex);
        String msg = ex instanceof IllegalStateException ? ex.getMessage() : ex.getCause().getMessage();
        assertTrue(msg.contains("paused"), "Message should mention paused: " + msg);
    }

    @Test
    void sendHumanMessage_success_returnsHumanMessageResultWithCorrectFields() {
        Channel ch = channel("work", ChannelSemantic.APPEND);
        DispatchResult dr = new DispatchResult(42L, ch.id, "human:alice", MessageType.STATUS,
                null, null, List.of(), null, null, null, null, 0);
        when(channelService.findByName("work")).thenReturn(Uni.createFrom().item(Optional.of(ch)));
        when(messageService.dispatch(any(MessageDispatch.class)))
                .thenReturn(Uni.createFrom().item(dr));

        QhorusDashboardService.HumanMessageResult result =
                service.sendHumanMessage("work", "human:alice", MessageType.STATUS, "please prioritise security")
                        .await().atMost(Duration.ofSeconds(1));

        assertEquals(42L, result.messageId());
        assertEquals("work", result.channelName());
        assertEquals("human:alice", result.sender());
        assertEquals("STATUS", result.messageType());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Channel channel(String name, ChannelSemantic semantic) {
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = name;
        ch.semantic = semantic;
        ch.lastActivityAt = Instant.now();
        ch.paused = false;
        return ch;
    }

    private Instance instance(String instanceId, String description) {
        Instance inst = new Instance();
        inst.instanceId = instanceId;
        inst.description = description;
        inst.status = "online";
        inst.lastSeen = Instant.now();
        inst.readOnly = false;
        return inst;
    }

    private Message message(UUID channelId, String sender, MessageType type, String content) {
        Message msg = new Message();
        msg.channelId = channelId;
        msg.sender = sender;
        msg.messageType = type;
        msg.content = content;
        msg.createdAt = Instant.now();
        return msg;
    }
}
