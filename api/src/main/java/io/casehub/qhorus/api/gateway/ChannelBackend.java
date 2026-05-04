package io.casehub.qhorus.api.gateway;

import java.util.Map;

import io.casehub.ledger.api.model.ActorType;

public interface ChannelBackend {
    String backendId();
    ActorType actorType();
    void open(ChannelRef channel, Map<String, String> metadata);
    /**
     * AgentChannelBackend.post() may throw — it is the source-of-truth write; failure is fatal.
     * All other implementations must catch internally — failure is non-fatal.
     */
    void post(ChannelRef channel, OutboundMessage message);
    void close(ChannelRef channel);
}
