# 0012 — JTA Post-Commit Dispatch for MessageObserver

Date: 2026-05-30
Status: Accepted

## Context and Problem Statement

`MessageObserverDispatcher.dispatch()` is called inside `MessageService.dispatch()`,
which is `@Transactional`. The default `InProcessMessageBus` observer calls
`Event<MessageReceivedEvent>.fireAsync()`, which schedules an async CDI event that
may execute before the enclosing transaction commits — creating a race where an
observer that reads qhorus message state sees absent or stale data.

## Decision Drivers

* Observers must see fully committed message state if they query the store
* `@TestTransaction` tests frequently place the TX in ROLLBACK_ONLY state; synchronization
  registration must not throw in that context
* Reactive path (`ReactiveMessageService`) has no JTA TSR equivalent

## Considered Options

* **Option A** — Defer via JTA TSR (`registerInterposedSynchronization`) with STATUS_ACTIVE gate
* **Option B** — Fire observers synchronously (original behaviour, pre-#166)
* **Option C** — Patch only `InProcessMessageBus` to self-register its async fire with TSR

## Decision Outcome

Chosen option: **Option A** (with synchronous fallback), because it guarantees all
observers see committed state without requiring each observer implementation to handle
the deferral itself.

### Positive Consequences

* All observers — current and future — automatically fire post-commit
* The `MessageReceivedEvent` self-contained payload contract is preserved (observers
  can still rely on it without querying the store, but querying now also works)
* `InProcessMessageBus` needs no changes

### Negative Consequences / Tradeoffs

* `MessageService` must inject `TransactionSynchronizationRegistry`; adds a Narayana JTA coupling
* `ReactiveMessageService` cannot participate (null TSR, synchronous fallback) — reactive path retains the pre-#166 race
* Any `@TestTransaction` test where a prior `@Transactional` call throws will see ROLLBACK_ONLY and trigger the synchronous fallback — observers fire pre-commit in that scenario only

## Pros and Cons of the Options

### Option A — JTA TSR with STATUS_ACTIVE gate

* ✅ All observers guaranteed post-commit without implementation changes
* ✅ Rollback-only transactions correctly skip observers (message never committed)
* ❌ Narayana "state 1" error if STATUS_ACTIVE not checked — non-obvious failure
* ❌ Reactive path excluded

### Option B — Synchronous dispatch (pre-#166 default)

* ✅ Simple; no TSR dependency
* ✅ Works in reactive path
* ❌ Observer reads during `InProcessMessageBus.fireAsync()` may see absent data
* ❌ Race condition grows as observers become more sophisticated

### Option C — Patch InProcessMessageBus only

* ✅ Minimal change surface
* ❌ Future observer implementations must remember to self-register — convention rather than enforcement
* ❌ Per-observer opt-in means the guarantee is never universal

## Links

* Refs qhorus#166
* Protocol PP-20260530-332d70 — jta-tsr-status-active-gate
* Garden entry GE-20260530-a14b49 — Narayana TSR "state 1" gotcha
