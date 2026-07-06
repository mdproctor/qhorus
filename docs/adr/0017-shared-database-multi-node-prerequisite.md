# ADR-0017: Shared Database as Multi-Node Prerequisite

**Status:** Accepted
**Date:** 2026-07-06
**Refs:** casehubio/qhorus#162

## Context

When multiple JVM processes embed Qhorus, the question arises: should each
node have its own database, or must they share one?

Issue #162 proposed three resolution paths for cross-node delivery:
1. Shared Qhorus service (single deployment)
2. CLUSTER MessageObserver relay (each node publishes, all subscribe)
3. Replicated channel store (shared database or distributed cache)

## Decision

**Shared PostgreSQL is a prerequisite for multi-node Qhorus, not an option.**

Independent databases per node produce two independent governance systems.
Every core function — channels, messages, commitments, the tamper-evident
ledger, instance discovery — requires a single source of truth to be
coherent. A COMMAND dispatched on Node A must be fulfillable from Node B.
The ledger must be a single audit trail. Instance discovery must return
all registered agents regardless of which node they connect to.

With shared PostgreSQL, the only gap is push notification — telling other
nodes that a new message exists so they can trigger their local backends.
This is solved by `ChannelActivityBroadcaster` (SPI) with a PostgreSQL
LISTEN/NOTIFY implementation (`casehub-qhorus-postgres-broadcaster`).

## Consequences

- Multi-node deployments require shared PostgreSQL — documented in
  `docs/messaging-architecture.md`
- Independent-database topologies are architecturally invalid and not
  supported
- Cross-node push notification is an SPI concern, not a consumer concern
- Claudony's `FleetMessageRelayObserver` becomes redundant once the
  broadcaster module is on the classpath (claudony#168)
