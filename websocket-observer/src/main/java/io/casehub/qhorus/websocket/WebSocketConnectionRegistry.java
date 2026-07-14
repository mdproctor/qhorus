package io.casehub.qhorus.websocket;

import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class WebSocketConnectionRegistry {

    public record BufferedMessage(Long messageId, String json) {}

    private final ConcurrentHashMap<UUID, Set<WebSocketConnection>>             channels       = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WebSocketConnection, List<BufferedMessage>> catchUpBuffers =
            new ConcurrentHashMap<>();

    public void subscribe(UUID channelId, WebSocketConnection connection) {
        channels.computeIfAbsent(channelId, k -> ConcurrentHashMap.newKeySet()).add(connection);
    }

    public void subscribeCatchingUp(UUID channelId, WebSocketConnection connection) {
        channels.computeIfAbsent(channelId, k -> ConcurrentHashMap.newKeySet()).add(connection);
        catchUpBuffers.put(connection, new ArrayList<>());
    }

    public List<BufferedMessage> completeCatchUp(UUID channelId, WebSocketConnection connection) {
        List<BufferedMessage> buffer = catchUpBuffers.remove(connection);
        return buffer != null ? List.copyOf(buffer) : List.of();
    }

    public boolean tryBufferForCatchUp(WebSocketConnection connection, Long messageId, String json) {
        List<BufferedMessage> buffer = catchUpBuffers.get(connection);
        if (buffer == null) {
            return false;
        }
        synchronized (buffer) {
            buffer.add(new BufferedMessage(messageId, json));
        }
        return true;
    }

    public void cancelCatchUp(UUID channelId, WebSocketConnection connection) {
        catchUpBuffers.remove(connection);
    }

    public void unsubscribe(UUID channelId, WebSocketConnection connection) {
        channels.computeIfPresent(channelId, (k, conns) -> {
            conns.remove(connection);
            return conns.isEmpty() ? null : conns;
        });
        catchUpBuffers.remove(connection);
    }

    public Set<WebSocketConnection> connections(UUID channelId) {
        return channels.getOrDefault(channelId, Set.of());
    }

    int channelCount() {
        return channels.size();
    }
}
