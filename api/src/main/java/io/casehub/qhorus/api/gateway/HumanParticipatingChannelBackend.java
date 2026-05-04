package io.casehub.qhorus.api.gateway;

/**
 * At most one per channel. Full speech act inbound via InboundNormaliser.
 * actorType() must return ActorType.HUMAN.
 * Call gateway.receiveHumanMessage() when inbound arrives.
 */
public interface HumanParticipatingChannelBackend extends ChannelBackend {}
