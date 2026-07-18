# Coordination Pathology Watchdog — Design Spec

**Issue:** #354 (parent: #349 Coordination Resilience)
**Date:** 2026-07-17
**Scale:** M | **Complexity:** Med

## Problem

37% of multi-agent failures are coordination breakdowns (MAST taxonomy). The WatchdogService monitors channels for BARRIER_STUCK, APPROVAL_PENDING, AGENT_STALE, CHANNEL_IDLE, QUEUE_DEPTH, and CONTEXT_PRESSURE. It does not detect:

- Agents stuck in infinite loops (same content repeated)
- COMMAND obligations with zero engagement
- Channels with active work but no terminal resolution
- Content echoed between agents without transformation

These patterns are observable from the message stream — no agent-level reasoning required.

## Approach

Inline expansion of the existing pattern: 4 new `evaluate*` methods in `WatchdogEvaluationService`, 4 new `AlertContext` records, 4 new enum values, one shared content similarity utility. No new abstractions.

## Data Model

### Watchdog record — new field

Add `Integer similarityPct` (nullable, 0–100) to the `Watchdog` record as the 6th field (after `thresholdCount`, before `notificationChannel`):

```java
public record Watchdog(
        UUID id,
        WatchdogConditionType conditionType,
        String targetName,
        Integer thresholdSeconds,
        Integer thresholdCount,
        Integer similarityPct,
        String notificationChannel,
        String createdBy,
        String tenancyId,
        Instant createdAt,
        Instant lastFiredAt) { ... }
```

`WatchdogEntity` gains `@Column(name = "similarity_pct") public Integer similarityPct`.

Flyway **V36**: `ALTER TABLE watchdog ADD COLUMN similarity_pct INTEGER`.

### Parameter mapping

| Condition | thresholdSeconds | thresholdCount | similarityPct |
|---|---|---|---|
| LOOP_DETECTED | time window (default 300) | repetition count (default 5) | content similarity % (default 70) |
| OBLIGATION_FAN_OUT | inactivity deadline (default 300) | — | — |
| CONVERSATION_STALL | stall duration (default 600) | — | — |
| ECHO_CHAMBER | time window (default 300) | min agents (default 2) | content similarity % (default 70) |

All existing conditions unaffected — `similarityPct` is nullable and ignored.

### Ripple

- `WatchdogEntity.fromDomain()` / `toDomain()` — String↔enum conversion for `conditionType`. `toDomain()` uses `WatchdogConditionType.fromString()` — unrecognized values log a warning and return null (caller filters). This preserves evaluation loop resilience: a single unknown DB value must not crash `crossTenantWatchdogStore.listAll()`
- `WatchdogConditionType.fromString(String)` — returns `Optional<WatchdogConditionType>`, wrapping `valueOf()` to avoid `IllegalArgumentException` on unrecognized strings (rollback safety, manual inserts)
- `Watchdog.Builder` — `conditionType` parameter and field become `WatchdogConditionType`; add `similarityPct` field
- `Watchdog.toBuilder()` — carry `similarityPct`
- `InMemoryWatchdogStore` / `InMemoryReactiveWatchdogStore` — use `toBuilder()` in `put()` instead of positional constructor
- `WatchdogSummary` record in `QhorusMcpToolsBase`
- MCP tool `create_watchdog` — new optional `similarity_pct` parameter; `conditionType` validated as enum
- `ConnectorAlertBridgeTest` — Watchdog construction in test helpers
- `evaluateAll()` fired block — use `w.toBuilder().lastFiredAt(now).build()` instead of positional constructor
- `evaluateAll()` switch — migrate from string literals to `WatchdogConditionType` enum values

## API Types

### WatchdogConditionType

```java
public enum WatchdogConditionType {
    BARRIER_STUCK, APPROVAL_PENDING, AGENT_STALE, CHANNEL_IDLE, QUEUE_DEPTH,
    CONTEXT_PRESSURE,
    LOOP_DETECTED, OBLIGATION_FAN_OUT, CONVERSATION_STALL, ECHO_CHAMBER
}
```

### AlertContext sealed hierarchy — 4 new records

All in `api/watchdog/`. The sealed interface `AlertContext` extends its permits clause.

```java
public record LoopDetectedContext(
        UUID channelId, String channelName,
        String sender, int messageCount, double maxSimilarity
) implements AlertContext {
    @Override public WatchdogConditionType conditionType() { return LOOP_DETECTED; }
}

public record ObligationFanOutContext(
        UUID channelId, String channelName,
        int staleCount, List<String> correlationIds
) implements AlertContext {
    @Override public WatchdogConditionType conditionType() { return OBLIGATION_FAN_OUT; }
}

public record ConversationStallContext(
        UUID channelId, String channelName,
        int stalledCount, List<String> correlationIds, long stalledSeconds
) implements AlertContext {
    @Override public WatchdogConditionType conditionType() { return CONVERSATION_STALL; }
}

public record EchoChamberContext(
        UUID channelId, String channelName,
        List<String> participants, double maxSimilarity
) implements AlertContext {
    @Override public WatchdogConditionType conditionType() { return ECHO_CHAMBER; }
}
```

## Content Similarity

`JaccardSimilarity` — package-private utility in `runtime/watchdog/`.

```java
final class JaccardSimilarity {
    static double similarity(String a, String b) { ... }
}
```

- Tokenisation: whitespace-split, lowercase, strip punctuation characters (`{ } [ ] ( ) : , " ' = ;`) from each token. Punctuation stripping reduces false positives on structured content (JSON, templates) where shared structural characters inflate similarity scores.
- Jaccard index = |intersection| / |union| of the resulting token sets
- Returns 0.0–1.0 (1.0 = identical token sets)
- Null/empty → 0.0; both empty → 1.0
- Deterministic, no embedding model — infrastructure-appropriate per issue spec
- **Known limitation:** Jaccard is order-insensitive and vocabulary-dominated. Messages with shared terminology (field names, command verbs, status templates) can produce elevated similarity even when semantically different. The `similarityPct` threshold is tunable per watchdog to accommodate domain-specific false-positive profiles.

Shared by `evaluateLoopDetected` and `evaluateEchoChamber`.

## Condition Evaluators

Four new private methods in `WatchdogEvaluationService`, dispatched from the `evaluateAll()` switch. As part of this change, the switch migrates from string literals to `WatchdogConditionType` enum values — the enum already exists for `AlertContext` and should be the single source of truth for condition type dispatch.

### evaluateLoopDetected

Detects a single agent repeating itself — consecutive messages with similar content.

1. For each channel matching `targetName`:
2. Fetch last `thresholdCount * 3` messages descending, excluding EVENTs
3. Group by sender
4. For each sender with >= `thresholdCount` messages:
5. Filter to messages within `thresholdSeconds` window, sorted by `createdAt` ascending
6. If still >= `thresholdCount`: compute Jaccard similarity for each consecutive pair (N-1 comparisons for N messages)
7. Find the longest run of consecutive pairs where similarity >= `similarityPct` / 100.0
8. If run length >= `thresholdCount - 1` (i.e., `thresholdCount` consecutive messages are all pairwise-similar): fire alert

The `* 3` fetch multiplier ensures enough messages per sender in multi-sender channels. Consecutive-pair comparison is O(N) — strictly cheaper than all-pairs O(N²). The consecutive approach detects agents stuck in a loop (repeating the same output) without false-firing on coincidental template reuse separated by distinct messages.

### evaluateObligationFanOut

Detects COMMAND obligations with zero engagement within deadline.

1. For each channel matching `targetName`:
2. `crossTenantCommitmentStore.findOpenByChannel(ch.id())` → OPEN commitments
3. Filter: `messageType == COMMAND`
4. Filter: `acknowledgedAt == null`
5. Filter: `createdAt < now - thresholdSeconds`
6. For each survivor: `crossTenantMessageStore.count(channelId + correlationId + excludeTypes=[COMMAND, EVENT])`
7. If count == 0: no STATUS or any other response — stale obligation
8. If any stale: fire alert (correlationIds capped at 5 in context)

Step 6 is one count query per surviving commitment — bounded by the number of stale obligations (few in healthy systems, exactly the set we detect in unhealthy ones).

**Known limitation:** This condition depends on `correlationId` integrity. If an agent responds with a fabricated correlationId that doesn't match the COMMAND's correlation, the count returns 0 (false positive). If an agent reuses another COMMAND's correlationId, a response to the wrong COMMAND could suppress the alert (false negative). The sibling correlation-strengthening work under #349 will eliminate this risk; until then, this condition has a known reliability limitation proportional to agent compliance.

### evaluateConversationStall

Detects a channel with active work where individual correlations are not completing.

1. For each channel matching `targetName`:
2. `crossTenantCommitmentStore.findOpenByChannel(ch.id())` → active commitments
3. If empty: skip (no active conversation to stall)
4. Filter to commitments where `createdAt < now - thresholdSeconds` (age guard — commitments younger than the threshold haven't had time to resolve)
5. If empty after filter: skip (not stalled, just new)
6. For each surviving commitment: query for the most recent obligation-resolution message (DONE, FAILURE, DECLINE, or HANDOFF) matching `channelId + correlationId`
7. If no resolution message exists, or its `createdAt < now - thresholdSeconds`: this correlation is stalled
8. If any correlation is stalled: fire alert with stalled count, correlationIds (capped at 5), and longest stall duration

Per-correlation checking prevents a recently-resolved correlation from masking stalled siblings in the same channel. Cost: one message query per age-qualifying active commitment — bounded by the same logic as evaluateObligationFanOut step 6.

**Terminal type rationale:** This uses the obligation-resolution set {DONE, FAILURE, DECLINE, HANDOFF}, not `MessageType.isTerminal()` which returns {HANDOFF, DONE, FAILURE}. DECLINE is included because it closes an obligation (transitions commitment to DECLINED state). HANDOFF is included because it transfers an obligation to a new debtor (transitions commitment to DELEGATED state). Both represent progress toward resolution from the channel's perspective — their absence within the stall window indicates nothing is completing.

Distinction from CHANNEL_IDLE: idle = no messages at all. Stall = messages flowing (STATUS updates, etc.) but nothing reaching terminal resolution.

### evaluateEchoChamber

Detects content relayed between agents without transformation — sustained echo, not isolated forwarding.

1. For each channel matching `targetName`:
2. Fetch last 50 messages descending, excluding EVENTs
3. Filter to messages within `thresholdSeconds` window
4. Group by sender — need >= `thresholdCount` (default 2) distinct senders
5. For each cross-sender message pair: compute Jaccard similarity
6. Count pairs where similarity >= `similarityPct` / 100.0
7. If count >= 2: fire alert with participant list and max similarity

The minimum of 2 similar cross-sender pairs distinguishes genuine echo chamber behavior (agents repeatedly relaying content) from legitimate single-message forwarding. A single forwarded message is normal multi-agent coordination; two or more instances of near-identical cross-sender content indicate a pattern.

LOOP_DETECTED catches self-repetition; ECHO_CHAMBER catches inter-agent repetition. Combinatorial space bounded by the 50-message fetch limit.

### No new store methods

All four conditions use existing query capabilities:

- `CrossTenantMessageStore.scan(MessageQuery)` — message fetching
- `CrossTenantMessageStore.count(MessageQuery)` — response counting
- `CrossTenantCommitmentStore.findOpenByChannel(UUID)` — active obligations
- `MessageQuery` filters: `channelId`, `sender`, `correlationId`, `messageType`, `excludeTypes`, `limit`, `descending`

## ConnectorAlertBridge

Extend `buildBody()` exhaustive switch with 4 new cases. The sealed `AlertContext` interface guarantees compile-time completeness — missing cases fail the build.

Each case formats: `event.summary()` + condition-specific detail lines (channel name, counts, similarity percentages, participant lists, correlation IDs).

## MCP Tools

- `create_watchdog` — new optional `similarity_pct` parameter (Integer, 0–100)
- `list_watchdogs` / `get_watchdog` — include `similarityPct` in `WatchdogSummary`

## Testing

All tests in `runtime/src/test/` using existing pattern: `@QuarkusTest` + `@TestProfile(WatchdogEnabledProfile)` + `@TestTransaction`. Unique channel names per test.

### JaccardSimilarity (plain unit test, no Quarkus)

- Identical strings → 1.0
- Disjoint strings → 0.0
- Partial overlap → correct ratio
- Null/empty → 0.0
- Case insensitivity
- Punctuation stripping: `{"key": "A"}` vs `{"key": "B"}` → similarity well below 70% (structured content regression)

### Per condition (2–3 tests each)

**LOOP_DETECTED:**
- fires when sender repeats content above similarity threshold
- no alert when content is dissimilar
- no alert when below repetition count
- no alert when similar messages are interleaved with dissimilar ones (consecutive algorithm regression)

**OBLIGATION_FAN_OUT:**
- fires when COMMAND has no response within deadline
- no alert when commitment is acknowledged
- no alert when STATUS message exists on correlationId

**CONVERSATION_STALL:**
- fires when active commitments but no terminal resolution
- no alert when recent terminal exists
- no alert when no active commitments
- no alert when all active commitments are younger than threshold (age guard regression)
- fires when one correlation is stalled even though another was recently resolved (per-correlation regression)

**ECHO_CHAMBER:**
- fires when multiple senders relay identical content
- no alert when content is transformed
- no alert when single sender (below min_agents)
- no alert when exactly one cross-sender pair is similar (minimum pair threshold regression)

### ConnectorAlertBridgeTest

Extend existing test to cover 4 new AlertContext subtypes (formatting only).

### Existing tests unchanged

`similarityPct` is nullable; existing Watchdog construction via builder defaults to null.

## Scope Boundaries

### Circular delegation (deferred — #368)

None of the four conditions detect circular HANDOFF chains (A→B→C→A). CONVERSATION_STALL provides partial coverage — a circular chain where no agent resolves within `thresholdSeconds` will be detected because the latest commitment stays OPEN. However, rapid circular delegation (each agent hands off within the threshold window) is not detected. Circular delegation detection requires graph traversal of HANDOFF chains through the commitment store — fundamentally different from the message-stream pattern detection in this spec.

### Engine-side alert consumption (out of scope — #369)

The engine receives watchdog alerts as text through connector delivery (`ConnectorAlertBridge` → `ConnectorService.send()`), not through the sealed `AlertContext` hierarchy directly. Adding new `AlertContext` subtypes does not cause a compile-time break in the engine. Engine-side changes to take condition-specific intervention on LOOP_DETECTED, ECHO_CHAMBER, CONVERSATION_STALL, or OBLIGATION_FAN_OUT are out of scope for this spec.

## Research Basis

- [MAST taxonomy](https://openreview.net/forum?id=wM521FqPvI) — 37% coordination breakdowns
- [Role drift and infinite loops](https://www.augmentcode.com/guides/why-multi-agent-llm-systems-fail-and-how-to-fix-them)
- [Coordination tax (DeepMind)](https://towardsdatascience.com/why-your-multi-agent-system-is-failing-escaping-the-17x-error-trap-of-the-bag-of-agents/)
