package io.casehub.qhorus.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import io.casehub.platform.api.credentials.CredentialPropertyKeys;
import io.casehub.platform.api.credentials.CredentialResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.connectors.slack.bot.SlackBotClient;
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;

/**
 * Unit tests for SlackChannelBackend — CDI-free, constructor injection.
 */
class SlackChannelBackendTest {

    private SlackBotBindingStore bindingStore;
    private SlackThreadCacheStore threadCacheStore;
    private SlackBotClient slackBotClient;
    private ChannelGateway gateway;
    private CredentialResolver credentialResolver;
    private SlackChannelBackend backend;

    private final UUID channelId = UUID.randomUUID();
    private final ChannelRef channelRef = new ChannelRef(channelId, "test-channel");
    private final String slackChannelId = "C123ABC";
    private final String workspaceId = "T123ABC";
    private final String token = "xoxb-test";

    @BeforeEach
    void setUp() {
        bindingStore = mock(SlackBotBindingStore.class);
        threadCacheStore = mock(SlackThreadCacheStore.class);
        slackBotClient = mock(SlackBotClient.class);
        gateway = mock(ChannelGateway.class);
        credentialResolver = mock(CredentialResolver.class);

        backend = new SlackChannelBackend(
                bindingStore, threadCacheStore, slackBotClient,
                new SlackInboundNormaliser(), gateway, credentialResolver);

        // Pre-populate binding cache — simulates onChannelInitialised having run
        SlackBotBinding binding = binding();
        backend.bindingCache.put(channelId, binding);

        // Default token stub for all tests that post to Slack
        when(credentialResolver.resolve(workspaceId))
                .thenReturn(Map.of(CredentialPropertyKeys.BEARER_TOKEN, token));
    }

    @Test
    void post_eventType_skipsImmediately() {
        OutboundMessage msg = outbound(MessageType.EVENT, UUID.randomUUID().toString(), "data");
        backend.post(channelRef, msg);
        verify(slackBotClient, never()).postMessage(anyString(), anyString(), anyString(), any());
    }

    @Test
    void post_nullContent_skipsImmediately() {
        OutboundMessage msg = outbound(MessageType.STATUS, UUID.randomUUID().toString(), null);
        backend.post(channelRef, msg);
        verify(slackBotClient, never()).postMessage(anyString(), anyString(), anyString(), any());
    }

    @Test
    void post_noBinding_skipsWithDebugLog() {
        backend.bindingCache.remove(channelId);
        OutboundMessage msg = outbound(MessageType.QUERY, null, "Hello");
        backend.post(channelRef, msg);
        verify(slackBotClient, never()).postMessage(anyString(), anyString(), anyString(), any());
    }

    @Test
    void post_nullCorrelationId_sendsTopLevelMessage() {
        when(slackBotClient.postMessage(token, slackChannelId, "Hello", null))
                .thenReturn(new SlackBotClient.PostResult(true, "1.1", null));
        OutboundMessage msg = outbound(MessageType.QUERY, null, "Hello");
        backend.post(channelRef, msg);
        verify(slackBotClient).postMessage(token, slackChannelId, "Hello", null);
        verify(threadCacheStore, never()).save(any(), any(), any());
    }

    @Test
    void post_firstMessageWithCorrId_sendsTopLevelAndCachesThreadTs() {
        String corrId = UUID.randomUUID().toString();
        when(threadCacheStore.findThreadTs(channelId, corrId)).thenReturn(Optional.empty());
        when(slackBotClient.postMessage(token, slackChannelId, "Hi", null))
                .thenReturn(new SlackBotClient.PostResult(true, "1.1", null));

        backend.post(channelRef, outbound(MessageType.COMMAND, corrId, "Hi"));

        verify(slackBotClient).postMessage(token, slackChannelId, "Hi", null);
        verify(threadCacheStore).save(channelId, corrId, "1.1");
        assertThat(backend.threadCache.get(channelId)).containsEntry(corrId, "1.1");
    }

    @Test
    void post_secondMessageSameCorrId_sendsAsThreadReply() {
        String corrId = UUID.randomUUID().toString();
        // Warm memory cache
        backend.threadCache.computeIfAbsent(channelId, k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(corrId, "1.1");
        when(slackBotClient.postMessage(token, slackChannelId, "Reply", "1.1"))
                .thenReturn(new SlackBotClient.PostResult(true, "1.2", null));

        backend.post(channelRef, outbound(MessageType.RESPONSE, corrId, "Reply"));

        verify(slackBotClient).postMessage(token, slackChannelId, "Reply", "1.1");
        verify(threadCacheStore, never()).save(any(), any(), any());
    }

    @Test
    void post_doneMessage_evictsCacheEntry() {
        String corrId = UUID.randomUUID().toString();
        backend.threadCache.computeIfAbsent(channelId, k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(corrId, "1.1");
        when(slackBotClient.postMessage(any(), any(), any(), any()))
                .thenReturn(new SlackBotClient.PostResult(true, "1.2", null));

        backend.post(channelRef, outbound(MessageType.DONE, corrId, "Done!"));

        verify(threadCacheStore).delete(channelId, corrId);
        assertThat(backend.threadCache.get(channelId)).doesNotContainKey(corrId);
    }

    @Test
    void post_failureMessage_evictsCacheEntry() {
        String corrId = UUID.randomUUID().toString();
        backend.threadCache.computeIfAbsent(channelId, k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(corrId, "1.1");
        when(slackBotClient.postMessage(any(), any(), any(), any()))
                .thenReturn(new SlackBotClient.PostResult(true, "1.2", null));

        backend.post(channelRef, outbound(MessageType.FAILURE, corrId, "Failed"));

        verify(threadCacheStore).delete(channelId, corrId);
    }

    @Test
    void post_declineMessage_evictsCacheEntry() {
        String corrId = UUID.randomUUID().toString();
        backend.threadCache.computeIfAbsent(channelId, k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(corrId, "1.1");
        when(slackBotClient.postMessage(any(), any(), any(), any()))
                .thenReturn(new SlackBotClient.PostResult(true, "1.2", null));

        backend.post(channelRef, outbound(MessageType.DECLINE, corrId, "Declined"));

        verify(threadCacheStore).delete(channelId, corrId);
    }

    @Test
    void post_handoffMessage_doesNotEvictCacheEntry() {
        String corrId = UUID.randomUUID().toString();
        backend.threadCache.computeIfAbsent(channelId, k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(corrId, "1.1");
        when(slackBotClient.postMessage(any(), any(), any(), any()))
                .thenReturn(new SlackBotClient.PostResult(true, "1.2", null));

        backend.post(channelRef, outbound(MessageType.HANDOFF, corrId, "Handing off"));

        verify(threadCacheStore, never()).delete(any(), any());
        assertThat(backend.threadCache.get(channelId)).containsKey(corrId);
    }

    @Test
    void post_responseMessage_doesNotEvictCacheEntry() {
        String corrId = UUID.randomUUID().toString();
        backend.threadCache.computeIfAbsent(channelId, k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(corrId, "1.1");
        when(slackBotClient.postMessage(any(), any(), any(), any()))
                .thenReturn(new SlackBotClient.PostResult(true, "1.2", null));

        backend.post(channelRef, outbound(MessageType.RESPONSE, corrId, "Answer"));

        verify(threadCacheStore, never()).delete(any(), any());
    }

    @Test
    void post_doneMessage_noCachedThread_doesNotWriteRecoveryAnchor() {
        String corrId = UUID.randomUUID().toString();
        when(threadCacheStore.findThreadTs(channelId, corrId)).thenReturn(Optional.empty());
        when(slackBotClient.postMessage(any(), any(), any(), isNull()))
                .thenReturn(new SlackBotClient.PostResult(true, "1.1", null));

        backend.post(channelRef, outbound(MessageType.DONE, corrId, "Done!"));

        verify(threadCacheStore, never()).save(any(), any(), any());
        verify(threadCacheStore).delete(channelId, corrId);
        assertThat(backend.threadCache).doesNotContainKey(channelId);
    }

    @Test
    void post_failureMessage_noCachedThread_doesNotWriteRecoveryAnchor() {
        String corrId = UUID.randomUUID().toString();
        when(threadCacheStore.findThreadTs(channelId, corrId)).thenReturn(Optional.empty());
        when(slackBotClient.postMessage(any(), any(), any(), isNull()))
                .thenReturn(new SlackBotClient.PostResult(true, "1.1", null));

        backend.post(channelRef, outbound(MessageType.FAILURE, corrId, "Failed"));

        verify(threadCacheStore, never()).save(any(), any(), any());
        verify(threadCacheStore).delete(channelId, corrId);
    }

    @Test
    void post_declineMessage_noCachedThread_doesNotWriteRecoveryAnchor() {
        String corrId = UUID.randomUUID().toString();
        when(threadCacheStore.findThreadTs(channelId, corrId)).thenReturn(Optional.empty());
        when(slackBotClient.postMessage(any(), any(), any(), isNull()))
                .thenReturn(new SlackBotClient.PostResult(true, "1.1", null));

        backend.post(channelRef, outbound(MessageType.DECLINE, corrId, "Declined"));

        verify(threadCacheStore, never()).save(any(), any(), any());
        verify(threadCacheStore).delete(channelId, corrId);
    }

    @Test
    void post_slackApiFailure_logsWarnNoMutation() {
        String corrId = UUID.randomUUID().toString();
        when(threadCacheStore.findThreadTs(channelId, corrId)).thenReturn(Optional.empty());
        when(slackBotClient.postMessage(any(), any(), any(), isNull()))
                .thenReturn(new SlackBotClient.PostResult(false, null, "channel_not_found"));

        backend.post(channelRef, outbound(MessageType.COMMAND, corrId, "Hi"));

        verify(threadCacheStore, never()).save(any(), any(), any());
        assertThat(backend.threadCache).doesNotContainKey(channelId);
    }

    @Test
    void onChannelInitialised_withBinding_populatesCachesAndRegisters() {
        UUID chId = UUID.randomUUID();
        String slackChId = "C456";
        SlackBotBinding binding = new SlackBotBinding();
        binding.channelId = chId;
        binding.slackChannelId = slackChId;
        binding.workspaceId = "T456";
        binding.createdAt = java.time.Instant.now();

        when(bindingStore.findByChannelId(chId)).thenReturn(Optional.of(binding));
        when(threadCacheStore.findByChannelId(chId)).thenReturn(List.of());

        backend.onChannelInitialised(new ChannelInitialisedEvent(chId, "my-channel", false));

        assertThat(backend.bindingCache).containsKey(chId);
        assertThat(backend.slackToChannel).containsKey(slackChId);
        verify(gateway).registerBackend(eq(chId), eq(backend), eq("human_participating"));
    }

    @Test
    void onChannelInitialised_withoutBinding_doesNothing() {
        UUID chId = UUID.randomUUID();
        when(bindingStore.findByChannelId(chId)).thenReturn(Optional.empty());

        backend.onChannelInitialised(new ChannelInitialisedEvent(chId, "no-binding", false));

        assertThat(backend.bindingCache).doesNotContainKey(chId);
        verify(gateway, never()).registerBackend(any(), any(), any());
    }

    @Test
    void onChannelInitialised_restartRecovery_loadsThreadCacheFromDb() {
        UUID chId = UUID.randomUUID();
        String corrId = UUID.randomUUID().toString();
        String threadTs = "1718567890.123456";

        SlackBotBinding binding = new SlackBotBinding();
        binding.channelId = chId;
        binding.slackChannelId = "C789";
        binding.workspaceId = "T789";
        binding.createdAt = java.time.Instant.now();

        SlackThreadCache entry = new SlackThreadCache();
        entry.id = new SlackThreadCacheId(chId, corrId);
        entry.threadTs = threadTs;

        when(bindingStore.findByChannelId(chId)).thenReturn(Optional.of(binding));
        when(threadCacheStore.findByChannelId(chId)).thenReturn(List.of(entry));

        backend.onChannelInitialised(new ChannelInitialisedEvent(chId, "recovery-channel", true));

        assertThat(backend.threadCache.get(chId)).containsEntry(corrId, threadTs);
    }

    @Test
    void onInboundMessage_unknownThreadReply_anchorsWithThreadRootTs() throws Exception {
        // Populate slackToChannel so the message routes correctly
        ChannelRef channelRef = new ChannelRef(channelId, "test-channel");
        backend.slackToChannel.put(slackChannelId, channelRef);

        String replyTs = "1718567890.999999";    // the reply's own ts
        String rootTs  = "1718567890.111111";   // the thread root ts (what Slack needs)

        Map<String, String> meta = Map.of("slack-ts", replyTs, "slack-thread-ts", rootTs);

        // No existing anchor for this thread
        when(threadCacheStore.findCorrelationId(channelId, rootTs)).thenReturn(Optional.empty());

        io.casehub.connectors.InboundMessage msg = new io.casehub.connectors.InboundMessage(
            io.casehub.connectors.InboundConnectorIds.SLACK_INBOUND,
            io.casehub.connectors.InboundConnectorTypes.SLACK,
            "U123", slackChannelId, "hello", java.util.List.of(),
            java.time.Instant.now(), meta, null);

        backend.onInboundMessage(msg).toCompletableFuture().join();

        // MUST save with rootTs (the thread root), NOT replyTs (the reply's own ts)
        verify(threadCacheStore).save(eq(channelId), anyString(), eq(rootTs));
    }

    @Test
    void evict_removesFromAllInMemoryMaps() {
        String corrId = UUID.randomUUID().toString();
        String slackChId = "C999";
        SlackBotBinding b = new SlackBotBinding();
        b.channelId = channelId;
        b.slackChannelId = slackChId;
        b.workspaceId = "T999";
        b.createdAt = java.time.Instant.now();

        backend.bindingCache.put(channelId, b);
        backend.slackToChannel.put(slackChId, channelRef);
        backend.threadCache.computeIfAbsent(channelId, k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(corrId, "1.1");

        backend.evict(channelId);

        assertThat(backend.bindingCache).doesNotContainKey(channelId);
        assertThat(backend.slackToChannel).doesNotContainKey(slackChId);
        assertThat(backend.threadCache).doesNotContainKey(channelId);
    }

    @Test
    void resolveToken_missingCredential_throwsNoSuchElement() {
        when(credentialResolver.resolve("unknown-workspace")).thenReturn(Map.of());

        assertThatThrownBy(() -> backend.resolveToken("unknown-workspace"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("unknown-workspace");
    }

    @Test
    void resolveToken_blankToken_throwsNoSuchElement() {
        when(credentialResolver.resolve(workspaceId))
                .thenReturn(Map.of(CredentialPropertyKeys.BEARER_TOKEN, "   "));

        assertThatThrownBy(() -> backend.resolveToken(workspaceId))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(workspaceId);
    }

    // --- helpers ---

    private SlackBotBinding binding() {
        SlackBotBinding b = new SlackBotBinding();
        b.channelId = channelId;
        b.slackChannelId = slackChannelId;
        b.workspaceId = workspaceId;
        b.createdAt = Instant.now();
        return b;
    }

    private OutboundMessage outbound(MessageType type, String corrId, String content) {
        return new OutboundMessage(UUID.randomUUID(), "agent:test", type, content, corrId, null, null, null, null);
    }
}
