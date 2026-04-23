# Quarkus Qhorus — Session Handover
**Date:** 2026-04-23 (fourteenth session — speech acts idea migrated; Claudony integration done)

## What Was Done This Session

- **Speech acts idea** moved from `claudony/IDEAS.md` to `quarkus-qhorus/IDEAS.md` — correctly identified as a Qhorus `MessageType` concern, not a Claudony UI concern
- **Claudony integration** (done by user in background): datasource rename → `quarkus.datasource.qhorus.*`, InMemory store cleanup in `MeshResourceInterjectionTest`, DESIGN.md + CLAUDE.md updated in Claudony repo
- **Last Panache bypass closed** — `getChannelTimeline` in `QhorusMcpTools` was still using `Message.find()` directly; now routes through `messageStore.scan()`. InMemory*Store `put()` methods now initialise timestamps (mirroring `@PrePersist`). Both fixed in `b9c804c`

## Current State

- **Branch:** `main`
- **Tests:** 716 runtime (44 skipped = `@Disabled` reactive runners) + 120 testing module + 4 examples
- **Open issue:** #87 — `ReactiveJpaMessageStore.countAllByChannel()` uses in-memory `listAll()` instead of GROUP BY; harmless while `@Disabled`
- **Uncommitted:** `.claude/settings.local.json` + 6 untracked plan files in `docs/superpowers/plans/`

## Key Architecture Facts

*Unchanged — `git show HEAD~1:HANDOFF.md`*

## Immediate Next Step

**Speech acts + MessageType redesign** — read `IDEAS.md` for full framing. Key question: does `request | response | status | handoff | done | event` get replaced, extended, or supplemented with a speech-act-informed taxonomy? Breaking change risk must be addressed.

Start with brainstorming: what concrete agent behaviours are currently ambiguous because of the flat enum, and what would richer types enable?

## References

| What | Path |
|---|---|
| Speech acts idea (full context) | `IDEAS.md` |
| MessageType enum | `runtime/src/main/java/io/quarkiverse/qhorus/runtime/message/MessageType.java` |
| Previous handover | `git show HEAD~1:HANDOFF.md` |
| Named datasource ADR | `adr/0004-named-datasource-isolation.md` |
