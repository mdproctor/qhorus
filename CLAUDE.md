# Qhorus — Claude Code Project Guide

## Project Type

type: java

**Stack:** Java 21 (on Java 26 JVM), Quarkus 3.32.2, GraalVM 25 (native image), quarkus-mcp-server 1.11.1

---

## What This Project Is

Qhorus is a Quarkus Native agent communication mesh — the peer-to-peer coordination layer for multi-agent AI systems. It is the Quarkus port of cross-claude-mcp (`~/claude/cross-claude-mcp`), redesigned based on research into A2A, AutoGen, LangGraph, Swarm, Letta, and CrewAI.

Agents (Claude instances or any other AI agents) connect via MCP and get:
- **Typed channels** with declared update semantics (APPEND, COLLECT, BARRIER, EPHEMERAL, LAST_WRITE)
- **Typed messages** (request · response · status · handoff · done · event)
- **Shared data store** with artefact lifecycle management (claim/release, UUID refs, chunked streaming)
- **Instance registry** with capability tags and three addressing modes (by id · by capability · by role)
- **`wait_for_reply`** long-poll with correlation IDs and SSE keepalives
- **Agent Cards** at `/.well-known/agent-card.json` for A2A ecosystem discovery

Qhorus is designed to run standalone OR be embedded in Claudony (`~/claude/claudony`) as part of the broader Quarkus Native AI Agent Ecosystem (see `docs/specs/`).

---

## Ecosystem Context

```
casehub (orchestration engine)   qhorus (communication mesh)
           ↑                              ↑
           └──────── claudony (integration layer) ──────────┘
```

Qhorus has no dependency on CaseHub or Claudony. It is the independent communication layer.

See `~/claude/cross-claude-mcp/docs/superpowers/specs/2026-04-13-quarkus-ai-ecosystem-design.md` for the full ecosystem design.

---

## Build and Test

```bash
# Run all tests
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test

# Run specific test
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=ClassName

# JVM build
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn package -DskipTests

# Native image build (requires GraalVM)
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
  mvn package -Pnative -DskipTests
```

**Use `mvn` not `./mvnw`** — maven wrapper not configured on this machine.

---

## Running

```bash
# Dev mode (H2 file DB, hot reload)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn quarkus:dev

# JVM jar
JAVA_HOME=$(/usr/libexec/java_home -v 26) java -jar target/quarkus-app/quarkus-run.jar

# Native binary
./target/qhorus-1.0.0-SNAPSHOT-runner
```

**Default port:** 7779 (avoids clash with Claudony server :7777 and agent :7778)

---

## Key URLs

- MCP endpoint: `http://localhost:7779/mcp` (Streamable HTTP — POST + GET)
- Health: `http://localhost:7779/q/health`
- Agent Card: `http://localhost:7779/.well-known/agent-card.json`
- REST API: `http://localhost:7779/api/*`

---

## Project Structure

```
src/main/java/dev/qhorus/
├── config/QhorusConfig.java         — all config properties
├── channel/
│   ├── Channel.java                 — Channel entity (Panache)
│   ├── ChannelSemantic.java         — enum: APPEND | COLLECT | BARRIER | EPHEMERAL | LAST_WRITE
│   └── ChannelService.java          — channel CRUD + semantic enforcement
├── message/
│   ├── Message.java                 — Message entity (Panache)
│   ├── MessageType.java             — enum: request | response | status | handoff | done | event
│   └── MessageService.java          — send, check, search, correlation
├── instance/
│   ├── Instance.java                — Instance entity (Panache)
│   ├── Capability.java              — capability tag entity
│   └── InstanceService.java         — register, heartbeat, stale detection, capability lookup
├── data/
│   ├── SharedData.java              — SharedData entity (Panache)
│   ├── ArtefactRef.java             — UUID-keyed artefact reference
│   └── DataService.java             — share, get, claim, release, cleanup
├── mcp/
│   └── QhorusMcpTools.java          — @Tool methods (MCP surface — see spec for full list)
└── agent/
    └── AgentCardResource.java       — GET /.well-known/agent-card.json
```

---

## Java and GraalVM on This Machine

```bash
# Java 26 (Oracle, system default) — use for dev and tests
JAVA_HOME=$(/usr/libexec/java_home -v 26)

# GraalVM 25 — use for native image builds only
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home
```

---

## Design Document

`docs/specs/2026-04-13-qhorus-design.md` is the primary design spec. It incorporates research from A2A, AutoGen, LangGraph, OpenAI Swarm, Letta, and CrewAI.

---

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** mdproctor/qhorus (to be created)

**Automatic behaviours (Claude follows these at all times in this project):**
- **Before implementation begins** — check if an active issue exists. If not, run issue-workflow Phase 1 before writing any code.
- **Before any commit** — run issue-workflow Phase 3 to confirm issue linkage.
- **All commits should reference an issue** — `Refs #N` (ongoing) or `Closes #N` (done).
