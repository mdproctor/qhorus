# Quarkus Qhorus — Session Handover
**Date:** 2026-04-13

## What Was Done

Project scaffolded and restructured to full Quarkiverse compliance:
- Renamed: `qhorus` → `quarkus-qhorus` (GitHub repo + local directory)
- groupId: `dev.qhorus` → `io.quarkiverse.qhorus`
- Structure: single module → **parent + runtime + deployment** (required Quarkiverse layout)
- Packages: `dev.qhorus` → `io.quarkiverse.qhorus.runtime` / `.deployment`
- Config prefix: `qhorus.` → `quarkus.qhorus.`
- Parent: now inherits from `io.quarkiverse:quarkiverse-parent:21`
- Added: `quarkus-extension-maven-plugin`, `quarkus-extension-processor` in runtime/deployment
- Added: `QhorusProcessor.java` (FeatureBuildItem), `META-INF/quarkus-extension.yaml`
- Added: `.github/project.yml`, `CODEOWNERS`, `build.yml` (Quarkiverse CI scaffold)
- No Java source yet — all scaffold, spec, and structure

GitHub: https://github.com/mdproctor/quarkus-qhorus

## What Qhorus Is

Quarkus extension providing a peer-to-peer agent communication mesh. Port of `~/claude/cross-claude-mcp`, redesigned after research into A2A, AutoGen, LangGraph, Swarm, Letta, CrewAI. Part of a three-project ecosystem — no dependency on CaseHub or Claudony.

## Key Design Decisions

- **Transport:** Streamable HTTP (MCP spec 2025-06-18). `quarkus-mcp-server-http` v1.11.1.
- **Channel semantics:** APPEND (default) · COLLECT · BARRIER · EPHEMERAL · LAST_WRITE
- **Message types:** `request · response · status · handoff · done · event` (`event` = observer-only)
- **`wait_for_reply`:** UUID correlation IDs, `PendingReply` table, SSE keepalives every 30s
- **Artefacts:** UUID refs on messages, not inline. `claim/release` lifecycle for GC.
- **Addressing:** by `instance_id` · by `capability:tag` · by `role:name`
- **HandoffMessage is terminal** — in-flight results discarded on handoff

> **Native gotcha:** `quarkus-mcp-server` ≥ 1.11.1 required — earlier versions silently broke sampling/elicitation in native image.

## Immediate Next Step — Phase 1: Data Model + Services

All source under `runtime/src/main/java/io/quarkiverse/qhorus/runtime/`.

1. **Config** — `config/QhorusConfig.java` with `@ConfigMapping(prefix = "quarkus.qhorus")`

2. **Entities** (all PanacheEntity, UUID PKs):
   - `channel/Channel.java` — name (unique), description, semantic (enum), barrierContributors, timestamps
   - `message/Message.java` — channelId, sender, messageType (enum), content, correlationId, inReplyTo, replyCount (denormalized), createdAt
   - `message/PendingReply.java` — correlationId (unique), instanceId, channelId, expiresAt
   - `instance/Instance.java` + `instance/Capability.java`
   - `data/SharedData.java` + `data/ArtefactClaim.java`

3. **Services** — ChannelService, MessageService, InstanceService, DataService. Panache only, no raw SQL.

4. **Flyway migration** — `runtime/src/main/resources/db/migration/V1__initial_schema.sql`

5. **Smoke test** — `runtime/src/test/java/io/quarkiverse/qhorus/SmokeTest.java`

Full data model ER diagram: `docs/specs/2026-04-13-qhorus-design.md` § Data Model

## Quarkiverse Rules (critical)

- `@BuildStep` only in `deployment/` — never in `runtime/`
- Config: `@ConfigMapping(prefix = "quarkus.qhorus")` not `@ConfigProperty`
- Datasource config set by consuming app — NOT in extension's `application.properties`
- `quarkus-extension-processor` must be in annotationProcessorPaths of BOTH runtime and deployment compiler

## References

| What | Path |
|---|---|
| Design spec | `docs/specs/2026-04-13-qhorus-design.md` |
| Ecosystem design | `~/claude/claudony/docs/superpowers/specs/2026-04-13-quarkus-ai-ecosystem-design.md` |
| Source project (Node.js) | `~/claude/cross-claude-mcp` — read `tools.mjs` for MCP tool semantics |
| Claudony (embedding target) | `~/claude/claudony` |
| CaseHub (SPI context) | `~/claude/casehub` |
| Reference extension | `github.com/quarkiverse/quarkus-mcp-server` — same structure, similar domain |
