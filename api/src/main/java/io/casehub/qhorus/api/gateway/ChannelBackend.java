package io.casehub.qhorus.api.gateway;

import io.casehub.platform.api.identity.ActorType;

import java.util.Map;

public interface ChannelBackend {
    String backendId();
    ActorType actorType();
    void open(ChannelRef channel, Map<String, String> metadata);
    /** See sub-interface for post() exception semantics. */
    void post(ChannelRef channel, OutboundMessage message);
    void close(ChannelRef channel);

    default DeliveryGuarantee deliveryGuarantee() {
        return DeliveryGuarantee.BEST_EFFORT;
    }

    default PostResult postTracked(ChannelRef channel, OutboundMessage message) {
        post(channel, message);
        return PostResult.ALL_DELIVERED;
    }

    default void deliverTo(ChannelRef channel, OutboundMessage message, String participantId) {
        throw new UnsupportedOperationException(
                backendId() + " does not support per-participant delivery");
    }

    default boolean supportsParticipantDelivery() {
        return false;
    }

}
