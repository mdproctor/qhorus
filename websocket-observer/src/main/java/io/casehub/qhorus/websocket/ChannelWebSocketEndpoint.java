package io.casehub.qhorus.websocket;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;
import io.casehub.qhorus.api.store.CrossTenantMessageStore;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.PathParam;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;

@WebSocket(path = "/qhorus/ws/channels/{channelId}")
public class ChannelWebSocketEndpoint {

    private static final Logger LOG = Logger.getLogger(ChannelWebSocketEndpoint.class);

    @Inject
    WebSocketConnectionRegistry registry;

    @Inject
    CrossTenantChannelStore channelStore;

    @Inject
    CrossTenantMessageStore messageStore;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WebSocketCatchUpConfig config;

    @OnOpen
    @RunOnVirtualThread
    void onOpen(@PathParam String channelId, WebSocketConnection connection) {
        UUID id;
        try {
            id = UUID.fromString(channelId);
        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid channelId: %s", channelId);
            connection.closeAndAwait(new CloseReason(1008, "Invalid channelId"));
            return;
        }

        Optional<Channel> channelOpt = channelStore.findById(id);
        if (channelOpt.isEmpty()) {
            LOG.debugf("Unknown channel: %s", channelId);
            connection.closeAndAwait(new CloseReason(1008, "Unknown channel"));
            return;
        }

        String channelName = channelOpt.get().name();
        Long   lastEventId = parseLastEventId(connection);

        if (lastEventId == null) {
            registry.subscribe(id, connection);
            LOG.debugf("WebSocket client subscribed to channel %s (live-only)", channelId);
            return;
        }

        registry.subscribeCatchingUp(id, connection);
        LOG.debugf("WebSocket client subscribed to channel %s with catch-up from %d", channelId, lastEventId);

        try {
            sendControl(connection, Map.of("control", "catchup_begin"));

            int maxMessages = config.maxMessages();
            List<Message> messages = messageStore.scan(
                    MessageQuery.poll(id, lastEventId, maxMessages + 1));

            boolean       truncated = messages.size() > maxMessages;
            List<Message> toSend    = truncated ? messages.subList(0, maxMessages) : messages;

            long highestSentMessageId = lastEventId;
            for (Message msg : toSend) {
                MessageReceivedEvent event = MessageReceivedEvent.fromMessage(msg, channelName);
                connection.sendTextAndAwait(objectMapper.writeValueAsString(event));
                if (msg.id() != null && msg.id() > highestSentMessageId) {
                    highestSentMessageId = msg.id();
                }
            }

            List<WebSocketConnectionRegistry.BufferedMessage> buffered =
                    registry.completeCatchUp(id, connection);
            for (var buf : buffered) {
                if (buf.messageId() != null && buf.messageId() > highestSentMessageId) {
                    connection.sendTextAndAwait(buf.json());
                    highestSentMessageId = buf.messageId();
                }
            }

            if (truncated) {
                long headId = messageStore.findLastMessage(id)
                                          .map(Message::id).orElse(highestSentMessageId);
                sendControl(connection, Map.of(
                        "control", "catchup_truncated",
                        "oldestAvailableId", toSend.get(0).id(),
                        "headId", headId));
            } else {
                sendControl(connection, Map.of(
                        "control", "catchup_end",
                        "lastMessageId", highestSentMessageId));
            }
        } catch (Exception e) {
            LOG.warnf("Catch-up failed for channel %s: %s", channelId, e.getMessage());
            registry.cancelCatchUp(id, connection);
        }
    }

    @OnClose
    void onClose(@PathParam String channelId, WebSocketConnection connection) {
        try {
            UUID id = UUID.fromString(channelId);
            registry.unsubscribe(id, connection);
            LOG.debugf("WebSocket client unsubscribed from channel %s", channelId);
        } catch (IllegalArgumentException e) {
            LOG.debugf("Invalid channelId on close: %s", channelId);
        }
    }

    private Long parseLastEventId(WebSocketConnection connection) {
        String query = connection.handshakeRequest().query();
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && "lastEventId".equals(kv[0])) {
                try {
                    return Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    LOG.warnf("Invalid lastEventId value: %s", kv[1]);
                    return null;
                }
            }
        }
        return null;
    }

    private void sendControl(WebSocketConnection connection, Map<String, Object> control) {
        try {
            connection.sendTextAndAwait(objectMapper.writeValueAsString(control));
        } catch (Exception e) {
            LOG.warnf("Failed to send control frame: %s", e.getMessage());
        }
    }
}
