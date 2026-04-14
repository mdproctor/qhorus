# Agent Protocol Comparison — A2A, ACP, and Qhorus

Three open standards address agent communication in different ways. This document
explains what each one does, how they differ, and how they work together.

---

## The Short Version

**A2A** (Google) and **ACP** (IBM/BeeAI) answer the question:
*"How do I reach this specific agent from outside?"*

**Qhorus** answers the question:
*"How do agents coordinate with each other once they're running?"*

They are complementary, not competing. A production multi-agent system will
typically use all three.

---

## Feature Comparison

| Feature | A2A (Google) | ACP (IBM/BeeAI) | Qhorus |
|---|---|---|---|
| **Topology** | Point-to-point — orchestrator → known specialist | Point-to-point — caller → specific agent endpoint | Mesh — N agents on named channels, no addresses needed |
| **Primary abstraction** | Task — submit, track, receive result | Run — submit, stream or poll result | Channel + Message — publish, subscribe, coordinate |
| **Addressing** | Endpoint URL | Endpoint URL | Channel name · `capability:tag` · `role:name` |
| **State** | Task state (submitted / working / completed / failed) | Run state (server-side) | Persistent channel history + PendingReply for correlation |
| **Async / streaming** | SSE — task artifact streaming via `append + last_chunk` | SSE — run result streaming | `wait_for_reply` with correlation IDs + keepalive |
| **Concurrent requests** | Sequential delegation — one orchestrator manages tasks | One caller per run | UUID correlation IDs — safe under N concurrent waits |
| **Human interaction** | No first-class concept | Designed for human-to-agent calls | First-class — humans post to channels; BARRIER gates require human contribution |
| **Multi-agent coordination** | Orchestrator → A → B (sequential delegation) | Point-to-point calls | COLLECT fan-in · BARRIER join gates · EPHEMERAL routing hints |
| **Discovery** | Agent Card at `/.well-known/agent-card.json` | Registry-based | Instance registry with capability tags + Agent Card |
| **Shared data / artefacts** | Task artifacts (append / last_chunk pattern) | Run outputs | UUID artefact refs · claim/release lifecycle · chunked streaming |
| **Observability** | Task status + artifacts | Run events | `event` message type — observer-only, never in agent context |
| **Transport** | REST (HTTP) | REST + SSE | MCP Streamable HTTP (spec 2025-06-18) |
| **LLM / framework binding** | Agnostic | Agnostic | Agnostic — any MCP client |
| **Channel semantics** | None | None | 5 declared semantics enforced server-side (APPEND, COLLECT, BARRIER, EPHEMERAL, LAST_WRITE) |
| **Origin** | Google, April 2025 | IBM / BeeAI, 2025 | Independent Quarkiverse project |

---

## What Each One Is Good At

### A2A
Best for **structured delegation** — an orchestrator that knows which specialist to call
and wants a clean task lifecycle with artifacts. Well-suited for:
- Orchestrator → specialist patterns
- Long-running tasks that produce artifacts
- Systems where the caller knows the callee's address in advance

### ACP
Best for **human-initiated or externally-triggered runs** — a caller (human or system)
that needs to invoke an agent and get a result back. Well-suited for:
- Human-to-agent interactions
- External systems triggering agent tasks
- Lightweight request/response with streaming

### Qhorus
Best for **multi-agent coordination** — agents that need to work together without knowing
each other's addresses, share large artefacts by reference, and be observable by humans
in real time. Well-suited for:
- N agents collaborating on a shared problem
- Fan-in patterns (multiple agents contribute, one reads the aggregate)
- Join gates (wait for all named agents before proceeding)
- Asynchronous request/reply with safe concurrent waits
- Real-time human interjection into agent conversations

---

## How They Work Together

In a complete multi-agent system, all three appear at different layers:

```
External callers                   Internal agent coordination
────────────────                   ──────────────────────────────
                                   Agent A ──┐
A2A orchestrator ──→ Agent A  or            ├──→ Qhorus channel ──→ Agent B
ACP caller       ──→ Agent A            ↕   │                   ──→ Agent C
Human browser    ──→ Agent A       Human ───┘
                                           ↕ wait_for_reply (corr. ID)
                                       Agent B responds
```

- **A2A / ACP** handle the external interface — how callers reach a specific agent
- **Qhorus** handles the internal mesh — how agents reach each other without knowing addresses

An agent deployed with Qhorus can simultaneously:
- Expose an A2A-compatible endpoint for external orchestrators
- Expose an ACP endpoint for human and system callers
- Participate in Qhorus channels with peer agents

Qhorus Phase 9 adds an optional A2A-compatible REST endpoint
(`POST /a2a/message:send`) that maps directly to `send_message` and `wait_for_reply`.
An analogous ACP endpoint can be added following the same pattern.

---

## Choosing Between Them

| Situation | Use |
|---|---|
| External system needs to call a specific agent | A2A or ACP |
| Human needs to trigger an agent task | ACP |
| Multiple agents need to coordinate without knowing addresses | Qhorus |
| You need fan-in (collect contributions from N agents) | Qhorus COLLECT channel |
| You need a join gate (wait for all contributors) | Qhorus BARRIER channel |
| You need a single authoritative value (no concurrent writes) | Qhorus LAST_WRITE channel |
| You need transient routing hints between agents | Qhorus EPHEMERAL channel |
| A human needs to interject into an ongoing agent conversation | Qhorus (human as first-class sender) |
| You want agents to be discoverable by external ecosystems | A2A Agent Card (Qhorus Phase 7) |

---

## Qhorus Compatibility

Qhorus publishes `/.well-known/agent-card.json` in A2A format (Phase 7), making every
Qhorus deployment discoverable by A2A orchestrators without any additional configuration.

Qhorus Phase 9 adds an optional A2A compatibility endpoint for external orchestrators
that want to delegate tasks to Qhorus agents using the A2A protocol. The same pattern
applies for ACP.

This means a Qhorus agent participates in all three ecosystems simultaneously —
reachable from any external caller, coordinating internally via the mesh.
