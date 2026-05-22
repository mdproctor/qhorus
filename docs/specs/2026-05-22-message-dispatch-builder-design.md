# MessageDispatch Builder API — Design Spec
**Date:** 2026-05-22  
**Issue:** casehubio/qhorus#184  
**Blocking:** casehubio/aml#30 (Layer 4 FinCEN audit trail)

---

## Problem

`MessageService.send()` has three problems that together prevent consumers from producing a unified, FinCEN-grade audit chain:

1. **9-parameter positional signature** — unmaintainable; three positional nulls with no indication of what they are.
2. **`subjectId` defaults to `channelId`** — every `MessageLedgerEntry` records `subjectId = channelId` (e.g. `"entity-resolution"`). A regulator querying `?subjectId=TXN-001` gets nothing. Engine entries use `subjectId = caseId`; qhorus entries use `subjectId = channelId`. The case chain and message chain are split. This is a platform-level gap.
3. **No cross-domain `causedByEntryId`** — callers (AML, engine, clinical) cannot link the first qhorus `MessageLedgerEntry` back to the domain entry that triggered the dispatch. The causal graph breaks at every module boundary.

---

## Solution

Replace `send()` with `dispatch(MessageDispatch)`. `send()` is deleted — no deprecation, no wrapper, gone.

**Breaking change:** `MessageResult` (in `api/message/`) is deleted. It was public API. All consumers migrating from `send()` receive `DispatchResult` instead.

---

## Section 1 — New Types (`api/message/`)

### `MessageDispatch`

Builder-constructed value object. All fields are primitives or value types — no JPA entities.

```java
record MessageDispatch(
    UUID channelId,
    String sender,
    MessageType type,
    String content,
    String correlationId,     // nullable
    Long inReplyTo,           // nullable — Message entity ID of the replied-to message
    List<UUID> artefactRefs,  // nullable
    String target,            // nullable — required for HANDOFF
    UUID subjectId,           // nullable — auto-propagated from correlation root if absent
    UUID causedByEntryId,     // nullable — auto-linked from inReplyTo entry if absent
    ActorType actorType
) {
    static Builder builder() { ... }
}
```

#### Builder validation — runtime, at `build()` invocation

All violations throw `IllegalArgumentException` naming the missing field and message type.

| Type | `inReplyTo` required | `correlationId` required | `target` required | Rationale |
|---|---|---|---|---|
| DONE, DECLINE, FAILURE | ✅ | ✅ | — | Resolves a commitment — both fields needed for `CommitmentService` to locate and close it |
| HANDOFF | ✅ | ✅ | ✅ | Parent commitment → DELEGATED; child OPEN anchored to same `correlationId`; `target` names the delegate — a HANDOFF to nobody is incoherent |
| RESPONSE | ✅ | — | — | Answers a query; no commitment lifecycle |
| STATUS | — | — | — | Unsolicited progress updates are explicitly valid — `inReplyTo` absent by design, not a caller bug |
| COMMAND, QUERY, EVENT | — | — | — | Initiating or notifying messages |

**Migration note:** Migrating `send()` call sites to the builder will surface latent protocol violations — DONE/DECLINE without `correlationId`, HANDOFF without `correlationId` or `target`. Each is a protocol fix, not a workaround to suppress. COMMANDs that expect completion via DONE/DECLINE/FAILURE **must** carry `correlationId` — a `correlationId`-free COMMAND is informational, opens no commitment, and cannot be completed via the builder.

### `DispatchResult`

Returned from every `dispatch()` call regardless of message type — including DONE, DECLINE, and FAILURE.

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
record DispatchResult(
    Long messageId,
    UUID channelId,
    String sender,
    MessageType type,
    String correlationId,
    Long inReplyTo,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<UUID> artefactRefs,
    String target,
    @Nullable UUID ledgerEntryId,    // null when ledger writes suppressed
    @Nullable UUID subjectId,        // resolved value actually written — may differ from dispatch.subjectId()
    @Nullable UUID causedByEntryId   // resolved value actually written — may differ from dispatch.causedByEntryId()
) {
    public DispatchResult {
        artefactRefs = artefactRefs == null ? List.of() : List.copyOf(artefactRefs);
    }
}
```

**`ledgerEntryId` null contract:** null means either no causal link was resolved **or** ledger writes are suppressed. Callers cannot distinguish the two from `DispatchResult` alone; if confirmation is compliance-critical, query the ledger directly. Null-safe pattern: `Optional.ofNullable(result.ledgerEntryId())`.

**`subjectId` and `causedByEntryId` when ledger disabled:** both null — callers cannot confirm resolved values when ledger writes are suppressed.

**JSON shape:** `@JsonInclude(NON_NULL)` means the JSON shape varies by message type — `inReplyTo` appears only for reply types, `causedByEntryId` appears only when a causal link was resolved, `ledgerEntryId` is absent when ledger is disabled. MCP callers must not assume a fixed field set. `artefactRefs` is omitted when empty (`NON_EMPTY`); an empty list and null are treated identically in the response.

**`artefactRefs` round-trip contract:** stored as comma-separated UUID strings in the TEXT column; `DispatchResult` exposes `List<UUID>` via active deserialization. The format is consistent with existing `Message` entity handling.

---

## Section 2 — Service Layer and Propagation

### `MessageService.dispatch(MessageDispatch)` — step sequence

`dispatch()` is annotated `@Transactional` (outer transaction boundary).

1. `MessageTypePolicy` check — server-side guard on `allowedTypes` (existing)
2. Commitment auto-open if `type == COMMAND && correlationId != null` (existing behaviour preserved)
3. Auto-claim `artefactRefs` if sender is a registered instance (existing behaviour preserved)
4. `messageStore.put(...)` — produces the `Message` entity; committed in the outer `@Transactional`
5. Extract primitives before the `REQUIRES_NEW` boundary: `Long messageId = message.id`, `@Nullable UUID commitmentId = message.commitmentId`
6. `ledgerWriteService.record(dispatch, messageId, commitmentId)` — `REQUIRES_NEW`: suspends outer tx, commits ledger entry independently, resumes
7. `MessageObserverDispatcher.dispatch(...)` — fires observers, nulls EVENT content (existing behaviour)
8. Construct and return `DispatchResult` from entity fields and `LedgerWriteOutcome`

**Transaction semantics:** Steps 1–5 and 7–8 run in the outer `@Transactional`. Step 6 runs in `REQUIRES_NEW`. A ledger write failure causes `REQUIRES_NEW` to roll back, the exception propagates to `dispatch()`, and the outer transaction rolls back — the `Message` row is not committed. A `Message` row can never be committed without a corresponding `MessageLedgerEntry`.

**Orphan ledger entry semantics:** If the `REQUIRES_NEW` ledger write commits but the outer transaction rolls back after step 6 (e.g. observer throws), a `MessageLedgerEntry` exists whose `messageId` points to a sequence value never committed as a `Message` row. This is a valid audit state — it records a dispatch attempt where message persistence failed. It is not data corruption. Consumers querying `MessageLedgerEntry` via JOIN to the `Message` table **must** use `LEFT JOIN`, not `INNER JOIN`. An `INNER JOIN` silently drops orphan entries — precisely the records that document a failed dispatch attempt.

### `LedgerWriteService.record()` — new signature

```java
LedgerWriteOutcome record(MessageDispatch dispatch,
                          Long messageId,
                          @Nullable UUID commitmentId)
```

No JPA entities cross the `REQUIRES_NEW` boundary. `MessageDispatch` is a plain record; `messageId` and `commitmentId` are primitives extracted in step 5. This eliminates the `LazyInitializationException` risk that would arise if `Channel` or `Message` entities (attached to the outer persistence context) were passed into the new `REQUIRES_NEW` context.

`LedgerWriteOutcome` is an internal value object carrying the three resolved fields back to `MessageService`:

```java
record LedgerWriteOutcome(
    UUID entryId,
    @Nullable UUID subjectId,        // resolved value written to ledger
    @Nullable UUID causedByEntryId   // resolved value written to ledger
) {}
```

**Visibility:** `LedgerWriteOutcome` must be accessible from both `runtime.ledger` (where it is produced) and `runtime.message` (where `MessageService` consumes it). Package-private is insufficient across these two packages. Place it in `io.casehub.qhorus.runtime.ledger` with non-private visibility, or return `@Nullable MessageLedgerEntry` directly from `record()` and have `MessageService` extract the three fields from the entity — either approach is valid. The implementation chooses.

`subjectId` and `causedByEntryId` flow from `LedgerWriteService` (where resolution happens) through this carrier to `DispatchResult`. Without them, `MessageService` would need to re-run the same repository queries redundantly.

**When ledger is disabled:** `record()` returns `LedgerWriteOutcome(null, null, null)` — never `null` itself. NPE risk at the call site is eliminated.

### Propagation rules — applied inside `record()` to every write

| Field | Priority 1 | Priority 2 | Priority 3 / default |
|---|---|---|---|
| `subjectId` | `dispatch.subjectId()` non-null | Earliest entry in `correlationId` thread with non-null `subjectId`, ordered by `sequenceNumber ASC` | `dispatch.channelId()` |
| `causedByEntryId` | `dispatch.causedByEntryId()` non-null | Ledger entry whose `messageId = dispatch.inReplyTo()` | null |

**`subjectId` rationale:** The domain aggregate (TXN-001, caseId) doesn't change across the lifetime of a conversation. Using the correlation root — not `inReplyTo` — ensures correct propagation for multi-hop dialogues where RESPONSE replies to QUERY, not directly to the original COMMAND. The `IS NOT NULL` guard in the query is a safety net for pre-migration entries only (harmless but not required for new entries).

**`causedByEntryId` rationale:** `inReplyTo` already encodes the immediate predecessor for all reply types. DONE → COMMAND, RESPONSE → QUERY, HANDOFF → COMMAND. The lookup is in the same `REQUIRES_NEW` transaction; the predecessor entry was committed in a prior `REQUIRES_NEW` and is visible immediately (no dirty read).

**HANDOFF causal chain:** In a HANDOFF flow, the delegate's DONE carries `inReplyTo = HANDOFF messageId` and `correlationId = C1`. The ledger chain resolves as COMMAND → HANDOFF → DONE, with `subjectId` propagating via Priority 2 (earliest entry in C1 = COMMAND). The subject is preserved throughout the full delegation chain.

**Independent `correlationId` threads:** When callers use independent `correlationId` per dispatch (e.g. AML's per-specialist threads), Priority 2 operates independently within each thread. DONE in thread C1 inherits `subjectId` from C1's COMMAND. Three threads, same domain subject, correct propagation in all three.

### New repository queries

Added to `MessageLedgerEntryRepository` (blocking) and `ReactiveMessageLedgerEntryRepository` (reactive, returns `Uni<Optional<>>`):

```java
// Priority 2 for subjectId — earliest entry in thread with a non-null subject
Optional<MessageLedgerEntry> findEarliestWithSubjectByCorrelationId(String correlationId);
// ORDER BY sequenceNumber ASC — monotonic MMR sequence, clock-skew-safe. Not occurred_at.

// Priority 2 for causedByEntryId — find ledger entry by Message entity ID
Optional<MessageLedgerEntry> findByMessageId(Long messageId);
```

Both return empty `Optional` on miss — propagation falls through to the next priority silently.

### `QhorusMcpTools.sendMessage()` changes

Two new optional MCP parameters: `subject_id` (String, nullable, parsed to UUID) and `caused_by_entry_id` (String, nullable, parsed to UUID). Malformed UUID strings throw `IllegalArgumentException` wrapped by `@WrapBusinessError`.

Internally: builds `MessageDispatch` and calls `dispatch()`. Returns `DispatchResult` directly — the MCP framework serializes it to JSON with `@JsonInclude` applied.

**MCP `correlationId` note:** Reply types (DONE, DECLINE, FAILURE, HANDOFF) should always include `correlationId` in MCP calls for correct commitment resolution. A DONE with null `correlationId` silently fails to close the commitment — the obligation stays OPEN. This is pre-existing behaviour, now documented here.

`MessageResult` is deleted. **Breaking change.** `ReactiveQhorusMcpTools.sendMessage()` mirrors identically.

### Reactive parity

`ReactiveMessageService.dispatch(MessageDispatch)` returns `Uni<DispatchResult>`. `ReactiveLedgerWriteService.record(...)` returns `Uni<LedgerWriteOutcome>`. All reactive repository queries return `Uni<Optional<...>>`. Everything mirrors the blocking stack.

---

## Section 3 — Testing Strategy

Four layers with distinct scopes. Do not mix concerns across layers.

### Layer 1 — Builder validation (pure unit, no Quarkus)

One test class covering all invariant groups. Positive cases: valid combinations pass `build()`. Negative cases: each missing required field throws `IllegalArgumentException` with a message that names the field and the message type.

Invariant groups: `inReplyTo` required set, `correlationId` required set, `target` required for HANDOFF only.

### Layer 2 — Propagation resolution (CDI-free unit)

`LedgerWriteService` tested with `StubMessageLedgerEntryRepository` — a package-private test utility class in `runtime/src/test/.../ledger/`, shared across all three sub-suites (not inlined per test). If `InMemoryMessageLedgerEntryRepository` exists in `casehub-qhorus-testing`, prefer it; otherwise `StubMessageLedgerEntryRepository` is the implementation.

Three sub-suites:
- `subjectId` priority ordering: explicit > correlation root lookup > channelId fallback
- `causedByEntryId` priority ordering: explicit > `inReplyTo` lookup > null
- Ledger disabled: `record()` returns `LedgerWriteOutcome(null, null, null)`; `DispatchResult` fields null

### Layer 3 — `dispatch()` end-to-end (`@QuarkusTest`, InMemory stores)

Tests **field correctness** — that `DispatchResult` and `MessageLedgerEntry` carry the right resolved values. Does **not** test `REQUIRES_NEW` atomicity — `REQUIRES_NEW` is a no-op with InMemory stores and transaction isolation assertions would pass vacuously. Atomicity is Layer 4's concern.

Key test cases:
- `DispatchResult` fields match what was written; `ledgerEntryId` is non-null
- Resolved `subjectId` in `DispatchResult` reflects correlation root propagation, not caller-supplied null
- Resolved `causedByEntryId` in `DispatchResult` reflects `inReplyTo` auto-link
- JSON shape of MCP response respects `@JsonInclude(NON_NULL)` and `@JsonInclude(NON_EMPTY)` — null fields absent, empty `artefactRefs` absent
- Builder errors surface as `ToolCallException` through `QhorusMcpTools.sendMessage()` (`@WrapBusinessError` boundary)
- HANDOFF commitment chain: COMMAND → HANDOFF → DONE; assert parent commitment `DELEGATED`, child commitment `FULFILLED`, `subjectId` propagated across all three `MessageLedgerEntry` records via Priority 2

### Layer 4 — Repository queries and atomicity (`@QuarkusTest`, H2)

Tests **`REQUIRES_NEW` atomicity** and **query correctness**. H2 provides real JPA transaction isolation. 

`MessageLedgerEntryTestFactory` — package-private test utility in `runtime/src/test/.../ledger/`. Provides sensible defaults for all required base `LedgerEntry` fields (`id`, `subjectId`, `sequenceNumber`, `entryType`, `actorId`, `actorType`, `occurredAt`) with a fluent override API. Shared across all Layer 4 tests — without this, the 7-field boilerplate will be independently rediscovered across test classes.

Key test cases:
- `findEarliestWithSubjectByCorrelationId`: multiple entries in thread — verify `sequenceNumber ASC` ordering returns correct entry; empty-Optional on no match
- `findByMessageId`: lookup by `Message.id` returns correct entry; empty-Optional on no match
- `REQUIRES_NEW` atomicity: ledger write failure → outer transaction rolls back → no `Message` row committed

### Migration

Mechanical substitution: `messageService.send(a, b, c, ...)` → `messageService.dispatch(MessageDispatch.builder()...build())`. Builder fields map 1:1 to positional args.

**Latent violations:** Migration will surface call sites using DONE/DECLINE without `correlationId`, or HANDOFF without `correlationId` or `target`. Each is a protocol fix — correct the test to use the right protocol, not suppress builder validation. The builder catching these is the point.

`ToolOverloadDiscoverabilityTest` extended: assert no public non-`@Tool` overloads of `dispatch()`.

### Reactive parity

`Reactive*` equivalents of Layer 3 tests marked `@Disabled` (Docker required for reactive DB). Follows existing convention.

---

## Open items for claudony team

- **LLM metadata fields** (`toolName`, `durationMs`, `tokenCount`, `sourceEntity`): currently extracted from `message.content` JSON inside `record()`. The new signature uses `dispatch.content()` — same value, different path. Confirm no behaviour change for LLM callers that populate EVENT content with telemetry JSON.

---

## What does not change

- `LedgerEntry` / `MessageLedgerEntry` schema — no migration; `subjectId` and `causedByEntryId` already exist on `LedgerEntry`
- Commitment lifecycle (`CommitmentService`) — unchanged
- `MessageObserver` / `MessageReceivedEvent` — unchanged; subject and causation are audit concerns, not dispatch concerns
- `MessageTypePolicy` — unchanged
- Rate limiting (`RateLimiter`) — unchanged
