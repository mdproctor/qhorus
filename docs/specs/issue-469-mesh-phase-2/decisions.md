## D1: Enforcement mode — design for v2, ship v1

**Choice:** Design the full visited-set propagation mechanism for hard enforcement (v2), but ship v1 as advisory-only (detection + watchdog alert, no invocation refusal).
**Alternatives:**
- v1 only (detection + advisory) — simpler but loses enforcement readiness; API changes later would break consumers
- v2 hard enforcement now — premature; MCP access control already limits the blast radius
**Rationale:** Building the infrastructure now means the API signature stabilises before consumers adopt it. Upgrading from advisory to enforcement is a config/policy change, not an API change.
**Trade-offs:** Slightly more implementation work than pure v1. Visited-set field exists in MessageDispatch even when enforcement is off.
**Sources:** Issue #468 §Constraint: "For v1, the direct guard plus MCP access control provides sufficient protection."
**Exploration:** quick
**Status:** captured

## D2: Propagation mechanism — MessageDispatch field

**Choice:** Add an `invocationContext` field to `MessageDispatch` (API record). The visited-set travels with the message through the entire dispatch pipeline. Agent-bridge populates it; MessageService preserves it through persistence and fanOut.
**Alternatives:**
- @RequestScoped CDI context — fragile across virtual threads; Quarkus context propagation not guaranteed for Thread.ofVirtual()
- Message metadata/payload piggyback — less type-safe, harder to enforce, invisible to the pipeline
**Rationale:** Explicit field is the cleanest propagation path. No thread-local magic, no CDI scope assumptions. The field is visible in the API contract, making the visited-set a first-class concept.
**Trade-offs:** Changes the API record signature (17th field). All OutboundMessage construction sites need updating. Flyway migration for the column.
**Sources:** MessageDispatch.java (16 fields currently), AgentProviderBackend.postTracked(), AgentInvocationRunner.run()
**Exploration:** quick
**Status:** captured

## D3: Responsibility split — bridge writes, core preserves

**Choice:** Agent-bridge is responsible for populating the invocationContext before dispatching. MessageService.dispatch() copies it through to persistence and OutboundMessage. The bridge reads it on the receiving end to detect loops. Core pipeline is a passive carrier.
**Alternatives:**
- MessageService enforces globally — makes loop detection a platform concern; tighter coupling between core and bridge concepts
**Rationale:** Loop detection is an agent-bridge-specific concern (only bridges invoke agents). The core pipeline should not enforce bridge-specific semantics. The bridge owns both ends of the contract.
**Trade-offs:** Loop detection requires the bridge module on classpath. Without it, the invocationContext field is unused (null, harmless).
**Sources:** AgentProviderBackend.java, MessageService.java dispatch pipeline
**Exploration:** quick
**Status:** captured

## D4: Visited-set representation — JSON string

**Choice:** `String invocationContext` (nullable JSON) on MessageDispatch and OutboundMessage. Agent-bridge serialises `Set<String>` to JSON array (e.g. `["agent-a","agent-b"]`) before dispatch; deserialises on receive. Stored as TEXT column in Message entity.
**Alternatives:**
- `Set<String>` field — type-safe but adds collection semantics to API records; needs JPA converter
- Ledger-based detection (no API change) — elegant but adds a DB query per invocation and has a race window
**Rationale:** JSON string is backward-compatible (null = no context), requires no collection types in API records, stores naturally in a TEXT column, and is trivially serialised/deserialised.
**Trade-offs:** Parse cost on every postTracked() call (negligible for small sets). Not type-safe at the API boundary — callers could put arbitrary JSON.
**Sources:** MessageDispatch.java, OutboundMessage.java, Message entity
**Exploration:** quick
**Status:** captured
