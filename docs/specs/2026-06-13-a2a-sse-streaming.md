# A2A SSE Streaming — #147

**Date:** 2026-06-13 (revised × 2)
**Issue:** qhorus#147
**Branch:** issue-276-sxs-batch

---

## Problem

`GET /a2a/tasks/{id}` is a poll endpoint. External A2A orchestrators polling for terminal state must busy-poll, wasting connections and adding latency. `A2AChannelBackend.post()` exists as the correct hook for SSE push but is currently a logging no-op.

---

## Scope

Blocking path only (`A2AChannelBackend` + `A2AResource`). The reactive path has no active `ReactiveA2AChannelBackend` — that is a separate future issue.

---

## Design

### §1 — Registry in A2AChannelBackend

The CDI bean cannot inject `@Context Sse` (JAX-RS context injection only works in resource classes). Instead, `A2AChannelBackend` stores a registry of `Consumer<OutboundMessage>` callbacks keyed by correlationId. Each consumer owns its own `Sse` and `SseEventSink` — they are captured in the closure when the stream endpoint creates them. The backend simply iterates and notifies.

**Registry field:**
```java
private final ConcurrentHashMap<UUID, Set<Consumer<OutboundMessage>>> sseStreams =
        new ConcurrentHashMap<>();
```

**Registration:**
```java
void registerStream(UUID correlationId, Consumer<OutboundMessage> consumer) {
    sseStreams.computeIfAbsent(correlationId, k -> ConcurrentHashMap.newKeySet())
              .add(consumer);
}
```

**Deregistration — race-free via `compute()`:**
```java
void deregisterStream(UUID correlationId, Consumer<OutboundMessage> consumer) {
    // compute() is atomic: no window between isEmpty() and remove()
    // Without it: another thread can registerStream() between isEmpty() check and
    // sseStreams.remove(), permanently orphaning the newly-added consumer.
    sseStreams.compute(correlationId, (k, s) -> {
        if (s == null) return null;
        s.remove(consumer);
        return s.isEmpty() ? null : s;
    });
}
```

**Test-visible accessor** (for integration tests — see §8):
```java
int streamCount(UUID correlationId) {
    Set<Consumer<OutboundMessage>> s = sseStreams.get(correlationId);
    return s == null ? 0 : s.size();
}
```

**Updated `post()`:**
```java
@Override
public void post(ChannelRef channel, OutboundMessage message) {
    LOG.debugf("A2A backend notified: channel=%s correlationId=%s type=%s",
            channel.name(), message.correlationId(), message.type());
    if (message.correlationId() == null) return;  // EVENT messages always have null correlationId
    Set<Consumer<OutboundMessage>> consumers = sseStreams.get(message.correlationId());
    if (consumers == null || consumers.isEmpty()) return;
    consumers.forEach(c -> c.accept(message));
}
```

`post()` is the notification hub only — it does not manage cleanup. Each consumer manages its own lifecycle (sink close + deregister). This keeps `post()` simple.

---

### §2 — New endpoint `GET /a2a/tasks/{id}/stream` in A2AResource

```java
@GET
@Path("/tasks/{id}/stream")
@Produces(MediaType.SERVER_SENT_EVENTS)
@Transactional
public void streamTask(
        @PathParam("id") String taskId,
        @Context SseEventSink sink,
        @Context Sse sse) {
```

**`@Transactional` semantics:** the initial CommitmentStore check and message existence lookup must be atomic — same requirement as `getTask()`. With SSE, `@Transactional` on a `void` method commits when the method body returns. The sink stays open after return; events flow without a transaction. Correct pattern: transactional initial check, then event-driven push.

**Logic:**

1. Return 501 if `!config.a2a().enabled()` — send `event: error` event and close.
2. Parse `taskId` as UUID; send `event: error` and close if not valid.
3. Look up messages by correlationId; if empty, send `event: error` event and close (task not found).
4. If already terminal (same CommitmentStore + message-history check as `getTask()`): send a single final `event: task_status_update` and close immediately. No dangling connection.
5. Otherwise: register a consumer with the backend; the consumer handles events until terminal or broken pipe.

**Consumer — `AtomicReference` for self-reference, deregister on any send failure:**

```java
UUID corrId = UUID.fromString(taskId);  // after UUID validation
AtomicReference<Consumer<OutboundMessage>> ref = new AtomicReference<>();
Consumer<OutboundMessage> consumer = msg -> {
    if (sink.isClosed()) {
        a2aBackend.deregisterStream(corrId, ref.get());
        return;
    }
    boolean terminal = A2ATaskState.TERMINAL_TYPES.contains(msg.type());
    String state = A2ATaskState.fromMessageType(msg.type());
    String json = """
            {"id":"%s","status":{"state":"%s"},"final":%b}
            """.formatted(taskId, state, terminal).strip();
    try {
        sink.send(sse.newEventBuilder()
                .name("task_status_update")
                .data(json)
                .build());
    } catch (Exception e) {
        // Broken pipe or I/O error — deregister immediately.
        // If left registered, every subsequent post() call will fail on this broken sink.
        LOG.debugf("SSE send failed for correlationId %s — deregistering", corrId);
        a2aBackend.deregisterStream(corrId, ref.get());
        return;
    }
    if (terminal) {
        if (!sink.isClosed()) sink.close();
        a2aBackend.deregisterStream(corrId, ref.get());
    }
};
ref.set(consumer);
a2aBackend.registerStream(corrId, consumer);
```

---

### §3 — SSE event format

**Success events:**
```
event: task_status_update
data: {"id":"<taskId>","status":{"state":"<state>"},"final":<bool>}
```

**Error events** (distinct type so clients dispatch by event name, no shape ambiguity):
```
event: error
data: {"id":"<taskId>","error":"<message>","final":true}
```

State mapping (see `A2ATaskState.fromMessageType`):

| MessageType | state | final |
|-------------|-------|-------|
| `DONE` | `"completed"` | `true` |
| `FAILURE` | `"failed"` | `true` |
| `DECLINE` | `"cancelled"` | `true` |
| `STATUS`, `RESPONSE`, others | `"working"` | `false` |

---

### §4 — A2ATaskState changes

#### 4a. Fix DECLINE → "cancelled" (protocol consistency)

**Problem:** `fromCommitmentState(DECLINED)` → "failed"; `fromMessageHistory(DECLINE)` → "failed" (via priority 2 shared with FAILURE). DECLINE is an explicit agent refusal; "failed" misrepresents it as an infrastructure failure. A2A protocol distinguishes "failed" (technical) from "cancelled" (explicit refusal). Fix both paths.

**`fromCommitmentState` update:**
```java
static String fromCommitmentState(CommitmentState state) {
    return switch (state) {
        case FULFILLED -> "completed";
        case DELEGATED, ACKNOWLEDGED -> "working";
        case FAILED, EXPIRED -> "failed";
        case DECLINED -> "cancelled";   // was "failed" — fixed in #147
        case OPEN -> "submitted";
    };
}
```

**Priority system refactor** (separate FAILURE=3 from DECLINE=2):
```java
private static int statePriority(MessageType t) {
    return switch (t) {
        case DONE, RESPONSE -> 4;
        case FAILURE        -> 3;
        case DECLINE        -> 2;
        case STATUS, HANDOFF -> 1;
        default             -> 0;
    };
}

private static String fromPriority(int p) {
    return switch (p) {
        case 4 -> "completed";
        case 3 -> "failed";
        case 2 -> "cancelled";
        case 1 -> "working";
        default -> "submitted";
    };
}
```

Existing semantics preserved: FAILURE-after-DONE still returns "completed" (priority 4 > 3). FAILURE+DECLINE together → "failed" (FAILURE wins at priority 3 > 2).

#### 4b. New method `fromMessageType`

```java
static String fromMessageType(MessageType type) {
    return switch (type) {
        case DONE    -> "completed";
        case FAILURE -> "failed";
        case DECLINE -> "cancelled";
        default      -> "working";
    };
}
```

#### 4c. TERMINAL_TYPES constant — defined in A2ATaskState

`A2ATaskState` is package-private in `io.casehub.qhorus.runtime.api` — same package as `A2AChannelBackend` and `A2AResource`. Both can reference it without widening visibility:

```java
static final Set<MessageType> TERMINAL_TYPES =
        Set.of(MessageType.DONE, MessageType.FAILURE, MessageType.DECLINE);
```

---

### §5 — Timing: SSE fires before the DB transaction commits

`ChannelGateway.fanOut()` dispatches `backend.post()` on a **virtual thread** (`Thread.ofVirtual().start(...)`) inside `MessageService.dispatch()`, which is `@Transactional`. The virtual thread may execute before the enclosing transaction commits.

Consequence: a client who receives a `final: true` SSE event and immediately polls `GET /a2a/tasks/{id}` may see the previous (non-terminal) state.

**Clients must treat the SSE event as a notification trigger, not a guarantee of poll consistency.** A brief retry loop on the poll endpoint after receiving a final SSE event is the correct client pattern. This behaviour is inherent to the architecture; all backends share it.

---

### §6 — Known constraint: server restart breaks SSE for in-flight tasks

`A2AChannelBackend` registers lazily via `ensureRegistered()`, called from `POST /a2a/message:send`. After a server restart, `ChannelGateway.onStart()` rebuilds the gateway registry and fires `ChannelInitialisedEvent` for each persisted channel, but `A2AChannelBackend` does not observe this event (lazy registration was a deliberate design decision per ADR-0008 and the 2026-05-22 spec). As a result, `A2AChannelBackend` is not in the gateway's registry after restart.

A client who subscribes to SSE after a restart — even for a previously in-flight task — will receive no events: `fanOut()` runs but has no `A2AChannelBackend` registered for that channel. Only a new `POST /a2a/message:send` call re-registers the backend.

**The proper fix** requires persisting A2A channel participation so the backend can re-register on `ChannelInitialisedEvent`. This is a separate, larger change. File a follow-on issue; do not half-fix with a workaround.

**Documentation for clients:** SSE subscriptions do not survive server restarts. Clients must re-subscribe and may need to poll `GET /a2a/tasks/{id}` to recover missed events.

---

### §7 — Not in scope

- Reactive path (`ReactiveA2AResource`, `ReactiveA2AChannelBackend`) — no reactive A2A backend exists yet.
- SSE keepalive heartbeat — Quarkus handles SSE connection timeout.
- Persistent A2A channel participation (required for restart recovery) — follow-on issue.

---

## Testing

### A2ATaskStateTest — existing tests to UPDATE (not just add)

These existing tests will fail after the §4 changes and **must be updated**:

| Test method | Current assertion | New assertion |
|-------------|-------------------|---------------|
| `declinedIsFailed()` | `fromCommitmentState(DECLINED)` → `"failed"` | → `"cancelled"` |
| `lastDeclineIsFailed()` | `fromMessageHistory(DECLINE)` → `"failed"` | → `"cancelled"` |
| `maxPriority_declineBeforeStatus_returnsFailed()` | DECLINE+STATUS → `"failed"` | DECLINE+STATUS → `"cancelled"` (DECLINE priority=2 > STATUS priority=1) |

New test cases to add:

- `fromMessageType_decline_returnsCancelled()` — `fromMessageType(DECLINE)` → "cancelled"
- `fromMessageType_failure_returnsFailed()` — `fromMessageType(FAILURE)` → "failed"
- `fromMessageType_done_returnsCompleted()` — `fromMessageType(DONE)` → "completed"
- `maxPriority_failureAndDecline_returnsFailedNotCancelled()` — FAILURE+DECLINE → "failed" (FAILURE wins at priority 3 > 2)
- `fromCommitmentState_declined_returnsCancelled()` — new assertion after rename

### SSE integration test — concurrency pattern

SSE endpoint tests must coordinate across threads. Pattern using `CountDownLatch` and the test-visible `streamCount()` accessor (replaces unreliable `Thread.sleep()`):

```java
@Test
void sseStream_receivesCompletedEvent_whenDoneMessageSent() throws Exception {
    // 1. Create channel + send COMMAND — must commit before SSE opens
    UUID channelId = ...;
    String taskId = ...;
    QuarkusTransaction.requiringNew().run(() -> {
        // channel create + COMMAND dispatch
    });

    // 2. Open SSE connection on a separate thread
    CountDownLatch latch = new CountDownLatch(1);
    List<String> events = new CopyOnWriteArrayList<>();
    CompletableFuture.runAsync(() -> {
        try (SseEventSource source = SseEventSource.target(
                target.path("/a2a/tasks/" + taskId + "/stream")).build()) {
            source.register(event -> {
                events.add(event.readData(String.class));
                latch.countDown();
            });
            source.open();
            latch.await(5, TimeUnit.SECONDS);
        } catch (Exception e) { /* propagate */ }
    });

    // 3. Wait for the sink to register — synchronize on actual condition, not time
    Awaitility.await().atMost(1, TimeUnit.SECONDS)
            .until(() -> a2aBackend.streamCount(UUID.fromString(taskId)) > 0);

    // 4. Dispatch DONE (must commit — not @TestTransaction)
    QuarkusTransaction.requiringNew().run(() ->
        messageService.dispatch(MessageDispatch.builder()
            .channelId(channelId).sender("agent").type(MessageType.DONE)
            .content("done").correlationId(taskId).actorType(ActorType.AGENT).build()));

    // 5. Wait for the event with timeout
    assertTrue(latch.await(5, TimeUnit.SECONDS), "No SSE event received within timeout");
    assertThat(events).anyMatch(e ->
            e.contains("\"state\":\"completed\"") && e.contains("\"final\":true"));
}
```

**No `@TestTransaction`.** All mutations use `QuarkusTransaction.requiringNew()` — they must commit so the SSE event fires after DB commit.

### Other test cases

- Already-terminal task → immediate final `event: task_status_update`, sink closes
- Task not found → `event: error`, sink closes
- `sink.send()` throws → consumer deregisters; subsequent `post()` does not call it
- DECLINE dispatch → `"state":"cancelled"`, `"final":true`
- Client disconnect → `streamCount(corrId)` drops to 0 (lazy cleanup on next `post()`)
