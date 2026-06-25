---
id: PP-20260623-77adf0
title: "CommitmentAttestationPolicy implementations must handle null CommitmentContext defensively"
type: rule
scope: platform
applies_to: "Any implementation of CommitmentAttestationPolicy in any casehub module"
severity: important
refs:
  - api/src/main/java/io/casehub/qhorus/api/spi/CommitmentAttestationPolicy.java
violation_hint: "NPE thrown in production when attestationFor(type, actorId, context) receives context=null"
created: 2026-06-23
updated: 2026-06-25
---

The `CommitmentAttestationPolicy` interface declares a single abstract method `attestationFor(MessageType, String, CommitmentContext)` with a nullable `CommitmentContext` parameter. Any implementation that dereferences `context` without a null guard will throw `NullPointerException` when invoked with `context=null`. Guard every context access: `if (context != null) { ... }`.

The `CommitmentContext` record now carries five fields:
- `correlationId` — identifies the obligation being discharged
- `channelId` — the channel the commitment was made on
- `channelName` — for human-readable logging; may be null
- `commitmentId` — the specific commitment record; may be null when not tracked
- `capabilityTag` — extracted from the COMMAND content's `"capability"` JSON field; may be null or `CapabilityTag.GLOBAL` when not available

Implementations should treat `null capabilityTag` as global scope (`CapabilityTag.GLOBAL`).

This includes casehub-devtown implementations that use `EvidentialChecker.checkObligation(terminalType, context)` — pass the nullable `context` through; `EvidentialChecker` accepts null and treats it as "no extended context available".
