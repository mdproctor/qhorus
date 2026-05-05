# ADR-0002: Persistence Abstraction — Store + scan(Query) Pattern

**Date:** 2026-04-18
**Status:** Accepted

## Context

Qhorus services called Panache entity statics directly (`Channel.find(...)`,
`Message.list(...)`, etc.). This made the JPA/Panache backend impossible to
swap without touching services or MCP tools, and made unit testing of Qhorus
consumers require a running database.

## Decision

Introduce per-domain store interfaces (`ChannelStore`, `MessageStore`,
`InstanceStore`, `DataStore`, `WatchdogStore`) following the
`put` / `find` / `scan(Query)` KV pattern established in quarkus-workitems.

Each store interface has:
- A `*Query` value object with immutable fields, static factories, a builder,
  and a `boolean matches(Entity)` predicate for in-memory implementations.
- A `Jpa*Store` default implementation (`@ApplicationScoped`) using Panache.
- An `InMemory*Store` in the `quarkus-qhorus-testing` module
  (`@Alternative @Priority(1)`) for consumers who want fast tests.

Services inject stores instead of calling Panache statics. Business logic
(BARRIER semantics, rate limiting, observer fanout) remains in services.

Reactive migration (`Uni<T>`) is deferred — store interfaces are blocking;
when reactive is added, only the interfaces and JPA implementations change.

## Rationale

- **quarkus-workitems** validated this pattern in the same ecosystem. The
  `scan(Query)` shape is strictly better than named finders for extensibility:
  adding a new filter dimension adds a field to the Query, not a new method
  to the interface.
- **CDI `@Alternative @Priority(1)`** is the Quarkus-native way to swap
  implementations — no builder pattern needed inside a CDI container.
- **No Info record layer** — Panache entities are POJOs; in-memory
  implementations store them in Maps without needing JPA. This saves a
  full mapping layer (validated by quarkus-workitems approach).

## Consequences

- Consumers add `quarkus-qhorus-testing` at test scope for instant-boot
  unit tests with no database.
- All 5 services depend on store interfaces — alternative backends
  (MongoDB, Redis, MVStore) implement those interfaces and activate via CDI.
- Reactive migration path: swap `T` → `Uni<T>` on interfaces + JPA impls.
- `WatchdogQuery` does not filter on `enabled` — that is a config-level
  concern, not an entity field. `WatchdogStore.scan()` returns all watchdogs
  by default; `WatchdogEvaluationService` handles enable/disable via config.

## References

- Cross-project comparison: `docs/specs/2026-04-17-persistence-abstraction-strategy.md`
- Design spec: `docs/superpowers/specs/2026-04-18-persistence-abstraction-design.md`
- quarkus-workitems: `WorkItemStore`, `WorkItemQuery` (established pattern)
