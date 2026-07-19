# Channel Summary Slot & Circular Delegation Watchdog

**Issues:** #355 (channel context summary), #368 (circular delegation detection)
**Date:** 2026-07-19
**Branch:** issue-355-summary-and-delegation

---

## Problem

Two coordination gaps in multi-agent channels:

1. **Context drift** (#355) — 65% of enterprise AI failures are context drift or memory loss. An agent joining a long-running channel must read the full history to understand "where are we?" Projections help (bounded fold), but agents need a "state of the conversation" without scanning messages.

2. **Circular delegation** (#368) — None of the existing watchdog conditions detect circular HANDOFF chains (A→B→C→A). CONVERSATION_STALL provides partial coverage but rapid circular delegation within the threshold window goes undetected.

---

## Feature 1: Channel Summary Slot (#355)

### Core Concept

A `ChannelSummary` is per-channel metadata — stored alongside the channel, not as a message in it. It holds a maintained summary: participants, decisions, pending obligations, current state. Agents call `get_channel_summary(channel)` before deciding whether to `project_channel` for full detail.

**Summaries vs projections:** Projections (`project_channel`) are deterministic, stateless folds computed on-demand from message history — always current, never stored. Summaries are pre-computed, stored, and may lag behind the actual channel state. When the hook uses an LLM for natural-language summarization, the summary is qualitatively different from a projection — non-deterministic, richer in interpretation, but potentially stale. Agents should treat summaries as best-effort context and fall back to `project_channel` when freshness is critical.

Qhorus provides: storage, lifecycle, SPI hook, MCP tools.
Consumers (blocks) provide: summarisation logic (LLM-based, L0–L4 hierarchy).

### Domain Type — `api/channel/`

```java
public record ChannelSummary(
        UUID id,
        UUID channelId,
        String content,           // opaque summary text; structure determined by consumer
        Instant updatedAt,
        String updatedBy,         // instanceId or "system:summary-scheduler"
        Long lastUpdatedMessageId, // message ID cursor at time of last update
        Integer updateAfterMessages, // nullable — trigger after N new messages
        Integer updateAfterSeconds,  // nullable — trigger after T seconds
        String tenancyId
) {}
```

One summary per channel. `updateAfterMessages` and `updateAfterSeconds` control auto-update triggers (both null = explicit-only). `lastUpdatedMessageId` tracks the cursor for incremental updates.

Builder follows the existing `Watchdog.Builder` pattern.

### JPA Entity — `runtime/channel/`

```java
@Entity(name = "ChannelSummary")
@Table(name = "channel_summary")
public class ChannelSummaryEntity extends PanacheEntityBase {
    @Id public UUID id;
    @Column(name = "channel_id", nullable = false, unique = true) public UUID channelId;
    @Column(name = "content", columnDefinition = "TEXT") public String content;
    @Column(name = "updated_at") public Instant updatedAt;
    @Column(name = "updated_by") public String updatedBy;
    @Column(name = "last_updated_message_id") public Long lastUpdatedMessageId;
    @Column(name = "update_after_messages") public Integer updateAfterMessages;
    @Column(name = "update_after_seconds") public Integer updateAfterSeconds;
    @Column(name = "tenancy_id", nullable = false) public String tenancyId;
}
```

FK to `channel(id)` with ON DELETE CASCADE. `@PrePersist` generates UUID and sets `tenancyId` default.

### Store Interfaces — `api/store/`

```java
public interface ChannelSummaryStore {
    ChannelSummary save(ChannelSummary summary);
    Optional<ChannelSummary> findByChannelId(UUID channelId);
    void deleteByChannelId(UUID channelId);
}

public interface ReactiveChannelSummaryStore {
    Uni<ChannelSummary> save(ChannelSummary summary);
    Uni<Optional<ChannelSummary>> findByChannelId(UUID channelId);
    Uni<Void> deleteByChannelId(UUID channelId);
}
```

Cross-tenant variant for scheduled sweep:

```java
public interface CrossTenantChannelSummaryStore {
    List<ChannelSummary> findAll();
    List<ChannelSummary> findWithAutoUpdateConfigured();
}
```

`findWithAutoUpdateConfigured()` returns only summaries where `updateAfterMessages IS NOT NULL OR updateAfterSeconds IS NOT NULL`. Used by the scheduler to avoid fetching and discarding unconfigured summaries.

JPA implementations in `runtime/store/jpa/`. InMemory implementations in `persistence-memory/`.

### SPI Hook — `api/spi/`

```java
@FunctionalInterface
public interface SummaryUpdateHook {
    String update(SummaryUpdateContext context);
}

public record SummaryUpdateContext(
        UUID channelId,
        String channelName,
        String tenancyId,
        String currentSummary,         // nullable — null on first update
        Long lastUpdatedMessageId,     // nullable — null on first update
        long messagesSinceLastUpdate
) {}
```

The hook receives context and returns updated summary text. The hook implementation (in blocks) queries messages and calls an LLM internally — it knows what window size and strategy to use. Hook implementations are `@ApplicationScoped` CDI beans and inject qhorus stores directly (e.g., `CrossTenantMessageStore`) to access message data. This cross-module dependency is expected — blocks already depends on qhorus as a consumer.

`@DefaultBean` no-op implementation:

```java
@DefaultBean
@ApplicationScoped
public class NoOpSummaryUpdateHook implements SummaryUpdateHook {
    @Override
    public String update(SummaryUpdateContext context) {
        return context.currentSummary();
    }
}
```

Reactive variant:

```java
@FunctionalInterface
public interface ReactiveSummaryUpdateHook {
    Uni<String> update(SummaryUpdateContext context);
}
```

With a `@DefaultBean` that wraps the blocking hook on a worker pool (following `reactive-blocking-spi-worker-pool` protocol).

### Service Layer — `runtime/channel/`

`ChannelSummaryService` (`@ApplicationScoped`, blocking):

- `getSummary(UUID channelId) → Optional<ChannelSummary>` — read
- `setSummary(UUID channelId, String content, String updatedBy) → ChannelSummary` — manual write (creates if absent, using channel's tenancyId). Advances `lastUpdatedMessageId` to the channel's current max message ID — this prevents the next scheduled sweep from overwriting the manual content
- `configureSummary(UUID channelId, Integer updateAfterMessages, Integer updateAfterSeconds) → ChannelSummary` — set auto-update thresholds (creates if absent). Validates: `updateAfterMessages` must be >= 1 or null; `updateAfterSeconds` must be >= 1 or null. Throws `IllegalArgumentException` for out-of-range values
- `triggerUpdate(UUID channelId) → Optional<ChannelSummary>` — invoke hook immediately, store result
- `deleteSummary(UUID channelId)` — remove

`ReactiveChannelSummaryService` — reactive mirror, `@IfBuildProperty(casehub.qhorus.reactive.enabled)`.

### Scheduled Sweep — `runtime/channel/`

`ChannelSummaryScheduler` — `@Scheduled` periodic check, same pattern as `WatchdogScheduler`:

```java
@Scheduled(every = "${casehub.qhorus.summary.check-interval-seconds:60}s",
           identity = "summary-update-check")
```

Algorithm:
1. If `casehub.qhorus.summary.enabled` is false, return
2. `crossTenantChannelSummaryStore.findWithAutoUpdateConfigured()` — summaries with thresholds set
3. For each summary:
   - If `updateAfterMessages` set: count messages in the channel WHERE `id > lastUpdatedMessageId` (or all messages if `lastUpdatedMessageId` is null). If count >= `updateAfterMessages`, trigger
   - If `updateAfterSeconds` set: if `updatedAt` is null or `now - updatedAt >= updateAfterSeconds`, trigger
   - If either threshold met: invoke `SummaryUpdateHook.update()`, store result
4. After each successful hook call: set `lastUpdatedMessageId` to the channel's current max message ID, set `updatedAt` to now

**First-time behavior:** When `lastUpdatedMessageId` is null (first update after `configure_channel_summary`), `messagesSinceLastUpdate` equals the total message count in the channel. The hook implementation decides how to handle this — e.g., it may window to the last N messages for LLM context rather than processing the full history. This is a hook implementation concern; the platform always provides the accurate count.

Execution is synchronous on the scheduler thread, matching the `WatchdogScheduler` pattern. Quarkus `@Scheduled` defaults to `concurrentExecution = SKIP` — if a sweep is still running when the next interval fires, the next sweep is skipped. LLM latency in hooks extends the sweep duration; the configurable interval accommodates this. Per-channel invocations are sequential within one sweep. Hook failures are non-fatal: WARN log with channel name and exception, skip that channel, continue sweep. The summary entity is NOT updated on failure — the next sweep will retry.

Uses `CrossTenantChannelSummaryStore`, `CrossTenantChannelStore`, `CrossTenantMessageStore` — follows `scheduled-service-cross-tenant-stores` protocol.

### MCP Tools

Added to `QhorusMcpTools` and `ReactiveQhorusMcpTools`:

- `get_channel_summary(channel)` — returns summary content, updatedAt, updatedBy, or "no summary configured"
- `update_channel_summary(channel, summary)` — manual write; bypasses hook entirely. Advances `lastUpdatedMessageId` to the channel's current max message ID so the next scheduled sweep does not overwrite the manual content
- `configure_channel_summary(channel, update_after_messages?, update_after_seconds?)` — set auto-update thresholds; creates summary entity if absent (with null content)
- `trigger_channel_summary_update(channel)` — invoke the configured `SummaryUpdateHook` on demand, store the result. Returns the updated summary or error if no hook is configured / channel not found

`channel` parameter accepts UUID or name (dual-identity, resolved at MCP boundary per `mcp-tool-channel-resolution-boundary` protocol).

### CDI Event

```java
public record ChannelSummaryUpdatedEvent(
        UUID channelId, String channelName, String updatedBy
) {}
```

Fired after every summary update (hook-triggered or manual). Optional module observers can react (e.g., push summary to connected agents via WebSocket).

### Naming Clash Resolution

The existing `QhorusMcpToolsBase.ChannelSummary` record (`name`, `description`, `semantic`) used in the `register` response conflicts with the new domain type. Rename it to `ChannelInfo`:

```java
record ChannelInfo(String name, String description, String semantic) {}
```

Update `RegisterResponse.activeChannels` type from `List<ChannelSummary>` to `List<ChannelInfo>`. Pre-release — breaking changes cost nothing.

### Configuration — `QhorusConfig`

```java
interface Summary {
    @WithDefault("true")
    boolean enabled();

    @WithDefault("60")
    int checkIntervalSeconds();
}
```

Prefix: `casehub.qhorus.summary.*`

### Flyway Migration

V37: `channel_summary` table.

```sql
CREATE TABLE channel_summary (
    id UUID PRIMARY KEY,
    channel_id UUID NOT NULL UNIQUE REFERENCES channel(id) ON DELETE CASCADE,
    content TEXT,
    updated_at TIMESTAMP,
    updated_by VARCHAR(255),
    last_updated_message_id BIGINT,
    update_after_messages INTEGER,
    update_after_seconds INTEGER,
    tenancy_id VARCHAR(255) NOT NULL DEFAULT '278776f9-e1b0-46fb-9032-8bddebdcf9ce'
);
```

---

## Feature 2: Circular Delegation Watchdog (#368)

### Core Concept

When agent A delegates to B, B to C, and C back to A, the work enters a cycle that will never resolve. Detection uses the commitment store: all commitments in a delegation chain share the same `correlationId`. If any `obligor` appears more than once across commitments with the same `correlationId`, that's a cycle.

This feature is detection-only — the alert gives operators the information to manually break the cycle (e.g., by declining one of the OPEN commitments in the chain). Unlike other watchdog conditions (CHANNEL_IDLE, CONVERSATION_STALL) which are self-limiting, circular delegation is self-perpetuating. Auto-decline of cycle-creating commitments is a potential future enhancement (see #368 for tracking).

### Watchdog Condition Type

Add `CIRCULAR_DELEGATION` to `WatchdogConditionType` enum.

### Alert Context — `api/watchdog/`

```java
public record CircularDelegationContext(
        UUID channelId,
        String channelName,
        String correlationId,
        List<String> cycle,     // ordered list of obligors forming the cycle
        int chainDepth          // total delegation chain length
) implements AlertContext {
    @Override
    public WatchdogConditionType conditionType() {
        return WatchdogConditionType.CIRCULAR_DELEGATION;
    }
}
```

Update `AlertContext` sealed permits list to include `CircularDelegationContext`.

### Store Method

Add to `CrossTenantCommitmentStore`:

```java
/** All commitments sharing a correlationId, ordered by createdAt ASC (chronological delegation order). */
List<Commitment> findAllByCorrelationId(String correlationId);
```

Returns ALL commitments (any state — OPEN, DELEGATED, FULFILLED, etc.) sharing a `correlationId`, ordered chronologically. This is the full delegation chain. The `ORDER BY createdAt ASC` contract is load-bearing — the cycle detection algorithm depends on chronological order to produce a meaningful cycle path (A → B → C → A, not A → C → B → A).

Add to `CommitmentStore` for blocking/reactive parity:

```java
/** All commitments sharing a correlationId, ordered by createdAt ASC. */
List<Commitment> findAllByCorrelationId(String correlationId);
```

Add to `ReactiveCommitmentStore`:

```java
/** All commitments sharing a correlationId, ordered by createdAt ASC. */
Uni<List<Commitment>> findAllByCorrelationId(String correlationId);
```

InMemory implementations in `persistence-memory/`. JPA implementations in `runtime/store/jpa/`.

### Evaluation Logic — `WatchdogEvaluationService`

```
evaluateCircularDelegation(Watchdog w, Instant now):
    maxDepth = w.thresholdCount() ?? 10
    channels = filter by targetName (same as other conditions)

    for each channel:
        openCommitments = crossTenantCommitmentStore.findOpenByChannel(channelId)
            .filter(c -> c.parentCommitmentId() != null)  // delegation children only

        checkedCorrelationIds = new HashSet<>()

        for each open commitment with parentCommitmentId:
            if correlationId already checked: skip
            checkedCorrelationIds.add(correlationId)

            allInChain = crossTenantCommitmentStore.findAllByCorrelationId(correlationId)
            if allInChain.size() > maxDepth: skip (safety bound)

            obligors = allInChain.stream()
                .map(Commitment::obligor)
                .filter(Objects::nonNull)
                .toList()

            // Check for repeated obligors
            seen = new HashSet<>()
            cycle = new ArrayList<>()
            for obligor in obligors:
                if !seen.add(obligor):
                    // Found the cycle — extract the cyclic portion
                    build cycle list from first occurrence to repeat
                    fire alert
                    break

    return fired
```

The `thresholdCount` parameter serves as a safety bound on chain depth (default 10). Chains deeper than this are skipped — they would indicate data issues, not normal delegation.

**Debounce:** The shared `isDebounced()` mechanism uses `thresholdSeconds` as the debounce window. For most conditions, `thresholdSeconds` has dual semantics (condition parameter + debounce). For CIRCULAR_DELEGATION, the condition is binary (cycle exists or not), so `thresholdSeconds` is purely debounce. If unset, debounce defaults to 1 second — the alert fires on every evaluation cycle for an unresolved cycle. Operators should set `thresholdSeconds` when registering this condition; 300 seconds is a reasonable default to avoid alert noise while still surfacing new cycles promptly.

### ConnectorAlertBridge Update

Add new case to the exhaustive switch in `ConnectorAlertBridge.buildBody()`:

```java
case CircularDelegationContext c -> event.summary()
    + "\nChannel: " + c.channelName()
    + "\nCorrelation ID: " + c.correlationId()
    + "\nCycle: " + String.join(" → ", c.cycle())
    + "\nChain depth: " + c.chainDepth();
```

### MCP Tool Update

`register_watchdog` already accepts `conditionType` as a string and validates via `WatchdogConditionType.fromString()`. The new enum value works automatically. No tool signature change needed.

---

## Cross-Cutting Concerns

### Modules Affected

| Module | #355 Changes | #368 Changes |
|--------|-------------|-------------|
| `api/` | ChannelSummary record, ChannelSummaryStore, ReactiveChannelSummaryStore, CrossTenantChannelSummaryStore (findAll + findWithAutoUpdateConfigured), SummaryUpdateHook, ReactiveSummaryUpdateHook, SummaryUpdateContext, NoOpSummaryUpdateHook, ChannelSummaryUpdatedEvent | CircularDelegationContext, WatchdogConditionType enum, AlertContext permits, CommitmentStore.findAllByCorrelationId, CrossTenantCommitmentStore.findAllByCorrelationId, ReactiveCommitmentStore.findAllByCorrelationId |
| `runtime/` | ChannelSummaryEntity, JpaChannelSummaryStore, ReactiveJpaChannelSummaryStore, CrossTenantJpaChannelSummaryStore, ChannelSummaryService, ReactiveChannelSummaryService, ChannelSummaryScheduler, MCP tools (4: get, update, configure, trigger), ChannelInfo rename | WatchdogEvaluationService.evaluateCircularDelegation, JpaCommitmentStore.findAllByCorrelationId, ReactiveJpaCommitmentStore, CrossTenantJpaCommitmentStore |
| `persistence-memory/` | InMemoryChannelSummaryStore, InMemoryReactiveChannelSummaryStore, InMemoryCrossTenantChannelSummaryStore | InMemoryCommitmentStore.findAllByCorrelationId, InMemoryReactiveCommitmentStore, InMemoryCrossTenantCommitmentStore |
| `connectors/` | — | ConnectorAlertBridge switch update |
| `deployment/` | — | — |

### Flyway

- V37: `channel_summary` table (next available per CLAUDE.md)

No migration needed for #368 — uses existing `commitment` table with existing columns.

### Testing Strategy

**#355:**
- Unit: ChannelSummaryService with InMemory stores (CDI-free)
- Unit: ChannelSummaryScheduler with mock stores and mock hook (CDI-free)
- Unit: SummaryUpdateHook contract (hook receives context, returns text)
- Integration: `@QuarkusTest` MCP tools (get/update/configure/trigger)
- Contract: ChannelSummaryStoreContractTest (blocking + reactive runners)

**#368:**
- Unit: cycle detection algorithm (CDI-free, InMemory stores)
- Unit: WatchdogEvaluationService.evaluateCircularDelegation with pre-built delegation chains
- Integration: `@QuarkusTest` with WatchdogEnabledProfile — register watchdog, create delegation cycle via HANDOFF, run evaluateAll(), assert alert
- Contract: CommitmentStoreContractTest extended with findAllByCorrelationId

### Consumer Impact

- **blocks:** Will implement `SummaryUpdateHook` to provide LLM-based summarisation
- **claudony:** Will use `get_channel_summary` and `update_channel_summary` MCP tools
- **engine:** No impact — engine uses `CommitmentStore.findOpenByObligor()`, not the new method

### CLAUDE.md Updates

After implementation:
- Document `ChannelSummaryStore` / `ReactiveChannelSummaryStore` in store interfaces section
- Document `SummaryUpdateHook` / `ReactiveSummaryUpdateHook` in SPI section
- Document MCP tools in tools section
- Document `ChannelInfo` rename
- Update Flyway version tracking (next: V38)
- Add `CIRCULAR_DELEGATION` to watchdog conditions documentation
- Add `findAllByCorrelationId` to store method documentation
