package io.casehub.qhorus.runtime.gateway;

import jakarta.enterprise.context.ApplicationScoped;
import io.casehub.qhorus.api.gateway.ChannelActivityBroadcaster;
import io.quarkus.arc.DefaultBean;

/**
 * No-op default implementation of {@link ChannelActivityBroadcaster}.
 *
 * <p>Override by providing a CDI bean with {@code @Alternative @Priority(100)} or higher.
 *
 * <p>Refs #162.
 */
@DefaultBean
@ApplicationScoped
public class NoOpChannelActivityBroadcaster implements ChannelActivityBroadcaster {
    @Override
    public void broadcast(ChannelActivityEvent event) {
        // no-op
    }
}
