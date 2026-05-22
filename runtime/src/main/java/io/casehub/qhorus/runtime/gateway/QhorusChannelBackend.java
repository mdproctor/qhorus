package io.casehub.qhorus.runtime.gateway;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.AgentChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.runtime.message.MessageService;

@ApplicationScoped
public class QhorusChannelBackend implements AgentChannelBackend {

    final MessageService messageService;

    @Inject
    public QhorusChannelBackend(MessageService messageService) {
        this.messageService = messageService;
    }

    @Override
    public String backendId() { return "qhorus-internal"; }

    @Override
    public ActorType actorType() { return ActorType.AGENT; }

    @Override
    public void open(ChannelRef channel, Map<String, String> metadata) { }

    /**
     * Dispatches an outbound message into qhorus. Called only from the test-only
     * {@code ChannelGateway.post()} path — do NOT use from production fan-out.
     *
     * <p>Limitation: reply-type messages (DONE, RESPONSE, DECLINE, FAILURE, HANDOFF)
     * require {@code inReplyTo} in the builder, but {@link OutboundMessage} does not
     * carry {@code inReplyTo}. Only COMMAND, QUERY, STATUS, and EVENT are safe to
     * dispatch through this path. See qhorus#190 for the fix.
     */
    @Override
    public void post(ChannelRef channel, OutboundMessage message) {
        String correlationId = message.correlationId() != null
                ? message.correlationId().toString() : null;
        messageService.dispatch(MessageDispatch.builder()
                .channelId(channel.id())
                .sender(message.sender())
                .type(message.type())
                .content(message.content())
                .correlationId(correlationId)
                .actorType(message.senderActorType())
                .build());
    }

    @Override
    public void close(ChannelRef channel) { }
}
