package io.casehub.qhorus.agent.bridge;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageDispatcher;
import org.jboss.logging.Logger;

import java.util.UUID;
import java.util.concurrent.Semaphore;

public class AgentInvocationRunner implements Runnable {

    private static final Logger LOG = Logger.getLogger(AgentInvocationRunner.class);

    public static final ThreadLocal<String> CURRENT_INVOCATION_CONTEXT = new ThreadLocal<>();

    private final UUID channelId;
    private final OutboundMessage inbound;
    private final AgentChannelBinding binding;
    private final AgentBackend agentBackend;
    private final AgentSession session;
    private final MessageDispatcher dispatcher;
    private final Semaphore concurrencyGuard;

    public AgentInvocationRunner(UUID channelId, OutboundMessage inbound,
                                  AgentChannelBinding binding, AgentBackend agentBackend,
                                  AgentSession session, MessageDispatcher dispatcher,
                                  Semaphore concurrencyGuard) {
        this.channelId = channelId;
        this.inbound = inbound;
        this.binding = binding;
        this.agentBackend = agentBackend;
        this.session = session;
        this.dispatcher = dispatcher;
        this.concurrencyGuard = concurrencyGuard;
    }

    @Override
    public void run() {
        try {
            concurrencyGuard.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        String augmentedContext = buildAugmentedContext(
                inbound.invocationContext(), binding.agentInstanceId());
        CURRENT_INVOCATION_CONTEXT.set(augmentedContext);

        try {
            StringBuilder text = new StringBuilder();
            AgentEvent.InvocationComplete stats = null;

            var events = session != null
                    ? session.query(inbound.content())
                    : agentBackend.invoke(AgentSessionConfig.of(
                            binding.agentBriefing(), inbound.content()));

            for (AgentEvent event : events.subscribe().asIterable()) {
                switch (event) {
                    case AgentEvent.TextDelta td -> text.append(td.text());
                    case AgentEvent.ToolCallComplete tc -> {
                        MessageDispatch statusMsg = SpeechActMapper.mapToolStatus(
                                channelId, inbound, binding.agentInstanceId(), tc, augmentedContext);
                        dispatcher.dispatch(statusMsg);
                    }
                    case AgentEvent.InvocationComplete ic -> stats = ic;
                    case AgentEvent.ThinkingDelta ignored -> {}
                    case AgentEvent.ToolCallDelta ignored -> {}
                    case AgentEvent.ToolResult ignored -> {}
                }
            }

            if (stats != null && stats.isError()) {
                MessageDispatch failure = SpeechActMapper.mapFailure(
                        channelId, inbound, binding.agentInstanceId(),
                        new RuntimeException("Agent invocation completed with error"),
                        augmentedContext);
                dispatcher.dispatch(failure);
            } else if (!text.isEmpty()) {
                MessageDispatch response = SpeechActMapper.mapToDispatch(
                        channelId, inbound, binding.agentInstanceId(),
                        text.toString(), stats, augmentedContext);
                dispatcher.dispatch(response);
            }
        } catch (Exception e) {
            LOG.warnf(e, "Agent invocation failed for %s on channel %s",
                    binding.agentInstanceId(), channelId);
            MessageDispatch failure = SpeechActMapper.mapFailure(
                    channelId, inbound, binding.agentInstanceId(), e, augmentedContext);
            dispatcher.dispatch(failure);
        } finally {
            CURRENT_INVOCATION_CONTEXT.remove();
            concurrencyGuard.release();
        }
    }

    static String buildAugmentedContext(String existingContext, String agentId) {
        if (existingContext == null || existingContext.isBlank()) {
            return "[\"" + agentId + "\"]";
        }
        String trimmed = existingContext.strip();
        if (trimmed.endsWith("]")) {
            return trimmed.substring(0, trimmed.length() - 1)
                    + ",\"" + agentId + "\"]";
        }
        return "[\"" + agentId + "\"]";
    }
}
