---
id: PP-20260522-e5e527
title: "Observers of ChannelInitialisedEvent must be idempotent"
type: rule
scope: repo
applies_to: "Any CDI bean that implements @Observes ChannelInitialisedEvent — in qhorus runtime or any consuming module (claudony, casehub-engine)"
severity: important
refs:
  - api/src/main/java/io/casehub/qhorus/api/gateway/ChannelInitialisedEvent.java
  - runtime/src/main/java/io/casehub/qhorus/runtime/gateway/ChannelGateway.java
violation_hint: "Observer calls registerBackend() unconditionally — on startup recovery the backend is registered twice, producing duplicate fanOut() calls for non-human_participating backends"
created: 2026-05-22
---

`ChannelGateway.initChannel()` fires `ChannelInitialisedEvent` unconditionally on every
call — both when a channel is first created and on startup recovery (where `initChannel()`
is called for every persisted channel). Observers must guard against duplicate
registration: use `ConcurrentHashMap.newKeySet().add(channelId)` as a registration guard
(returns `false` if already present), or check `gateway.listBackends(channelId)` before
calling `registerBackend()`. The `human_participating` backend type already enforces a
duplicate guard internally; `agent` and `human_observer` types do not and will silently
accumulate duplicate entries in the fanOut registry.
