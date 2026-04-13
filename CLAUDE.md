# Quarkus Qhorus — Claude Code Project Guide

## Project Type

type: java

**Stack:** Java 21 (on Java 26 JVM), Quarkus 3.32.2, GraalVM 25 (native image), quarkus-mcp-server 1.11.1

---

## What This Project Is

Qhorus is a Quarkus extension providing an agent communication mesh — the peer-to-peer coordination layer for multi-agent AI systems. It is the Quarkus port of cross-claude-mcp (`~/claude/cross-claude-mcp`), redesigned based on research into A2A, AutoGen, LangGraph, Swarm, Letta, and CrewAI.

Any Quarkus app adds `io.quarkiverse.qhorus:quarkus-qhorus` as a dependency and its agents immediately get:
- **Typed channels** with declared update semantics (APPEND, COLLECT, BARRIER, EPHEMERAL, LAST_WRITE)
- **Typed messages** (request · response · status · handoff · done · event)
- **Shared data store** with artefact lifecycle management (claim/release, UUID refs, chunked streaming)
- **Instance registry** with capability tags and three addressing modes (by id · by capability · by role)
- **`wait_for_reply`** long-poll with correlation IDs and SSE keepalives
- **Agent Cards** at `/.well-known/agent-card.json` for A2A ecosystem discovery

Qhorus is designed to be embedded in Claudony (`~/claude/claudony`) as part of the broader Quarkus Native AI Agent Ecosystem, and eventually submitted to Quarkiverse.

---

## Quarkiverse Naming

This project follows Quarkiverse naming conventions throughout:

| Element | Value |
|---|---|
| GitHub repo | `mdproctor/quarkus-qhorus` (→ `quarkiverse/quarkus-qhorus` when submitted) |
| groupId | `io.quarkiverse.qhorus` |
| Parent artifactId | `quarkus-qhorus-parent` |
| Runtime artifactId | `quarkus-qhorus` |
| Deployment artifactId | `quarkus-qhorus-deployment` |
| Root Java package | `io.quarkiverse.qhorus` |
| Runtime subpackage | `io.quarkiverse.qhorus.runtime` |
| Deployment subpackage | `io.quarkiverse.qhorus.deployment` |
| Config prefix | `quarkus.qhorus` |
| Feature name | `qhorus` |

---

## Ecosystem Context

```
casehub (orchestration engine)   quarkus-qhorus (communication mesh)
           ↑                              ↑
           └──────── claudony (integration layer) ──────────┘
```

Qhorus has no dependency on CaseHub or Claudony — it is the independent communication layer.

---

## Project Structure

```
quarkus-qhorus/
├── runtime/                             — Extension runtime module
│   └── src/main/java/io/quarkiverse/qhorus/runtime/
│       ├── config/QhorusConfig.java     — @ConfigMapping(prefix = "quarkus.qhorus")
│       ├── channel/
│       │   ├── Channel.java             — PanacheEntity
│       │   ├── ChannelSemantic.java     — enum: APPEND|COLLECT|BARRIER|EPHEMERAL|LAST_WRITE
│       │   └── ChannelService.java
│       ├── message/
│       │   ├── Message.java             — PanacheEntity
│       │   ├── MessageType.java         — enum: request|response|status|handoff|done|event
│       │   ├── PendingReply.java        — PanacheEntity (correlation ID tracking)
│       │   └── MessageService.java
│       ├── instance/
│       │   ├── Instance.java            — PanacheEntity
│       │   ├── Capability.java          — PanacheEntity (capability tags)
│       │   └── InstanceService.java
│       ├── data/
│       │   ├── SharedData.java          — PanacheEntity
│       │   ├── ArtefactClaim.java       — PanacheEntity (claim/release lifecycle)
│       │   └── DataService.java
│       ├── mcp/
│       │   └── QhorusMcpTools.java      — @Tool methods (all MCP tools)
│       └── api/
│           └── AgentCardResource.java   — GET /.well-known/agent-card.json
├── deployment/                          — Extension deployment (build-time) module
│   └── src/main/java/io/quarkiverse/qhorus/deployment/
│       └── QhorusProcessor.java         — @BuildStep: FeatureBuildItem + native config
├── docs/specs/                          — Design specs
└── .github/                             — Quarkiverse CI workflows
```

---

## Build and Test

```bash
# Build all modules
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install

# Run tests (runtime module)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test

# Run specific test
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=ClassName -pl runtime

# Native image build (requires GraalVM)
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
  mvn package -Pnative -DskipTests
```

**Use `mvn` not `./mvnw`** — maven wrapper not configured on this machine.

**Quarkiverse format check:** CI runs `mvn -Dno-format` to skip the enforced Quarkiverse code formatting. Run `mvn` locally to apply formatting (via Quarkiverse parent's formatter plugin).

---

## Java and GraalVM on This Machine

```bash
# Java 26 (Oracle, system default) — use for dev and tests
JAVA_HOME=$(/usr/libexec/java_home -v 26)

# GraalVM 25 — use for native image builds only
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home
```

**Native gotcha:** `quarkus-mcp-server` 1.11.1 fixed sampling and elicitation in native image — they were silently broken in earlier versions. Always use ≥ 1.11.1.

---

## Design Document

`docs/specs/2026-04-13-qhorus-design.md` is the primary design spec. It incorporates research from A2A, AutoGen, LangGraph, OpenAI Swarm, Letta, and CrewAI.

---

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** mdproctor/quarkus-qhorus

**Automatic behaviours (Claude follows these at all times in this project):**
- **Before implementation begins** — check if an active issue exists. If not, run issue-workflow Phase 1 before writing any code.
- **Before any commit** — run issue-workflow Phase 3 to confirm issue linkage.
- **All commits should reference an issue** — `Refs #N` (ongoing) or `Closes #N` (done).
