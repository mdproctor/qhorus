# 0003 — Reactive Dual Stack: Blocking + Reactive across all layers

Date: 2026-04-21
Status: Accepted

## Context and Problem Statement

Quarkus encourages reactive programming via Vert.x and SmallRye Mutiny.
Blocking I/O on the event loop is a footgun — it starves other requests and
degrades throughput. Qhorus's initial implementation is fully blocking
(`@Transactional`, blocking Panache entity statics, `Thread.sleep` in
`wait_for_reply`), which works in Quarkus dev mode and test suites but is
not event-loop-safe for high-throughput reactive deployments.

quarkus-ledger already ships a dual-stack repository layer (blocking
`LedgerEntryRepository` + `@Alternative ReactiveLedgerEntryRepository`)
established in Qhorus issue #68. That precedent showed dual-stack is viable
without forking the codebase.

The question: how to add reactive support across stores, services, MCP tools,
and REST resources without breaking the existing blocking stack or forcing
consumers to migrate?

## Decision Drivers

* Backward compatibility — existing consumer code injecting blocking services
  must be unchanged
* Opt-in — consumers who don't need reactive should pay no cost
* Testability — reactive paths must be verifiable even before Docker/PostgreSQL
  is available
* Incremental delivery — each layer (stores, services, tools, REST) can ship
  independently
* quarkus-mcp-server 1.11.1 natively supports `Uni<T>` return types on
  `@Tool` methods

## Considered Options

**Option A — Full reactive replacement**: Replace all blocking services and
tools with reactive equivalents. Remove the blocking stack.

Rejected: Too disruptive. Existing Claudony consumers inject `ChannelService`
by concrete type; a direct replacement would break them. Also prevents gradual
rollout.

**Option B — Blocking-only with `@Blocking` wrappers on every method**:
Keep the blocking stack; annotate everything `@Blocking` so Quarkus offloads
to worker threads.

Rejected: `@Blocking` is a workaround, not a solution. Event-loop-safe code
requires non-blocking I/O paths, not just offloaded blocking ones. This
approach also doesn't enable `Uni<T>` return types on `@Tool` methods, which
quarkus-mcp-server prefers for reactive deployments.

**Option C — Dual-stack with build-time activation flag (chosen)**:
Implement a parallel reactive layer at every tier. Use Quarkus build-time
annotations (`@IfBuildProperty`, `@UnlessBuildProperty`) and CDI `@Alternative`
to select which stack is active at build time. A single property
`quarkus.qhorus.reactive.enabled` toggles the entire reactive stack.

## Decision Outcome

Option C — dual-stack with `quarkus.qhorus.reactive.enabled`.

### What was built

Five reactive tiers implemented in parallel with the blocking stack:

| Tier | Blocking (always active by default) | Reactive (@IfBuildProperty) |
|---|---|---|
| Store interfaces | `*Store` (5 domains) | `Reactive*Store` (5 domains) |
| JPA implementations | `Jpa*Store` | `ReactiveJpa*Store` (`@Alternative`) |
| InMemory test stores | `InMemory*Store` (`@Alternative @Priority(1)`) | `InMemoryReactive*Store` (`@Alternative @Priority(1)`) |
| Services | `*Service` (5) + `LedgerWriteService` | `Reactive*Service` (5) + `ReactiveLedgerWriteService` (`@Alternative`) |
| MCP tools | `QhorusMcpTools extends QhorusMcpToolsBase` (`@UnlessBuildProperty`) | `ReactiveQhorusMcpTools extends QhorusMcpToolsBase` (`@IfBuildProperty`) |
| REST resources | `AgentCardResource`, `A2AResource` (`@UnlessBuildProperty`) | `ReactiveAgentCardResource`, `ReactiveA2AResource` (`@IfBuildProperty`) |

### Activation mechanism

`QhorusBuildConfig` (deployment module, `ConfigPhase.BUILD_TIME`) holds
`quarkus.qhorus.reactive.enabled` defaulting to `false`. At build time:

- When `false` (default): blocking beans active; reactive beans not registered
  (suppressed by `@IfBuildProperty`)
- When `true`: reactive beans registered; blocking MCP and REST beans
  deactivated (suppressed by `@UnlessBuildProperty(enableIfMissing=true)`)

`QhorusProcessor` gains `@BuildStep(onlyIf = ReactiveEnabled.class)` that
marks reactive beans as unremovable via `UnremovableBeanBuildItem`.

### Shared code via QhorusMcpToolsBase

All 23 response records, 7 entity→DTO mappers, and 3 validator helpers are
extracted from `QhorusMcpTools` into `QhorusMcpToolsBase` (abstract, no CDI,
no `@Tool`). Both tool classes extend it; records are shared without
duplication. [ADR-0001](0001-mcp-tool-return-type-strategy.md) and
[ADR-0002](0002-persistence-abstraction-store-pattern.md) precedents informed
the extraction strategy.

### Category A vs Category B tools

`ReactiveQhorusMcpTools` implements 39 tools in two categories:
- **Category A (20 tools)**: pure reactive chains via reactive services, returning `Uni<T>` directly
- **Category B (19 tools)**: `@Blocking` + private `blockingXxx` helpers with exact blocking-service logic, returning `Uni<T>` wrapping a synchronous result

Category B covers tools that use Panache entity statics (channel semantic
dispatch, `wait_for_reply` poll loop, PendingReply management) not yet
exposed via reactive store interfaces. Category A covers all simple CRUD
and read tools.

## Consequences

**Positive:**
- Blocking stack unchanged — zero consumer impact at `reactive.enabled=false`
- Reactive stack opt-in — consumers who need Vert.x compatibility set one build property
- quarkus-mcp-server `Uni<T>` return types supported natively in 1.11.1
- Contract test infrastructure (abstract `*StoreContractTest`, `*ServiceContractTest`
  bases) ensures both stacks maintain identical correctness semantics
- Incremental delivery — each tier shipped as a separate GitHub issue (#74–#80)

**Negative / accepted tradeoffs:**
- Larger codebase — parallel reactive services, tools, REST resources add ~3000 lines
- Category B `@Blocking` tools are not truly reactive — they use blocking services
  on worker threads. A future issue can convert them to pure reactive chains as
  reactive store interfaces are extended
- Reactive service integration tests are `@Disabled` — H2 has no async JDBC
  driver, so `Panache.withTransaction()` in reactive services cannot be tested
  without Docker/PostgreSQL. The `ReactiveTestProfile` and `@Disabled` runners
  are scaffolded and ready to enable when Docker becomes available
- `@IfBuildProperty` is evaluated at build time — test profiles cannot override
  it at runtime, meaning `ReactiveQhorusMcpTools` cannot be activated in
  `@QuarkusTest` via a profile setting

## quarkus-ledger Precedent

quarkus-ledger already established the dual-stack pattern at the repository
layer: `LedgerEntryRepository` (blocking, `EntityManager`) and
`ReactiveLedgerEntryRepository` (`@Alternative`, Hibernate Reactive Panache).
Qhorus `ReactiveAgentMessageLedgerEntryRepository` extends this reactive
interface and was added in issue #68 — the first dual-stack component in the
codebase. The reactive dual-stack epic (#73) generalised this pattern to all
five store domains and the full service/tool/REST stack.
