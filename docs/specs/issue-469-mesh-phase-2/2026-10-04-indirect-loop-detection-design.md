# Indirect Loop Detection — Invocation-Context Propagation

**Issue:** casehubio/qhorus#468
**Epic:** casehubio/qhorus#469 (Mesh Phase 2)
**Date:** 2026-10-04
**Status:** Design

---

## Problem

The agent-bridge module's direct loop guard in `AgentProviderBackend.postTracked()` checks `message.sender().equals(binding.agentInstanceId())` — this prevents direct loops (A→A) but not indirect loops (A→X→B→Y→A) where an MCP-enabled agent dispatches a COMMAND via the mesh that eventually cycles back.

Indirect loops are only possible when agents have MCP server access, which is restricted to governance-critical agents per the agent-bridge spec's §MCP Server Exposure. For v1, the direct guard plus MCP access control provides sufficient protection. This design builds the full propagation infrastructure (v2-ready) but ships with advisory-only enforcement (v1).

## Design

### Invocation Context as a Visited-Set

A visited-set of agent instanceIds travels with the message through the dispatch pipeline as a JSON-serialised string field. Each agent-bridge adds its instanceId to the set before dispatching responses. When a bridge receives a message for invocation, it checks the visited-set — if its instanceId is present, a loop has been detected.

### Field: `invocationContext`

A nullable `String` field added to three API records:

| Record | Position | Type | Default |
|---|---|---|---|
| `MessageDispatch` | 17th field | `String` (nullable JSON) | `null` |
| `OutboundMessage` | 13th field | `String` (nullable JSON) | `null` |
| `Message` entity | new column | TEXT (nullable) | `null` |

Format: JSON array of agent instanceId strings, e.g. `["agent-a","agent-b"]`. Null means no invocation context (default for all non-bridge dispatches).

### Data Flow

```
1. External COMMAND arrives at channel (no invocationContext)
   
2. DeliveryService pump → AgentProviderBackend.postTracked()
   - Reads invocationContext from OutboundMessage (null or existing set)
   - Checks: is binding.agentInstanceId() in the set? → NO
   - Spawns AgentInvocationRunner with the inbound context
   
3. AgentInvocationRunner.run()
   - Builds augmented context: existingSet ∪ {binding.agentInstanceId()}
   - Serialises to JSON: ["agent-a"]
   - All dispatches (RESPONSE, STATUS, FAILURE) carry this context
   - If the agent calls an MCP tool that dispatches a COMMAND,
     the MCP tool implementation passes invocationContext through
   
4. MessageService.dispatch()
   - Persists invocationContext to Message.invocationContext column
   - Passes through to ChannelGateway.fanOut() → OutboundMessage
   
5. DeliveryService pump → another AgentProviderBackend.postTracked()
   - Reads invocationContext: ["agent-a"]
   - Checks: is binding.agentInstanceId() in the set? → YES if agent-a
   - Loop detected → advisory alert (v1) or refuse invocation (v2)
```

### Responsibility Split

| Component | Responsibility |
|---|---|
| `AgentInvocationRunner` | Builds augmented visited-set, sets on all dispatches |
| `AgentProviderBackend` | Reads visited-set, detects loops, enforces (v1: alert, v2: refuse) |
| `MessageService` | Passive carrier — persists and passes through |
| `ChannelGateway` / `DeliveryBatchExecutor` | Passive carrier — copies to OutboundMessage |
| `MeshService` / MCP tools | Pass invocationContext from the current dispatch context |

### v1 Enforcement (Advisory)

When `AgentProviderBackend.postTracked()` detects a loop (agent in visited-set):

1. Log a WARN with the full visited-set chain
2. Dispatch a system EVENT to the channel with telemetry:
   ```json
   {"tool_name": "loop_detection", "source_entity": "agent-bridge",
    "loop_chain": ["agent-a", "agent-b", "agent-a"]}
   ```
3. Return `PostResult.ALL_DELIVERED` (do not invoke the agent)

This fires any registered watchdog with `LOOP_DETECTED` condition type (already exists) and creates a ledger entry for audit.

### v2 Enforcement (Future)

When enforcement is upgraded, `postTracked()` throws or returns a failure result instead of silently skipping. The delivery pump handles the failure via its existing retry/circuit-breaker mechanism. The visited-set propagation infrastructure is identical — only the enforcement policy changes.

### MCP Tool Context Threading

The critical gap: when an agent calls an MCP tool that dispatches a COMMAND, the tool implementation needs access to the current invocationContext. The MCP tool runs on the same virtual thread as the `AgentInvocationRunner` (the agent's tool call is synchronous from the runner's perspective).

**Approach:** Use a `ThreadLocal<String>` in `AgentInvocationRunner`:

```java
public static final ThreadLocal<String> CURRENT_INVOCATION_CONTEXT = new ThreadLocal<>();
```

Before entering the agent event iteration loop, set the ThreadLocal. Clear it in the finally block. MCP tool implementations that dispatch COMMANDs read this ThreadLocal and pass it to `MessageDispatch.builder().invocationContext(...)`.

This works because:
- The MCP tool call from the agent executes synchronously on the runner's virtual thread
- Virtual threads have their own ThreadLocal storage
- The ThreadLocal is cleared after the invocation completes (no leakage)

For tools that dispatch asynchronously (rare), the ThreadLocal value must be captured before the async boundary and passed explicitly. This is a documented contract, not an automated propagation.

### Migration

- **Flyway V56:** `ALTER TABLE message ADD COLUMN invocation_context TEXT` (nullable, no default)
- **Message entity:** `invocationContext` field with `@Column(name = "invocation_context")`

### API Backward Compatibility

- `MessageDispatch`: new 17th field; existing Builder gains `.invocationContext(String)` setter; existing construction sites pass `null`
- `OutboundMessage`: new 13th field; backward-compatible constructors delegate with `null`
- `Message` entity: nullable column; existing messages have `null` (no invocation context)

### Testing

1. **Unit test:** `AgentProviderBackend` detects loop when invocationContext contains its instanceId
2. **Unit test:** `AgentInvocationRunner` augments visited-set before dispatching
3. **Integration test:** Full chain — Agent A dispatches COMMAND → Agent B receives → Agent B dispatches COMMAND back → Agent A detects loop (advisory EVENT dispatched)
4. **CDI-free test:** ThreadLocal propagation to simulated MCP tool dispatch

## References

- `AgentProviderBackend.java:69` — existing direct loop guard
- `AgentInvocationRunner.java:41-93` — agent event iteration and dispatch
- `MessageDispatch.java` — 16-field API record (becomes 17)
- `OutboundMessage.java` — 12-field API record (becomes 13)
- `WatchdogConditionType.LOOP_DETECTED` — existing watchdog condition
- Issue #468 — problem statement and design direction
- `docs/protocols/casehub/sse-active-model-virtual-thread.md` — virtual thread threading model
