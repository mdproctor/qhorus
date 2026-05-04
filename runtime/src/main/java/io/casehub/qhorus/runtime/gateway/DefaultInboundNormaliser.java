package io.casehub.qhorus.runtime.gateway;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.arc.DefaultBean;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.InboundHumanMessage;
import io.casehub.qhorus.api.gateway.InboundNormaliser;
import io.casehub.qhorus.api.gateway.NormalisedMessage;
import io.casehub.qhorus.api.message.MessageType;

@DefaultBean
@ApplicationScoped
public class DefaultInboundNormaliser implements InboundNormaliser {

    @Override
    public NormalisedMessage normalise(ChannelRef channel, InboundHumanMessage raw) {
        return new NormalisedMessage(
                MessageType.QUERY,
                raw.content(),
                "human:" + raw.externalSenderId());
    }
}
