# Persistence Abstraction — Design Spec
**Date:** 2026-04-18
**Status:** Approved — pending implementation plan
**Informs:** ADR-0002

---

## Goal

Introduce a proper DB abstraction layer to Qhorus so that the JPA/Panache
implementation can be swapped without touching services or MCP tools. Aligns
with the `Store` + `scan(Query)` pattern established in quarkus-workitems.
Reactive migration (`Uni<T>`) is a separate follow-on step.

Reference: `docs/specs/2026-04-17-persistence-abstraction-strategy.md`

---

## Package and Module Structure

New `testing/` Maven module alongside `runtime/` and `deployment/`.
Inside `runtime`, a new `store/` package tree:

```
runtime/src/main/java/io/quarkiverse/qhorus/runtime/
    store/
        ChannelStore.java
        MessageStore.java
        InstanceStore.java
        DataStore.java
        WatchdogStore.java
        query/
            ChannelQuery.java
            MessageQuery.java
            InstanceQuery.java
            DataQuery.java
            WatchdogQuery.java
        jpa/
            JpaChannelStore.java
            JpaMessageStore.java
            JpaInstanceStore.java
            JpaDataStore.java
            JpaWatchdogStore.java

testing/src/main/java/io/quarkiverse/qhorus/testing/
    InMemoryChannelStore.java
    InMemoryMessageStore.java
    InMemoryInstanceStore.java
    InMemoryDataStore.java
    InMemoryWatchdogStore.java
```

Ledger (`AgentMessageLedgerEntryRepository`) is untouched.
Existing service classes stay in their current packages.

---

## Store Interfaces

All stores follow KV semantics: `put` / `find` / `scan` plus domain-specific
methods where the query pattern does not fit.

```java
public interface ChannelStore {
    Channel put(Channel channel);
    Optional<Channel> find(UUID id);
    Optional<Channel> findByName(String name);
    List<Channel> scan(ChannelQuery query);
    void delete(UUID id);
}

public interface MessageStore {
    Message put(Message message);
    Optional<Message> find(Long id);
    List<Message> scan(MessageQuery query);
    void deleteAll(UUID channelId);     // EPHEMERAL / COLLECT / BARRIER clear
    void delete(Long id);               // delete_message tool
    int countByChannel(UUID channelId); // ChannelDetail message count
}

public interface InstanceStore {
    Instance put(Instance instance);
    Optional<Instance> find(UUID id);
    Optional<Instance> findByInstanceId(String instanceId);
    List<Instance> scan(InstanceQuery query);
    void putCapabilities(UUID instanceId, List<String> tags);
    void deleteCapabilities(UUID instanceId);
    List<String> findCapabilities(UUID instanceId);
    void delete(UUID id);
}

public interface DataStore {
    SharedData put(SharedData data);
    Optional<SharedData> find(UUID id);
    Optional<SharedData> findByKey(String key);
    List<SharedData> scan(DataQuery query);
    ArtefactClaim putClaim(ArtefactClaim claim);
    void deleteClaim(UUID artefactId, UUID instanceId);
    int countClaims(UUID artefactId);
    void delete(UUID id);
}

public interface WatchdogStore {
    Watchdog put(Watchdog watchdog);
    Optional<Watchdog> find(UUID id);
    List<Watchdog> scan(WatchdogQuery query);
    void delete(UUID id);
}
```

**Design notes:**
- `MessageStore.countByChannel` and `DataStore.countClaims` are explicit methods
  (not derived from `scan`) because they must issue a single `COUNT` query —
  materialising rows to count them is wrong.
- `InstanceStore.putCapabilities` / `deleteCapabilities` are separate from
  `put(Instance)` because capabilities are a child table; the JPA impl does
  delete-then-insert. `putCapabilities` is always replace-all — callers pass
  the complete desired tag set.
- `WatchdogStore` is intentionally minimal. `WatchdogQuery` absorbs future
  filter dimensions without interface changes.

---

## Query Objects

Immutable value objects. `null` = unconstrained. Static factories for common
patterns; builder for composition.

```java
public final class ChannelQuery {
    private final String namePattern;       // LIKE, null = all
    private final ChannelSemantic semantic;
    private final Boolean paused;

    public static ChannelQuery all() { ... }
    public static ChannelQuery byName(String pattern) { ... }
    public static ChannelQuery paused() { ... }
    public Builder toBuilder() { ... }
}

public final class MessageQuery {
    private final UUID channelId;
    private final Long afterId;             // cursor pagination
    private final Integer limit;
    private final List<MessageType> excludeTypes;
    private final String sender;
    private final String target;            // instance/capability/role filter
    private final String contentPattern;    // LIKE, for search_messages
    private final Long inReplyTo;           // for get_replies

    public static MessageQuery forChannel(UUID channelId) { ... }
    public static MessageQuery poll(UUID channelId, Long afterId, int limit) { ... }
    public static MessageQuery replies(UUID channelId, Long inReplyTo) { ... }
    public Builder toBuilder() { ... }
}

public final class InstanceQuery {
    private final String capability;
    private final String status;
    private final Instant staleOlderThan;

    public static InstanceQuery online() { ... }
    public static InstanceQuery byCapability(String tag) { ... }
    public static InstanceQuery staleOlderThan(Instant threshold) { ... }
    public Builder toBuilder() { ... }
}

public final class DataQuery {
    private final String createdBy;
    private final Boolean complete;

    public static DataQuery all() { ... }
    public static DataQuery complete() { ... }
    public static DataQuery byCreator(String instanceId) { ... }
    public Builder toBuilder() { ... }
}

public final class WatchdogQuery {
    private final Boolean enabled;
    private final String conditionType;

    public static WatchdogQuery enabled() { ... }
    public static WatchdogQuery all() { ... }
    public Builder toBuilder() { ... }
}
```

---

## JPA Implementations

Thin `@ApplicationScoped` beans. Move existing Panache calls out of services
verbatim — no logic changes, just relocation.

```java
@ApplicationScoped
public class JpaChannelStore implements ChannelStore {

    @Override @Transactional
    public Channel put(Channel channel) {
        channel.persistAndFlush();
        return channel;
    }

    @Override
    public Optional<Channel> find(UUID id) {
        return Optional.ofNullable(Channel.findById(id));
    }

    @Override
    public Optional<Channel> findByName(String name) {
        return Channel.find("name", name).firstResultOptional();
    }

    @Override
    public List<Channel> scan(ChannelQuery q) {
        // dynamic JPQL from non-null query fields
    }

    @Override @Transactional
    public void delete(UUID id) {
        Channel.deleteById(id);
    }
}
```

All other JPA stores follow the same pattern. The existing Panache queries
move here unchanged — this is a relocation, not a rewrite.

---

## Service Migration

Services replace direct Panache static calls with injected store calls.
`@Transactional` on write operations stays at the service level — the
transaction wraps the full business operation, not individual store calls.

**Before:**
```java
// Inside ChannelService
return Channel.find("name", name).firstResultOptional();
```

**After:**
```java
@Inject ChannelStore channelStore;

return channelStore.findByName(name);
```

**What does not change:**
- Business logic — BARRIER evaluation, rate limiting, LAST_WRITE enforcement,
  observer fanout all stay in services.
- `@Transactional` at service level for write operations.
- `QhorusMcpTools` — injects services, not stores; zero change.
- `LedgerWriteService` — already isolated with `REQUIRES_NEW`, untouched.
- All 561 existing tests — the JPA stores are the default; tests keep running
  against H2 as before.

---

## testing/ Module

In-memory implementations activated via CDI `@Alternative @Priority(1)`.
No Hibernate, no H2 — boot is near-instant.

```java
@Alternative @Priority(1)
@ApplicationScoped
public class InMemoryChannelStore implements ChannelStore {
    private final Map<UUID, Channel> store = new LinkedHashMap<>();

    @Override
    public Channel put(Channel ch) {
        if (ch.id == null) ch.id = UUID.randomUUID();
        store.put(ch.id, ch);
        return ch;
    }
    @Override public Optional<Channel> find(UUID id) {
        return Optional.ofNullable(store.get(id));
    }
    @Override public Optional<Channel> findByName(String name) {
        return store.values().stream().filter(c -> c.name.equals(name)).findFirst();
    }
    @Override public List<Channel> scan(ChannelQuery q) {
        return store.values().stream().filter(q::matches).toList();
        // Each Query class exposes a matches(Entity) predicate that mirrors
        // the JPA WHERE logic in memory — defined on the query value object.
    }
    @Override public void delete(UUID id) { store.remove(id); }

    public void clear() { store.clear(); }
}
```

All five in-memory stores follow this shape. `scan()` delegates to
`q.matches(entity)` — a predicate on the query object that mirrors the
JPA WHERE logic in memory.

**pom.xml:**
```xml
<artifactId>quarkus-qhorus-testing</artifactId>
<dependencies>
    <dependency>
        <groupId>io.quarkiverse.qhorus</groupId>
        <artifactId>quarkus-qhorus</artifactId>
    </dependency>
    <!-- No Hibernate, no H2 -->
</dependencies>
```

**Consuming projects add to test scope:**
```xml
<dependency>
    <groupId>io.quarkiverse.qhorus</groupId>
    <artifactId>quarkus-qhorus-testing</artifactId>
    <scope>test</scope>
</dependency>
```

Qhorus's own tests continue using `@QuarkusTest` + H2 + the JPA stores —
they are integration tests verifying the real Panache implementation.
The `testing/` module is for consumers of Qhorus who want fast unit tests
without a database.

---

## What This Is Not

- Not a reactive migration. Store method signatures are blocking (`T`, `void`).
  Reactive (`Uni<T>`) is a separate, subsequent step — store interfaces are
  the only seam that needs to change when that happens.
- Not a rewrite of business logic. Services keep all behavioural rules.
- Not a change to the MCP tool surface. `QhorusMcpTools` is untouched.
- Not a change to the ledger. `AgentMessageLedgerEntryRepository` stays as-is.

---

## Decision Record

This design informs **ADR-0002** (to be written alongside implementation).
The cross-project rationale is in
`docs/specs/2026-04-17-persistence-abstraction-strategy.md`.
