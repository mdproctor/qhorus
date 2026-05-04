package io.casehub.qhorus.api.gateway;

import java.util.Map;

import io.casehub.ledger.api.model.ActorType;

public interface ChannelBackend {
    String backendId();
    ActorType actorType();
    void open(ChannelRef channel, Map<String, String> metadata);
    /** See sub-interface for post() exception semantics. */
    void post(ChannelRef channel, OutboundMessage message);
    void close(ChannelRef channel);
}
