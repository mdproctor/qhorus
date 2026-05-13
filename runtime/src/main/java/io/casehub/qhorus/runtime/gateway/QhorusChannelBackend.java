package io.casehub.qhorus.runtime.gateway;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.qhorus.api.gateway.AgentChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
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

    @Override
    public void post(ChannelRef channel, OutboundMessage message) {
        String correlationId = message.correlationId() != null
                ? message.correlationId().toString() : null;
        messageService.send(channel.id(), message.sender(), message.type(),
                message.content(), correlationId, null, null, null,
                message.senderActorType());
    }

    @Override
    public void close(ChannelRef channel) { }
}
