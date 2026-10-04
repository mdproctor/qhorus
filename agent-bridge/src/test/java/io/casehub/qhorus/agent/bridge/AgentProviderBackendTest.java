package io.casehub.qhorus.agent.bridge;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.DeliveryGuarantee;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.gateway.PostResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AgentProviderBackendTest {

    private static final UUID CHANNEL_ID = UUID.randomUUID();
    private static final String AGENT_ID = "code-reviewer";
    private static final String BACKEND_KEY = "claude";

    private final CopyOnWriteArrayList<MessageDispatch> dispatched = new CopyOnWriteArrayList<>();
    private CountDownLatch invocationLatch;
    private AgentBackend stubBackend;
    private AgentProviderBackend backend;

    @BeforeEach
    void setUp() {
        dispatched.clear();
        invocationLatch = new CountDownLatch(1);
        stubBackend = new AgentBackend() {
            @Override public String key() { return BACKEND_KEY; }
            @Override public Multi<AgentEvent> invoke(AgentSessionConfig config) {
                return Multi.createFrom().<AgentEvent>items(
                        new AgentEvent.TextDelta("LGTM"),
                        new AgentEvent.InvocationComplete(100, 50, 0, 0, 0, null, 1000L, 900L, "s1", 1, false)
                );
            }
            @Override public AgentSession openSession(AgentSessionInit init) { return null; }
        };
        var binding = AgentChannelBinding.builder(CHANNEL_ID, AGENT_ID, BACKEND_KEY)
                .persistent(false).maxConcurrency(3).build();
        backend = new AgentProviderBackend(binding, stubBackend, dispatch -> {
            dispatched.add(dispatch);
            invocationLatch.countDown();
            return null;
        });
    }

    private OutboundMessage message(MessageType type, String sender, String target) {
        return new OutboundMessage(UUID.randomUUID(), 1L, sender, type,
                "Review this", null, UUID.randomUUID().toString(), null,
                ActorType.AGENT, List.of(), target, null);
    }

    @Test
    void postTracked_commandTriggers_invocation() throws Exception {
        PostResult result = backend.postTracked(
                new ChannelRef(CHANNEL_ID, "review-channel"),
                message(MessageType.COMMAND, "requester", AGENT_ID));
        assertThat(result).isEqualTo(PostResult.ALL_DELIVERED);
        invocationLatch.await(5, TimeUnit.SECONDS);
        assertThat(dispatched).anyMatch(d -> d.type() == MessageType.RESPONSE);
    }

    @Test
    void postTracked_queryTriggers_invocation() throws Exception {
        PostResult result = backend.postTracked(
                new ChannelRef(CHANNEL_ID, "review-channel"),
                message(MessageType.QUERY, "requester", AGENT_ID));
        assertThat(result).isEqualTo(PostResult.ALL_DELIVERED);
        invocationLatch.await(5, TimeUnit.SECONDS);
        assertThat(dispatched).anyMatch(d -> d.type() == MessageType.RESPONSE);
    }

    @Test
    void postTracked_statusSkips_invocation() throws Exception {
        backend.postTracked(
                new ChannelRef(CHANNEL_ID, "review-channel"),
                message(MessageType.STATUS, "someone", AGENT_ID));
        Thread.sleep(200);
        assertThat(dispatched).isEmpty();
    }

    @Test
    void postTracked_senderLoopGuard_skips() throws Exception {
        backend.postTracked(
                new ChannelRef(CHANNEL_ID, "review-channel"),
                message(MessageType.COMMAND, AGENT_ID, AGENT_ID));
        Thread.sleep(200);
        assertThat(dispatched).isEmpty();
    }

    @Test
    void postTracked_targetMismatch_skips() throws Exception {
        backend.postTracked(
                new ChannelRef(CHANNEL_ID, "ch"),
                message(MessageType.COMMAND, "requester", "other-agent"));
        Thread.sleep(200);
        assertThat(dispatched).isEmpty();
    }

    @Test
    void postTracked_nullTarget_triggers_invocation() throws Exception {
        var msg = new OutboundMessage(UUID.randomUUID(), 1L, "requester",
                MessageType.COMMAND, "content", null, UUID.randomUUID().toString(), null,
                ActorType.AGENT, List.of(), null, null);
        backend.postTracked(new ChannelRef(CHANNEL_ID, "ch"), msg);
        invocationLatch.await(5, TimeUnit.SECONDS);
        assertThat(dispatched).anyMatch(d -> d.type() == MessageType.RESPONSE);
    }

    @Test
    void postTracked_indirectLoopDetected_skipsInvocation() throws Exception {
        String context = "[\"" + AGENT_ID + "\",\"other-agent\"]";
        var msg = new OutboundMessage(UUID.randomUUID(), 1L, "requester",
                MessageType.COMMAND, "content", null, UUID.randomUUID().toString(), null,
                ActorType.AGENT, List.of(), AGENT_ID, null, context);
        backend.postTracked(new ChannelRef(CHANNEL_ID, "ch"), msg);
        Thread.sleep(200);
        assertThat(dispatched).noneMatch(d -> d.type() == MessageType.RESPONSE);
        assertThat(dispatched).anyMatch(d -> d.type() == MessageType.EVENT);
    }

    @Test
    void postTracked_noLoopInContext_proceedsNormally() throws Exception {
        String context = "[\"other-agent\"]";
        var msg = new OutboundMessage(UUID.randomUUID(), 1L, "requester",
                MessageType.COMMAND, "content", null, UUID.randomUUID().toString(), null,
                ActorType.AGENT, List.of(), AGENT_ID, null, context);
        backend.postTracked(new ChannelRef(CHANNEL_ID, "ch"), msg);
        invocationLatch.await(5, TimeUnit.SECONDS);
        assertThat(dispatched).anyMatch(d -> d.type() == MessageType.RESPONSE);
    }

    @Test
    void deliveryGuarantee_isAtLeastOnce() {
        assertThat(backend.deliveryGuarantee()).isEqualTo(DeliveryGuarantee.AT_LEAST_ONCE);
    }

    @Test
    void backendId_includesAgentInstanceId() {
        assertThat(backend.backendId()).isEqualTo("agent-bridge-code-reviewer");
    }
}
