# Quarkus Qhorus — Session Handover
**Date:** 2026-04-14 (fourth session)

## What Was Done This Session

**Phase 11 — Access control and governance** (epic #45, issues #46–#49)
- Per-channel write permissions: `allowed_writers` ACL (Flyway V5), `set_channel_writers`, 23 tests
- Admin role: `admin_instances` (Flyway V6), `set_channel_admins`, `caller_instance_id` on
  pause/resume/force_release/clear_channel, 23 tests
- Rate limiting: `RateLimiter` @ApplicationScoped bean, sliding 60s window (Flyway V7),
  `set_channel_rate_limits`, 21 tests
- Observer mode: `ObserverRegistry` @ApplicationScoped bean, `register_observer`,
  `read_observer_events`, `deregister_observer`, 15 tests
- Epic #45 closed; all issues #46–#49 closed

**Tests:** 521 passing (up from 439)

## Critical Testing Conventions (new this session)

*Prior conventions unchanged — `git show HEAD~1:HANDOFF.md` § Critical Testing Conventions*

- `RateLimiter` and `ObserverRegistry` are `@ApplicationScoped` in-memory beans — state does NOT
  roll back with `@TestTransaction`. Use unique channel names and observer IDs per test.
- `check_messages` excludes `EVENT` messages by design — use `read_observer_events` (with a
  registered observer) to assert EVENT delivery in tests.

## Current State

- **Tests:** 521 passing, 0 failing
- **Last commit:** `ba25197` feat(instance): observer mode
- **Uncommitted:** CLAUDE.md, blog/INDEX.md, blog/mdp02 entry, HANDOFF.md
- **Open issues:** none
- **Flyway:** V7 (latest)

## Immediate Next Step

**Phase 12 — Structured observability**

Run issue-workflow Phase 1 first. Scope from the design spec:
- Mandatory `event` payload schema (`agent_id`, `tool_name`, `timestamp`, `duration_ms`,
  optional `correlation_id`, optional `token_count`)
- `list_events(channel_name, after_id, limit)` query tool
- `get_channel_timeline` — ordered view of all message types for a channel
- Structured audit trail queryable by time range and agent

## References

| What | Path |
|---|---|
| Design spec | `docs/specs/2026-04-13-qhorus-design.md` |
| Implementation tracker | `docs/DESIGN.md` (phases 1–7, 9–11 ✅; 8 Claudony; 12 pending) |
| Blog entry this session | `blog/2026-04-14-mdp02-access-control-governance.md` |
| Garden PRs this session | Hortora/garden#51–#54 (4 entries) |
| Previous handover | `git show HEAD~1:HANDOFF.md` |
