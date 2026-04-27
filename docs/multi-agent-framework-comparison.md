# Multi-Agent Framework Comparison

> **Context:** This document provides a feature-level comparison. For the theoretical foundations
> of the normative layer capabilities shown in Part 0, see
> [normative-layer.md](normative-layer.md). For navigation across the full body of works, see
> [normative-framework.md](normative-framework.md).

Comparison of Qhorus against other notable agent communication frameworks,
protocols, and platforms. Rows are features and characteristics; columns are
projects. Shared capabilities are visible at a glance; differentiators stand out.

> **Legend:** ✅ supported · ❌ not supported · 🔶 partial or planned

---

## Part 0 — Normative Layer Capabilities

The normative layer is not a feature category other frameworks are behind on — it is a
structural property that either exists or does not. No framework in this comparison provides
it except Qhorus. The table below isolates these capabilities before the feature-level
comparison so the gap is visible.

| Capability | Gastown | **Qhorus** | AutoGen | LangGraph | CrewAI | Swarm | LangChain4j |
|---|---|---|---|---|---|---|---|
| **Formal commitment lifecycle** (obligation states: OPEN→ACKNOWLEDGED→FULFILLED/DECLINED/FAILED/DELEGATED/EXPIRED) | ❌ Bead has 6-stage data lifecycle; no obligation semantics | ✅ 7-state CommitmentStore, full lifecycle tracking | ❌ | ❌ | ❌ | ❌ | ❌ |
| **FAILURE vs DECLINE distinction** — did the agent try and fail, or deliberately refuse? | ❌ Both appear as timeout to Witness | ✅ First-class speech acts, always distinct | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Delegation traceability** — formal chain when obligation transfers between agents | ❌ Re-sling loses history | ✅ HANDOFF creates causedByEntryId chain, queryable forever | ❌ | 🔶 Graph edges exist but no obligation semantics | ❌ | ❌ | ❌ |
| **Stalled obligation detection** — surfaces unresolved obligations without polling | 🔶 Witness timeout on agent health, not obligation health | ✅ `list_stalled_obligations` — single query, precise duration | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Trust derived from behaviour** — routing decisions driven by demonstrated performance | 🔶 Wasteland stamps (manual, multi-dimensional, not automatic) | ✅ Bayesian Beta + EigenTrust, auto-computed from attestation history | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Post-incident forensics** — who held what obligation, what they knew, when they acted | 🔶 Bead history exists; no causal chain across agents | ✅ Full obligation chain with `causedByEntryId`, CaseContext snapshot at DONE | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Formal semantic grounding** — vocabulary derived from proven formal theory | ❌ Custom vocabulary (bead, sling, nudge, GUPP) | ✅ Speech act theory (Searle), deontic logic (Von Wright, Meyer), social commitments (Singh) | ❌ | ❌ | ❌ | ❌ | ❌ |
| **LLM convergence property** — independently trained LLMs interpret the protocol consistently | ❌ No formal grounding; each LLM implementation may diverge | ✅ Formal grounding ensures consistent interpretation across LLM providers | ❌ | ❌ | ❌ | ❌ | ❌ |

*For the full argument on why formal semantic grounding produces convergence, see [normative-layer.md § Theoretical Foundations](normative-layer.md#theoretical-foundations--from-academic-tradition-to-practical-implementation).*

---

## Part 1 — Coordination and Communication

| Feature | cross-claude-mcp | Gastown | **Qhorus** | A2A (Google) | ACP (IBM/BeeAI) | AutoGen (Microsoft) | Swarm (OpenAI) | LangGraph | Letta/MemGPT | CrewAI | MCP (Anthropic) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **Coordination model** | Channel pub/sub | Hook/bead-based work queue per agent | Channel mesh (N:N) | Task delegation | Run request | Group conversation | Handoff chain | State graph | Single stateful agent | Role-based crew | Tool invocation |
| **Topology** | N:N channels | Town/rig hierarchy; N:N via cross-rig routing (routes.jsonl) | N:N channels | 1:1 orchestrator→specialist | 1:1 caller→agent | 1:N group chat | Sequential 1:1 handoffs | DAG / graph nodes | 1:1 user→agent | Crew (sequential or hierarchical) | 1:1 LLM→tool server |
| **Agent addressing** | By instance_id only | By rig name and agent role | Name · `capability:tag` · `role:name` | Endpoint URL | Endpoint URL | Agent name (in-process) | Function/agent object | Node name (in-process) | Agent ID (REST) | Role name (in-process) | Server endpoint URL |
| **Channel / topic semantics** | Single type (append) | ❌ None — beads are the unit | **5 declared, enforced server-side** (APPEND, COLLECT, BARRIER, EPHEMERAL, LAST_WRITE) | None | None | None | None | State reducers (in-process) | None | None | None |
| **Typed message taxonomy** | 6 types, all agent-visible | ❌ nudge (notify) + sling (assign) — 2 informal types, no formal semantics | **7 types** — adds `event` (observer-only, excluded from agent context) | Opaque task content | Opaque run content | Role-based (user / assistant / function) | Role-based | User-defined node outputs | human / tool / system / reasoning | Task outputs (untyped) | Tool results / resource content |
| **wait_for_reply / correlation** | Polls any non-self message — unsafe under concurrent requests | 🔶 Witness patrol cycles (polling) | **UUID correlation IDs** — safe under N concurrent waits; PendingReply table | Poll task status | SSE stream or poll | Blocking turn-based | Sequential only | `interrupt()` / resume | Request / response | Sequential task execution | Synchronous tool calls |
| **State persistence** | SQLite / PostgreSQL (disposable) | ✅ Dolt SQL — git semantics, time-travel queries, durable | **PostgreSQL — durable, versioned schema (Flyway)** | Task state (ephemeral server-side) | Run state (server-side) | In-memory conversation history | In-memory only | In-memory or custom checkpointer | PostgreSQL / SQLite (durable) | In-memory | Stateless tools |
| **Shared data / artefacts** | Blob inline in message content | 🔶 Bead content inline — no claim/release lifecycle | **UUID artefact refs + claim/release GC + chunked streaming** | Task artifacts (append / last_chunk) | Run outputs | Passed via conversation context | Function args / return values | Graph state | Memory blocks (archival, recall, in-context) | Task output chaining | Resource content / tool results |
| **Multi-agent coordination patterns** | Append-only channels | GUPP hook processing; convoy grouping; formula sequences | COLLECT fan-in · BARRIER join gates · EPHEMERAL routing · LAST_WRITE authoritative state | Sequential delegation only | Point-to-point only | Round-robin / selector / custom | Handoff (one agent at a time) | Conditional branching, parallel nodes | Single agent focus | Sequential / hierarchical crew | None (single LLM) |

---

## Part 2 — Human Interaction and Discovery

| Feature | cross-claude-mcp | Gastown | **Qhorus** | A2A (Google) | ACP (IBM/BeeAI) | AutoGen (Microsoft) | Swarm (OpenAI) | LangGraph | Letta/MemGPT | CrewAI | MCP (Anthropic) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **Human in the loop** | Human can post (no enforcement) | 🔶 Crew role (human with full git clone) — no formal HITL enforcement | **First-class sender; BARRIER gates require human contribution; `event` type for real-time observation** | ❌ No concept | ✅ Designed for human→agent calls | ✅ `HumanProxyAgent`; confirmation requests | 🔶 Return to human | ✅ `interrupt()` / resume for HITL | ✅ Core use case (user ↔ agent) | 🔶 Human feedback between tasks | Via LLM confirmation |
| **Agent discovery** | Manual / hardcoded | 🔶 Town/rig structure; known roles — no capability tags | **Instance registry + capability tags + Agent Card** (Phase 7) | **Agent Card** at `/.well-known/agent-card.json` | Registry-based | ❌ Hardcoded in code | ❌ Hardcoded | ❌ None | Agent directory (Letta Cloud) | ❌ None | ❌ Configured endpoints |
| **Observability** | ❌ None | ✅ OTel (comprehensive); gt feed; gt problems | **`event` message type** — observer-only, never pollutes agent context | Task status + artifacts | Run events / streaming | Logging hooks | ❌ None | **LangSmith** integration | Built-in tracking | Verbose mode only | ❌ None |
| **HandoffMessage safety** | ❌ No enforcement — agent can keep producing after handoff | ❌ Re-sling; chain lost | ✅ Terminal — in-flight results discarded once handoff produced | N/A | N/A | 🔶 Agent return signals intent | Sequential only | Via graph transitions | N/A | Sequential only | N/A |
| **Termination conditions** | `done` message only | Bead CLOSE state | Composable: done · max-messages · keyword · timeout · functional predicate | Task completion | Run completion | Max turns · human confirm · custom | Return value | Graph `END` node | Session end | Task completion | Tool result |

---

## Part 3 — Platform and Deployment

| Feature | cross-claude-mcp | Gastown | **Qhorus** | A2A (Google) | ACP (IBM/BeeAI) | AutoGen (Microsoft) | Swarm (OpenAI) | LangGraph | Letta/MemGPT | CrewAI | MCP (Anthropic) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **Transport** | stdio · SSE · MCP Streamable HTTP | gt CLI / Dolt SQL / tmux sessions | **MCP Streamable HTTP** (spec 2025-06-18) | REST (HTTP) | REST + SSE | In-process / gRPC (AutoGen 0.4+) | In-process (Python) | In-process / REST (LangServe) | REST API | In-process (Python) | stdio · Streamable HTTP · SSE |
| **Language / framework** | Node.js | Go 1.25+ | **Java / Quarkus** — any MCP client connects | Protocol-level (language-agnostic) | Protocol-level (language-agnostic) | Python | Python | Python (JS beta) | Python | Python | Protocol-level |
| **LLM binding** | Primarily Claude | Claude Code, Copilot, Gemini, Cursor, Codex | **LLM-agnostic** — any MCP client | LLM-agnostic | LLM-agnostic | Multi-LLM (OpenAI, Azure, Gemini…) | OpenAI API | Multi-LLM | Multi-LLM | Multi-LLM | LLM-agnostic |
| **Long-term memory** | ❌ None | ✅ Dolt bead history (de facto persistent record) | ❌ None (coordination layer only) | ❌ None | ❌ None | 🔶 In-session history only | ❌ None | 🔶 Via checkpointer (pluggable) | ✅ **Core feature** — archival, recall, in-context memory blocks | 🔶 Via tools | 🔶 Via resource providers |
| **Code execution** | ❌ None | ✅ Polecats in git worktrees | ❌ None | ❌ None | ❌ None | ✅ **Built-in** (Docker sandbox) | ❌ None | 🔶 Via tools | 🔶 Via tools | 🔶 Via tools | 🔶 Via tool calls |
| **Embeddable as library** | ❌ Standalone server only | ❌ Full daemon — not embeddable | ✅ **Quarkus extension** — Maven dependency | ❌ Protocol only | ❌ Protocol only | ✅ Python package | ✅ Python package | ✅ Python package | ✅ Python package | ✅ Python package | ✅ SDK |
| **Native image / runtime size** | Node.js ~80 MB | Go binary (~15MB) | **GraalVM native target ~30 MB, milliseconds to ready** | N/A (HTTP calls) | N/A (HTTP calls) | Python runtime | Python runtime | Python runtime | Python runtime | Python runtime | Depends on host |
| **A2A compatible** | ❌ | ❌ | ✅ Phase 9 (optional endpoint) | ✅ **Native** | 🔶 | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **ACP compatible** | ❌ | ❌ | 🔶 Phase 9b (same pattern as A2A) | 🔶 | ✅ **Native** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Production maturity** | Prototype / demo | ✅ v1.0.1 (production, April 2026) | Production-grade Quarkiverse extension | ✅ v1.0 (Google, April 2025) | Active development | ✅ Production (Microsoft) | ⚠️ Experimental / educational | ✅ Production (LangChain) | ✅ Production (commercial) | ✅ Production (commercial) | ✅ Production (Anthropic, 2025) |
| **Primary use case** | Claude agent coordination (prototype) | AI coding agent coordination for software engineering teams | **Multi-agent mesh coordination in production Quarkus systems** | External orchestrator→specialist delegation | Human/system→agent invocation | Research and enterprise multi-agent | Teaching agent handoff patterns | Stateful workflow graphs | Long-term memory agents | Role-based agent teams | LLM tool access |

---

## What Qhorus Uniquely Provides

Across all frameworks surveyed, Qhorus is the only system that combines:

1. **N:N channel mesh** — agents coordinate without knowing each other's addresses
2. **Declared, enforced channel semantics** — COLLECT, BARRIER, EPHEMERAL, LAST_WRITE (borrowed from LangGraph's Pregel model but enforced server-side, not in-process)
3. **UUID correlation IDs for wait_for_reply** — safe under concurrent requests, not positional
4. **Artefact refs with claim/release lifecycle** — not inline content duplication
5. **`event` message type** — clean observability boundary, never pollutes agent context
6. **Durable state with versioned schema** — survives restarts, upgrades, and rolling deploys
7. **Java/Quarkus native** — JVM ecosystem, GraalVM native target, embeddable as a Maven dependency
8. **Human as first-class participant** — not a proxy or callback, a peer
9. **Formal commitment lifecycle** — OPEN → ACKNOWLEDGED → FULFILLED/DECLINED/FAILED/DELEGATED/EXPIRED with full causal chain
10. **FAILURE vs DECLINE distinction** — always distinct; never conflated as timeout
11. **Trust derived from behaviour** — Bayesian Beta + EigenTrust auto-computed from attestation history
12. **LLM convergence property** — formal semantic grounding ensures consistent interpretation across independently trained LLMs

---

## Where Other Frameworks Excel

| Framework | Standout capability |
|---|---|
| **Letta/MemGPT** | Long-term memory — archival, recall, and in-context memory blocks for persistent agent identity |
| **AutoGen** | Code execution — built-in Docker sandbox; mature group chat orchestration |
| **LangGraph** | Graph-based workflow — conditional branching, parallel execution, HITL via `interrupt()` |
| **CrewAI** | Role-based simplicity — easiest entry point for "a team of agents with roles" patterns |
| **Swarm** | Pedagogical clarity — best for understanding agent handoff fundamentals |
| **A2A** | Ecosystem standard — the broadest external orchestrator compatibility |
| **MCP** | Tool access standard — universal LLM-to-tool connection layer |

---

*Research sources: Google A2A v1.0, Microsoft AutoGen, OpenAI Swarm, LangGraph (Pregel model),
Letta (MemGPT), CrewAI, MCP spec 2025-06-18, IBM/BeeAI ACP.*

---

## Related Documents

| Document | What it covers |
|----------|---------------|
| [normative-framework.md](normative-framework.md) | Entry point and navigation for this body of works |
| [normative-layer.md](normative-layer.md) | Theoretical foundations, worked examples, the full normative layer argument |
| [agent-protocol-comparison.md](agent-protocol-comparison.md) | How Qhorus, A2A, and ACP are complementary rather than competing |
