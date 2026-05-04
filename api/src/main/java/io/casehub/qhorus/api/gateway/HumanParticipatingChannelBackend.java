package io.casehub.qhorus.api.gateway;

/**
 * At most one per channel. Full speech act inbound via {@link InboundNormaliser}.
 * {@code actorType()} must return {@code ActorType.HUMAN}.
 * Call {@code gateway.receiveHumanMessage()} when inbound arrives.
 *
 * <p>{@code post()} must catch all exceptions internally — failure is non-fatal;
 * the gateway logs and continues.
 */
public interface HumanParticipatingChannelBackend extends ChannelBackend {}
