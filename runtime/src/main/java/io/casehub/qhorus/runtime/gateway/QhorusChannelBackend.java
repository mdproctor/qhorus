package io.casehub.qhorus.runtime.gateway;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.AgentChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;

/**
 * Default agent-channel backend registered on every channel by {@link ChannelGateway}.
 *
 * <p>{@code post()} is a deliberate no-op: {@link ChannelGateway#fanOut} explicitly
 * skips this backend ({@code if (entry.backend() == agentBackend) continue}) because
 * message persistence already happened before fanOut is called. The backend exists
 * solely as the registry anchor for the {@code qhorus-internal} slot.
 */
@ApplicationScoped
public class QhorusChannelBackend implements AgentChannelBackend {

    private static final Logger LOG = Logger.getLogger(QhorusChannelBackend.class);

    @Override
    public String backendId() { return "qhorus-internal"; }

    @Override
    public ActorType actorType() { return ActorType.AGENT; }

    @Override
    public void open(final ChannelRef channel, final Map<String, String> metadata) { }

    /** No-op — fanOut explicitly skips this backend; persistence already happened. */
    @Override
    public void post(final ChannelRef channel, final OutboundMessage message) {
        LOG.debugf("QhorusChannelBackend.post() no-op for channel=%s (persistence already done)", channel.name());
    }

    @Override
    public void close(final ChannelRef channel) { }
}
