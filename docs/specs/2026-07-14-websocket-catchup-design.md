# WebSocket Observer: Catch-Up Mechanism for Reconnecting Clients

**Issue:** #346
**Date:** 2026-07-14
**Status:** Approved

## Problem

The WebSocket observer module pushes `MessageReceivedEvent` JSON to connected clients via `WebSocketMessageObserver` (Scope CLUSTER). The connection registry (`WebSocketConnectionRegistry`) is in-memory — when a client disconnects, its subscription is removed. On reconnection, the client resubscribes but has no way to recover messages missed during the gap.

## Design

### Core API Change: `MessageReceivedEvent` gains `messageId`

`MessageReceivedEvent` gains a `Long messageId` as its first field — the message's sequential database ID. This is a first-principles fix: the message identity was missing from the event, preventing any observer from correlating back to the original message.

```java
public record MessageReceivedEvent(
        Long messageId,
        String channelName,
        UUID channelId,
        String tenancyId,
        MessageType messageType,
        String senderId,
        String correlationId,
        Instant occurredAt,
        String content,
        String topic) { ... }
```

Breaking change to all call sites (pre-release — no backward compatibility concern).

`MessageObserverDispatcher.dispatch()` and `dispatchClusterOnly()` pass `message.id()` when constructing the event.

### `MessageReceivedEvent.fromMessage()` Factory

A static factory method on the record converts `Message` + `channelName` → `MessageReceivedEvent`:

```java
public static MessageReceivedEvent fromMessage(Message message, String channelName) {
    String content = message.messageType() == MessageType.EVENT ? null : message.content();
    Instant occurredAt = message.createdAt() != null ? message.createdAt() : Instant.now();
    return new MessageReceivedEvent(
            message.id(), channelName, message.channelId(), message.tenancyId(),
            message.messageType(), message.sender(), message.correlationId(),
            occurredAt, content, message.topic());
}
```

Eliminates duplication between `MessageObserverDispatcher` (live dispatch) and `ChannelWebSocketEndpoint` (catch-up replay). Both call sites use the same conversion. `MessageObserverDispatcher` delegates to this factory.

### CloudEventMapper Improvement

`CloudEventMapper.toCloudEvent()` uses `String.valueOf(event.messageId())` as the CloudEvent ID instead of `UUID.randomUUID()`. The message ID is unique within the source (channel), sequential, and enables CloudEvent-level dedup. Falls back to random UUID if `messageId` is null.

### WebSocket Catch-Up Protocol

Client connects with an optional query parameter:

```
ws://host/qhorus/ws/channels/{channelId}?lastEventId=42
```

If `lastEventId` is absent or empty — live-only mode (current behavior, no catch-up).

**Wire format:**

Message frames are `MessageReceivedEvent` JSON (now including `messageId`). Control frames are distinguished by a `control` field:

```json
{"control":"catchup_begin"}
```
```json
{"messageId":43,"channelName":"ops","channelId":"...","messageType":"STATUS",...}
{"messageId":44,"channelName":"ops","channelId":"...","messageType":"COMMAND",...}
```
```json
{"control":"catchup_end","lastMessageId":44}
```

If the gap exceeds the configured maximum:

```json
{"control":"catchup_truncated","oldestAvailableId":43,"headId":590}
```

Client discrimination: `control` field present → control frame; `messageType` field present → message frame. No overlap.

`catchup_end` includes `lastMessageId` so the client knows its cursor is current. `catchup_truncated` includes `oldestAvailableId` (first replayed message) and `headId` (current channel head) so the client can show a gap indicator or fetch history via REST.

### `ChannelWebSocketEndpoint` Catch-Up Flow

`@OnOpen` gains `@RunOnVirtualThread` — catch-up involves JDBC queries and multiple blocking `sendTextAndAwait` calls that cannot run on the Vert.x event loop.

Flow:

1. Parse `channelId`, validate channel exists via `CrossTenantChannelStore.findById()`, cache `channelName`
2. Parse `lastEventId` from `connection.handshakeRequest().query()`
3. If no `lastEventId` → subscribe via `registry.subscribe(channelId, connection)` and return (live-only mode)
4. Subscribe with catch-up buffering — `registry.subscribeCatchingUp(channelId, connection)`. Live messages dispatched to this connection are buffered by `WebSocketMessageObserver` instead of sent directly.
5. Send `{"control":"catchup_begin"}`
6. Query `crossTenantMessageStore.scan(MessageQuery.poll(channelId, lastEventId, maxMessages + 1))` — +1 to detect truncation
7. For each message (up to `maxMessages`): convert via `MessageReceivedEvent.fromMessage(message, channelName)`, serialize, `sendTextAndAwait`. Track `lastCatchUpMessageId`.
8. Transition to live — `registry.completeCatchUp(channelId, connection)` atomically clears catch-up state and returns `List<BufferedMessage>`. Send each buffered message with `messageId > lastCatchUpMessageId` (dedup against catch-up results). Track `highestSentMessageId` — the maximum `messageId` across catch-up and flushed buffer messages.
9. If result size > `maxMessages` → query `crossTenantMessageStore.findLastMessage(channelId)` to obtain head ID, send `catchup_truncated` with `oldestAvailableId` and `headId`; else → send `catchup_end` with `lastMessageId` set to `highestSentMessageId`

**Error handling:** Steps 4–9 are wrapped in a try-catch. On any exception after step 4 (DB error, `sendTextAndAwait` failure, unchecked exception), `registry.cancelCatchUp(channelId, connection)` discards the buffer before the exception propagates. `unsubscribe()` also cleans up any catch-up buffer as a defensive safety net — ensuring no leak even if the error path is reached via `@OnClose` before `cancelCatchUp`.

**Server-side catch-up buffering:** Between subscribe (step 4) and transition (step 8), live messages arrive for this connection via `WebSocketMessageObserver`. These are buffered in a per-connection queue inside `WebSocketConnectionRegistry` instead of being sent to the client. After catch-up sends complete, the buffer is atomically drained and flushed — ensuring strictly ordered delivery without interleaving catch-up and live messages on the wire.

`WebSocketMessageObserver.onMessage()` checks catch-up state before sending: `registry.tryBufferForCatchUp(connection, event.messageId(), json)` returns `true` if the message was buffered (connection is catching up), `false` if the connection is live and the caller should send directly.

**`WebSocketConnectionRegistry` additions:**

- `subscribeCatchingUp(UUID channelId, WebSocketConnection connection)` — subscribes and creates a per-connection catch-up buffer
- `completeCatchUp(UUID channelId, WebSocketConnection connection)` — atomically transitions to live mode, returns `List<BufferedMessage>` (record: `Long messageId`, `String json`)
- `tryBufferForCatchUp(WebSocketConnection connection, Long messageId, String json)` — buffers messageId and json if catching up, returns `false` if live
- `cancelCatchUp(UUID channelId, WebSocketConnection connection)` — discards catch-up buffer without flushing (error path)
- `unsubscribe(UUID channelId, WebSocketConnection connection)` — unchanged channel-set removal; also cleans up any catch-up buffer (defensive safety net)

New injections: `CrossTenantChannelStore`, `CrossTenantMessageStore`, `ObjectMapper`, `WebSocketCatchUpConfig`.

### Configuration

```properties
casehub.qhorus.websocket.catchup.max-messages=500
```

Single property via `@ConfigMapping(prefix = "casehub.qhorus.websocket.catchup")`. Default 500.

## Changes Summary

| Change | Module | Scope |
|--------|--------|-------|
| Add `messageId` to `MessageReceivedEvent` | `api` | Breaking, all call sites |
| Add `fromMessage()` factory | `api` | New static method |
| `MessageObserverDispatcher` passes `message.id()`, delegates to `fromMessage()` | `runtime` | 2 construction sites |
| `CloudEventMapper` uses `messageId` as CloudEvent ID | `runtime` | 1 method |
| `ChannelWebSocketEndpoint` catch-up logic + `@RunOnVirtualThread` | `websocket-observer` | Expanded `@OnOpen` |
| `WebSocketConnectionRegistry` catch-up buffering | `websocket-observer` | 5 new methods, `BufferedMessage` record |
| `WebSocketMessageObserver` catch-up awareness | `websocket-observer` | Buffering check in `onMessage` |
| `WebSocketCatchUpConfig` | `websocket-observer` | New `@ConfigMapping` |
| Update all `MessageReceivedEvent` test constructors | cross-module | Mechanical |

No new Flyway migration. No new modules. No new store interfaces.

## Testing

**Unit tests (CDI-free, `websocket-observer/`):**

- `MessageReceivedEvent.fromMessage()` — field mapping, EVENT content nulling, null `createdAt` fallback
- `ChannelWebSocketEndpoint` catch-up:
  - No `lastEventId` → no catch-up, no control frames
  - `lastEventId` present → catchup_begin, messages in order, catchup_end
  - Truncation → catchup_truncated with correct headId from `findLastMessage`
  - Invalid `lastEventId` (non-numeric) → no catch-up, log warning
  - Unknown channel → connection closed with error
  - Invalid channelId (not a UUID) → connection closed with error
  - Client already current (empty result) → catchup_begin + catchup_end, no messages
  - DB error during catch-up → buffer cleaned up via `cancelCatchUp`, no leak
  - `catchup_end.lastMessageId` reflects highest messageId including flushed buffer
- `WebSocketConnectionRegistry` catch-up buffering:
  - `subscribeCatchingUp` → messages buffered via `tryBufferForCatchUp`
  - `completeCatchUp` → returns `List<BufferedMessage>` with messageId and json, subsequent `tryBufferForCatchUp` returns `false`
  - `cancelCatchUp` → discards buffer, subsequent `tryBufferForCatchUp` returns `false`
  - `unsubscribe` during catch-up → also cleans up catch-up buffer
  - `subscribe` (live-only) → `tryBufferForCatchUp` returns `false` immediately
- `WebSocketMessageObserver` catch-up integration:
  - Catching-up connection → messages buffered, not sent
  - Live connection → messages sent directly
  - Mixed connections on same channel → only catching-up ones buffered
- `CloudEventMapper` → messageId used as CloudEvent ID, null fallback

**Integration tests (`@QuarkusTest`, `websocket-observer/`):**

- WebSocket connect with `lastEventId` query parameter → receives catchup_begin, replayed messages, catchup_end in order
- Concurrent live dispatch during catch-up → no message loss, no interleaving
- `@RunOnVirtualThread` interaction with blocking `sendTextAndAwait` and JDBC queries
- Unknown channel → connection closed with appropriate error
- Truncation with `maxMessages` exceeded → catchup_truncated with valid headId

**Existing test updates (mechanical):** All `MessageReceivedEvent` constructor call sites gain `messageId` as first parameter.

## Known Constraints

- **Cross-tenant subscriptions:** WebSocket subscriptions are cross-tenant — any client can subscribe to any channel regardless of tenancy. Catch-up uses `CrossTenantMessageStore` and `CrossTenantChannelStore`, consistent with the live dispatch path (`WebSocketMessageObserver` delivers to all subscribers regardless of tenancy). Tenancy enforcement for WebSocket connections is a cross-cutting authentication concern, not specific to catch-up.
- **Single-node registry (#347):** `WebSocketConnectionRegistry` is JVM-local. Catch-up replays from the message store (shared), but subscription state does not survive server restart. Clients must reconnect and re-catch-up after a server restart.
