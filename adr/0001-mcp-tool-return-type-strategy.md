# 0001 — MCP Tool Return Type Strategy: String vs Structured

Date: 2026-04-16
Status: Accepted

## Context and Problem Statement

At Phase 8, Claudony and Qhorus share a single `/mcp` endpoint. Claudony's 8 tools
all return `String`; Qhorus's 39 tools return structured records (`ChannelDetail`,
`MessageResult`, `List<...>`, etc.). Two incompatible patterns on one endpoint raised
the question: should they be unified, and if so, in which direction?

## Decision Drivers

* Different consumers: Claudony tools are read by Claude (the AI, reasoning about text);
  Qhorus tools are processed programmatically by AI agents that need reliable field access
* Error handling must be consistent across all 47 tools at the unified endpoint
* quarkus-mcp-server serialises both String and structured returns as text content — the
  difference is only visible when consuming agents parse the response

## Considered Options

* **Option A** — Unify to String: change all Qhorus structured-type tools to return
  human-readable text
* **Option B** — Unify to structured: change all Claudony String-returning tools to
  return structured records
* **Option C** — Keep both; unify only the error handling layer using
  quarkus-mcp-server's `isError: true` content mechanism

## Decision Outcome

Chosen option: **Option C**, because the return type is correct for each tool's consumer,
and the only real problem is error handling — which can be solved without changing return types.

### Positive Consequences

* Claudony tools remain readable text — Claude reads "Created 'my-session' (id=abc)"
  without parsing
* Qhorus tools remain structured — agents reliably extract `lastId` for pagination,
  `activeChannels` after registration, and so on; these cannot be parse-from-text reliably
* No breaking change to either tool surface

### Negative Consequences / Tradeoffs

* Error handling requires investigating quarkus-mcp-server's `ToolResponse` / `isError`
  API — structured tools currently surface errors as JSON-RPC protocol errors rather
  than tool content with `isError: true`
* Two patterns coexist on the same endpoint; contributors must understand which applies
  and why

## Pros and Cons of the Options

### Option A — Unify to String

* ✅ Simple: single pattern, trivial Option A error handling everywhere
* ✅ No library API investigation needed
* ❌ Agents must parse structured data from free text — fragile, not what MCP is designed for
* ❌ `check_messages` returning `lastId` as text breaks pagination reliability
* ❌ Loses the JSON schema in `tools/list` that agents use to understand response shape

### Option B — Unify to structured types

* ✅ Consistent tooling: everything uses the same pattern
* ❌ Claudony session tools don't need structure — adding it adds complexity with no benefit
* ❌ Claude reads Claudony tool output as text; structured output is noisier and harder to
  reason about than "Session deleted."
* ❌ More invasive change — all 8 Claudony tool return types need changing

### Option C — Keep both, unify error handling

* ✅ Each tool's return type matches its consumer: text for Claude, structure for agents
* ✅ No breaking changes
* ✅ Future-proof: if quarkus-mcp-server adds richer tool response types, Qhorus can adopt
  them without changing the successful-path contract
* ❌ Error handling for Qhorus structured tools requires using `ToolResponse` with
  `isError: true` — API to be confirmed against quarkus-mcp-server 1.11.1

## Links

* Issue #55 — Qhorus Option A error handling investigation
* Claudony issue #55 — MCP hardening (error handling + timeouts)
* `docs/phase8-claudony-integration.md` — Phase 8 embedding briefing
