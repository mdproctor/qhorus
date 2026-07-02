# Store SPI Migration to api/ — Design Spec

**Issue:** casehubio/qhorus#314
**Date:** 2026-06-30
**Status:** Approved

## Problem

All 8 Store SPI interfaces live in `runtime/store/` and reference JPA entity types (`Channel`, `Message`, `Commitment`, etc.) in their method signatures. This forces `persistence-memory/` and any future persistence backend to depend on the full runtime module. It violates the module-tier-structure protocol: "SPI method signatures take domain POJOs, not JPA entity types."

## Decision

Introduce domain records in `api/`, move Store SPIs to `api/store/`, rename JPA entities to `*Entity` in `runtime/`. Domain records own the clean concept names. No backward-compatibility shims.

## Domain Records

9 immutable Java records in `api/`, one per domain concept. No JPA annotations, no Panache, no framework dependencies. Builder pattern for records with many fields (Channel, Message, Commitment); canonical constructor for small records.

Domain records own clean typed fields — CSV strings from the JPA layer are parsed into proper collections at the conversion boundary:

| Entity field (String CSV) | Record field (typed) |
|---|---|
| `allowedTypes` | `Set<MessageType>` |
| `deniedTypes` | `Set<MessageType>` |
| `allowedWriters` | `List<String>` |
| `adminInstances` | `List<String>` |
| `barrierContributors` | `List<String>` |
| `artefactRefs` (Message) | `List<UUID>` |

The conversion layer (`fromDomain`/`toDomain`) handles CSV↔collection parsing. This is consistent with `ChannelCreateRequest`, which already uses `Set<MessageType>` for type constraint fields.

| Record | Package | Key fields |
|--------|---------|------------|
| `Channel` | `api/channel/` | id, name, description, semantic, barrierContributors (`List<String>`), allowedWriters (`List<String>`), adminInstances (`List<String>`), rateLimitPerChannel, rateLimitPerInstance, allowedTypes (`Set<MessageType>`), deniedTypes (`Set<MessageType>`), paused, autoCreated, tenancyId, createdAt, lastActivityAt |
| `Message` | `api/message/` | id, channelId, sender, messageType, actorType, tenancyId, content, correlationId, inReplyTo, replyCount, artefactRefs (`List<UUID>`), target, commitmentId, deadline, acknowledgedAt, version, createdAt |
| `Commitment` | `api/message/` | id, correlationId, channelId, messageType, requester, obligor, state, expiresAt, acknowledgedAt, resolvedAt, delegatedTo, parentCommitmentId, tenancyId, createdAt |
| `Instance` | `api/instance/` | id, instanceId, description, status, claudonySessionId, sessionToken, readOnly, lastSeen, registeredAt |
| `SharedData` | `api/data/` | id, key, content, createdBy, description, complete, sizeBytes, createdAt, updatedAt |
| `ArtefactClaim` | `api/data/` | id, artefactId, instanceId, claimedAt |
| `Watchdog` | `api/watchdog/` | id, conditionType, targetName, thresholdSeconds, thresholdCount, notificationChannel, createdBy, tenancyId, createdAt, lastFiredAt |
| `DeliveryCursor` | `api/gateway/` | id, channelId, backendId, lastDeliveredId, lastDeliveredVersion, updatedAt, createdAt |
| `ChannelConnectorBinding` | `api/channel/` | channelId, inboundConnectorId, externalKey, outboundConnectorId, outboundDestination |

Existing api/ DTOs (`ChannelDetail`, `MessageView`, `MessageDispatch`, `DispatchResult`) remain as purpose-specific read/write views. They are projections of the domain model, not the model itself.

`ChannelCreateRequest` and `ChannelSlugValidator` move from `runtime/channel/` to `api/channel/` — they are pure Java using only api types.

### Channel creation flow

After migration, the creation path is: `ChannelCreateRequest → Channel domain record → ChannelStore.put(Channel) → JpaChannelStore converts via ChannelEntity.fromDomain(channel)`.

The `Channel` domain record gets a static factory: `Channel.fromRequest(ChannelCreateRequest req, String tenancyId)`. This factory generates UUID and timestamps (previously handled by `@PrePersist` on the entity). The domain record is the authoritative source — the entity's `@PrePersist` remains as a JPA safety net but is never the primary ID/timestamp source in normal code paths.

## Store SPIs

All Store SPI interfaces move from `runtime/store/` to `api/store/` (package `io.casehub.qhorus.api.store`):

**Blocking:** `ChannelStore`, `MessageStore`, `CommitmentStore`, `InstanceStore`, `DataStore`, `WatchdogStore`, `DeliveryCursorStore`, `ChannelBindingStore`

**Reactive:** `ReactiveChannelStore`, `ReactiveMessageStore`, `ReactiveCommitmentStore`, `ReactiveInstanceStore`, `ReactiveDataStore`, `ReactiveWatchdogStore`

**Cross-tenant:** `CrossTenantChannelStore`, `CrossTenantMessageStore` — these are independent interfaces (not extending ChannelStore/MessageStore) with their own entity-typed method signatures (`List<Channel>`, `Optional<Channel>`, `List<Message>`, `Optional<Message>`) that must change to domain record types

**Query types** move to `api/store/query/`: `ChannelQuery`, `MessageQuery`, `InstanceQuery`, `DataQuery`, `WatchdogQuery`. Their `matches()` methods change to reference domain records instead of JPA entities — field access changes from `ch.name` to `ch.name()` (record accessors).

## JPA Entity Renaming

10 JPA entities renamed with `Entity` suffix in `runtime/`:

| Current | New | Package (unchanged) | Domain record? |
|---------|-----|-------------------|----------------|
| `Channel` | `ChannelEntity` | `runtime/channel/` | Yes |
| `Message` | `MessageEntity` | `runtime/message/` | Yes |
| `Commitment` | `CommitmentEntity` | `runtime/message/` | Yes |
| `Instance` | `InstanceEntity` | `runtime/instance/` | Yes |
| `Capability` | `CapabilityEntity` | `runtime/instance/` | No — internal to InstanceStore; surfaced as `List<String>` via `putCapabilities()`/`findCapabilities()` |
| `SharedData` | `SharedDataEntity` | `runtime/data/` | Yes |
| `ArtefactClaim` | `ArtefactClaimEntity` | `runtime/data/` | Yes |
| `Watchdog` | `WatchdogEntity` | `runtime/watchdog/` | Yes |
| `DeliveryCursor` | `DeliveryCursorEntity` | `runtime/gateway/` | Yes |
| `ChannelConnectorBinding` | `ChannelConnectorBindingEntity` | `runtime/channel/` | Yes |

`@Table(name = ...)` annotations unchanged — no schema impact. `@PrePersist` hooks stay on entities.

## Conversion Layer

Each JPA entity gets two public static methods for domain record conversion:

```java
public static ChannelEntity fromDomain(Channel channel) { ... }
public Channel toDomain() { ... }
```

Public visibility is required because JPA entities and JPA store implementations are in different packages (e.g. `runtime.channel.ChannelEntity` vs `runtime.store.jpa.JpaChannelStore`). These methods are internal to the runtime module and invisible to api/ consumers regardless of Java access level.

The conversion methods handle CSV↔collection parsing for typed domain record fields (e.g. `MessageType.serializeTypes(channel.allowedTypes())` in `fromDomain`, `MessageType.parseTypes(allowedTypes)` in `toDomain`).

JPA store implementations (`JpaChannelStore`, etc.) convert at the boundary — domain records in, domain records out. InMemory stores in `persistence-memory/` work directly with domain records (no entity conversion needed).

## testing/ Duplicate Cleanup

The #169 extraction left duplicate InMemory stores in `testing/` alongside the canonical copies in `persistence-memory/`. This causes CDI ambiguity in connector-backend. This migration removes the duplicates from `testing/`, keeping only:
- `RecordingChannelBackend` (test utility)
- `MessageLedgerEntryTestFactory` (test fixture)
- `CommitmentServiceTest` (unit test)
- Contract test base classes

**Consumer migration:** `testing/pom.xml` already declares a `<dependency>` on `casehub-qhorus-persistence-memory`. When the duplicate InMemory stores are removed from `testing/`, consumers of `casehub-qhorus-testing` continue to receive the canonical `persistence-memory/` implementations transitively — no consumer POM changes needed. This satisfies module-tier-structure protocol checklist item 4: "testing/ module depends on persistence-memory/ so existing test consumers are unaffected."

## Internal Impact

Services in `runtime/` (`ChannelService`, `MessageService`, `CommitmentService`, `ChannelGateway`, `DeliveryService`, `QhorusMcpTools`, etc.) update imports from entity types to domain records. Field access changes from `channel.name` to `channel.name()`. `QhorusEntityMapper` maps domain records to DTOs instead of entities to DTOs.

**ARC42STORIES.MD updates:**
- §5 module structure: add `persistence-memory/` to the tree, update `testing/` description (test utilities only, not persistence implementations), update `api/` package table to include `api.store` and `api.channel.Channel` etc.
- §5 runtime key packages: update `runtime.store` description (JPA impls only, SPI interfaces moved to api/)
- §5 runtime entity names: `Channel` → `ChannelEntity`, etc.

**ADR:** File new ADR (0017) recording the reversal of ADR-0002's "No Info record layer" decision. ADR-0002 chose not to introduce domain POJOs because "Panache entities are POJOs; in-memory implementations store them in Maps without needing JPA." This no longer holds — the entities carry JPA annotations that force persistence-memory/ to depend on the full runtime module, violating the module-tier-structure protocol.

## Cross-Repo Impact

`casehub-engine` (actor-state module) directly injects `CommitmentStore` and `ChannelStore`. After this change, imports resolve from `api/store/` instead of `runtime/store/`, and return types change from JPA entities to domain records. Mechanical import and field-access update.

`casehub-ops` (deployment module) references `ChannelService`, `Channel` JPA entity, `ChannelCreateRequest`, `ChannelConnectorBinding` entity, `ChannelBindingStore` SPI, and `CrossTenantChannelStore` SPI. After migration: entity types become domain records from `api/`, store SPIs resolve from `api/store/` instead of `runtime/store/`. The `ChannelDriftChecker` additionally simplifies — its `typesMatch()` method currently parses CSV strings from the entity to compare against `Set<MessageType>` from the desired-state spec, and `csvSetMatch()` parses CSV strings for `allowedWriters`/`adminInstances`/`barrierContributors`. With typed domain record fields, these methods simplify to direct collection comparisons.

`casehub-drafthouse` (server module) imports `Channel`, `ChannelCreateRequest`, `ChannelService`, `MessageService`, and `Message` from `runtime/`. Production files affected: `DebateMcpTools.java`, `DraftHouseMcpTools.java`, `DebateEventResource.java`, `DebateStreamEntry.java`. After migration: `Channel` and `Message` become domain records from `api/`, `ChannelCreateRequest` moves to `api/channel/`. Field access changes from `channel.id`/`channel.name` to `channel.id()`/`channel.name()`, and `msg.content`/`msg.sender`/`msg.createdAt` to record accessors. Import updates for all entity and `ChannelCreateRequest` types.

`casehub-clinical` (runtime module) imports `ChannelCreateRequest` from `runtime/channel/` in `ProtocolDeviationService.java`. After migration: import changes to `api/channel/ChannelCreateRequest`. The clinical code also uses `channel.id` field access (from `ChannelService.findByName()` return) which changes to `channel.id()`.

Cross-repo issues to file:
- `casehub-engine`: update Store imports and field access (entity → domain record types)
- `casehub-ops`: update imports for `Channel`, `ChannelCreateRequest`, `ChannelConnectorBinding` (entity → domain record), `ChannelBindingStore`, `CrossTenantChannelStore` (store SPI from api/); simplify `ChannelDriftChecker` CSV-parsing methods; update Maven dependency from runtime to api/ where possible
- `casehub-drafthouse`: update `Channel`, `Message`, `ChannelCreateRequest` imports and field access in 4+ production files
- `casehub-clinical`: update `ChannelCreateRequest` import and `channel.id` field access in `ProtocolDeviationService`
- `casehub-parent`: update `docs/repos/casehub-qhorus.md` deep-dive; update PLATFORM.md cross-repo dependency map entries for qhorus (Store SPIs now in api/store/, not runtime/store/) and expand ops→qhorus edge to include `ChannelBindingStore`, `CrossTenantChannelStore`, `ChannelConnectorBinding`

## Maven Dependency Inversion

This is the primary structural outcome of the migration. `persistence-memory/pom.xml` currently depends on `casehub-qhorus` (the runtime module, Tier 3). After migration:

```
persistence-memory/ dependency:  casehub-qhorus (runtime) → casehub-qhorus-api (Tier 1)
```

With Store SPIs and domain records in `api/`, `persistence-memory/` no longer needs any runtime types. It depends only on the lightweight Tier 1 module — no JPA, no Panache, no Quarkus runtime on its classpath. Any future persistence backend (`persistence-mongodb/`, `persistence-redis/`, etc.) also depends only on `casehub-qhorus-api`.

**api/ acquires a Mutiny dependency.** The 6 reactive store interfaces (`ReactiveChannelStore`, `ReactiveMessageStore`, etc.) use `Uni<T>` and `Multi<T>` in their signatures. Moving them to `api/store/` requires adding `io.smallrye.reactive:mutiny` to `api/pom.xml` with `provided` scope. `provided` is correct because reactive stores are consumed only within qhorus runtime and persistence-memory — both already have Mutiny on their classpath. External consumers (casehub-engine, casehub-ops, casehub-drafthouse, casehub-clinical) use blocking store interfaces and do not gain Mutiny transitively. The api/ POM description updates to: "Domain records, Store SPI interfaces, enums, and exception types — no JPA, safe for any module to depend on."

## What Does NOT Change

- Database schema (zero Flyway migrations)
- MCP tool signatures (take/return strings and primitives)
- Gateway SPI types (already in api/)
- Existing api/ DTOs (remain as purpose-specific views)
- CDI priority ladder (InMemory @Alternative @Priority(1))

## Protocol Compliance

- **module-tier-structure**: Store SPIs in Tier 1, domain records pure Java, JPA in Tier 3
- **persistence-backend-cdi-priority**: CDI ladder unchanged; testing/ cleanup fixes ambiguity
- **consumer-spi-placement**: Store SPIs are consumer-facing → api/ placement correct
- **PLATFORM.md Store SPI Pattern**: All 7 checklist items satisfied
