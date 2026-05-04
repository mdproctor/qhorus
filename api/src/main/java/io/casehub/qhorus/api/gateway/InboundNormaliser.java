package io.casehub.qhorus.api.gateway;

@FunctionalInterface
public interface InboundNormaliser {
    NormalisedMessage normalise(ChannelRef channel, InboundHumanMessage raw);
}
