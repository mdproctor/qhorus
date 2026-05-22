# 0008 — Channel Backend Registration via CDI Lifecycle Event

Date: 2026-05-22
Status: Accepted

## Context and Problem Statement

`ChannelGateway` maintains an in-memory registry of backends per channel. This
registry is lost on restart — persisted channels have no backend registered until
re-created or accessed. External backends (Claudony's `ClaudonyChannelBackend`,
`A2AChannelBackend`) each implemented their own startup recovery logic, duplicating
the same restart-awareness problem across every backend implementation.

## Decision Drivers

* External backends must not need to solve the same restart-recovery problem
  independently
* The qhorus-api module must remain usable without depending on runtime internals
* Backend registration must complete before the HTTP server accepts requests
* A broken backend observer must not abort the entire startup sequence

## Considered Options

* **Option A** — Startup hook only (restore agentBackend, each external backend
  handles own restart)
* **Option B** — Durable registry (persist backend registrations to DB; restore on
  startup)
* **Option C** — CDI event per channel init (`ChannelInitialisedEvent`), fired from
  `initChannel()` on both creation and startup recovery

## Decision Outcome

Chosen option: **Option C**, because it inverts the dependency: Qhorus notifies
backends that a channel is ready, and each backend decides whether to register —
without Qhorus knowing about any specific backend implementation.

### Positive Consequences

* External backends implement `@Observes ChannelInitialisedEvent` — one consistent
  registration point for both first-creation and restart-recovery paths
* `ChannelInitialisedEvent` is a plain record in `casehub-qhorus-api` — consumers
  observe it without depending on the runtime JAR
* `ChannelGateway.onStart()` fires the event for every persisted channel before HTTP
  starts, ensuring all backends are registered before requests arrive

### Negative Consequences / Tradeoffs

* Observers must be idempotent — the event fires unconditionally from `initChannel()`,
  including on repeated calls (see PP-20260522-e5e527)
* Startup hook exception isolation is required — each `initChannel()` call in `onStart()`
  is wrapped in try/catch so a broken observer cannot abort remaining channel
  initialisations (see PP-20260522-c3a8c1)
* No way to distinguish "first creation" from "restart recovery" at the event level —
  tracked as #183 for a future `recovered` flag

## Pros and Cons of the Options

### Option A — Startup hook only

* ✅ Simple — no new API surface
* ❌ Every new backend must independently implement restart recovery
* ❌ A2AChannelBackend lazy-registers on first message — no recovery for pre-existing channels

### Option B — Durable registry

* ✅ Registry state survives restart without any observer logic
* ❌ Significant scope — new DB table, migration, transactional concerns
* ❌ Forces Qhorus to model which external backends are registered (violates SPI boundary)

### Option C — CDI event (chosen)

* ✅ Backends stay decoupled — Qhorus fires; backends decide
* ✅ Works for both creation and restart with one mechanism
* ✅ Minimal API surface — one record in the api module
* ❌ Idempotency burden on all observers

## Links

* [PP-20260522-e5e527 — Observer idempotency contract](../protocols/casehub/channel-initialised-event-observer-idempotency.md)
* [PP-20260522-c3a8c1 — Startup handler exception isolation](../protocols/casehub/startup-event-handler-exception-isolation.md)
* [#183 — recovered flag on ChannelInitialisedEvent](https://github.com/casehubio/qhorus/issues/183)
* Closes #181
