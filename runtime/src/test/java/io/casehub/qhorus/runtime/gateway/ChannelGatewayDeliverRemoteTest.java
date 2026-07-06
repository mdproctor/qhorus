package io.casehub.qhorus.runtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.enterprise.event.Event;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.*;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;
import io.casehub.qhorus.api.store.CrossTenantMessageStore;
import io.casehub.qhorus.runtime.config.DeliveryConfig;

class ChannelGatewayDeliverRemoteTest {

    private ChannelGateway gateway;
    private final UUID channelId = UUID.randomUUID();
    private final String channelName = "test-channel";
    private final List<OutboundMessage> posted = new CopyOnWriteArrayList<>();
    private StubCrossTenantMessageStore messageStore;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        messageStore = new StubCrossTenantMessageStore();

        // Construct gateway with stubs — same pattern as ChannelGatewayTest
        gateway = new ChannelGateway(
                new StubAgentBackend(),       // agentBackend
                null,                          // normaliser (unused)
                null,                          // messageService (unused)
                null,                          // channelService (unused)
                new StubCrossTenantChannelStore(),
                mock(Event.class),             // channelInitialisedEvents
                new StubDeliveryConfig(),
                messageStore);
    }

    @Test
    void deliverRemote_callsPostOnBestEffortBackend() throws Exception {
        // Register a BEST_EFFORT backend
        gateway.initChannel(channelId, new ChannelRef(channelId, channelName));
        gateway.registerBackend(channelId, new RecordingBackend("test-be", ActorType.AGENT, posted),
                "agent");

        gateway.deliverRemote(channelId, 1L);
        Thread.sleep(100); // virtual thread dispatch

        assertThat(posted).hasSize(1);
        assertThat(posted.get(0).sender()).isEqualTo("agent-1");
    }

    @Test
    void deliverRemote_skipsAgentBackend() throws Exception {
        gateway.initChannel(channelId, new ChannelRef(channelId, channelName));
        // No additional backends registered — only the agent backend

        gateway.deliverRemote(channelId, 1L);
        Thread.sleep(100);

        assertThat(posted).isEmpty();
    }

    @Test
    void deliverRemote_skipsAtLeastOnceBackend() throws Exception {
        gateway.initChannel(channelId, new ChannelRef(channelId, channelName));
        gateway.registerBackend(channelId,
                new RecordingBackend("tracked-be", ActorType.AGENT, posted, DeliveryGuarantee.AT_LEAST_ONCE),
                "agent");

        gateway.deliverRemote(channelId, 1L);
        Thread.sleep(100);

        assertThat(posted).isEmpty();
    }

    @Test
    void deliverRemote_lazyInitializesUnknownChannel() throws Exception {
        // Do NOT call initChannel — simulate a channel created on another node
        gateway.deliverRemote(channelId, 1L);
        Thread.sleep(100);

        // Verify the channel was lazy-initialized (registry now has an entry)
        assertThat(gateway.listBackends(channelId)).isNotEmpty();
    }

    @Test
    void deliverRemote_skipsWhenMessageNotFound() {
        gateway.initChannel(channelId, new ChannelRef(channelId, channelName));
        // messageId 999L does not exist in the stub store
        gateway.deliverRemote(channelId, 999L);
        // No exception, no post
        assertThat(posted).isEmpty();
    }

    @Test
    void deliverRemote_skipsWhenChannelNotFound() {
        UUID unknownChannelId = UUID.randomUUID();
        // Message exists but channel does not
        messageStore.messages.put(1L, buildMessage(1L, unknownChannelId));

        gateway.deliverRemote(unknownChannelId, 1L);
        // No exception, no post
        assertThat(posted).isEmpty();
    }

    // ── Stubs (inline, same pattern as existing gateway tests) ──────────────

    static class StubAgentBackend implements AgentChannelBackend {
        @Override public String backendId() { return "qhorus-internal"; }
        @Override public ActorType actorType() { return ActorType.AGENT; }
        @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
        @Override public void post(ChannelRef channel, OutboundMessage message) {}
        @Override public void close(ChannelRef channel) {}
    }

    static class RecordingBackend implements ChannelBackend {
        private final String id;
        private final ActorType actorType;
        private final List<OutboundMessage> posts;
        private final DeliveryGuarantee guarantee;

        RecordingBackend(String id, ActorType actorType, List<OutboundMessage> posts) {
            this(id, actorType, posts, DeliveryGuarantee.BEST_EFFORT);
        }

        RecordingBackend(String id, ActorType actorType, List<OutboundMessage> posts, DeliveryGuarantee guarantee) {
            this.id = id;
            this.actorType = actorType;
            this.posts = posts;
            this.guarantee = guarantee;
        }

        @Override public String backendId() { return id; }
        @Override public ActorType actorType() { return actorType; }
        @Override public DeliveryGuarantee deliveryGuarantee() { return guarantee; }
        @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
        @Override public void post(ChannelRef channel, OutboundMessage message) {
            posts.add(message);
        }
        @Override public void close(ChannelRef channel) {}
    }

    class StubCrossTenantChannelStore implements CrossTenantChannelStore {
        @Override
        public Optional<Channel> findById(UUID id) {
            if (id.equals(channelId)) {
                return Optional.of(Channel.builder(channelName)
                        .id(channelId)
                        .semantic(ChannelSemantic.APPEND)
                        .build());
            }
            return Optional.empty();
        }

        @Override public List<Channel> listAll() { return List.of(); }
        @Override public Optional<Channel> findByNameAndTenancy(String name, String tenancyId) { return Optional.empty(); }
    }

    static class StubCrossTenantMessageStore implements CrossTenantMessageStore {
        final Map<Long, Message> messages = new HashMap<>();

        StubCrossTenantMessageStore() {
            // Seed with test message id=1L
            messages.put(1L, buildMessage(1L, null));
        }

        @Override
        public Optional<Message> find(Long id) {
            return Optional.ofNullable(messages.get(id));
        }

        @Override public List<Message> scan(io.casehub.qhorus.api.store.query.MessageQuery query) { return List.of(); }
        @Override public long count(io.casehub.qhorus.api.store.query.MessageQuery query) { return 0; }
        @Override public int countByChannel(UUID channelId) { return 0; }
        @Override public List<String> distinctSendersByChannel(UUID channelId, MessageType excludedType) { return List.of(); }
        @Override public Optional<Message> findLastMessage(UUID channelId) { return Optional.empty(); }
    }

    static class StubDeliveryConfig implements DeliveryConfig {
        @Override public boolean enabled() { return false; }
        @Override public int batchSize() { return 100; }
        @Override public int maxConsecutiveFailures() { return 10; }
        @Override public String reconciliationInterval() { return "30s"; }
    }

    private static Message buildMessage(Long id, UUID channelId) {
        return Message.builder()
                .id(id)
                .channelId(channelId)
                .sender("agent-1")
                .messageType(MessageType.COMMAND)
                .content("test command")
                .actorType(ActorType.AGENT)
                .createdAt(Instant.now())
                .build();
    }
}
