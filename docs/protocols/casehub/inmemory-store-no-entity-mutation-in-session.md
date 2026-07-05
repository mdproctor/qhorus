---
id: PP-20260618-100368
title: "InMemory store methods must not mutate PanacheEntity fields within Panache.withSession() scope"
type: rule
scope: platform
applies_to: "casehub-qhorus-persistence-memory — all InMemory*Store and InMemoryReactive*Store implementations"
severity: critical
garden_ref: GE-20260618-d81cef
violation_hint: "InMemory store method does `entity.field = value` rather than a no-op or side-map; JPA UPDATE fires during session flush"
created: 2026-06-18
---

InMemory store implementations must not modify `PanacheEntity` fields in-place in any method that
will be called from within `Panache.withSession()`. Hibernate's bytecode enhancement instruments
all `PanacheEntity` subclasses with dirty tracking; field writes within an active session scope are
detected and generate a JPA UPDATE at flush — even if the entity was never persisted or loaded in
that session. The CDI injection test confirming the InMemory bean is selected is a false positive:
it proves the right bean is injected, not that entity mutations are safe. Methods that would
otherwise update metadata fields (e.g. `updateLastActivity`) should be no-ops in InMemory
implementations; creation-time defaults set via `put()` are sufficient for test contexts.
