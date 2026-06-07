# Spec: Ledger dtype scope re-architecture (#253) + UUID-first channel service (#252)

**Date:** 2026-06-07  
**Issues:** qhorus#253 (bug), qhorus#252 (follow-up from #237)  
**Branch:** issue-253-ledger-seq-dtype-fix  
**Schema change:** None. No migration needed. Next domain migration: V18.  
**Independence:** #253 and #252 are fully independent — they touch disjoint layers and can be implemented in any order.

---

## Problem statement

### #253 — Sequence constraint violation from dtype-scoped queries

`MessageLedgerEntryRepository.findLatestBySubjectId()` queries `FROM MessageLedgerEntry`,
seeing only rows with dtype `QHORUS_MESSAGE`. When a consuming application writes any other
`LedgerEntry` subclass (e.g. `AmlCaseOpenedLedgerEntry`, dtype `AML_CASE_OPENED`) to the
same `subjectId` before dispatching a qhorus message, the query returns `null` and
`LedgerWriteService` assigns `sequenceNumber=1` — colliding with the domain entry already
holding seq=1 for that subject. `IDX_LEDGER_ENTRY_SUBJECT_SEQ` fires as a constraint
violation.

The same dtype-scoping bug affects other `LedgerEntryRepository` interface methods in the
same class. The blocking path has inconsistent correctness across interface methods —
some already use `FROM LedgerEntry` correctly, others use `FROM MessageLedgerEntry` wrongly.
`ReactiveMessageLedgerEntryRepository` is uniformly broken: every interface method goes
through `MessageReactivePanacheRepo` (typed to `MessageLedgerEntry`), making all of them
dtype-scoped bugs regardless of whether the blocking equivalent is already correct.

### Root cause: conflated dtype contracts

`MessageLedgerEntryRepository` has two incompatible contracts in one class:
1. Implements `LedgerEntryRepository` → requires `FROM LedgerEntry` (cross-dtype)
2. Serves as the qhorus-specific query layer → requires `FROM MessageLedgerEntry`

Every method added to this class defaults to the qhorus pattern. The bugs are not one-off
mistakes — the class design guarantees future regressions. There is a correct cross-dtype
implementation already in `casehub-ledger` (`JpaLedgerEntryRepository`, annotated
`@ApplicationScoped @Alternative` with no `@Priority` — verified against source). qhorus
overrides it via `@Priority(10)` with a dtype-scoped replacement. See §
"Architectural context" below for why we don't use the library class directly today.

An unsafe cast `(MessageLedgerEntry) prior` in `LedgerWriteService` is also present, which
would throw a `ClassCastException` if a domain entry ever becomes a `causedByEntryId`.

### #252 — Double lookup after resolveChannelAsync

After `resolveChannelAsync` / `resolveChannel` resolves a `Channel` entity, six methods in
both `ReactiveChannelService` and `ChannelService` accept `String name` and perform a second
`findByName()` lookup inside the transaction. `setTypeConstraints` was corrected to
UUID-first in #237. The six remaining methods were not.

---

## Architectural context for #253

### Why not use `JpaLedgerEntryRepository` from casehub-ledger directly

`casehub-ledger` ships `JpaLedgerEntryRepository` (`@ApplicationScoped @Alternative`, no
`@Priority` — verified). `@Alternative` without `@Priority` means CDI does NOT globally
activate it; it is dormant unless explicitly selected via `quarkus.arc.selected-alternatives`
or `beans.xml`. Removing `@Priority(10)` from `MessageLedgerEntryRepository` leaves
`LedgerEntryJpaRepository` (our new class) as the only active `LedgerEntryRepository` bean —
no CDI ambiguity.

`JpaLedgerEntryRepository.save()` calls `LedgerSequenceAllocator.nextSequenceNumber(subjectId)`
to assign the sequence atomically. `LedgerWriteService.record()` also computes `sequenceNumber`
by calling `findLatestBySubjectId()` first and setting it on the entry before calling `save()`.
These two mechanisms would conflict — the library's allocator would overwrite the manually-set
value.

**Correct long-term path:** remove the manual sequence computation from `LedgerWriteService`
and delegate entirely to `LedgerSequenceAllocator` via the library's `save()`. This requires a
tracked issue. See § "Deferred issues to file".

**Fix for now:** create `LedgerEntryJpaRepository` in qhorus — a new class implementing
`LedgerEntryRepository` with `FROM LedgerEntry` JPQL for all query methods, and a plain
`em.persist(entry); return entry;` `save()` that does not interfere with the existing
sequence computation in `LedgerWriteService`.

---

## Design: #253

### Blocking path JPQL audit

**Methods that need JPQL changed** (currently scope to `FROM MessageLedgerEntry` wrongly):

| Method | Current (wrong) | Fixed |
|---|---|---|
| `findLatestBySubjectId` | `FROM MessageLedgerEntry … ORDER BY seqNum DESC` | `FROM LedgerEntry … ORDER BY seqNum DESC` |
| `findBySubjectId` | `FROM MessageLedgerEntry … ORDER BY seqNum ASC` | `FROM LedgerEntry … ORDER BY seqNum ASC` |
| `findEntryById` | `em.find(MessageLedgerEntry.class, id)` | `em.find(LedgerEntry.class, id)` |
| `findAllEvents` | `FROM MessageLedgerEntry WHERE entryType = :type` | `FROM LedgerEntry WHERE entryType = :type` |
| `findEventsByActorId` | `FROM MessageLedgerEntry WHERE entryType = :type AND actorId = :actorId` | `FROM LedgerEntry WHERE entryType = :type AND actorId = :actorId` |

**Note:** `LedgerEntry` is **abstract**. `em.find(LedgerEntry.class, id)` is correct — Hibernate
resolves the concrete subtype via JOINED inheritance. Do not change to a non-abstract subtype.

**Methods already correct** (use `FROM LedgerEntry`; copy as-is to `LedgerEntryJpaRepository`):

`listAll`, `findByActorId`, `findByActorRole`, `findBySubjectIdAndTimeRange`, `findByTimeRange`, `findCausedBy`

**Method that stays qhorus-scoped** (`FROM MessageLedgerEntry` is intentionally correct):

`findEarliestWithSubjectByCorrelationId` — queries on `correlationId`, which is a
`MessageLedgerEntry`-specific field (not on `LedgerEntry`). Switching to `FROM LedgerEntry`
would be a JPQL compilation error. This method remains in `MessageLedgerEntryRepository`.
Its return type stays `Optional<MessageLedgerEntry>`.

### Reactive path JPQL audit

Every interface method in `ReactiveMessageLedgerEntryRepository` uses `repo.*` (Panache,
typed to `MessageLedgerEntry`), making ALL of them dtype-scoped bugs. This is worse than
the blocking path where some methods were already correct. The full list of broken reactive
methods to fix:

`findLatestBySubjectId`, `findBySubjectId`, `findEntryById`, `listAll`, `findAllEvents`,
`findEventsByActorId`, `findByActorId`, `findByActorRole`, `findBySubjectIdAndTimeRange`,
`findByTimeRange`, `findCausedBy`

---

### New class: `LedgerEntryJpaRepository`

```java
@ApplicationScoped  // plain — no @Priority, no @Alternative
public class LedgerEntryJpaRepository implements LedgerEntryRepository {

    @Inject
    @PersistenceUnit("qhorus")
    EntityManager em;

    @Override
    public LedgerEntry save(LedgerEntry entry) {
        em.persist(entry);   // no sequence allocation — LedgerWriteService owns that
        return entry;
    }

    @Override
    public Optional<LedgerEntry> findLatestBySubjectId(UUID subjectId) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.subjectId = :sid ORDER BY e.sequenceNumber DESC",
                LedgerEntry.class)
                .setParameter("sid", subjectId)
                .setMaxResults(1)
                .getResultStream().findFirst();
    }

    // ... all other LedgerEntryRepository interface methods with FROM LedgerEntry JPQL
    // save(), saveAttestation(), findAttestations*() all use EntityManager directly
}
```

### Refactored: `MessageLedgerEntryRepository`

- Remove `implements LedgerEntryRepository`
- Remove `@Priority(10)` — annotation is dead once the interface is removed
- Remove all `@Override` interface methods
- `findEarliestWithSubjectByCorrelationId` stays (qhorus-scoped, `FROM MessageLedgerEntry`)
- Retained methods (qhorus-specific only): `findByChannelId`, `listEntries` (×2), `findAllByCorrelationId`, `findAncestorChain`, `findStalledCommands`, `countByOutcome`, `findByActorIdInChannel`, `findEventsSince`, `findLatestByCorrelationId`, `findByMessageId`, `findByCorrelationIdAcrossChannels`, `findEarliestWithSubjectByCorrelationId`

### Updated: `LedgerWriteService`

Two injections replace the single `repository` injection:

```java
@Inject LedgerEntryJpaRepository ledger;       // save, findLatestBySubjectId,
                                                // findEntryById, saveAttestation
@Inject MessageLedgerEntryRepository messageRepo; // findByMessageId,
                                                  // findEarliestWithSubjectByCorrelationId
```

Cast fix — replace unsafe cast with instanceof pattern:

```java
// Before (throws ClassCastException if domain entry is causedByEntryId):
repository.findEntryById(resolvedCausedByEntryId).ifPresent(prior -> {
    final MessageLedgerEntry priorEntry = (MessageLedgerEntry) prior;
    if ("COMMAND".equals(priorEntry.messageType) || "HANDOFF".equals(priorEntry.messageType)) {
        writeAttestation(..., priorEntry, ...);
    }
});

// After:
ledger.findEntryById(resolvedCausedByEntryId).ifPresent(prior -> {
    if (prior instanceof MessageLedgerEntry priorMsg &&
            ("COMMAND".equals(priorMsg.messageType) || "HANDOFF".equals(priorMsg.messageType))) {
        writeAttestation(..., priorMsg, ...);
    }
});
```

### New class: `ReactiveLedgerEntryJpaRepository`

```java
@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
public class ReactiveLedgerEntryJpaRepository implements ReactiveLedgerEntryRepository {

    @Inject
    MessageReactivePanacheRepo repo; // session access only — never for typed queries

    @Override
    public Uni<LedgerEntry> save(LedgerEntry entry) {
        return repo.getSession()
                .flatMap(session -> session.persist(entry).replaceWith(entry));
    }

    @Override
    public Uni<Optional<LedgerEntry>> findLatestBySubjectId(UUID subjectId) {
        return repo.getSession()
                .flatMap(session -> session
                        .createQuery(
                                "SELECT e FROM LedgerEntry e WHERE e.subjectId = :sid ORDER BY e.sequenceNumber DESC",
                                LedgerEntry.class)
                        .setParameter("sid", subjectId)
                        .setMaxResults(1)
                        .getSingleResultOrNull()
                        .map(Optional::ofNullable));
    }
    // ... all other interface methods follow the same getSession() + raw JPQL pattern
}
```

**`@IfBuildProperty` gate is required** — without it, `ReactiveLedgerEntryJpaRepository`
is active in non-reactive builds where `MessageReactivePanacheRepo` is absent, causing CDI
injection failure. In non-reactive builds, `StubReactiveLedgerEntryRepository` (`@DefaultBean`)
satisfies the `ReactiveLedgerEntryRepository` injection point. No change to that stub.

### Refactored: `ReactiveMessageLedgerEntryRepository`

- Remove `implements ReactiveLedgerEntryRepository`
- **Keep `@IfBuildProperty` gate — mandatory.** This class injects `MessageReactivePanacheRepo`,
  which is itself gated by `@IfBuildProperty`. In non-reactive builds `MessageReactivePanacheRepo`
  does not exist; removing the gate causes CDI build-time injection failure, not a runtime error.
  The gate stays because of the injection dependency, regardless of interface satisfaction.
- Retain all qhorus-specific methods: `findByChannelId`, `findLatestByCorrelationId`, `findByMessageId`, `findEarliestWithSubjectByCorrelationId`
- All interface method overrides removed
- **Remove `@Inject ActorIdentityProvider actorIdentityProvider`** — used only by `saveAttestation()` (calls `tokenise`) and `findAttestationsByAttestorIdAndCapabilityTag()` (calls `tokeniseForQuery`), both of which are interface methods moving to `ReactiveLedgerEntryJpaRepository`. After removal the field is unused. Add it to `ReactiveLedgerEntryJpaRepository` instead.

### Updated: `ReactiveLedgerWriteService`

Two injections replace the single `reactiveRepo` injection:

```java
@Inject ReactiveLedgerEntryJpaRepository ledger;      // save, findLatestBySubjectId,
                                                       // findEntryById, saveAttestation
@Inject ReactiveMessageLedgerEntryRepository messageRepo; // findByMessageId,
                                                          // findEarliestWithSubjectByCorrelationId
```

**Complexity warning:** `ReactiveLedgerWriteService.record()` is a deeply-nested `Uni` chain
with five sequential async steps. This is the most complex implementation step in this spec —
do not treat it as a mechanical copy of the blocking pattern.

Specific changes required beyond renaming the injection fields:

1. `resolveSubjectId(dispatch)` currently calls `reactiveRepo.findEarliestWithSubjectByCorrelationId()` → change to `messageRepo.findEarliestWithSubjectByCorrelationId()`
2. `resolveCausedByEntryId(dispatch)` currently calls `reactiveRepo.findByMessageId()` → change to `messageRepo.findByMessageId()`
3. Main chain `reactiveRepo.findLatestBySubjectId()` → `ledger.findLatestBySubjectId()`
4. Main chain line: `latestOpt.map(e -> ((MessageLedgerEntry) e).sequenceNumber + 1)` — the cast is unnecessary after `findLatestBySubjectId` returns `Optional<LedgerEntry>` (which has `sequenceNumber`). Change to `latestOpt.map(e -> e.sequenceNumber + 1)`.
5. Main chain `reactiveRepo.save(entry)` → `ledger.save(entry)`
6. `writeAttestation()` calls `reactiveRepo.findEntryById()` → `ledger.findEntryById()` and `reactiveRepo.saveAttestation()` → `ledger.saveAttestation()`
7. `writeAttestation()` contains `final MessageLedgerEntry prior = (MessageLedgerEntry) priorOpt.get()` — same unsafe cast as blocking path. Apply the same instanceof fix: `if (priorOpt.get() instanceof MessageLedgerEntry prior && ...)`

### Test stubs — shared-state solution

Current `StubMessageLedgerEntryRepository` holds `entries: List<MessageLedgerEntry>` backing
both the cross-dtype lookups and `findByMessageId`. After the split, `save()` writes to
`StubLedgerEntryJpaRepository` while `findByMessageId` reads from `StubMessageLedgerEntryRepository`.
Without sharing, `findByMessageId` searches an empty list.

**Solution:** constructor injection with a shared `List<LedgerEntry>` reference:

```java
// In the test:
List<LedgerEntry> shared = new ArrayList<>();
var ledger = new StubLedgerEntryJpaRepository(shared);
var messageRepo = new StubMessageLedgerEntryRepository(shared);
service.ledger = ledger;
service.messageRepo = messageRepo;
```

`StubLedgerEntryJpaRepository` (new): **implements `LedgerEntryRepository` directly** —
do NOT extend `LedgerEntryJpaRepository`, which has `@Inject EntityManager` and no no-arg
constructor usable without CDI. Direct implementation is cleaner and avoids null field pitfalls.
Accepts shared list. The three methods called by `LedgerWriteService.record()` in the unit
test path must have working implementations:

```java
@Override
public LedgerEntry save(LedgerEntry entry) {
    if (entry.id == null) entry.id = UUID.randomUUID(); // simulate @PrePersist
    entries.add(entry);
    return entry;
}

@Override
public Optional<LedgerEntry> findLatestBySubjectId(UUID subjectId) {
    return entries.stream()
            .filter(e -> subjectId.equals(e.subjectId))
            .max(Comparator.comparingInt(e -> e.sequenceNumber));
}

@Override
public Optional<LedgerEntry> findEntryById(UUID id) {
    return entries.stream()
            .filter(e -> id.equals(e.id))
            .findFirst();
}
```

All remaining interface methods return no-op values — they are not exercised by
`LedgerWriteService.record()` in CDI-free unit tests: collections return `List.of()`,
`Optional`-returning methods return `Optional.empty()`, `saveAttestation` returns its argument
(same pattern as the existing `StubMessageLedgerEntryRepository.saveAttestation()`).

`StubMessageLedgerEntryRepository` (slimmed): accepts shared list; overrides only
`findByMessageId` and `findEarliestWithSubjectByCorrelationId`. Both methods filter the
shared `List<LedgerEntry>` by instanceof before casting:

```java
@Override
public Optional<MessageLedgerEntry> findByMessageId(Long messageId) {
    return entries.stream()
            .filter(e -> e instanceof MessageLedgerEntry m && messageId.equals(m.messageId))
            .map(e -> (MessageLedgerEntry) e)
            .findFirst();
}

@Override
public Optional<MessageLedgerEntry> findEarliestWithSubjectByCorrelationId(String corrId) {
    return entries.stream()
            .filter(e -> e instanceof MessageLedgerEntry m
                    && corrId.equals(m.correlationId) && m.subjectId != null)
            .map(e -> (MessageLedgerEntry) e)
            .min(Comparator.comparingInt(e -> e.sequenceNumber));
}
```

### Regression test: `PlainLedgerEntry` origin

`PlainLedgerEntry` (`io.casehub.ledger.runtime.model.PlainLedgerEntry`) — verified to exist
in both the casehub-ledger source and the jar on the test classpath. It is `@Entity
@Table(name = "plain_ledger_entry") @DiscriminatorValue("PLAIN")` — a concrete `LedgerEntry`
subclass with no domain-specific fields. Its table is created automatically by Hibernate
`drop-and-create`. No additional setup needed.

### Test scenarios required

**`CrossDtypeSequenceTest` (`@QuarkusTest`)** — integration test, reproduces the constraint violation:

```
1. QuarkusTransaction.requiringNew().run(() -> {
       PlainLedgerEntry plain = new PlainLedgerEntry();
       plain.subjectId = X;
       plain.sequenceNumber = 1;
       plain.entryType = LedgerEntryType.EVENT;   // NOT NULL in schema
       plain.occurredAt = Instant.now();
       plain.actorType = ActorType.SYSTEM;
       ledger.save(plain);   // LedgerEntryJpaRepository is qhorus-PU scoped — use it,
                             // not @Inject EntityManager (unqualified = wrong datasource)
   })
2. Create channel + instance (unique names, unique subjectId=X)
3. messageService.dispatch(COMMAND, subjectId=X, ...)
   → LedgerWriteService.record() (REQUIRES_NEW) calls ledger.findLatestBySubjectId(X)
   → after fix: sees PlainLedgerEntry (seq=1) → assigns seq=2
   → before fix: sees nothing → assigns seq=1 → constraint violation
4. Assert: ledger.findBySubjectId(X) returns 2 entries, sequenceNumbers=[1, 2]
```

**`LedgerEntryJpaRepositoryTest` (`@QuarkusTest`)** — repository-level correctness:

`findBySubjectId_returns_plain_dtype_entries` — use two `PlainLedgerEntry` instances
(not `MessageLedgerEntry`): bare `MessageLedgerEntry` construction fails `NOT NULL` constraints
on `messageType`, `channelId`, `entryType`, etc. Two `PlainLedgerEntry` instances isolate the
repository predicate with no service dependency. The name reflects what the test actually
covers: `FROM LedgerEntry` returns non-QHORUS_MESSAGE entries (proving dtype-scope is removed).
Mixed-dtype validation would require `MessageLedgerEntry` construction via the full service
stack and is covered by `CrossDtypeSequenceTest`.
```
persist PlainLedgerEntry(subjectId=Y, seq=1, entryType=EVENT)
persist PlainLedgerEntry(subjectId=Y, seq=2, entryType=EVENT)
→ findBySubjectId(Y) returns both entries (size=2)
```

`findEntryById_finds_non_message_entry`:
```
persist PlainLedgerEntry(subjectId=Z, seq=1, entryType=EVENT) → id assigned
→ findEntryById(plainEntry.id) returns the PlainLedgerEntry (not empty)
```

**`LedgerWriteServiceTest` (CDI-free unit test using stubs)** — instanceof cast fix:

```
findEntryById_domain_causedByEntryId_skips_attestation:
  Inject shared-list stubs into LedgerWriteService
  Save a PlainLedgerEntry to the shared list (stands in for a committed domain causedByEntry)
  Dispatch a DONE message with causedByEntryId = plainEntry.id
  → instanceof check: PlainLedgerEntry is NOT a MessageLedgerEntry → writeAttestation skipped
  → no ClassCastException; no LedgerAttestation saved
  → assert no attestation in stub (saveAttestation not called)
```

---

## Design: #252

Replace name-based methods in both `ReactiveChannelService` and `ChannelService`.
No wrappers, no name-based overloads — remove the old signatures, update all callers.

**Confirmed:** ChannelGateway, A2AChannelBackend, and connector backends only call
`listAll`, `findByName`, `create`, and `findByConnectorKey` — none of the six methods
being changed.

**`ChannelServiceTest` note:** only `delete` has test coverage (4 call sites). The other
five methods (`setRateLimits`, `setAllowedWriters`, `setAdminInstances`, `pause`, `resume`)
have no direct `ChannelServiceTest` coverage — they are exercised only through MCP tool
integration tests. No additional unit tests are being added here; this is a pre-existing
coverage gap, not a regression.

### Methods replaced (name → UUID) in both services

`setRateLimits`, `setAllowedWriters`, `setAdminInstances`, `pause`, `resume`, `delete`

### Signatures and patterns

**`ReactiveChannelService`** — pattern from `setTypeConstraints`:

```java
public Uni<Channel> pause(UUID channelId) {
    return Panache.withTransaction("qhorus", () -> channelStore.find(channelId)
            .map(opt -> opt.orElseThrow(
                    () -> new IllegalArgumentException("Channel not found: " + channelId)))
            .map(ch -> { ch.paused = true; return ch; }));
}

public Uni<Long> delete(UUID channelId, boolean force) {
    return Panache.withTransaction("qhorus", () -> channelStore.find(channelId)
            .map(opt -> opt.orElseThrow(
                    () -> new IllegalArgumentException("Channel not found: " + channelId)))
            .map(ch -> {
                int count = messageStore.countByChannel(ch.id);
                if (count > 0 && !force) throw new IllegalStateException("...");
                if (count > 0) messageStore.deleteAll(ch.id);
                channelStore.delete(ch.id);
                return (long) count;
            }));
}
```

**`ChannelService`** — pattern from `setTypeConstraints`:

```java
@Transactional
public Channel pause(UUID channelId) {
    Channel ch = channelStore.find(channelId)
            .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
    ch.paused = true;
    return ch;
}

@Transactional
public long delete(UUID channelId, boolean force) {
    Channel ch = channelStore.find(channelId)
            .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
    int count = messageStore.countByChannel(ch.id);
    if (count > 0 && !force) throw new IllegalStateException("...");
    if (count > 0) messageStore.deleteAll(ch.id);
    channelStore.delete(ch.id);
    return count;
}
```

**Note on `commitmentStore.deleteAll`:** the service `delete` patterns above intentionally
omit `commitmentStore.deleteAll(ch.id)`. That step is performed at the MCP tool layer
(`QhorusMcpTools` / `ReactiveQhorusMcpTools`) before calling the service, because
`fk_commitment_channel` has no CASCADE. The service layer handles only `messageStore`
cleanup. This is unchanged from the existing behaviour — the UUID-first refactor preserves it.

### Callers updated

| File | Sites | Change |
|---|---|---|
| `ReactiveQhorusMcpTools` | 6 | `ch.name` / `resolved.name` → `ch.id` |
| `QhorusMcpTools` | 6 | `ch.name` / `resolved.name` → `ch.id` |
| `ChannelServiceTest` | 4 | `delete(name, ...)` → `delete(ch.id, ...)` after resolving via `findByName` |

### Protocol update (PP-20260606-f899bc)

Current text: *"resolve at @Tool boundary; private blockingXxx helpers receive resolved String name only"*

Replacement text: *"resolve at @Tool boundary via resolveChannel/resolveChannelAsync; all six UUID-first service methods (setRateLimits, setAllowedWriters, setAdminInstances, pause, resume, delete) receive ch.id (UUID); private helpers that receive resolved name do so only for read-only lookups where no UUID-first service method exists"*

---

## Deferred issues to file

File these issues before starting implementation on this branch:

1. **casehub-ledger: provide `DefaultLedgerEntryJpaRepository` for consumers** — `JpaLedgerEntryRepository` is `@Alternative`; consumers shouldn't need to write their own. A `@DefaultBean` cross-dtype implementation would eliminate per-consumer override classes.

2. **Move sequence assignment from `LedgerWriteService` to `LedgerSequenceAllocator`** — `LedgerWriteService` manually computes `sequenceNumber` via `findLatestBySubjectId`. `JpaLedgerEntryRepository.save()` already uses `LedgerSequenceAllocator` correctly. Migrating removes the manual computation and allows using the library `save()` directly.

---

## Files changed

### #253

| File | Action |
|---|---|
| `runtime/.../ledger/LedgerEntryJpaRepository.java` | CREATE — cross-dtype `LedgerEntryRepository` impl |
| `runtime/.../ledger/ReactiveLedgerEntryJpaRepository.java` | CREATE — reactive cross-dtype impl |
| `runtime/.../ledger/MessageLedgerEntryRepository.java` | MODIFY — remove `implements LedgerEntryRepository`, remove `@Priority(10)`, remove 11 interface-method overrides |
| `runtime/.../ledger/ReactiveMessageLedgerEntryRepository.java` | MODIFY — remove `implements ReactiveLedgerEntryRepository`, remove all interface-method overrides, remove `@Inject ActorIdentityProvider` |
| `runtime/.../ledger/LedgerWriteService.java` | MODIFY — two injections, instanceof cast fix |
| `runtime/.../ledger/ReactiveLedgerWriteService.java` | MODIFY — two injections |
| `runtime/src/test/.../ledger/StubLedgerEntryJpaRepository.java` | CREATE — cross-dtype stub (shared list constructor) |
| `runtime/src/test/.../ledger/StubMessageLedgerEntryRepository.java` | MODIFY — remove interface overrides, add shared list constructor, retain findByMessageId + findEarliestWithSubjectByCorrelationId |
| `runtime/src/test/.../ledger/StubReactiveLedgerEntryRepository.java` | NO CHANGE — implements `ReactiveLedgerEntryRepository` directly; not affected by split |
| `runtime/src/test/.../ledger/CrossDtypeSequenceTest.java` | CREATE — integration regression test |
| `runtime/src/test/.../ledger/LedgerEntryJpaRepositoryTest.java` | CREATE — repository correctness tests for findBySubjectId, findEntryById |
| `CLAUDE.md` | UPDATE — document `LedgerEntryJpaRepository` (cross-dtype) and `MessageLedgerEntryRepository` (qhorus-scoped only); update injection description for `LedgerWriteService` |

### #252

| File | Action |
|---|---|
| `runtime/.../channel/ReactiveChannelService.java` | MODIFY — 6 UUID-first replacements |
| `runtime/.../channel/ChannelService.java` | MODIFY — 6 UUID-first replacements |
| `runtime/.../mcp/ReactiveQhorusMcpTools.java` | MODIFY — 6 call sites |
| `runtime/.../mcp/QhorusMcpTools.java` | MODIFY — 6 call sites |
| `runtime/src/test/.../channel/ChannelServiceTest.java` | MODIFY — 4 `delete` call sites |

---

## Invariants enforced by the new design

1. `LedgerEntryJpaRepository` / `ReactiveLedgerEntryJpaRepository` contain no `MessageLedgerEntry` in any JPQL string — verified by code review.
2. `MessageLedgerEntryRepository` and `ReactiveMessageLedgerEntryRepository` do not implement their respective repository interfaces — compiler-enforced.
3. `LedgerWriteService.record()` and `ReactiveLedgerWriteService.record()` have no unchecked casts from `LedgerEntry` — compiler-enforced by instanceof pattern.
4. All six channel service mutation methods in both services take `UUID channelId` — compiler-enforced.
5. `ReactiveQhorusMcpTools` and `QhorusMcpTools` pass `ch.id` to all six service methods — compiler-enforced after #252.
