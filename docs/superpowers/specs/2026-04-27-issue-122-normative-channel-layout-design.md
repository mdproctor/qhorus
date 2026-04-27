# Issue #122 — Normative Channel Layout: Design Spec

**Date:** 2026-04-27
**Issue:** casehubio/quarkus-qhorus#122
**Epic:** casehubio/quarkus-qhorus#119 (MCP consistency)

---

## Overview

This spec covers three deliverables that together make the NormativeChannelLayout a
first-class Qhorus concept — not just a Claudony convention:

1. **`MessageTypePolicy` SPI** — pluggable enforcement of per-channel type constraints,
   enforced at both the MCP tool layer (client, fail-fast) and `MessageService` (server,
   safety net). Default implementation reads `allowedTypes` stored on the `Channel` entity.

2. **`examples/normative-layout/`** — new CI module; deterministic scenario (no LLM) proving
   the 3-channel pattern is mechanically correct. Canonical reference importable by Claudony
   and CaseHub. Plus a real Jlama scenario added to `examples/agent-communication/`.

3. **Documentation** — `docs/normative-channel-layout.md` (new, Qhorus-owned);
   updates to `docs/specs/2026-04-13-qhorus-design.md`, CLAUDE.md, and the Claudony
   framework spec reference section.

---

## 1. `MessageTypePolicy` SPI

### 1.1 Interface

```java
package io.quarkiverse.qhorus.runtime.message;

public interface MessageTypePolicy {

    /**
     * Called before a message is persisted. Implementations throw
     * {@link MessageTypeViolationException} to reject the message.
     * A null or empty {@code allowedTypes} on the channel means all types are permitted.
     */
    void validate(Channel channel, MessageType type);
}
```

Location: `runtime/src/main/java/io/quarkiverse/qhorus/runtime/message/MessageTypePolicy.java`

### 1.2 Exception

```java
package io.quarkiverse.qhorus.runtime.message;

public class MessageTypeViolationException extends RuntimeException {
    public MessageTypeViolationException(String channel, MessageType attempted, String allowed) {
        super("Channel '" + channel + "' does not permit " + attempted
                + ". Allowed: " + allowed);
    }
}
```

### 1.3 Default Implementation — `StoredMessageTypePolicy`

```java
@ApplicationScoped
public class StoredMessageTypePolicy implements MessageTypePolicy {

    @Override
    public void validate(Channel channel, MessageType type) {
        if (channel.allowedTypes == null || channel.allowedTypes.isBlank()) {
            return; // open — all types permitted
        }
        Set<MessageType> allowed = Arrays.stream(channel.allowedTypes.split(","))
                .map(String::trim)
                .map(MessageType::valueOf)
                .collect(Collectors.toSet());
        if (!allowed.contains(type)) {
            throw new MessageTypeViolationException(channel.name, type, channel.allowedTypes);
        }
    }
}
```

Replaceable via `@Alternative @Priority(n)`. No configuration property required — the
constraint lives on the channel entity itself.

### 1.4 Enforcement Points

**Client-side (MCP tool layer — `QhorusMcpTools.sendMessage()`):**
- Injects `MessageTypePolicy`
- Resolves channel by name → calls `policy.validate(channel, type)` before delegating to service
- `MessageTypeViolationException` is caught and returned as an MCP error string
- Fail-fast: no round-trip to the service if the type is disallowed

**Server-side (`MessageService.send()`):**
- Injects `MessageTypePolicy`
- Loads `Channel` via `channelService.findById(channelId)` at the top of the main `send()` overload
  (the channel object is needed; this is a single lookup by PK, already in the Quarkus L1 cache
  within the same transaction)
- Calls `policy.validate(channel, type)` before message construction
- Safety net for any non-MCP caller (REST, direct injection, future adapters)
- Same exception type propagates up

**Reactive mirror (`ReactiveQhorusMcpTools`, `ReactiveLedgerWriteService`):**
- Same enforcement pattern in the reactive send path

### 1.5 `Channel` Entity Change

New field added to `Channel`:

```java
/**
 * Comma-separated list of permitted MessageType names.
 * Null means all types are permitted (open channel).
 * Example: "EVENT" for a telemetry-only observe channel.
 */
@Column(name = "allowed_types", columnDefinition = "TEXT")
public String allowedTypes;
```

Storage: comma-separated `MessageType` names (e.g. `"EVENT"`, `"QUERY,COMMAND,RESPONSE"`).
Consistent with the existing `allowedWriters` and `barrierContributors` fields.

### 1.6 `ChannelService` Changes

New `create()` overload chain extended by one level:

```java
public Channel create(String name, String description, ChannelSemantic semantic,
        String barrierContributors, String allowedWriters, String adminInstances,
        Integer rateLimitPerChannel, Integer rateLimitPerInstance, String allowedTypes)
```

Existing overloads delegate up with `allowedTypes = null` — fully backwards-compatible.

### 1.7 `create_channel` MCP Tool Change

New optional parameter added to both `QhorusMcpTools` and `ReactiveQhorusMcpTools`:

```
allowed_types — Comma-separated MessageType names permitted on this channel.
                Null or blank = all types permitted (default).
                Example: "EVENT" for a telemetry-only observe channel.
```

The `create_channel` description is updated to mention `allowedTypes` enforcement.

---

## 2. Examples

### 2.1 `examples/normative-layout/` — New CI Module

**Purpose:** Canonical, deterministic proof that the 3-channel NormativeChannelLayout
works correctly. No LLM dependency. Runs in CI alongside `type-system/`.
Importable by Claudony and CaseHub as the Layer 1 reference.

**Module:** `quarkus-qhorus-example-normative-layout`

**Scenario:** Secure Code Review (from the Claudony framework spec Layer 1)
- `researcher-001` registers, opens channels, analyses `AuthService.java`, shares artefact, posts DONE
- `reviewer-001` picks up DONE, retrieves artefact, posts QUERY to researcher, receives RESPONSE, posts DONE
- `observe` channel: EVENT-only; any attempt to post a non-EVENT type is rejected
- `oversight` channel: QUERY and COMMAND only; any EVENT posted is rejected

**Test classes:**

| Class | What it tests |
|---|---|
| `NormativeLayoutHappyPathTest` | Full researcher→reviewer flow; all commitments fulfilled |
| `NormativeLayoutTypeEnforcementTest` | EVENT rejected on work; non-EVENT rejected on observe; correct errors |
| `NormativeLayoutObligationTest` | QUERY creates OPEN commitment; RESPONSE fulfils it; no stale obligations at end |
| `NormativeLayoutRobustnessTest` | DECLINE discharges obligation; FAILURE discharges; parallel workers on BARRIER channel |
| `NormativeLayoutCorrectnessTest` | Ledger entries count correct; commitment state transitions verified; artefact lifecycle |

**Key scenario class:** `SecureCodeReviewScenario` — a plain Java class (not a test) that
encapsulates the scenario steps against injected `*Service` beans. Tests call into it.
Importable as a test dependency by other modules.

**Channel setup (per test):**
```java
channelService.create("case-test/work",    "Worker coordination", APPEND, null, null, null, null, null, null);
channelService.create("case-test/observe", "Telemetry",           APPEND, null, null, null, null, null, "EVENT");
channelService.create("case-test/oversight","Human governance",   APPEND, null, null, null, null, null, "QUERY,COMMAND");
```

### 2.2 `examples/agent-communication/` — Jlama Normative Layout Scenario

**New test class:** `NormativeLayoutAgentTest` (behind `-Pwith-llm-examples` profile)

Runs the same Secure Code Review scenario but with real Jlama-powered Claude agents:
- `OrchestratorAgent` drives the researcher role
- `WorkerAgent` drives the reviewer role
- Channels created with correct `allowedTypes` constraints
- Test asserts: final DONE posted, no stale commitments, ledger entry count

This complements the deterministic CI proof in `examples/normative-layout/` with a
real end-to-end demonstration.

---

## 3. Documentation

### 3.1 New: `docs/normative-channel-layout.md`

Qhorus-owned documentation of the NormativeChannelLayout. Contents:
- The 3-channel pattern (work / observe / oversight) with their normative roles
- `allowedTypes` field and how to configure it on `create_channel`
- `MessageTypePolicy` SPI — how to plug in a custom policy
- The Layer 1 Secure Code Review example (prose + channel setup snippet)
- Project template: copy-pasteable `create_channel` calls for the normative layout
- Anti-patterns: EVENT on work, QUERY on observe, obligation-carrying acts on oversight

### 3.2 Updated: `docs/specs/2026-04-13-qhorus-design.md`

- **Channel model section:** add `allowedTypes` field
- **SPI section:** add `MessageTypePolicy` alongside existing extension points
- **MCP tool surface:** update `create_channel` description; update `send_message` to note type enforcement
- **Examples section:** add `normative-layout` module entry

### 3.3 Updated: CLAUDE.md

- **Project structure:** add `examples/normative-layout/` module entry
- **Channel entity:** document `allowedTypes` field
- **MCP tool surface:** update `create_channel` param count
- **Testing conventions:** note `allowed_types` enforcement in `MessageService` and MCP layer
- **`QhorusMcpToolsBase` / `QhorusMcpTools`:** update tool count

### 3.4 Updated: Claudony framework spec reference section

The `list_ledger_entries` tool signature in the Claudony spec reference section
(`~/claude/claudony/docs/superpowers/specs/2026-04-27-claudony-agent-mesh-framework.md`)
already reflects the correct current API. Verify `create_channel` entry matches the new
`allowed_types` parameter and update if needed.

---

## 4. Testing Strategy

### Unit tests (no Quarkus, no DB)

| Layer | What |
|---|---|
| `StoredMessageTypePolicyTest` | null allowedTypes = all permitted; single type; multi-type; invalid name throws; wrong type throws with correct message |
| `MessageTypeViolationExceptionTest` | message format; channel name; type name; allowed list preserved |

### Integration tests (`@QuarkusTest`, H2)

| Class | What |
|---|---|
| `MessageTypePolicyIntegrationTest` | Server-side enforcement: `MessageService.send()` throws on violation |
| `CreateChannelWithAllowedTypesTest` | `create_channel` MCP tool persists `allowedTypes`; round-trips correctly |
| `SendMessageTypeEnforcementTest` | MCP `send_message` rejects on violation (client-side); accepted types succeed |
| `ChannelServiceAllowedTypesTest` | `ChannelService.create()` new overload; backwards compat of existing overloads |

### Store contract tests

`ChannelStoreContractTest` extended with `allowedTypes` round-trip assertion (blocking and reactive runners).

### End-to-end / correctness tests (in `examples/normative-layout/`)

Full scenario: researcher→reviewer; correct commitment lifecycle; ledger entry counts;
artefact lifecycle; type enforcement at both enforcement points.

### Robustness tests

- `DECLINE` on observe channel rejected (wrong type) even when client policy bypassed
  (direct `MessageService` call) — server-side catches it
- Custom `@Alternative MessageTypePolicy` that permits all types — pluggability verified
- `allowedTypes` with whitespace in comma-separated values — trimmed correctly
- Unknown type name in stored `allowedTypes` — `IllegalArgumentException` at validate time
- Channel with `allowedTypes=""` (blank) treated as null (all permitted)

---

## 5. Scope Boundaries

**In scope:**
- `MessageTypePolicy` SPI + `StoredMessageTypePolicy` + `MessageTypeViolationException`
- `Channel.allowedTypes` field + migration (schema drop-and-create in tests; note in release)
- `ChannelService.create()` overload extension
- `create_channel` and `send_message` tool updates (blocking + reactive)
- `examples/normative-layout/` new module
- `NormativeLayoutAgentTest` in `examples/agent-communication/`
- `docs/normative-channel-layout.md` (new)
- Updates to `docs/specs/2026-04-13-qhorus-design.md`, CLAUDE.md, Claudony spec

**Out of scope:**
- `InMemoryChannelStore` does not need schema migration — it's in-memory
- No runtime migration of existing channels — `allowedTypes = null` (open) is the safe default
- `allowedTypes` is surfaced in `ChannelDetail` response record — callers must be able
  to inspect what a channel permits; `toChannelDetail()` mapper in `QhorusMcpToolsBase`
  is updated accordingly
- `set_channel_allowed_types` management tool — not in this issue; can be raised separately
