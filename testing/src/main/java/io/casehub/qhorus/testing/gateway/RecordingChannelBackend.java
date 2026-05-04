package io.casehub.qhorus.testing.gateway;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;

public class RecordingChannelBackend implements ChannelBackend {

    private final String backendId;
    private final ActorType actorType;
    private final List<OutboundMessage> posts = new ArrayList<>();
    private final List<ChannelRef> opens = new ArrayList<>();
    private final List<ChannelRef> closes = new ArrayList<>();
    private volatile RuntimeException throwOnPost;

    public RecordingChannelBackend(String backendId, ActorType actorType) {
        this.backendId = backendId;
        this.actorType = actorType;
    }

    public void throwOnNextPost(RuntimeException ex) {
        this.throwOnPost = ex;
    }

    @Override
    public String backendId() { return backendId; }

    @Override
    public ActorType actorType() { return actorType; }

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

    public List<OutboundMessage> posts() { return Collections.unmodifiableList(posts); }
    public List<ChannelRef> opens() { return Collections.unmodifiableList(opens); }
    public List<ChannelRef> closes() { return Collections.unmodifiableList(closes); }
    public void clear() { posts.clear(); opens.clear(); closes.clear(); throwOnPost = null; }
}
