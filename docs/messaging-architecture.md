# Qhorus Messaging Architecture

Qhorus is a governance mesh for multi-agent AI. Agents, workers, and LLMs can
run anywhere — same JVM, same machine, local network, internet. The messaging
architecture has to work across all of those topologies without the application
code knowing which one it's in.

This document describes how qhorus handles that.

---

## Channels: The Communication Primitive

Every conversation in qhorus happens on a **channel** — a named, typed, durable
message surface. Channels are not transient: they persist across reconnections
and carry a full normative history (the ledger). Any participant can join or
observe a channel by registering a backend.

Channels have declared update semantics: APPEND, COLLECT, BARRIER, EPHEMERAL,
LAST_WRITE. These affect how messages accumulate, not how they're delivered.

Messages on a channel are typed speech acts — nine types drawn from speech act
theory: QUERY, COMMAND, RESPONSE, STATUS, DECLINE, HANDOFF, DONE, FAILURE,
EVENT. Together they form a complete vocabulary for agent obligations.

---

## The Mesh: Participants Anywhere

The topology diagram below shows the problem qhorus solves. Every participant
uses the same channel API regardless of where they live.

![The Qhorus mesh — participants at every topological distance connected via a shared channel API](assets/messaging/mesh-topology.svg)

The API is identical for all participants. A Claudony terminal on a remote machine
calls the same MCP tools as an embedded harness running in the same JVM. The mesh
abstracts topology.

---

## The Two Notification Paths

When `MessageService.dispatch()` persists a message, two independent notification
mechanisms fire. They serve different purposes and work at different scopes.

![Two notification paths from MessageService.dispatch(): ChannelBackend fan-out (per-channel, targeted) and MessageObserver (global, all messages)](assets/messaging/two-notification-paths.svg)

**ChannelBackend fan-out** is selective. You register a backend on a specific channel;
`fanOut()` calls `post()` on it for every message on that channel. The backend knows
the channel, knows the direction, and can be anywhere — same JVM (CDI bean), remote
service (HTTP call), external platform (WhatsApp API). `ClaudonyChannelBackend` is
the canonical example: it receives every message on a channel and pushes it to the
Claudony conversation panel, wherever that panel lives.

**MessageObserver** is a broadcast. Every persisted message — all 9 types, all
channels — fires all registered `MessageObserver` implementations. Observers filter
themselves. This is the pattern for cross-cutting harness concerns: a clinical
harness observing every channel for PI responses, an AML harness monitoring for
escalation triggers. The observer doesn't need to know which channels to watch.

---

## The MessageObserver SPI

`MessageObserver` is defined in `casehub-qhorus-api` — the contract layer with no
runtime dependencies:

```java
@FunctionalInterface
public interface MessageObserver {
    void onMessage(MessageReceivedEvent event);
    default Scope scope() { return Scope.LOCAL; }
    enum Scope { LOCAL, CLUSTER }
}
```

`LOCAL` means delivery within the same JVM — zero serialisation, zero network
latency. `CLUSTER` means cross-process delivery via whatever transport the
implementation uses.

`MessageService.dispatch()` uses `Instance<MessageObserver>` to dispatch to all
registered implementations after persistence:

```java
@Inject Instance<MessageObserver> observers;

// after messageStore.put(message)
String content = type == MessageType.EVENT ? null : message.content;
var event = new MessageReceivedEvent(ch.name, ch.id, type, sender, corrId, content);
for (var obs : observers) {
    try { obs.onMessage(event); }
    catch (Exception e) { LOG.warnf("MessageObserver %s failed: %s",
                                    obs.getClass().getSimpleName(), e.getMessage()); }
}
```

Failures are non-fatal and non-propagating — Dead Letter semantics. A broken
remote observer does not affect message persistence or any other observer.

### What MessageReceivedEvent carries

```java
public record MessageReceivedEvent(
    String channelName,   // for name-based routing (e.g. clinical: parse deviationId)
    UUID   channelId,     // for reliable programmatic lookup
    MessageType messageType,
    String senderId,
    String correlationId, // nullable — links response to originating COMMAND
    String content        // null for EVENT — see PP-20260508-90428f
) {}
```

EVENT content is always null. The telemetry fields (tool_name, duration_ms, token_count)
live in the ledger entry, not the notification event.

### The default implementation

`InProcessMessageBus` is the `@DefaultBean` — the fast path for embedded harnesses:

```java
@DefaultBean @ApplicationScoped
class InProcessMessageBus implements MessageObserver {
    @Inject Event<MessageReceivedEvent> cdiEvent;

    @Override
    public void onMessage(MessageReceivedEvent event) {
        cdiEvent.fireAsync(event)
                .exceptionally(t -> { LOG.warn("CDI observer failed", t); return null; });
    }
}
```

Harness code observes via `@ObservesAsync`:

```java
void onPiResponse(@ObservesAsync MessageReceivedEvent event) {
    if (event.messageType() != MessageType.DONE &&
        event.messageType() != MessageType.DECLINE) return;
    // ...
}
```

Zero configuration. Zero network. Zero serialisation.

---

## Transport Scope: LOCAL vs CLUSTER

This is the same distinction Vert.x draws between local consumers (in-JVM only)
and cluster-wide consumers (propagated across nodes). The scope declaration on
`MessageObserver` lets qhorus — and operators — understand what's registered.

![MessageObserver transport scope — LOCAL (CDI, in-JVM) vs CLUSTER (Kafka, WebSocket, Webhook)](assets/messaging/transport-scope.svg)

The left side ships today. The right side is plugged in when the deployment topology
requires it — no changes to qhorus core, no changes to harness observers. A
`KafkaMessageBus` registered as an additional `@ApplicationScoped` bean fires
alongside the CDI bus. Both run. Kafka consumers and CDI observers coexist.

Multiple observers of the same scope can coexist. `Instance<MessageObserver>` iterates
all of them. There is no "replace the CDI bus with Kafka" — you add the Kafka bus
alongside it and let consumers decide which delivery mechanism they want.

### Choosing the right scope for your topology

**Embedded Qhorus (same JVM):** `Scope.LOCAL` and CDI `ChannelBackend` beans are the
right choice. Claudony embeds Qhorus — the conversation panel backend, harness observers,
and the CDI event bus all fire in-process with zero serialisation overhead. This is the
fast path and the correct default for any consumer that runs Qhorus as an embedded
extension.

**Separate Qhorus service (distinct process):** `Scope.LOCAL` CDI delivery does not
cross process boundaries. A `ChannelBackend` CDI bean registered in the Qhorus process
cannot push to a consumer running in a different process. This topology requires a
`CLUSTER`-scoped `MessageObserver` — a `KafkaMessageBus`, `WebSocketMessageBus`,
or webhook implementation that carries the event across the wire. Register it as an
additional `@ApplicationScoped` bean; the CDI bus continues running alongside it.

**Multi-node fleet (embedded, multiple instances):** Each node embeds its own Qhorus
instance. CDI delivery fires per-node only — a message posted on Node B does not reach
CDI observers on Node A. This is not just a CDI limitation: `ClaudonyChannelBackend.post()`
on Node B only reaches browsers connected to Node B. `listChannels()` on Node A queries
Node A's local store. The fan-out and observer mechanisms both operate within the node
boundary. See the dedicated section below and casehubio/qhorus#162.

---

## A2A Integration

The Agent-to-Agent protocol is one of several inbound paths into qhorus. Messages
arriving via A2A go through `A2AChannelBackend`, which normalises them to the internal
speech-act type and calls `QhorusMcpTools.sendMessage()`. From there the flow is
identical to any other message — persistence, ledger, fan-out, observer notification.

![A2A integration path: external agent → A2AChannelBackend → QhorusMcpTools → MessageService → persist and notify](assets/messaging/a2a-integration.svg)

`A2AChannelBackend` does the hard work: six-step sender identity resolution,
COMMAND/RESPONSE correlation, task state mapping via `CommitmentService`. Once
the message enters `MessageService`, the A2A origin is irrelevant — it's a speech
act like any other.

Outbound A2A — a qhorus agent responding to an external A2A caller — flows
through `A2AResource.getTask()`, which reads `CommitmentStore` to map the
internal commitment state to the A2A task state format.

---

## Why Two Paths and Not One

A natural question: why maintain both `ChannelBackend` fan-out and `MessageObserver`?
They're not redundant — they serve different roles.

**ChannelBackend** knows the channel. It's registered on a specific channel by ID.
It receives messages in context — it knows it's the WhatsApp group for case 4521,
the Claudony panel for session 99, the Slack thread for incident 7. This context
is essential for backends that route, transform, or present messages.

**MessageObserver** knows nothing about channels. It sees every message from every
channel. This is appropriate for cross-cutting concerns — a clinical harness
monitoring all oversight channels for PI decisions, an AML engine scanning all
channels for escalation triggers. Registering a `ChannelBackend` per channel for
this use case would require dynamic registration logic and channel enumeration;
`MessageObserver` handles it in one observer method.

The pattern is well established. The [Vert.x EventBus][vertx-eb] makes the same
distinction: local consumers (in-JVM, not cluster-propagated) vs cluster consumers
(distributed). Enterprise Integration Patterns calls the outer mechanism a
[Publish-Subscribe Channel][eip-pubsub] and the per-channel delivery a
[Channel Adapter][eip-adapter].

---

## Pattern References

This architecture implements several established patterns:

| Pattern | Source | Where in qhorus |
|---|---|---|
| Publish-Subscribe Channel | EIP §Messaging Channels | `MessageObserver` broadcast |
| Channel Adapter | EIP §Messaging Channels | Each `MessageObserver` impl |
| Messaging Gateway | EIP §Messaging Endpoints | `MessageObserver` SPI decouples `MessageService` from transport |
| Dead Letter (non-propagating failure) | EIP §System Management | try-catch per observer, log and continue |
| Local vs Cluster Consumer | [Vert.x EventBus][vertx-eb] | `Scope.LOCAL` vs `Scope.CLUSTER` |
| Pluggable Transport Connector | [SmallRye Reactive Messaging][smallrye] | `MessageObserver` impls as transport connectors |

[vertx-eb]: https://vertx.io/docs/vertx-core/java/#event_bus
[eip-pubsub]: https://www.enterpriseintegrationpatterns.com/patterns/messaging/PublishSubscribeChannel.html
[eip-adapter]: https://www.enterpriseintegrationpatterns.com/patterns/messaging/ChannelAdapter.html
[smallrye]: https://quarkus.io/guides/kafka

---

## Known Architectural Gap: Multi-node Embedded Fleet

When Qhorus is embedded in multiple nodes (a Claudony fleet, a horizontally-scaled
harness), each node has an independent channel store, CDI container, and set of
registered backends.

A message posted on Node B:
- persists to **Node B's** channel store
- fires `fanOut()` to **Node B's** registered `ChannelBackend` beans
- fires `MessageObserver` (LOCAL scope) on **Node B**

A browser connected to **Node A** receives nothing. Node A's `listChannels()` queries
its own store — the message isn't there. Node A's `ClaudonyChannelBackend` was never
called. The problem is not CDI-specific; it applies equally to the ChannelBackend
fan-out path.

Adding a `CLUSTER`-scoped `MessageObserver` on Node B helps only if Node A also
subscribes to the same broker and re-fires its local backends on receipt — which
requires a shared broker, deduplication logic, and careful ordering guarantees.

Three resolution paths exist, none currently implemented:

**Option 1 — Shared Qhorus service.** All nodes talk to a single Qhorus deployment.
One channel store, one fan-out, no cross-node problem. Architecturally correct for
fleet at scale; Qhorus already exposes its full API over MCP so this is operationally
feasible. Requires a deployment model decision.

**Option 2 — CLUSTER MessageObserver relay.** Node B publishes to Kafka; all nodes
subscribe and re-fire their local backends. Requires a shared broker, deduplication,
and consensus on event schema. Complex but keeps embedding topology.

**Option 3 — Replicated channel store.** Shared database or distributed cache backing
the channel store. `listChannels()` returns consistent data across nodes; push delivery
still fires per-node only. Partial fix.

Tracking: casehubio/qhorus#162 (root gap), casehubio/claudony#118 (Claudony fleet impact).

---

## What Ships When

| Capability | Status |
|---|---|
| ChannelBackend fan-out (`fanOut()`, `ClaudonyChannelBackend`, connectors) | ✅ live |
| A2A protocol bridge (`A2AChannelBackend`, actor resolution) | ✅ live |
| `MessageReceivedEvent` record, `MessageObserver` SPI | 🔧 qhorus#153 |
| `InProcessMessageBus` (CDI default, `Scope.LOCAL`) | 🔧 qhorus#153 |
| `KafkaMessageBus`, `WebSocketMessageBus`, webhook impl | ⬜ future |
| SmallRye / MicroProfile Reactive Messaging bridge | ⬜ future |
| Cross-node delivery in multi-node embedded fleet | ⚠️ gap — qhorus#162 |

The SPI is the seam. When a Claudony terminal on a remote machine needs push
notification beyond what `ClaudonyChannelBackend` already provides, a
`WebSocketMessageBus` implementing `MessageObserver` is the answer — registered
as a CDI bean, picked up by `Instance<MessageObserver>` alongside the CDI bus,
no changes anywhere else.
