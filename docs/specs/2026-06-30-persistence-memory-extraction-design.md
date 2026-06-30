# Persistence-Memory Module Extraction

**Issue:** #169  
**Date:** 2026-06-30  
**Status:** Approved  

## Problem

InMemory store implementations live in `casehub-qhorus-testing`. This conflates
persistence implementations with test utilities. The in-memory stores serve two
purposes — test isolation and zero-config ephemeral installs — neither of which
is a test utility.

## Design

### New Module

| Property | Value |
|----------|-------|
| Folder | `persistence-memory/` |
| ArtifactId | `casehub-qhorus-persistence-memory` |
| Package | `io.casehub.qhorus.persistence.memory` |
| Compile dependency | `casehub-qhorus` (runtime) |

### What Moves

All 20 InMemory store classes from `testing/src/main/` → `persistence-memory/src/main/`:

**Blocking stores (8):**
- `InMemoryChannelStore`
- `InMemoryMessageStore`
- `InMemoryCommitmentStore`
- `InMemoryInstanceStore`
- `InMemoryDataStore`
- `InMemoryWatchdogStore`
- `InMemoryChannelBindingStore`
- `InMemoryDeliveryCursorStore`

**Cross-tenant stores (4):**
- `InMemoryCrossTenantChannelStore`
- `InMemoryCrossTenantCommitmentStore`
- `InMemoryCrossTenantMessageStore`
- `InMemoryCrossTenantWatchdogStore`

**Reactive stores (6):**
- `InMemoryReactiveChannelStore`
- `InMemoryReactiveCommitmentStore`
- `InMemoryReactiveDataStore`
- `InMemoryReactiveInstanceStore`
- `InMemoryReactiveMessageStore`
- `InMemoryReactiveWatchdogStore`

**Tests that move with their subjects:**
- All `InMemory*StoreTest` classes (blocking + reactive)
- All contract test base classes (`*StoreContractTest`)
- `InMemoryStoresDualInterfaceTest`

### What Stays in testing/

- `MessageLedgerEntryTestFactory` — test factory for ledger entries (depends on `MessageLedgerEntry` from runtime, genuinely test-only)
- `RecordingChannelBackend` — test double for gateway integration tests (not a persistence implementation)
- `CommitmentServiceTest` — lives here to avoid a module cycle

### Package Change

`io.casehub.qhorus.testing` → `io.casehub.qhorus.persistence.memory`

Breaking change. All imports and `quarkus.arc.selected-alternatives` entries across the platform update. Affected:

| Module | What updates |
|--------|-------------|
| `runtime/` test stubs | Imports |
| `connector-backend/` tests | Imports |
| `slack-channel/` tests | Imports |
| `examples/type-system/` | `application.properties` selected-alternatives |
| `examples/normative-layout/` | Imports, `application.properties` |
| `examples/agent-communication/` | Imports |
| `claudony` (external) | Imports in `casehub/` and `app/` test sources, `application.properties` |

### Dependency Graph

```
persistence-memory/  →  casehub-qhorus (runtime)  →  casehub-qhorus-api
testing/             →  persistence-memory/  →  casehub-qhorus (runtime)
```

`testing/` gains a compile dependency on `casehub-qhorus-persistence-memory`.
All existing consumers of `casehub-qhorus-testing` transitively get InMemory
stores with zero POM changes.

### POM Structure

`persistence-memory/pom.xml`:
- Parent: `casehub-qhorus-parent`
- Dependencies: `casehub-qhorus` (compile), `jakarta.enterprise.cdi-api` (provided)
- Plugins: `jandex-maven-plugin` (Quarkus CDI bean discovery)
- Test dependencies: `junit-jupiter`, `assertj-core`, `smallrye-mutiny` (for reactive store tests)

### Known Limitation

Store SPI interfaces live in `runtime/`, not `api/`. This forces persistence-memory/
to depend on the full runtime module. Tracked in #314 for future resolution.

## Protocol Compliance

- **PP-20260512-module-tiers:** persistence-memory/ is the canonical location per the Store SPI pattern
- **PP-20260508-5c0e4b:** Folder name is `persistence-memory` (no repo prefix)
- **PP-20260618-100368:** InMemory stores preserve the no-mutation-in-session invariant (already compliant)
- **PP-20260618-131cdf:** Consumer selected-alternatives entries update to new package

## Out of Scope

- Moving Store SPI interfaces to api/ (#314)
- Introducing domain POJOs separate from JPA entities
- Changes to RecordingChannelBackend API
