# 0013 — A2AChannelBackend uses lazy registration — restart survivability requires persistent A2A channel participation

Date: 2026-06-13
Status: Accepted

## Context and Problem Statement

`A2AChannelBackend` registers itself on a channel lazily via `ensureRegistered()`, called
from `POST /a2a/message:send`. After a server restart, `ChannelGateway.onStart()` fires
`ChannelInitialisedEvent` for every persisted channel, but `A2AChannelBackend` does not
observe this event. As a result, existing in-flight A2A tasks will not receive SSE
streaming events after a restart — `fanOut()` runs but finds no A2A backend registered.

## Decision Drivers

* Eager registration (via `@Observes ChannelInitialisedEvent`) would register on ALL
  persisted channels, not just channels with A2A activity — creating unnecessary backend
  slots and spurious log noise
* Proper selective re-registration requires knowing which channels had prior A2A
  participation — information not currently persisted
* `ConnectorChannelBackend` uses the event-observer pattern because it has a persistent
  `ChannelConnectorBinding` entity that provides the selection criteria

## Considered Options

* **Option A** — Keep lazy registration via `ensureRegistered()` (status quo)
* **Option B** — Observe `ChannelInitialisedEvent` and register on ALL channels
* **Option C** — Persist A2A channel participation as a new entity; re-register selectively on event

## Decision Outcome

Chosen option: **Option A** (keep lazy registration), because Option C (the correct
long-term fix) requires a new entity, migration, and store implementation — out of scope
for this batch. Option B would register the A2A backend on every channel including
non-A2A channels, adding overhead and obscuring the intent. The limitation is documented
in Javadoc, in protocol, and tracked as qhorus#278.

### Positive Consequences

* No new entity or migration required
* No unnecessary backend registrations on non-A2A channels
* Simple, easy to reason about

### Negative Consequences / Tradeoffs

* SSE subscriptions do not survive server restarts — clients must re-subscribe
* Clients must poll `GET /a2a/tasks/{id}` to recover state after a restart
* The limitation is silent (no runtime warning) — clients need documentation awareness

## Pros and Cons of the Options

### Option A — Lazy registration via ensureRegistered()

* ✅ Simple, no new storage
* ✅ Only registers on channels with real A2A activity
* ❌ Does not survive server restart — SSE subscriptions lost

### Option B — Observe ChannelInitialisedEvent, register on all channels

* ✅ Survives restart
* ❌ Registers on non-A2A channels — creates noise and unnecessary overhead
* ❌ No selection criterion — all channels treated as A2A-capable

### Option C — Persist A2A channel participation, re-register selectively

* ✅ Correct restart survivability
* ✅ Selective — only re-registers channels with prior A2A activity
* ❌ Requires new entity, Flyway migration, store, and service — significant scope

## Links

* qhorus#278 — SSE keepalive + server-side timeout for orphaned consumers
* [docs/protocols/casehub/sse-sink-async-close.md](../protocols/casehub/sse-sink-async-close.md)
