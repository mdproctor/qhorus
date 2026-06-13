# 0015 — ReactiveMessageService trust gate uses worker-pool delegation, not a new ReactiveObligorTrustPolicy SPI

Date: 2026-06-13
Status: Accepted

## Context and Problem Statement

`ReactiveMessageService` previously called `trustGateService.meetsThresholdAsync()` directly
for the COMMAND trust gate, bypassing the `ObligorTrustPolicy` SPI. Custom `ObligorTrustPolicy`
beans — which may add risk-model logic beyond the default threshold check — were silently
ignored in the reactive path. The question: how should the reactive trust gate honour the SPI
without blocking the Vert.x I/O thread?

## Decision Drivers

* `ObligorTrustPolicy.permits()` is a blocking call (JPA query via `TrustGateService`)
* Blocking calls must not execute on the Vert.x I/O thread — causes head-of-line blocking
* A new `ReactiveObligorTrustPolicy` SPI would require a new interface, `@DefaultBean` bridge, and API surface

## Considered Options

* **Option A** — Wrap `ObligorTrustPolicy.permits()` in `Uni.createFrom().item(() -> ...).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())`
* **Option B** — Define a new `ReactiveObligorTrustPolicy` SPI with `Uni<Boolean> permitsAsync(ObligorTrustContext)`; provide a `@DefaultBean` blocking bridge

## Decision Outcome

Chosen option: **Option A** (worker-pool delegation), because the `ObligorTrustPolicy`
interface is already the correct abstraction — `permits()` encapsulates threshold logic and
custom overrides. Adding a parallel reactive SPI would duplicate the API surface for no
architectural benefit. The worker-pool pattern correctly shifts the blocking call off the
Vert.x I/O thread. Custom policy beans are honoured in both blocking and reactive paths
with no changes to the SPI or its implementations.

### Positive Consequences

* Custom `ObligorTrustPolicy` beans now honoured in the reactive path — full SPI parity
* No new interface, no new `@DefaultBean` bridge
* `TrustGateService` injection removed from `ReactiveMessageService` — simpler dependency graph
* Parity enforced structurally by `BlockingTierPurityTest`

### Negative Consequences / Tradeoffs

* Custom policy authors cannot provide a native async implementation without a future reactive SPI
* Worker-pool hop adds latency to the trust gate — acceptable at enforcement gate frequency

## Pros and Cons of the Options

### Option A — Worker-pool delegation via runSubscriptionOn

* ✅ No new API surface
* ✅ Existing `ObligorTrustPolicy` beans work without changes
* ✅ Three lines of code
* ❌ Custom policy cannot be native-async without a future reactive SPI

### Option B — New ReactiveObligorTrustPolicy SPI

* ✅ Native async path available for custom policies
* ❌ New interface + default bridge + API surface to maintain
* ❌ Existing custom policy implementations need updating
* ❌ Unnecessary complexity for the current use case

## Links

* qhorus#235 — reactive ObligorTrustPolicy SPI implementation
* [docs/protocols/casehub/reactive-blocking-spi-worker-pool.md](../protocols/casehub/reactive-blocking-spi-worker-pool.md)
* GE-20260529-ff186e — garden technique: emitOn(Infrastructure.getDefaultWorkerPool())
