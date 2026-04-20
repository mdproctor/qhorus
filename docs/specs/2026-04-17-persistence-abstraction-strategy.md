# Persistence Abstraction Strategy — Cross-Project Comparison
**Date:** 2026-04-17
**Author:** Quarkus Qhorus session (for review by Qhorus, CaseHub, and quarkus-workitems Claudes)
**Status:** Reference — informs ADRs in each project

---

## Purpose

This document compares three persistence abstraction approaches in use across
the Quarkiverse ecosystem: the SWF SDK Java pattern (the origin), the
quarkus-workitems adaptation (the recommended model), and CaseHub's current
approach (predates the convergence). It is intended to:

1. Record the recommendation for **Qhorus** — adopt the quarkus-workitems pattern.
2. Give **CaseHub Claude** enough detail to evaluate whether CaseHub should
   align with quarkus-workitems, and what that migration would cost.
3. Serve as the reference for ADRs in each project when decisions are committed.

---

## The Three Approaches

### 1. SWF SDK Java — the origin

**Project:** `io.serverlessworkflow` / `swf-sdk-java`
**Package:** `impl/persistence/`

SWF is a CNCF Serverless Workflow runtime. Its persistence layer must be
framework-agnostic: no CDI, no Quarkus, runnable in any JVM environment.
That requirement drives the complexity.

**Layer stack (top to bottom):**

```
PersistenceInstanceHandlers          ← composite: writer + reader
  PersistenceInstanceWriter          ← async event interface (CompletableFuture<Void>)
    started(), completed(), failed(),
    suspended(), resumed(),
    taskRetried(), taskCompleted()
  PersistenceInstanceReader          ← query interface
    find(definition, instanceId)
    scanAll(definition)

  ↓ delegates to ↓

PersistenceInstanceStore             ← the backing: one method: begin()
  PersistenceInstanceTransaction     ← extends PersistenceInstanceOperations
    commit(definition)
    rollback(definition)

PersistenceInstanceOperations        ← CRUD contract
    writeInstanceData(context)
    removeProcessInstance(context)
    writeRetryTask(context, task)
    writeCompletedTask(context, task)
    writeStatus(context, status)
    clearStatus(context)
    scanAll(applicationId, definition) → Stream<PersistenceWorkflowInfo>
    readWorkflowInfo(definition, id)   → Optional<PersistenceWorkflowInfo>
```

**Key design points:**
- Writer/Reader split: writes are lifecycle events (async `CompletableFuture<Void>`);
  reads are synchronous queries.
- `PersistenceWorkflowInfo` is a plain record — the store never returns JPA
  entities. Every implementation produces this record; callers get clean data.
- `BigMapInstanceTransaction<V,T,S,A>` — generic template for KV store
  implementations. Type parameters are the serialised value types.
- Wired via **builder pattern**, not CDI. `DefaultPersistenceInstanceHandlers.from(store)`.
- Concrete implementation: `MVStorePersistenceStore` (H2 MVStore engine).
- Test contract: `AbstractHandlerPersistenceTest` — abstract base class;
  subclasses provide `persistenceStore()`. Tests the full lifecycle.

**Why it's right for SWF, overkill for Quarkus extensions:**
The Writer/Reader split, `CompletableFuture<Void>`, builder wiring, and
`Info` records all exist because SWF cannot assume CDI, `@Transactional`,
or any container. A Quarkus extension has all of those. Adopting the full
SWF stack in a Quarkus extension adds indirection without benefit.

---

### 2. quarkus-workitems — the recommended adaptation

**Project:** `quarkus-workitems`
**Package:** `runtime/repository/`

WorkItems was explicitly asked to adopt SWF's persistence conventions and
came back with a principled simplification: keep the spirit (Store
terminology, KV semantics, Query value objects, CDI substitutability) and
drop what CDI already provides (explicit transaction management, builder
wiring, Writer/Reader split, Info records).

**Core interfaces:**

```java
// WorkItemStore.java — the entire persistence SPI
public interface WorkItemStore {
    WorkItem put(WorkItem workItem);
    Optional<WorkItem> get(UUID id);
    List<WorkItem> scan(WorkItemQuery query);
    default List<WorkItem> scanAll() { return scan(WorkItemQuery.all()); }
}

// AuditEntryStore.java — append-only
public interface AuditEntryStore {
    void append(AuditEntry entry);
    List<AuditEntry> findByWorkItemId(UUID workItemId);
}
```

**WorkItemQuery — the query abstraction:**

A single immutable value object replaces all named finder methods.
Backends translate it to their native query language.

```java
// Static factories for common patterns:
WorkItemQuery.all()
WorkItemQuery.inbox(assigneeId, candidateGroups, candidateUserId)
WorkItemQuery.expired(Instant.now())
WorkItemQuery.claimExpired(Instant.now())
WorkItemQuery.byLabelPattern("legal/**")

// Composable via builder:
WorkItemQuery.inbox("alice", List.of("finance"), null)
    .toBuilder().priority(HIGH).category("contracts").build()
```

Semantics: assignment fields use OR logic (visibility from any dimension);
all other fields use AND logic; `null` means unconstrained.

**Default JPA implementation:**

```java
@ApplicationScoped
public class JpaWorkItemStore implements WorkItemStore {
    public WorkItem put(WorkItem item) { item.persistAndFlush(); return item; }
    public Optional<WorkItem> get(UUID id) { return Optional.ofNullable(WorkItem.findById(id)); }
    public List<WorkItem> scan(WorkItemQuery q) { /* dynamic JPQL from query fields */ }
}
```

Note: `WorkItem` is a Panache entity returned directly — no Info record
translation layer. This works because Panache entities are POJOs; an
in-memory implementation stores them in a `Map` without needing JPA.

**In-memory test implementation (testing/ module):**

```java
@Alternative @Priority(1)   // CDI replaces JPA impl when testing module is on classpath
@ApplicationScoped
public class InMemoryWorkItemStore implements WorkItemStore {
    private final Map<UUID, WorkItem> store = new LinkedHashMap<>();
    // ...implements same scan semantics in memory
    public void clear() { store.clear(); }  // called in @BeforeEach
}
```

**Module layout:**

```
runtime/src/main/java/.../repository/
    WorkItemStore.java          ← SPI interface
    WorkItemQuery.java          ← query value object
    AuditEntryStore.java        ← append-only SPI
    jpa/
        JpaWorkItemStore.java   ← default Panache implementation
        JpaAuditEntryStore.java

testing/src/main/java/.../
    InMemoryWorkItemStore.java  ← @Alternative @Priority(1)
    InMemoryAuditEntryStore.java
```

**What was simplified vs SWF:**

| SWF SDK | quarkus-workitems | Why |
|---|---|---|
| Builder wiring | CDI `@ApplicationScoped` | Container already provides wiring |
| Explicit `begin`/`commit`/`rollback` | `@Transactional` on service methods | JTA handles it |
| Writer/Reader split | Unified store | No event-driven/async distinction needed |
| `PersistenceInfo` records | Return entity directly | Entities are POJOs; saves mapping layer |
| `CompletableFuture<Void>` | Blocking (`void` / `T`) | Reactive is a separate migration step |
| Abstract base classes for impl | Implement interface directly | Less hierarchy; clearer |

**What was preserved:**
- `Store` terminology (not Repository, not DAO)
- KV semantics: `put` / `get` / `scan`
- `Query` value object (backend-agnostic criteria)
- Per-domain stores
- CDI substitutability via `@Alternative @Priority(1)`
- Dedicated `testing/` module

---

### 3. CaseHub — current approach

**Project:** `casehub`
**Modules:** `casehub-core`, `casehub-persistence-memory`, `casehub-persistence-hibernate`

CaseHub predates the SWF/workitems convergence and follows its own clean
design. It is not wrong — but it diverges in ways worth re-examining.

**Core SPI interfaces (`casehub-core`):**

```java
// CaseFileRepository.java
public interface CaseFileRepository {
    CaseFile create(String caseType, Map<String, Object> initialState,
                    PropagationContext propagationContext);
    CaseFile createChild(String caseType, Map<String, Object> initialState, CaseFile parent);
    Optional<CaseFile> findById(Long id);
    List<CaseFile> findByStatus(CaseStatus status);
    void save(CaseFile caseFile);
    void delete(Long id);
}

// TaskRepository.java
public interface TaskRepository {
    Task create(String taskType, Map<String, Object> context,
                Set<String> requiredCapabilities, PropagationContext propagationContext,
                CaseFile owningCase);
    Task createAutonomous(String taskType, Map<String, Object> context,
                          String assignedWorkerId, CaseFile owningCase,
                          PropagationContext propagationContext);
    Optional<Task> findById(Long id);
    List<Task> findByStatus(TaskStatus status);
    List<Task> findByWorker(String workerId);
    void save(Task task);
    void delete(Long id);
}
```

**Domain model: interfaces, not records or entities**

CaseHub's domain objects (`CaseFile`, `Task`) are **Java interfaces**,
not JPA entities or plain records. Implementations (`HibernateCaseFile`,
`InMemoryCaseFile`) provide the actual storage. This is a richer pattern —
it supports things like per-key optimistic locking and ephemeral listeners —
but it means the persistence contract returns `CaseFile` (the interface),
not a concrete entity. Each backend implements its own `CaseFile`.

```java
public interface CaseFile {
    Long getId();
    Long getVersion();
    // Workspace blackboard
    <T> Optional<T> get(String key, Class<T> type);
    void put(String key, Object value);
    void putIfVersion(String key, Object value, long expectedVersion)  // optimistic lock
        throws StaleVersionException;
    // Graph
    Optional<CaseFile> getParentCase();
    List<CaseFile> getChildCases();
    List<Task> getTasks();
    // Lifecycle
    CaseStatus getStatus();
    void complete();
    void fail(ErrorInfo error);
    // Listeners (in-memory only)
    void onChange(String key, Consumer<CaseFileItemEvent> listener);
}
```

**In-memory implementation (`casehub-persistence-memory`):**

```java
@ApplicationScoped
public class InMemoryCaseFileRepository implements CaseFileRepository {
    private final Map<Long, InMemoryCaseFile> store = new ConcurrentHashMap<>();
    // InMemoryCaseFile implements CaseFile using ConcurrentHashMap + AtomicReference
    // Full per-key versioning, thread-safe mutations, ephemeral listener support
}
```

**Hibernate implementation (`casehub-persistence-hibernate`):**

```java
@ApplicationScoped
public class HibernateCaseFileRepository implements CaseFileRepository {
    @Inject EntityManager em;

    @Override @Transactional
    public CaseFile create(...) { /* em.persist(new HibernateCaseFile(...)) */ }

    @Override @Transactional
    public Optional<CaseFile> findById(Long id) {
        return Optional.ofNullable(em.find(HibernateCaseFile.class, id));
    }
    // etc.
}
```

Note: deliberately avoids `PanacheRepositoryBase` to prevent method signature
conflicts — `findById(Id)` in Panache returns `Entity`, but the SPI needs
`Optional<CaseFile>`.

**HibernateCaseFile key design decisions:**

```java
@Entity
@Table(name = "case_files")
public class HibernateCaseFile implements CaseFile {
    @Id @GeneratedValue(strategy = SEQUENCE, generator = "case_file_seq")
    private Long id;

    @Version  // Hibernate optimistic locking at row level
    private Long version;

    // Workspace stored as JSON TEXT blob — ALL items in one column
    @Column(columnDefinition = "TEXT")
    @Convert(converter = ObjectMapConverter.class)
    private Map<String, Object> items;

    // Per-key versions stored as JSON map (for fine-grained putIfVersion support)
    @Convert(converter = StringMapConverter.class)
    private Map<String, String> itemVersions;

    // PropagationContext denormalised into flat columns
    @Column(name = "trace_id") private String traceId;
    @Convert(converter = StringMapConverter.class)
    private Map<String, String> inheritedAttributes;
    private Instant deadline;
    private Long remainingBudgetSeconds;

    // Lazy graph
    @ManyToOne(fetch = LAZY)  @JoinColumn(name = "parent_case_id")
    private HibernateCaseFile parentCase;
    @OneToMany(mappedBy = "parentCase", cascade = ALL, fetch = LAZY)
    private List<HibernateCaseFile> childCases;

    // Listeners are @Transient — ephemeral, reset on load
    @Transient
    private Map<String, List<Consumer<CaseFileItemEvent>>> keyListeners;
}
```

**ObjectMapConverter limitation (documented):**
```
Jackson deserialises JSON numbers as Integer/Long/Double and complex objects
as LinkedHashMap. Custom POJOs stored in the workspace do NOT round-trip
correctly — only JSON-primitive-compatible types are supported.
```

**Execution model:** 100% blocking. `@Transactional` on all repository methods.
No reactive types.

**Terminology:** `Repository` (not `Store`). `findById`, `findByStatus`,
`create`, `save`, `delete` — SQL-shaped naming.

---

## Comparison Matrix

| Dimension | SWF SDK | quarkus-workitems | CaseHub |
|---|---|---|---|
| **Abstraction term** | Store | Store | Repository |
| **Query pattern** | Individual methods on `Operations` | `scan(Query)` value object | Individual `findByX()` methods |
| **Domain object type** | `Info` records (DTOs) | JPA entity (Panache) | Interface (`CaseFile`, `Task`) |
| **Wiring** | Builder (no CDI) | CDI `@ApplicationScoped` | CDI `@ApplicationScoped` |
| **Test substitute** | Abstract base class + `persistenceStore()` | `@Alternative @Priority(1)` | Separate module, `@ApplicationScoped` |
| **Transaction handling** | Explicit `begin`/`commit`/`rollback` | `@Transactional` on service | `@Transactional` on repository |
| **Async/reactive** | `CompletableFuture<Void>` | Blocking | Blocking |
| **Separate testing module** | Yes (`persistence/tests/`) | Yes (`testing/`) | Yes (`casehub-persistence-memory`) |
| **Named finder methods** | Yes (on Operations) | No — replaced by `scan(Query)` | Yes |
| **`create()` in SPI** | No — callers build via context | No — callers `put()` | Yes — factory method in repository |
| **`save()` in SPI** | No — callers `put()` | `put()` | Yes — explicit `save()` |
| **Optimistic locking** | Via serialized versions | Hibernate `@Version` | Both: `@Version` row-level + per-key `putIfVersion` |
| **Per-key versioning** | No | No | Yes (CaseFile workspace) |

---

## The `create()` / `save()` question

CaseHub's repository has both `create()` and `save()` — two entry points for
writes. quarkus-workitems collapses these into a single `put()`. This is worth
examining:

**CaseHub's `create()`** does more than persist — it constructs the entity with
domain semantics (`PropagationContext`, parent/child wiring, initial state).
Callers pass raw parameters; the repository (or its Hibernate implementation)
builds the `HibernateCaseFile` and persists it.

**workitems' `put()`** is simpler: callers construct the `WorkItem` (via
`WorkItemService.create()`), then the service calls `store.put(item)`. The
store is dumb; construction logic stays in the service.

**The implication for CaseHub:** moving to `put()` semantics would mean the
`create` factory logic currently in `HibernateCaseFileRepository` would move
to a service layer (or remain in a domain factory). That's arguably better
separation — but it's a behavioural change, not just a rename.

---

## CaseHub: alignment assessment

### Arguments for aligning with quarkus-workitems

1. **Terminology consistency.** `Store` + `put`/`get`/`scan` will be the
   pattern across Qhorus, workitems, and potentially other Quarkiverse
   extensions. CaseHub using `Repository` + `findByX` / `save` / `create`
   is an inconsistency callers of both will notice.

2. **`scan(Query)` is strictly better than named finders** for extensibility.
   Adding a new filter dimension to `CaseFile` queries currently means a new
   method on the interface. A `CaseFileQuery` value object makes that additive
   without breaking implementors.

3. **CDI substitutability via `@Alternative @Priority(1)`** is simpler than
   maintaining a separate module that callers must explicitly activate. The
   workitems `testing/` module works automatically on test classpath.

4. **The domain object interface pattern** (`CaseFile` as an interface) is
   CaseHub's most distinctive choice. quarkus-workitems returns the JPA entity
   directly. For CaseHub this would be a bigger change — `HibernateCaseFile`
   would need to become the primary type returned, with `InMemoryCaseFile`
   implementing it as a plain POJO. The per-key versioning (`putIfVersion`)
   and ephemeral listeners make this harder: those features are inherently
   implementation-specific and don't map cleanly to a Panache entity.

5. **The `putIfVersion` / per-key optimistic locking** is CaseHub's most
   distinctive feature and the hardest to align. quarkus-workitems has no
   equivalent — it uses Hibernate `@Version` at row level only. If per-key
   versioning is essential to CaseHub semantics, the interface pattern is
   justified and alignment becomes partial, not full.

### Recommended alignment path for CaseHub

**High-value, low-cost changes (align now):**

- Rename `CaseFileRepository` → `CaseFileStore`, `TaskRepository` → `TaskStore`.
- Replace `findByStatus()` with `scan(CaseFileQuery)` — a `CaseFileQuery`
  with `status`, `caseType`, `parentId` fields handles all current finders.
- Collapse `create()` + `save()` into `put()` — move construction logic to
  `CaseFileService` (if one exists) or a `CaseFileFactory`.
- `delete(Long id)` → `delete(CaseFile)` or keep `delete(Long id)` — minor.
- Add `@Alternative @Priority(1)` to `InMemoryCaseFileRepository` so test
  activation is automatic (currently requires explicit wiring).

**Keep CaseHub-specific (don't change):**

- The `CaseFile` / `Task` interface pattern — the per-key versioning
  (`putIfVersion`) and ephemeral listeners genuinely require it. This
  is a legitimate divergence from workitems; CaseHub's blackboard semantics
  are richer than workitems' flat `payload` field.
- The separate `casehub-persistence-memory` and `casehub-persistence-hibernate`
  modules — the module structure is correct; just align the CDI wiring.

**Result:** CaseHub converges on `Store` terminology and `scan(Query)` pattern
while retaining the interface-based domain model that its per-key versioning
requires. The cost is modest; the benefit is a consistent vocabulary across
the ecosystem.

---

## Recommendation for Qhorus

Follow quarkus-workitems' pattern exactly. Per-domain store interfaces under
`runtime/store/`, JPA implementations under `runtime/store/jpa/`, in-memory
implementations in a new `testing/` module.

**Proposed stores:**

| Interface | Core methods | Query object |
|---|---|---|
| `ChannelStore` | `put`, `find(UUID)`, `findByName(String)`, `scan(ChannelQuery)`, `delete(UUID)` | `ChannelQuery(namePattern, semantic, paused)` |
| `MessageStore` | `put`, `find(Long)`, `scan(MessageQuery)`, `deleteAll(UUID channelId)` | `MessageQuery(channelId, afterId, limit, excludeTypes, sender, target)` |
| `InstanceStore` | `put`, `find(UUID)`, `scan(InstanceQuery)`, `putCapabilities(UUID, List<String>)`, `deleteCapabilities(UUID)` | `InstanceQuery(capability, status, staleBefore)` |
| `DataStore` | `put`, `find(UUID)`, `findByKey(String)`, `scan(DataQuery)`, `putClaim(ArtefactClaim)`, `deleteClaim(UUID, UUID)`, `delete(UUID)` | `DataQuery(createdBy, complete)` |
| `WatchdogStore` | `put`, `find(UUID)`, `scan(WatchdogQuery)`, `delete(UUID)` | `WatchdogQuery(enabled, conditionType)` |

Ledger: `AgentMessageLedgerEntryRepository` already follows the repository
pattern from quarkus-ledger — leave it; align naming in a later pass if needed.

Services inject stores instead of calling Panache entity statics.
Business logic (BARRIER semantics, rate limiting, observer fanout) stays
in services — stores are dumb persistence seams.

**This document informs ADR-0002 in Qhorus once the design is approved.**

---

## Not an ADR — a reference

This document is a cross-project design comparison, not a project-specific
architectural decision. Each project should create its own ADR when it
commits to an approach:

- **Qhorus:** ADR-0002 — Persistence abstraction: `Store` + `scan(Query)` pattern
- **CaseHub:** ADR-N — Rename repositories to stores, introduce `CaseFileQuery`

Each ADR can reference this document for the rationale.
