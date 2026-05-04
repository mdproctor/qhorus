package io.casehub.qhorus.api.gateway;

/**
 * Unlimited per channel. Inbound capped to EVENT by gateway regardless of content.
 * actorType() must return ActorType.HUMAN.
 * Call gateway.receiveObserverSignal() when inbound arrives.
 */
public interface HumanObserverChannelBackend extends ChannelBackend {}
