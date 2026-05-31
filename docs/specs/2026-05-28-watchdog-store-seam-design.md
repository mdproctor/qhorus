# Watchdog Store Seam + Router Comment — Design Spec

**Issues:** casehubio/qhorus#205, casehubio/qhorus#206
**Branch:** issue-205-206-watchdog-store-seam
**Date:** 2026-05-28

---

## Problem

`WatchdogEvaluationService` has three direct Panache static calls that bypass the store seam:

| Method | Bypass |
|--------|--------|
| `evaluateApprovalPending` | `Commitment.list(...)` — bypasses `CommitmentStore` |
| `evaluateAgentStale` | `Instance.list(...)` — bypasses `InstanceStore` |
| `evaluateQueueDepth` | `Message.count(...)` — bypasses `MessageStore` |

`evaluateBarrierStuck` and `evaluateChannelIdle` already use stores correctly. The three bypasses break testability (tests can't swap stores) and violate the platform's store-seam pattern.

`ConfiguredWatchdogAlertRouter.route()` ignores its `event` parameter with no explanation, creating a maintenance hazard and discovery problem for implementors.

---

## Fix 1: `evaluateApprovalPending` → `CommitmentStore`

**Approach:** `commitmentStore.findAllOpen()` + Java filter.

`CommitmentStore.findAllOpen()` returns all OPEN or ACKNOWLEDGED commitments. After the call, filter in Java:
- `expiresAt != null` (preserve original `expiresAt IS NOT NULL` semantics)
- `threshold == 0 || c.expiresAt.isBefore(now.plusSeconds(60 - threshold))` (preserve original threshold predicate)

No API changes to `CommitmentStore`. The watchdog runs periodically (not a hot path) — pulling slightly more rows than strictly necessary is acceptable.

`findExpiredBefore(Instant cutoff)` was considered but rejected: for threshold==0 we would need a far-future sentinel, and `Instant.MAX` overflows SQL timestamp types in both H2 and PostgreSQL.

---

## Fix 2: `evaluateAgentStale` → `InstanceStore`

**Approach:** `instanceStore.scan(InstanceQuery)`.

`InstanceQuery` already has `status` and `staleOlderThan` filters. `JpaInstanceStore.scan()` applies both at the SQL level. Direct replacement:

```java
instanceStore.scan(InstanceQuery.builder().status("stale").staleOlderThan(cutoff).build())
```

`WatchdogEvaluationService` currently does not inject `InstanceStore`. Add `@Inject InstanceStore instanceStore`.

No API changes to `InstanceStore` or `InstanceQuery`.

---

## Fix 3: `evaluateQueueDepth` → `MessageStore`

**Approach:** Add `long count(MessageQuery query)` to `MessageStore` (and `Uni<Long>` to `ReactiveMessageStore`).

`MessageQuery` already carries `excludeTypes`. The new `count()` method uses the same WHERE-clause builder pattern as `scan()`, but executes a COUNT query rather than fetching rows.

`InMemoryMessageStore.count()` uses `store.values().stream().filter(q::matches).count()` directly — not `scan(query).size()`, because `scan()` applies `limit` which would give wrong counts.

The call site in `evaluateQueueDepth`:
```java
messageStore.count(
    MessageQuery.builder()
        .channelId(ch.id)
        .excludeTypes(List.of(MessageType.EVENT))
        .build())
```

`countByChannel(UUID, MessageType excludedType)` was considered but rejected. `count(MessageQuery)` is more composable and completes the `scan` / `count` query API pair. `MessageQuery.excludeTypes` already exists — a new method should use it.

---

## #206: `ConfiguredWatchdogAlertRouter.route()` comment

Add a Javadoc comment explaining:
1. **Why `event` is unused:** V1 fan-out — every alert is delivered to all configured endpoints regardless of which condition fired or what alert context it carries.
2. **How to override:** implement `WatchdogAlertRouter` with any normal CDI scope (e.g. `@ApplicationScoped`) — CDI automatically selects any non-`@DefaultBean` qualifying bean over the default. No `@Alternative` or priority annotation is needed.

No behavioural changes.

---

## Change Surface

| File | Change |
|------|--------|
| `MessageStore` | + `long count(MessageQuery query)` |
| `ReactiveMessageStore` | + `Uni<Long> count(MessageQuery query)` |
| `JpaMessageStore` | implement `count()` |
| `InMemoryMessageStore` | implement `count()` as stream filter |
| `ReactiveJpaMessageStore` | implement reactive `count()` |
| `InMemoryReactiveMessageStore` | delegate to blocking `count()` |
| `MessageStoreContractTest` | abstract `count()` + two contract tests |
| `WatchdogEvaluationService` | inject `InstanceStore`; fix 3 methods |
| `ConfiguredWatchdogAlertRouter` | doc comment on `route()` |

## What Does Not Change

- `CommitmentStore` — no new methods; `findAllOpen()` is sufficient
- `InstanceStore` / `InstanceQuery` — no new methods; existing builder covers the query
- `WatchdogStore`, `ChannelStore`, `DataStore` — untouched
- Flyway migrations — none
- SPI contracts in `casehub-qhorus-api` — untouched
- Cross-repo consumers — no impact

---

## Platform Coherence

- Store seam fix aligns with the established pattern across all other qhorus services
- `count(MessageQuery)` completes the `MessageStore` query API (scan/count pair) without adding specialised one-off methods
- No cross-repo propagation required; this is purely internal to `casehub-qhorus`
