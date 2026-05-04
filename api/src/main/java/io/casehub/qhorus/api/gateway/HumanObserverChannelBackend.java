package io.casehub.qhorus.api.gateway;

/**
 * Unlimited per channel. Inbound capped to {@code EVENT} by gateway regardless of content.
 * {@code actorType()} must return {@code ActorType.HUMAN}.
 * Call {@code gateway.receiveObserverSignal()} when inbound arrives.
 *
 * <p>{@code post()} must catch all exceptions internally — failure is non-fatal;
 * the gateway logs and continues.
 */
public interface HumanObserverChannelBackend extends ChannelBackend {}
