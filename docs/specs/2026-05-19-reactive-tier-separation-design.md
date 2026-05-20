# Reactive Service Tier Separation — Design Spec

**Issue:** casehubio/qhorus#172  
**Date:** 2026-05-19  
**Protocols:** PP-20260519-f2e160 (reactive-blocking-tier-separation), PP-20260519-39a9a5 (reactive-service-build-gating)  
**Reference implementation:** casehub-ledger#92

---

## Problem

Qhorus reactive beans are currently gated with `@IfBuildProperty(name = "quarkus.datasource.qhorus.reactive", stringValue = "true")` directly on runtime bean classes. This has two problems:

1. **Wrong property name.** The platform protocol (PP-20260519-39a9a5) establishes `casehub.<module>.reactive.enabled` as the standard property. Using a Quarkus datasource property name (`quarkus.datasource.*`) is a convention violation and couples bean activation to the datasource configuration namespace.

2. **Wrong property name for per-bean gating.** `@IfBuildProperty` on runtime beans is reliable only when the property is formally declared as `BUILD_TIME` phase config. The datasource property `quarkus.datasource.qhorus.reactive` is treated as build-time by Quarkus implicitly; a custom property like `casehub.qhorus.reactive.enabled` requires an explicit `@ConfigRoot(phase = BUILD_TIME)` declaration to be reliable.

Additionally, `ReactiveQhorusMcpTools` violates PP-20260519-f2e160 by mixing reactive (`Uni<T>`) methods with `@Blocking @Transactional` helpers. The blocking helpers exist because reactive store/service methods were missing when Category B tools were implemented. This debt is resolved as part of this issue.

---

## Approach

Rename the gating property and formally declare it as `BUILD_TIME` config:

- New `QhorusBuildTimeConfig` in the `deployment` module — `@ConfigRoot(phase = BUILD_TIME)` declaring `casehub.qhorus.reactive.enabled` (default `false`)
- Per-bean `@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")` on reactive beans (renamed from `quarkus.datasource.qhorus.reactive`) — this mechanism is now reliable because the property is formally declared BUILD_TIME
- Per-bean `@UnlessBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true", enableIfMissing = true)` on conflicting blocking entry-point beans
- **Note:** The `ExcludedTypeBuildItem` centralised-processor approach (casehub-ledger#92 reference pattern) was attempted but does not work in this Quarkus 3.32.2 test configuration — the `@BuildStep` with `List<ExcludedTypeBuildItem>` return type is not invoked during Quarkus test augmentation from a workspace deployment module. Per-bean `@IfBuildProperty` with a BUILD_TIME-declared property achieves equivalent correctness.
- Category B `@Blocking @Transactional` tools converted to pure `Uni<T>` by filling gaps in the reactive store/service tier (Plan B — separate plan)
- Structural purity tests enforce the tier boundary going forward

---

## Section 1 — Build-time Gating

### `QhorusBuildTimeConfig` (new, in `deployment/`)

```java
@ConfigMapping(prefix = "casehub.qhorus")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface QhorusBuildTimeConfig {
    /** Reactive service tier configuration. */
    ReactiveConfig reactive();

    interface ReactiveConfig {
        /**
         * Whether to activate the reactive service tier.
         * Set to {@code true} in deployments that provide a reactive datasource.
         * JDBC-only consumers must leave this unset (defaults to {@code false}).
         */
        @WithDefault("false")
        boolean enabled();
    }
}
```

### `QhorusProcessor` — feature registration only

`QhorusProcessor` registers the `"qhorus"` feature. Gating is handled per-bean via `@IfBuildProperty` / `@UnlessBuildProperty` annotations on runtime classes, which are now reliable because `casehub.qhorus.reactive.enabled` is formally declared as `BUILD_TIME` in `QhorusBuildTimeConfig`.

**Note on `ExcludedTypeBuildItem` approach:** The centralised-processor approach (`@BuildStep` returning `List<ExcludedTypeBuildItem>`) was attempted but the build step is not invoked during Quarkus 3.32.2 test augmentation from a workspace deployment module. Per-bean `@IfBuildProperty` with a formally declared BUILD_TIME property achieves equivalent correctness and is the implemented approach.

### Per-bean gating

Reactive beans carry `@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")` — active only when the property is explicitly set to `true`. When absent (default), reactive beans are excluded.

Conflicting blocking entry-point beans carry `@UnlessBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true", enableIfMissing = true)` — active by default (property absent), excluded when reactive is enabled.

Blocking service beans with no conflicting reactive counterpart (`ChannelService`, `MessageService`, etc.) have no gating annotation — always present in both stacks.

### Test properties

`runtime/src/test/resources/application.properties`:
- **Do NOT add** `casehub.qhorus.reactive.enabled` — this is a BUILD_TIME-only property. Its presence in `application.properties` (which SmallRye Config reads at runtime) triggers `SRCFG00050: does not map to any root` validation error. The property is absent → reactive beans excluded (correct for H2/JDBC tests).
- Keep: `quarkus.datasource.qhorus.reactive=false` — suppresses `FastBootHibernateReactivePersistenceProvider`, separate concern from bean gating.

`@TestProfile` classes for reactive testing (all `@Disabled`, require Docker/PostgreSQL) set `"casehub.qhorus.reactive.enabled", "true"` in `getConfigOverrides()` — build-time override applied at augmentation for the restarted context.

---

## Section 2 — Reactive Purity: Category B Conversion

### Current state

`ReactiveQhorusMcpTools` has two categories:
- **Category A (pure reactive):** `Uni<T>`-returning methods using reactive services — already correct
- **Category B (`@Blocking @Transactional`):** wrapper methods delegating to blocking services — protocol violation

### `wait_for_reply` — accepted exception

`wait_for_reply` is a long-poll operation. `@Blocking` is architecturally correct here regardless of which stack is active — it holds a thread while polling for a reply. Converting it to a reactive polling loop would change observable semantics (timeout handling, interruption) without benefit. It stays `@Blocking` with a Javadoc comment stating the reason.

### All other Category B tools — converted to `Uni<T>`

Conversion requires filling gaps in the reactive service/store tier. The gaps fall into three groups:

**Group 1 — Raw Panache violations** (`force_release_channel`, `get_channel_digest`):  
These bypass the store abstraction by calling `Message.<Message>find(...)` directly. Fix: add the needed query methods to `ReactiveMessageStore` and use them, eliminating direct Panache calls. (Note: `get_channel_timeline` was already fixed separately in #173.)

**Group 2 — Ledger query tools** (`get_obligation_chain`, `get_obligation_stats`, `get_telemetry_summary`):  
Currently use blocking `MessageLedgerEntryRepository`. Fix: route to `ReactiveMessageLedgerEntryRepository` (already exists in the codebase).

**Group 3 — Service delegation** (all other Category B tools):  
Use blocking channel/message/instance/data services. Fix: add the missing `Uni<T>`-returning methods to the corresponding reactive services. The reactive services already exist; they are missing specific methods that the blocking services have.

### `ReactiveQhorusMcpTools` after conversion

- Imports `jakarta.transaction.Transactional` removed (except `wait_for_reply`)
- No `blockingXxx` private helpers remain (except for `wait_for_reply`)
- All tools return `Uni<T>` directly from reactive service/store calls
- Category B section comment updated or removed

---

## Section 3 — Structural Purity Tests

### `BlockingTierPurityTest` (new, in `runtime/src/test/`)

Verifies that blocking-tier service beans contain no `Uni<T>`-returning methods. Enforces PP-20260519-f2e160 going forward.

Beans checked: `ChannelService`, `MessageService`, `InstanceService`, `DataService`, `WatchdogService`, `LedgerWriteService`, `CommitmentService`.

Pattern follows ledger's `BlockingTierPurityTest` exactly — pure reflection, no Quarkus context, fast.

### `ReactiveTierPurityTest` (new, in `runtime/src/test/`)

Verifies that reactive-tier service beans contain no `@Transactional`-annotated methods. Enforces the complement of PP-20260519-f2e160.

Beans checked: `ReactiveChannelService`, `ReactiveMessageService`, `ReactiveInstanceService`, `ReactiveDataService`, `ReactiveWatchdogService`, `ReactiveLedgerWriteService`.

`ReactiveQhorusMcpTools` is intentionally excluded — `wait_for_reply` holds a documented `@Blocking` exception. The purity tests govern the service tier, not the MCP entry point.

---

## Out of Scope

- `ReactiveQhorusMcpTools.wait_for_reply` reactive conversion — documented exception, separate concern
- `StubReactiveLedgerEntryRepository` in test sources — remains as-is (satisfies ledger's reactive CDI dependency in H2 test suite, per PP-20260519-1f5e2c)
- Reactive JPA store tests (`@Disabled`) — unaffected by this change
- Flyway migrations — no schema changes in this issue

---

## Coherence with Ledger

Qhorus diverges from ledger in one place: it has **conflicting bean pairs** (MCP tools, REST resources) where blocking and reactive variants cannot coexist. Ledger has no equivalent. The two-sided `excludeReactiveBeans()` in `QhorusProcessor` handles this — blocking service beans (non-conflicting) are always present as in ledger; only the conflicting entry-point beans require mutual exclusion.
