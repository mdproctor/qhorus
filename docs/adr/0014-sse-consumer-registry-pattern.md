# 0014 — A2AChannelBackend SSE registry uses Consumer<OutboundMessage> callbacks, not SseEventSink directly

Date: 2026-06-13
Status: Accepted

## Context and Problem Statement

`A2AChannelBackend` is a CDI `@ApplicationScoped` bean — not a JAX-RS resource.
JAX-RS `@Context` injection (`Sse`, `SseEventSink`) only works in resource classes.
The backend needs to push SSE events to registered clients when `post()` is called from
`ChannelGateway.fanOut()`. How should it hold references to active SSE connections?

## Decision Drivers

* CDI beans cannot inject `@Context Sse` — they cannot construct `OutboundSseEvent` instances
* `SseEventSink.send()` requires an `OutboundSseEvent` built via `Sse.newEventBuilder()`
* The JAX-RS resource (`A2AResource`) DOES have access to `@Context Sse` and `@Context SseEventSink`

## Considered Options

* **Option A** — Store `Consumer<OutboundMessage>` callbacks; each consumer captures `Sse` and `SseEventSink` in its closure from the resource context
* **Option B** — Store `SseEventSink` directly; inject `Sse` into the CDI bean via a producer or helper
* **Option C** — Make `A2AChannelBackend` a JAX-RS resource and handle SSE registration internally

## Decision Outcome

Chosen option: **Option A** (Consumer callbacks), because it cleanly separates concerns:
the CDI bean owns the registry and notification logic; the JAX-RS resource owns the
HTTP/SSE wiring. The consumer lambda captures `Sse` and `SseEventSink` from the resource's
`@Context` injection, eliminating the need for any JAX-RS context in the CDI bean.
`AtomicReference` handles the self-referential deregistration pattern.

### Positive Consequences

* No JAX-RS context in a CDI bean — no framework violation
* Consumer lifecycle fully owned by the creator (`A2AResource.streamTask`)
* Deregistration on terminal event or send failure handled by each consumer independently
* `A2AChannelBackend.post()` needs no SSE knowledge — it just iterates callbacks

### Negative Consequences / Tradeoffs

* `AtomicReference<Consumer<OutboundMessage>>` self-reference pattern is non-obvious
* Consumer must be created and wired before registration — ordering matters

## Pros and Cons of the Options

### Option A — Consumer<OutboundMessage> callbacks

* ✅ No JAX-RS context in CDI bean
* ✅ Clean separation of HTTP wiring and notification dispatch
* ✅ Consumer lifecycle owned at creation site
* ❌ AtomicReference self-reference pattern adds indirection

### Option B — Store SseEventSink directly in CDI bean

* ✅ More direct — backend holds the sink
* ❌ Requires injecting Sse into a CDI bean via workaround (producer, helper)
* ❌ Mixes JAX-RS context into CDI bean

### Option C — Make A2AChannelBackend a JAX-RS resource

* ✅ @Context injection would work natively
* ❌ JAX-RS resources are not designed to be persistent long-lived beans with registries
* ❌ ChannelBackend SPI expects CDI beans, not JAX-RS resources

## Links

* qhorus#147 — A2A SSE streaming
* [docs/protocols/casehub/sse-sink-async-close.md](../protocols/casehub/sse-sink-async-close.md)
