package io.casehub.qhorus.agent.bridge;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.DeliveryGuarantee;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.gateway.PostResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageDispatcher;
import io.casehub.qhorus.api.message.MessageType;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

public class AgentProviderBackend implements ChannelBackend {

    private static final Logger LOG = Logger.getLogger(AgentProviderBackend.class);

    private static final Set<MessageType> INVOCATION_TYPES =
            Set.of(MessageType.COMMAND, MessageType.QUERY, MessageType.PROPOSE);

    private final AgentChannelBinding binding;
    private final AgentBackend agentBackend;
    private final MessageDispatcher dispatcher;
    private final Semaphore concurrencyGuard;
    private volatile AgentSession session;

    public AgentProviderBackend(AgentChannelBinding binding, AgentBackend agentBackend,
                                 MessageDispatcher dispatcher) {
        this.binding = binding;
        this.agentBackend = agentBackend;
        this.dispatcher = dispatcher;
        this.concurrencyGuard = new Semaphore(binding.maxConcurrency());
    }

    void setSession(AgentSession session) {
        this.session = session;
    }

    @Override
    public String backendId() {
        return "agent-bridge-" + binding.agentInstanceId();
    }

    @Override
    public ActorType actorType() {
        return ActorType.AGENT;
    }

    @Override
    public DeliveryGuarantee deliveryGuarantee() {
        return DeliveryGuarantee.AT_LEAST_ONCE;
    }

    @Override
    public void open(ChannelRef channel, Map<String, String> metadata) {}

    @Override
    public void post(ChannelRef channel, OutboundMessage message) {
        postTracked(channel, message);
    }

    @Override
    public PostResult postTracked(ChannelRef channel, OutboundMessage message) {
        if (message.sender().equals(binding.agentInstanceId())) {
            return PostResult.ALL_DELIVERED;
        }

        String context = message.invocationContext();
        if (context != null && context.contains("\"" + binding.agentInstanceId() + "\"")) {
            LOG.warnf("Indirect loop detected for %s — visited: %s",
                    binding.agentInstanceId(), context);
            try {
                dispatcher.dispatch(MessageDispatch.builder()
                        .channelId(channel.id())
                        .sender("system:agent-bridge")
                        .type(MessageType.EVENT)
                        .actorType(ActorType.SYSTEM)
                        .telemetry("{\"tool_name\":\"loop_detection\",\"source_entity\":\"agent-bridge\""
                                + ",\"loop_agent\":\"" + binding.agentInstanceId() + "\""
                                + ",\"invocation_context\":" + context + "}")
                        .build());
            } catch (Exception e) {
                LOG.debugf(e, "Failed to dispatch loop detection EVENT");
            }
            return PostResult.ALL_DELIVERED;
        }

        if (!INVOCATION_TYPES.contains(message.type())) {
            return PostResult.ALL_DELIVERED;
        }

        String target = message.target();
        if (target != null && !target.isBlank()
                && !target.equals(binding.agentInstanceId())) {
            return PostResult.ALL_DELIVERED;
        }

        Thread.ofVirtual().name("agent-bridge-" + binding.agentInstanceId())
                .start(new AgentInvocationRunner(
                        channel.id(), message, binding, agentBackend,
                        session, dispatcher, concurrencyGuard));

        return PostResult.ALL_DELIVERED;
    }

    @Override
    public void close(ChannelRef channel) {
        if (session != null) {
            session.close();
            session = null;
        }
    }
}
