---
id: PP-20260608-054090
title: "Informatory role, not message type, defines whether a message belongs on an observe channel — STATUS for content-bearing observations, EVENT for content-free signals"
type: rule
scope: repo
applies_to: "All MessageDispatch.Builder call sites in qhorus runtime, MCP tools, gateway, connector-backend, and consumer modules posting to observe channels"
severity: important
refs:
  - api/src/main/java/io/casehub/qhorus/api/message/MessageDispatch.java
  - runtime/src/main/java/io/casehub/qhorus/runtime/message/MessageObserverDispatcher.java
  - connector-backend/src/main/java/io/casehub/qhorus/connector/backend/ConnectorQhorusMeshBridge.java
violation_hint: "MessageDispatch.builder().type(EVENT).content(payload) — Builder.build() throws IllegalArgumentException. Use STATUS with .content(payload) for content-bearing observe-channel messages. Use .telemetry(json) for internal ledger telemetry."
updated: 2026-06-12
created: 2026-06-08
---

The **informatory role** — not the message type — defines whether a message belongs on an observe channel. A message is informatory when it carries information without opening an expectation of reply in that channel.

**EVENT** is designed exclusively for this role: content-free signal, no deontic character. `MessageDispatch.Builder.build()` enforces this — setting `.content(non-null)` on an EVENT dispatch throws `IllegalArgumentException`. Use `.telemetry(json)` for internal qhorus infrastructure telemetry (toolName, durationMs, tokenCount) — this field is stored in ledger columns and is never delivered to observers.

**STATUS** serves an informatory role when posted as a standalone observation with no correlating COMMAND and no expected reply. Use STATUS for content-bearing informatory messages on observe channels — it reaches observers with content intact. The type declares intent; the role is determined by use and context.

The observe channel accepts both EVENT and STATUS per PLATFORM.md: EVENT for content-free signals (tool call telemetry, state change signals), STATUS for content-bearing state reports (connector delivery notifications, agent status broadcasts). The earlier EVENT-only example in `agent-mesh-framework.md` overstated the restriction — it has been corrected.

**Pending `casehub-ledger#126`:** once telemetry is fully decoupled from the content field, an explicit architectural decision on whether EVENT should eventually support application-tier content can be made. Until then, STATUS is the correct type for content-bearing observe-channel messages.
