# Channel Backend Abstraction — Design Spec

**Date:** 2026-05-04  
**Issue:** casehubio/qhorus#131  
**Status:** Approved — proceeding to implementation plan

---

## 1. Architecture Overview

A `ChannelGateway` sits above all backends. Qhorus itself is one backend — the always-present
internal agent mesh. External backends (human-facing transports, protocol bridges) register
per-channel and receive fan-out after the internal backend persists.

```
Agent / Worker
    ↓  send_message (MCP tool)
ChannelGateway
    ├── QhorusChannelBackend        [always registered, AgentChannelBackend]
    │     └── MessageService → LedgerWriteService
    ├── WhatsAppChannelBackend      [HumanParticipatingChannelBackend, at most one per channel]
    ├── ClaudonyPanelChannelBackend [HumanObserverChannelBackend, unlimited]
    └── SlackChannelBackend         [HumanObserverChannelBackend or HumanParticipating]

Human reply (participating backend)
    → ChannelGateway.receiveHumanMessage()
    → InboundNormaliser SPI
    → MessageService → LedgerWriteService (ActorType.HUMAN)

Human signal (observer backend)
    → ChannelGateway.receiveObserverSignal()
    → forced MessageType.EVENT
    → MessageService → LedgerWriteService (ActorType.HUMAN)
```

### Backend taxonomy

| Backend interface | Actor type | Per-channel limit | Inbound |
|---|---|---|---|
| `AgentChannelBackend` | `ActorType.AGENT` | 1 (always `QhorusChannelBackend`) | n/a |
| `HumanParticipatingChannelBackend` | `ActorType.HUMAN` | **at most 1** | Full speech act taxonomy via `InboundNormaliser` |
| `HumanObserverChannelBackend` | `ActorType.HUMAN` | Unlimited | `EVENT` only — enforced by gateway |

### Why at most one `HumanParticipatingChannelBackend`

Two participatory human surfaces on the same channel produce two independent conversation
threads. A human replying on WhatsApp saying one thing and a human replying on Slack saying
another are structurally divergent conversations — the agent cannot know they represent
different threads and may try to honour both. This violates Qhorus's coherence invariant
that a channel is a single accountable communication surface.

Observer backends do not create this problem: their inbound signals are `EVENT` only and
create no agent obligations.

### A2A and ACP — protocol bridges, not transport backends

A2A (`role: "user"/"agent"`) and ACP carry both human and agent messages in the same
protocol. They are protocol multiplexers, not single-actor-type transports. They dispatch
into the appropriate gateway inbound path based on resolved actor type. Tracked separately
in casehubio/qhorus#135.

### Module placement

| Type | Module |
|---|---|
| SPIs: `ChannelBackend`, `HumanParticipatingChannelBackend`, `HumanObserverChannelBackend`, `AgentChannelBackend`, `InboundNormaliser`, value records | `casehub-qhorus-api` |
| `ChannelGateway`, `QhorusChannelBackend`, `DefaultInboundNormaliser`, `Senders` | `casehub-qhorus-runtime` |
| `InMemoryChannelBackendRegistry` | `casehub-qhorus-testing` |

SPIs in `api` so consumers (Claudony, connectors) implement without depending on `runtime`.

---

## 2. SPI Contracts (`casehub-qhorus-api`)

New package: `io.casehub.qhorus.api.gateway`

### Backend interfaces

```java
public interface ChannelBackend {
    String backendId();
    ActorType actorType();
    void open(ChannelRef channel, Map<String, String> metadata);
    // AgentChannelBackend.post() may throw — it is the source-of-truth write; failure is fatal.
    // All other backend post() implementations must catch internally — failure is non-fatal.
    void post(ChannelRef channel, OutboundMessage message);
    void close(ChannelRef channel);
}

// At most one per channel. Full speech act inbound via InboundNormaliser.
public interface HumanParticipatingChannelBackend extends ChannelBackend {}

// Unlimited per channel. Inbound capped to EVENT by gateway regardless of content.
public interface HumanObserverChannelBackend extends ChannelBackend {}

// Always registered. Internal Qhorus agent mesh.
public interface AgentChannelBackend extends ChannelBackend {}
```

### Value types

```java
public record ChannelRef(UUID id, String name) {}

public record OutboundMessage(
    UUID messageId,
    String sender,
    MessageType type,
    String content,
    UUID correlationId,
    ActorType senderActorType) {}

public record InboundHumanMessage(
    String externalSenderId,
    String text,
    Instant receivedAt,
    Map<String, String> metadata) {}

public record ObserverSignal(
    String externalSenderId,
    String content,
    Instant receivedAt,
    Map<String, String> metadata) {}

public record NormalisedMessage(
    MessageType type,
    String content,
    String senderInstanceId) {}
```

### `InboundNormaliser` SPI

```java
@FunctionalInterface
public interface InboundNormaliser {
    NormalisedMessage normalise(ChannelRef channel, InboundHumanMessage raw);
}
```

`senderInstanceId` in `NormalisedMessage` must use format `human:{externalSenderId}` so
`ActorTypeResolver` correctly stamps `ActorType.HUMAN` in the ledger entry. This is a
documented platform convention.

---

## 3. `ChannelGateway` (`casehub-qhorus-runtime`)

```java
@ApplicationScoped
public class ChannelGateway {

    // Register a backend on a channel.
    // Throws DuplicateParticipatingBackendException if a second
    // HumanParticipatingChannelBackend is registered on the same channel.
    // Throws IllegalArgumentException if attempting to deregister "qhorus-internal".
    void registerBackend(UUID channelId, ChannelBackend backend);
    void deregisterBackend(UUID channelId, String backendId);
    List<BackendRegistration> listBackends(UUID channelId);
    // BackendRegistration: record(String backendId, String backendType, ActorType actorType)

    // Outbound — called by QhorusMcpTools.sendMessage()
    void post(UUID channelId, OutboundMessage message);

    // Inbound — called by backends
    void receiveHumanMessage(ChannelRef channel, InboundHumanMessage message);
    void receiveObserverSignal(ChannelRef channel, ObserverSignal signal);
}
```

### Outbound flow

1. `QhorusMcpTools.sendMessage()` → `ChannelGateway.post()`
2. Gateway calls `AgentChannelBackend.post()` **synchronously** (persistence, source of truth)
3. On success: gateway dispatches async tasks to all remaining backends via `ManagedExecutor`
4. Backend exceptions caught, logged — never propagate to caller

### Inbound (participating)

1. Backend calls `gateway.receiveHumanMessage(channel, raw)`
2. Gateway calls `InboundNormaliser.normalise(channel, raw)` → `NormalisedMessage`
3. Gateway calls `MessageService.send()` with resolved type and `human:{externalSenderId}` sender
4. `LedgerWriteService` records with `ActorType.HUMAN`

### Inbound (observer)

1. Backend calls `gateway.receiveObserverSignal(channel, signal)`
2. Gateway **forces** `MessageType.EVENT` — not negotiable, not trusted from backend
3. Gateway calls `MessageService.send()` with `EVENT` type

### In-memory registry

Backend registrations are in-memory. Lost on restart. `QhorusChannelBackend` is auto-registered
on `create_channel` and auto-deregistered on `delete_channel`. External backends must
re-register on reconnect. Persistent registration is #132 (delivery guarantees).

---

## 4. `QhorusChannelBackend` and `DefaultInboundNormaliser`

### `QhorusChannelBackend`

```java
@ApplicationScoped
public class QhorusChannelBackend implements AgentChannelBackend {
    @Override public String backendId() { return "qhorus-internal"; }
    @Override public ActorType actorType() { return ActorType.AGENT; }
    @Override public void open(ChannelRef channel, Map<String, String> metadata) { }
    @Override public void post(ChannelRef channel, OutboundMessage message) {
        // delegates to MessageService.send() → LedgerWriteService.record()
    }
    @Override public void close(ChannelRef channel) { }
}
```

Everything inside `MessageService` is unchanged: channel semantics, rate limiting,
`MessageTypePolicy`, commitment lifecycle, ledger write.

### `DefaultInboundNormaliser`

```java
@DefaultBean @ApplicationScoped
public class DefaultInboundNormaliser implements InboundNormaliser {
    @Override
    public NormalisedMessage normalise(ChannelRef channel, InboundHumanMessage raw) {
        return new NormalisedMessage(
            MessageType.QUERY,                        // conservative default
            raw.text(),
            "human:" + raw.externalSenderId()        // ActorTypeResolver → HUMAN
        );
    }
}
```

Replace with `@Alternative @Priority(1)` LLM-based classifier when needed.

### `Senders` constant

```java
public final class Senders {
    public static final String HUMAN = "human";
}
```

Replaces the `"human"` magic string in `respond_to_approval`. Aligns with `ActorType.HUMAN`
vocabulary. One place, named, documented.

---

## 5. MCP Tool Changes

### Existing tools — transparent behaviour change

| Tool | Change |
|---|---|
| `send_message` | Routes through `ChannelGateway.post()`. Caller API unchanged. |
| `create_channel` | Auto-registers `QhorusChannelBackend` on gateway. |
| `delete_channel` | Deregisters all backends, calls `close()` on each. |

### New tools

```
register_backend(channel_name, backend_id, backend_type, metadata)
  Associates a named backend (already registered as a CDI bean) with a channel.
  CDI backends self-register via gateway.registerBackend(channelId, this) on channel open.
  This MCP tool is for agent-driven association when an agent directs routing to a
  specific named backend (e.g. routing case-123/work to a Slack workspace backend).
  backend_type: "human_participating" | "human_observer"
  error: second human_participating on same channel
  returns: registration confirmation

deregister_backend(channel_name, backend_id)
  error: cannot remove "qhorus-internal"

list_backends(channel_name)
  returns: all registered backends with backendId, backendType, actorType
```

Backends receive inbound via CDI — they inject `ChannelGateway` and call
`gateway.receiveHumanMessage()` / `gateway.receiveObserverSignal()` directly.
MCP is the agent-facing surface only; backend↔gateway communication is internal CDI.

---

## 6. Testing Strategy

### Unit tests (no Quarkus, no DB)

- `ChannelGateway` routing: fan-out order, async dispatch, observer EVENT enforcement
- `DuplicateParticipatingBackendException` on second participating backend
- `QhorusChannelBackend.post()` delegates to `MessageService`
- `DefaultInboundNormaliser`: all inputs → `QUERY`, sender format `human:{id}`
- `Senders.HUMAN` constant pinned against regression
- `ActorTypeResolver` alignment (pending ledger#75): `"human"` → `HUMAN`, `"agent"` → `AGENT`

### Integration tests (`@QuarkusTest`, H2)

- `send_message` → gateway → `QhorusChannelBackend` → ledger entry (happy path, existing behaviour)
- `send_message` with registered `HumanObserverChannelBackend` → observer `post()` called
- Observer signal forced to `EVENT` regardless of content (correctness)
- `HumanParticipatingChannelBackend` inbound → ledger entry with `ActorType.HUMAN` (correctness)
- Duplicate participating backend registration → rejected with clear error (robustness)
- `delete_channel` deregisters all backends, calls `close()` (lifecycle)
- Backend `post()` throws → primary `send_message` succeeds (robustness)
- `create_channel` → `list_backends` returns `qhorus-internal` (correctness)

### End-to-end tests

- Agent posts → observer backend receives (full fan-out)
- Human replies via participating backend → ledger shows `HUMAN` sender (full inbound path)
- Observer signal → `EVENT` in ledger, not speech act (correctness)
- Two channels, independent backend registrations (isolation)
- Backend fails on `post()` → agent send succeeds (robustness)

### `InMemoryChannelBackendRegistry`

Added to `casehub-qhorus-testing` — same pattern as existing `InMemory*Store` alternatives.
Consumer unit tests wire backends without Quarkus.

---

## 7. Documentation Changes

### `casehubio/qhorus/docs/`

| Document | Change |
|---|---|
| `agent-mesh-framework.md` | New Part: Channel Gateway — backend types, registration, inbound/outbound flows, human participation model |
| `agent-protocol-comparison.md` | Replace "Phase 9 optional endpoint" with `A2AChannelBackend` protocol bridge model |
| `multi-agent-framework-comparison.md` | Update A2A row from "🔶 planned" to "✅ via protocol bridge with actor identity resolution" |
| `normative-layer.md` | Add: gateway preserves normative guarantees — all messages still flow through `LedgerWriteService` |
| `normative-summary.md` | Update gap analysis — channel abstraction gap being closed |
| `normative-channel-layout.md` | Add: layouts are backend-agnostic — normative layer applies regardless of transport |

### `casehubio/qhorus/docs/adr/`

New ADR: channel backend abstraction — records why Approach A, why at-most-one participating
backend, why `ActorType` as canonical vocabulary, why A2A is a protocol bridge.

### `casehubio/parent/docs/repos/casehub-qhorus.md`

Add: `ChannelGateway`, SPI hierarchy, `InboundNormaliser`, new MCP tools, `Senders` constant.

### `casehubio/parent/docs/conventions/`

New: `qhorus-actor-type-mapping.md` — `ActorType` → A2A role mapping, `human:{id}` sender
format convention, interop contract for A2A callers that are AI agents.

### `casehubio/qhorus/CLAUDE.md`

Update: Project Structure (new `gateway/` package in `api/` and `runtime/`), Testing
conventions (`InMemoryChannelBackendRegistry`), MCP tool surface (3 new tools).

### Cross-reference sweep (during implementation)

- All "Phase 9" references → updated
- All `"human"` magic string references → `Senders.HUMAN`
- All `ActorType` usages in docs → verified consistent
- All inter-doc links verified live

---

## 8. Epic Structure

#131 becomes an epic. Sub-issues:

| Issue | What | Dependency |
|---|---|---|
| New | SPI contracts + `InMemoryChannelBackendRegistry` in testing | none |
| New | `ChannelGateway` + `QhorusChannelBackend` + `DefaultInboundNormaliser` | SPI issue |
| New | MCP tools: `register_backend`, `deregister_backend`, `list_backends` + `create/delete_channel` wiring | Gateway issue |
| New | `Senders` constant + `respond_to_approval` cleanup | none |
| New | Full doc sweep: all 7 doc changes + ADR + platform convention | Gateway issue |
| casehubio/ledger#75 | `ActorTypeResolver` explicit A2A rules | none (ledger repo) |
| casehubio/qhorus#135 | A2A protocol bridge backend | Gateway issue + ledger#75 |
| casehubio/qhorus#132 | Delivery guarantees (persistent registration) | This entire epic |
