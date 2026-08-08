package io.casehub.qhorus.testing.gateway;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.DeliveryGuarantee;
import io.casehub.qhorus.api.gateway.OutboundMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class RecordingChannelBackend implements ChannelBackend {

    private final String backendId;
    private final ActorType actorType;
    private final DeliveryGuarantee deliveryGuarantee;
    private final List<OutboundMessage> posts = new ArrayList<>();
    private final List<ChannelRef> opens = new ArrayList<>();
    private final List<ChannelRef> closes = new ArrayList<>();
    private volatile RuntimeException throwOnPost;

    public RecordingChannelBackend(String backendId, ActorType actorType) {
        this(backendId, actorType, DeliveryGuarantee.BEST_EFFORT);
    }

    public RecordingChannelBackend(String backendId, ActorType actorType, DeliveryGuarantee deliveryGuarantee) {
        this.backendId = backendId;
        this.actorType = actorType;
        this.deliveryGuarantee = deliveryGuarantee;
    }

    public void throwOnNextPost(RuntimeException ex) {
        this.throwOnPost = ex;
    }

    @Override
    public String backendId() { return backendId; }

    @Override
    public ActorType actorType() { return actorType; }

    @Override
    public DeliveryGuarantee deliveryGuarantee() { return deliveryGuarantee; }

    @Override
    public void open(ChannelRef channel, Map<String, String> metadata) {
        opens.add(channel);
    }

    @Override
    public void post(ChannelRef channel, OutboundMessage message) {
        if (throwOnPost != null) {
            RuntimeException ex = throwOnPost;
            throwOnPost = null;
            throw ex;
        }
        posts.add(message);
    }

    @Override
    public void close(ChannelRef channel) {
        closes.add(channel);
    }

    private final    List<OutboundMessage> postTrackedCalls = new ArrayList<>();
    private final    List<DeliverToCall>   deliverToCalls   = new ArrayList<>();
    private          java.util.Set<String> failParticipants = java.util.Set.of();
    private volatile RuntimeException      throwOnDeliverTo;

    public record DeliverToCall(ChannelRef channel, OutboundMessage message, String participantId) {}

    public void setFailParticipants(java.util.Set<String> participantIds) {
        this.failParticipants = java.util.Set.copyOf(participantIds);
    }

    public void throwOnNextDeliverTo(RuntimeException ex) {
        this.throwOnDeliverTo = ex;
    }

    @Override
    public io.casehub.qhorus.api.gateway.PostResult postTracked(ChannelRef channel, OutboundMessage message) {
        if (throwOnPost != null) {
            RuntimeException ex = throwOnPost;
            throwOnPost = null;
            throw ex;
        }
        posts.add(message);
        postTrackedCalls.add(message);
        return failParticipants.isEmpty()
               ? io.casehub.qhorus.api.gateway.PostResult.ALL_DELIVERED
               : new io.casehub.qhorus.api.gateway.PostResult(failParticipants);
    }

    @Override
    public void deliverTo(ChannelRef channel, OutboundMessage message, String participantId) {
        if (throwOnDeliverTo != null) {
            RuntimeException ex = throwOnDeliverTo;
            throwOnDeliverTo = null;
            throw ex;
        }
        deliverToCalls.add(new DeliverToCall(channel, message, participantId));
    }

    @Override
    public boolean supportsParticipantDelivery() {
        return true;
    }

    public List<OutboundMessage> postTrackedCalls() {return Collections.unmodifiableList(postTrackedCalls);}

    public List<DeliverToCall> deliverToCalls()     {return Collections.unmodifiableList(deliverToCalls);}


    public List<OutboundMessage> posts() { return Collections.unmodifiableList(posts); }
    public List<ChannelRef> opens() { return Collections.unmodifiableList(opens); }
    public List<ChannelRef> closes() { return Collections.unmodifiableList(closes); }
    public void clear() { posts.clear(); opens.clear(); closes.clear(); postTrackedCalls.clear(); deliverToCalls.clear(); throwOnPost = null; throwOnDeliverTo = null; failParticipants = java.util.Set.of(); }
}
