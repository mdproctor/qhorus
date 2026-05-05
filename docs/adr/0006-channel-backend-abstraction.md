# ADR-0006 — Channel Backend Abstraction

**Date:** 2026-05-04
**Status:** Accepted
**Refs:** casehubio/qhorus#131

## Context

Qhorus channels were designed for agent-to-agent communication within the Qhorus topology. Making them backend-agnostic (WhatsApp, Slack, Claudony panel, A2A) required a gateway layer without breaking existing normative guarantees.

## Decision

Introduce a `ChannelGateway` with a `ChannelBackend` SPI hierarchy:

- `AgentChannelBackend` — always registered (`QhorusChannelBackend`); wraps `MessageService`. `post()` may throw — persistence failure is fatal.
- `HumanParticipatingChannelBackend` — at most one per channel; full speech act inbound via `InboundNormaliser`. `post()` must swallow exceptions.
- `HumanObserverChannelBackend` — unlimited per channel; inbound capped to `EVENT` by gateway. `post()` must swallow exceptions.

Actor vocabulary aligned with `casehub-ledger`'s `ActorType` enum (`HUMAN`, `AGENT`, `SYSTEM`). `Senders.HUMAN = "human"` is the canonical human sender constant (in `casehub-qhorus-api`).

## Production flow

`sendMessage()` calls `messageService.send()` for persistence (inside the `@Transactional` boundary), then calls `channelGateway.fanOut()` to dispatch to external backends via Java 21 virtual threads. `ChannelGateway.post()` is package-private and used only in unit tests.

## Alternatives Considered

**Decorated MessageService** — simpler but inbound path was awkward; at-most-one constraint unenforceable at type level.

**CDI event bus** — loosely coupled but non-deterministic ordering; enforcing "at most one participating backend" required a registry anyway.

## At-Most-One `HumanParticipatingChannelBackend`

Two human participatory surfaces on the same channel produce two independent conversation threads. An agent cannot know they represent different threads and may honour both — violating Qhorus's coherence invariant that a channel is a single accountable communication surface. `DuplicateParticipatingBackendException` is thrown on registration; the check-then-add is synchronized on the channel's backend list.

## ActorType Alignment

`HumanParticipatingChannelBackend` and `HumanObserverChannelBackend` align with `ActorType.HUMAN`. `AgentChannelBackend` aligns with `ActorType.AGENT`. `Senders.HUMAN = "human"` resolves to `ActorType.HUMAN` via `ActorTypeResolver` catch-all. This ensures backend type names, ledger entries, and `ActorTypeResolver` share one vocabulary.

## A2A — Protocol Bridge, Not Transport

A2A carries both `role: "user"` (human) and `role: "agent"` (AI). It is a protocol multiplexer that dispatches into the appropriate gateway inbound path based on resolved actor type. Tracked in casehubio/qhorus#135 (blocked on casehubio/ledger#75).

## Consequences

- All existing MCP tool behaviour is preserved; gateway is additive above `MessageService`
- External backend fan-out is async (Java 21 virtual threads); failures are non-fatal and logged
- Inbound normalisation is pluggable via `InboundNormaliser` SPI; default always returns QUERY
- Backend registrations are in-memory; persistence deferred to casehubio/qhorus#132
- `register_backend` MCP tool (agent-driven dynamic association) deferred to casehubio/qhorus#140
