---
id: PP-20260608-07daa6
title: "Tests asserting MessageObserver invocation must use QuarkusTransaction.requiringNew(), not @TestTransaction"
type: rule
scope: repo
applies_to: "@QuarkusTest classes that dispatch messages via MessageService and assert on observer.received lists or similar observer state"
severity: important
refs:
  - runtime/src/main/java/io/casehub/qhorus/runtime/message/MessageObserverDispatcher.java
  - runtime/src/test/java/io/casehub/qhorus/runtime/message/QualifiedMessageObserverTest.java
violation_hint: "Observer received list remains empty after messageService.dispatch() in a @TestTransaction test — no error, silent failure."
garden_ref: "GE-20260608-038af4"
created: 2026-06-08
---

`MessageObserverDispatcher` registers a JTA `Synchronization` via `TransactionSynchronizationRegistry.registerInterposedSynchronization()`. Observers only fire on `afterCompletion(STATUS_COMMITTED)`. In a `@TestTransaction` test, the transaction rolls back after the test method — `afterCompletion` fires with `STATUS_ROLLEDBACK` and all observer callbacks are silently skipped. Tests asserting observer invocation must instead use `QuarkusTransaction.requiringNew().run(() -> messageService.dispatch(...))` to create a nested transaction that actually commits. After `run()` returns, `afterCompletion(COMMITTED)` has already fired synchronously in the same thread and observer state is safe to assert. Entity setup (channel creation) must also be committed before the dispatch call — use a separate `QuarkusTransaction.requiringNew()` block. Note: the synchronous dispatch path (no active JTA transaction, `tsr == null`) does not have this limitation; it fires observers immediately. Only tests where JTA is active are affected.
