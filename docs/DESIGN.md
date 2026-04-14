# Quarkus Qhorus — Design Document

## Overview

Qhorus is a Quarkus extension providing a peer-to-peer agent communication
mesh for multi-agent AI systems. Any Quarkus app adds `quarkus-qhorus` as a
dependency and its agents get typed channels, typed messages, a shared data
store with artefact lifecycle management, an instance registry with capability
tags, and `wait_for_reply` long-polling with correlation IDs.

Primary design specification: `docs/specs/2026-04-13-qhorus-design.md`

---

## Component Structure

Maven multi-module layout following Quarkiverse conventions:

| Module | Artifact | Purpose |
|---|---|---|
| Parent | `quarkus-qhorus-parent` | BOM, version management |
| Runtime | `quarkus-qhorus` | Extension runtime — entities, services, MCP tools, REST |
| Deployment | `quarkus-qhorus-deployment` | Build-time processor — feature registration, native config |

---

## Technology Stack

| Concern | Choice | Notes |
|---|---|---|
| Runtime | Java 21 (on Java 26 JVM) | `maven.compiler.release=21` |
| Framework | Quarkus 3.32.2 | Inherits `quarkiverse-parent:21` |
| Persistence | Hibernate ORM + Panache (active record) | Panache `PanacheEntityBase`, UUID PKs |
| Schema migrations | Flyway | `V1__initial_schema.sql`; consuming app owns datasource config |
| MCP transport | `quarkus-mcp-server-http` 1.11.1 | Streamable HTTP (MCP spec 2025-06-18) |
| JDBC (dev/test) | H2 (optional dep) | PostgreSQL for production |
| Native image | GraalVM 25 | `JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/...` |

---

## Domain Model

Seven Panache entities across four packages. All use UUID primary keys set in
`@PrePersist`; timestamps set in `@PrePersist` / `@PreUpdate`.

### Channel (`runtime/channel/`)

| Entity | Key Fields | Notes |
|---|---|---|
| `Channel` | `id UUID`, `name` (unique), `semantic`, `barrierContributors`, `createdAt`, `lastActivityAt` | `semantic` is `ChannelSemantic` enum |
| `ChannelSemantic` | `APPEND \| COLLECT \| BARRIER \| EPHEMERAL \| LAST_WRITE` | Enum; stored as STRING |

### Message (`runtime/message/`)

| Entity | Key Fields | Notes |
|---|---|---|
| `Message` | `id BIGINT` (sequence), `channelId UUID FK`, `sender`, `messageType`, `content TEXT`, `correlationId`, `inReplyTo`, `replyCount` (denormalized), `artefactRefs` | Sequence PK for ordering |
| `MessageType` | `REQUEST \| RESPONSE \| STATUS \| HANDOFF \| DONE \| EVENT` | `EVENT` is observer-only (`isAgentVisible() = false`) |
| `PendingReply` | `id UUID`, `correlationId` (unique), `instanceId`, `channelId`, `expiresAt` | Tracks `wait_for_reply` correlation |

### Instance (`runtime/instance/`)

| Entity | Key Fields | Notes |
|---|---|---|
| `Instance` | `id UUID`, `instanceId` (unique string), `description`, `status` (online/offline/stale), `claudonySessionId` (optional), `lastSeen`, `registeredAt` | `claudonySessionId` always optional |
| `Capability` | `id UUID`, `instanceId UUID FK`, `tag` | One row per tag per instance |

### Shared Data (`runtime/data/`)

| Entity | Key Fields | Notes |
|---|---|---|
| `SharedData` | `id UUID`, `data_key` (unique), `content TEXT`, `createdBy`, `description`, `complete BOOLEAN`, `sizeBytes` (denormalized) | Column `data_key` (not `key` — reserved word in H2) |
| `ArtefactClaim` | `id UUID`, `artefactId UUID FK`, `instanceId UUID FK`, `claimedAt` | Claim/release lifecycle for GC |

---

## Services

All services are `@ApplicationScoped`. Mutating methods are `@Transactional`.

| Service | Package | Responsibilities |
|---|---|---|
| `ChannelService` | `runtime.channel` | create, findByName, listAll, updateLastActivity |
| `MessageService` | `runtime.message` | send (increments `replyCount`, updates `channel.lastActivityAt`), pollAfter (excludes EVENT), findById, findByCorrelationId |
| `InstanceService` | `runtime.instance` | register (upsert + capability replacement), heartbeat, findByInstanceId, findByCapability, listAll, markStaleOlderThan |
| `DataService` | `runtime.data` | store (create or chunked append), getByKey, getByUuid, listAll, claim, release, isGcEligible |

**Key invariants:**
- `MessageService.send()` always calls `ChannelService.updateLastActivity()` — channel `lastActivityAt` is always current.
- `MessageService.pollAfter()` filters out `EVENT` messages — agent context is never polluted with telemetry.
- `DataService.isGcEligible()` requires `complete = true AND claimCount = 0` — incomplete artefacts never GC-eligible.
- `InstanceService.register()` replaces capability tags on every upsert — no stale tags accumulate.

---

## Configuration

`QhorusConfig` (`runtime.config`): `@ConfigMapping(prefix = "quarkus.qhorus")`

| Property | Default | Meaning |
|---|---|---|
| `quarkus.qhorus.cleanup.stale-instance-seconds` | 120 | Threshold for `markStaleOlderThan` |
| `quarkus.qhorus.cleanup.data-retention-days` | 7 | Days before old messages/data purge |

Consuming app owns all datasource config — none in the extension's `application.properties`.

---

## Build Roadmap

| Phase | Status | What |
|---|---|---|
| **1 — Core data model + services** | ✅ Done | Entities, services, Flyway V1, 33 tests |
| **2 — MCP tools** | ⬜ Pending | `@Tool` methods in `QhorusMcpTools`, APPEND channels only |
| **3 — Channel semantics** | ⬜ Pending | COLLECT, BARRIER, EPHEMERAL, LAST_WRITE enforcement |
| **4 — Correlation + wait_for_reply** | ⬜ Pending | PendingReply, SSE keepalives |
| **5 — Artefacts** | ⬜ Pending | Claim/release in MCP tools, chunked streaming |
| **6 — Addressing** | ⬜ Pending | Capability tags, tag-based dispatch, role broadcast |
| **7 — Agent Card** | ⬜ Pending | `/.well-known/agent-card.json` |
| **8 — Embed in Claudony** | ⬜ Pending | Unified MCP endpoint |
| **9 — A2A compat** | ⬜ Pending | Optional A2A endpoint |

---

## Testing Strategy

- `@QuarkusTest` + `@TestTransaction` per test method — each test rolls back, no data leakage
- H2 in-memory datasource for all tests; Flyway runs V1 migration at boot
- No mocks — all tests exercise real Panache against real H2
- Test classes mirror domain packages: `channel/`, `message/`, `instance/`, `data/`
- `SmokeTest` exercises the full cross-domain workflow in one boot
