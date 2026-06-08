---
id: PP-20260608-054090
title: "MessageType.EVENT must not carry content — use STATUS for broadcasts, telemetry for internal ledger data"
type: rule
scope: repo
applies_to: "All MessageDispatch.Builder call sites in qhorus runtime, MCP tools, gateway, and consumer modules"
severity: important
refs:
  - api/src/main/java/io/casehub/qhorus/api/message/MessageDispatch.java
  - runtime/src/main/java/io/casehub/qhorus/runtime/message/MessageObserverDispatcher.java
violation_hint: "MessageDispatch.builder().type(EVENT).content(payload) — Builder.build() throws IllegalArgumentException. Using STATUS with content on an observe channel is the correct alternative."
created: 2026-06-08
---

`MessageType.EVENT` is a content-free signal type. `MessageDispatch.Builder.build()` enforces this: setting `.content(non-null)` on an EVENT dispatch throws `IllegalArgumentException` at call time. Two alternatives exist depending on use: `.telemetry(json)` for internal qhorus infrastructure that needs ledger telemetry columns populated (`toolName`, `durationMs`, `tokenCount`) — this field is internal and never delivered to observers; `MessageType.STATUS` with `.content(payload)` for application-tier content-bearing broadcasts on observe channels, which reaches observers with content intact. The nullification in `MessageObserverDispatcher` is dead code for all production paths after this rule — it guards only against direct `Message` entity construction bypassing the Builder. Pending `casehub-ledger#126`, which will fully decouple telemetry from the content field, enabling an explicit architectural decision on whether EVENT should eventually support application content.
