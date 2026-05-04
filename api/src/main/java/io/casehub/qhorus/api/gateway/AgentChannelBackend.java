package io.casehub.qhorus.api.gateway;

/**
 * Always registered. Internal Qhorus agent mesh. {@code actorType()} must return
 * {@code ActorType.AGENT}.
 *
 * <p>{@code post()} may throw — it is the source-of-truth write; the gateway
 * treats failure as fatal and surfaces the error to the caller.
 */
public interface AgentChannelBackend extends ChannelBackend {}
