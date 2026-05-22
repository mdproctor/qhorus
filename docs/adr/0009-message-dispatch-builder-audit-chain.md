# 0009 — Replace `MessageService.send()` with `MessageDispatch` builder for ledger audit chain completeness

Date: 2026-05-22
Status: Accepted

## Context and Problem Statement

`MessageService.send()` had a 9-parameter positional signature. More critically, every `MessageLedgerEntry` recorded `subjectId = channelId` (the capability lane — "entity-resolution", "pattern-analysis") rather than the domain aggregate being processed (TXN-001, caseId). Cross-domain causal links (`causedByEntryId`) could not be set by callers. The result: regulators querying `?subjectId=TXN-001` received nothing; the engine case chain and qhorus message chain were split at every module boundary.

## Decision Drivers

* FinCEN-grade audit chain requires every `MessageLedgerEntry` to carry the domain aggregate UUID as `subjectId`
* Cross-domain causal links must be expressible at call-site without secondary ledger queries
* The positional API was unmaintainable and admitted no further extension
* `causedByEntryId` auto-linking from `inReplyTo` must work for all 9 speech-act types without per-type special-casing

## Considered Options

* **Option A** — `MessageDispatch` builder + `DispatchResult`, ledger propagation in `LedgerWriteService`
* **Option B** — Add `subjectId`/`causedByEntryId` as extra positional parameters to `send()`
* **Option C** — Keep `send()`, add a separate `LedgerService.link()` callers must invoke after each send

## Decision Outcome

Chosen option: **Option A**, because the builder enforces protocol invariants at construction time, `DispatchResult` returns the resolved ledger entry UUID enabling downstream chaining without re-querying, and encapsulating the ledger write inside `dispatch()` makes the atomicity contract (`REQUIRES_NEW`) coherent and invisible to callers.

### Positive Consequences

* Every `MessageLedgerEntry` carries the correct domain aggregate as `subjectId` — `?subjectId=TXN-001` returns the full investigation chain
* `causedByEntryId` auto-links from `inReplyTo` (Priority 2) for all 9 types — no per-type caller logic
* Builder enforces speech-act protocol at `build()` time: DONE/DECLINE/FAILURE require `inReplyTo` + `correlationId`; RESPONSE requires `inReplyTo`; HANDOFF requires all three + `target`
* `DispatchResult.ledgerEntryId()` enables caller chaining without secondary queries
* Ledger write atomicity (`REQUIRES_NEW`) is owned by `dispatch()` — callers cannot accidentally skip it

### Negative Consequences / Tradeoffs

* Breaking change: `send()` deleted, `MessageResult` deleted — all 134 call sites migrated
* `A2AChannelBackend` now bypasses rate limiter and `allowed_writers` ACL (tracked in #188)
* `findById()` round-trip in `QhorusMcpTools` for deadline write (tracked in #187)

## Pros and Cons of the Options

### Option A — `MessageDispatch` builder + `DispatchResult`

* ✅ Protocol invariants enforced at build time — violations caught immediately, not downstream
* ✅ `DispatchResult` returns resolved `ledgerEntryId`, `subjectId`, `causedByEntryId` — no secondary queries
* ✅ Ledger write atomicity owned by `dispatch()` — impossible to skip
* ✅ No JPA entities cross the `REQUIRES_NEW` boundary — `LazyInitializationException` eliminated
* ❌ All 134 call sites required migration

### Option B — Extra positional parameters on `send()`

* ✅ Less migration
* ❌ 11-parameter positional signature is worse than 9
* ❌ Protocol invariants still not enforced — callers can pass null for required fields
* ❌ Ledger write still separate — atomicity not guaranteed

### Option C — Separate `LedgerService.link()` call

* ✅ No migration of `send()` callers
* ❌ Two-step dispatch pattern — callers routinely forget the second call
* ❌ Causal chain broken any time a caller omits `link()`
* ❌ Does not fix `subjectId` propagation

## Links

* Spec: `docs/specs/2026-05-22-message-dispatch-builder-design.md`
* Issue: casehubio/qhorus#184 (blocking casehubio/aml#30)
* Protocol PP-20260522-3dca14: speech-act builder validation matrix
* Protocol PP-20260522-056cc2: no JPA entities across REQUIRES_NEW
