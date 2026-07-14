package io.casehub.qhorus.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Set;

@ApplicationScoped
public class WebSocketMessageObserver implements MessageObserver {

    private static final Logger LOG = Logger.getLogger(WebSocketMessageObserver.class);

    private final ObjectMapper objectMapper;
    private final WebSocketConnectionRegistry registry;

    @Inject
    public WebSocketMessageObserver(ObjectMapper objectMapper, WebSocketConnectionRegistry registry) {
        this.objectMapper = objectMapper;
        this.registry = registry;
    }

    @Override
    public void onMessage(MessageReceivedEvent event) {
        Set<WebSocketConnection> connections = registry.connections(event.channelId());
        if (connections.isEmpty()) {
            return;
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            LOG.warnf("Failed to serialize event for WebSocket push — channel=%s: %s",
                      event.channelId(), e.getMessage());
            return;
        }

        for (WebSocketConnection conn : Set.copyOf(connections)) {
            if (registry.tryBufferForCatchUp(conn, event.messageId(), json)) {
                continue;
            }
            try {
                conn.sendTextAndAwait(json);
            } catch (Exception e) {
                LOG.debugf("WebSocket send failed for channel %s: %s",
                           event.channelId(), e.getMessage());
            }
        }
    }

    @Override
    public Scope scope() {
        return Scope.CLUSTER;
    }
}
