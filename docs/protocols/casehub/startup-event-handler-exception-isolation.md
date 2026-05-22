---
id: PP-20260522-c3a8c1
title: "Startup event handlers that fan-out via CDI events must catch per-item"
type: rule
scope: platform
applies_to: "Any Quarkus bean with @Observes StartupEvent that calls Event.fire() or initChannel() in a loop"
severity: important
refs:
  - runtime/src/main/java/io/casehub/qhorus/runtime/gateway/ChannelGateway.java
violation_hint: "forEach() or stream().forEach() wrapping a CDI fire() call — one broken observer aborts all remaining initialisations and halts startup"
created: 2026-05-22
---

Synchronous CDI `Event.fire()` propagates observer exceptions to the caller. When fired
inside a `@Observes StartupEvent` handler, an unchecked exception from any observer flows
back through `fire()` and exits the startup handler — Quarkus treats this as a startup
failure. Wrap each per-item `fire()` call (or each loop iteration that can fire) in a
try/catch that logs and continues, so a broken observer for one item does not prevent the
remaining items from being initialised. Use `@ObservesAsync` + `CompletionStage` only when
the caller can tolerate deferred completion; the synchronous per-item catch is the safe
default for startup recovery loops.
