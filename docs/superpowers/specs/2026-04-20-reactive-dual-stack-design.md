# Qhorus Reactive Dual Stack — Design Spec

**Date:** 2026-04-20  
**Status:** Approved, pending implementation  
**Related ADR:** TBD (ADR-0003 — Dual blocking/reactive stack)

---

## Overview

Qhorus will ship both a blocking stack (existing, default) and a reactive stack
(`@Alternative`, opt-in) across every layer: stores, services, MCP tools, and REST
resources. A single build-time property activates the reactive stack. The two stacks
share all validation, mapping, and business rule logic through common base classes
and utility methods.

This mirrors the pattern established by quarkus-ledger: the extension is opinionated
about the SPI shape but neutral on which implementation the consumer activates.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    MCP / REST layer                         │
│  QhorusMcpTools (blocking)   ReactiveQhorusMcpTools (@Alt)  │
│              ↑ both extend QhorusMcpToolsBase ↑             │
├─────────────────────────────────────────────────────────────┤
│                    Service layer                            │
│  ChannelService (blocking)   ReactiveChannelService (@Alt)  │
│  MessageService              ReactiveMessageService         │
│  InstanceService             ReactiveInstanceService        │
│  DataService                 ReactiveDataService            │
│  LedgerWriteService          ReactiveLedgerWriteService     │
├─────────────────────────────────────────────────────────────┤
│                    Store layer                              │
│  *Store (blocking iface)     Reactive*Store (reactive iface)│
│  Jpa*Store (blocking impl)   ReactiveJpa*Store (@Alt impl)  │
│  InMemory*Store (testing)    InMemoryReactive*Store         │
│                              (delegates to InMemory*Store)  │
├─────────────────────────────────────────────────────────────┤
│         Ledger layer (dual already — done in #68)           │
│  AgentMessageLedgerEntryRepository (blocking)               │
│  ReactiveAgentMessageLedgerEntryRepository (@Alt)           │
└─────────────────────────────────────────────────────────────┘
```

---

## Layer Design

### Store layer

**Blocking (existing):** `*Store` interfaces return plain `T` / `Optional<T>` /
`List<T>`. `Jpa*Store` implements them using `EntityManager` and blocking Panache.

**Reactive (new):** `Reactive*Store` interfaces mirror `*Store` but return `Uni<T>` /
`Uni<Optional<T>>` / `Uni<List<T>>`. `ReactiveJpa*Store` implements them using
`ReactivePanacheRepository<E, UUID>` and `Panache.withTransaction()`.

**Testing (new):** `InMemoryReactive*Store` wraps `InMemory*Store` internally.
All state and logic live in `InMemory*Store`; the reactive wrapper converts return
values via `Uni.createFrom().item(...)`. No duplication of in-memory logic.

Five domains: Channel, Message, Instance, Data, Watchdog.

### Service layer

**Blocking (existing):** `*Service` classes inject `*Store`, annotate mutating
methods `@Transactional`.

**Reactive (new):** `Reactive*Service` classes inject `Reactive*Store`, use
`Panache.withTransaction(() -> ...)` for mutation, return `Uni<T>` throughout.

Shared validation logic extracted to package-private utility classes (one per
domain) — e.g. `ChannelValidation`, `MessageValidation`. Both blocking and
reactive services call these.

### Tool layer

**`QhorusMcpToolsBase` (new abstract class, not a CDI bean):**
- All `protected` validation helpers (input parsing, null checks, semantic parsing)
- All response-record mapping: `toChannelDetail(Channel)`, `toMessageResult(Message)`, etc.
- All public static response records (`ChannelDetail`, `MessageResult`, `CheckResult`, etc.)
- No `@Tool` annotations, no CDI annotations, no service injection

**`QhorusMcpTools extends QhorusMcpToolsBase` (blocking, existing):**
- `@ApplicationScoped`, `@WrapBusinessError`
- Injects blocking services
- `@Tool` methods: `@Transactional`, plain return types (`ChannelDetail`, `String`, etc.)
- All tool logic delegates to base class helpers

**`ReactiveQhorusMcpTools extends QhorusMcpToolsBase` (reactive, new):**
- `@Alternative @ApplicationScoped`, `@WrapBusinessError`
- Injects reactive services
- `@Tool` methods: return `Uni<T>` via reactive service chains
- Active only when `quarkus.qhorus.reactive.enabled=true`

### REST layer

`AgentCardResource` (read-only, no store access): minimal change — can return
`Uni<Response>` trivially.

`A2AResource` (guarded by `quarkus.qhorus.a2a.enabled`): reactive variant injects
reactive services and returns `Uni<Response>`.

### Ledger layer

Already dual (#68):
- `AgentMessageLedgerEntryRepository` (blocking) ✅
- `ReactiveAgentMessageLedgerEntryRepository` (`@Alternative`) ✅
- `LedgerWriteService` stays blocking (runs in `REQUIRES_NEW`)
- `ReactiveLedgerWriteService` (new `@Alternative`) uses reactive transaction

### Activation

Single build-time property: `quarkus.qhorus.reactive.enabled=true`

`QhorusProcessor` produces a `SyntheticBeanBuildItem` that selects the reactive
alternatives when the property is set. Default is `false` — blocking stack active,
reactive stack dormant.

---

## Test Strategy

### Principle: one scenario, two runners

Abstract contract test base classes define all scenarios as concrete `@Test` methods.
Blocking and reactive subclasses supply the service under test via abstract factory
methods. The reactive subclass unwraps `Uni` via `.await().indefinitely()` in its
factory implementation — test assertion code is identical across both subclasses.

```java
// Abstract contract (shared scenarios)
abstract class ChannelServiceContractTest {
    abstract Channel createChannel(String name, String desc, ChannelSemantic s);
    abstract Optional<Channel> findByName(String name);

    @Test void createChannel_persistsCorrectly() { ... }
    @Test void findByName_returnsEmpty_whenNotFound() { ... }
}

// Blocking runner
@QuarkusTest @TestTransaction
class ChannelServiceTest extends ChannelServiceContractTest {
    @Inject ChannelService svc;
    Channel createChannel(...) { return svc.create(...); }
}

// Reactive runner
@QuarkusTest @TestReactiveTransaction
class ReactiveChannelServiceTest extends ChannelServiceContractTest {
    @Inject ReactiveChannelService svc;
    Channel createChannel(...) { return svc.create(...).await().indefinitely(); }
}
```

### Test coverage tiers

| Tier | Blocking | Reactive |
|---|---|---|
| Store unit tests (InMemory) | `*StoreContractTest` → `InMemory*StoreTest` | `*StoreContractTest` → `InMemoryReactive*StoreTest` |
| Store integration tests (@QuarkusTest) | `Jpa*StoreTest` | `ReactiveJpa*StoreTest` |
| Service integration tests | `*ServiceContractTest` → `*ServiceTest` | `*ServiceContractTest` → `Reactive*ServiceTest` |
| MCP tool tests | existing pattern | `Reactive*ToolTest` |
| End-to-end / happy path | `SmokeTest` | `ReactiveSmokeTest` |

### Reactive test datasource

Tests use H2 via `vertx-jdbc-client` (not true async, but identical semantics).
Reactive tests activate with a separate `@TestProfile` that sets
`quarkus.qhorus.reactive.enabled=true` and configures the reactive H2 datasource.
Blocking tests are unaffected — `quarkus.datasource.reactive=false` stays default.

---

## Shared Code Inventory

| What | Where | Used by |
|---|---|---|
| Response records | `QhorusMcpToolsBase` | Both tool classes, A2A, tests |
| Entity→DTO mappers | `QhorusMcpToolsBase` (protected) | Both tool classes |
| Input validators | `QhorusMcpToolsBase` (protected) | Both tool classes |
| Domain validation utils | `*Validation` per package | Both `*Service` + `Reactive*Service` |
| InMemory store state | `InMemory*Store` | `InMemoryReactive*Store` via delegation |
| Contract test scenarios | `*ContractTest` | Blocking + reactive test runners |

---

## What Does Not Change

- `Channel`, `Message`, `Instance`, `SharedData`, `ArtefactClaim`, `PendingReply`,
  `Watchdog`, `AgentMessageLedgerEntry` entities — plain `@Entity`, no changes
- Flyway migrations — schema is shared; both stacks use the same tables
- `QhorusConfig` — no new config properties except `reactive.enabled`
- `QhorusProcessor` — gains one conditional build step; no other changes
- `AgentCard` and `A2A` feature guard pattern — unchanged

---

## Implementation Phases

1. **Store interfaces + reactive JPA implementations** (5 domains)
2. **InMemoryReactive*Store** (testing module)
3. **QhorusMcpToolsBase refactor** — extract shared code from existing `QhorusMcpTools`
4. **Reactive services** (5 domains)
5. **ReactiveLedgerWriteService**
6. **ReactiveQhorusMcpTools**
7. **Reactive REST resources** (AgentCard, A2A)
8. **Activation build step** (QhorusProcessor)
9. **Contract test base classes** + reactive test runners
10. **Documentation** — DESIGN.md, ADR-0003, CLAUDE.md

Each phase ships independently with its own issue(s) under the epic.
