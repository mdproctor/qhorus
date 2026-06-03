# qhorus Workspace

**Name:** qhorus

**Project repo:** /Users/mdproctor/claude/casehub/qhorus
**Workspace type:** public

## Session Start

Run `add-dir /Users/mdproctor/claude/casehub/qhorus` before any other work.

## Artifact Locations

| Skill | Writes to |
|-------|-----------|
| brainstorming (specs) | `specs/` |
| writing-plans (plans) | `plans/` |
| handover | `HANDOFF.md` |
| idea-log | `IDEAS.md` |
| design-snapshot | `snapshots/` |
| java-update-design / update-primary-doc | `design/JOURNAL.md` (created by `epic`) |
| adr | `adr/` |
| write-blog | `blog/` |

## Structure

- `HANDOFF.md` — session handover (single file, overwritten each session)
- `IDEAS.md` — idea log (single file)
- `specs/` — brainstorming / design specs (superpowers output)
- `plans/` — implementation plans (superpowers output)
- `snapshots/` — design snapshots with INDEX.md (auto-pruned, max 10)
- `adr/` — architecture decision records with INDEX.md
- `blog/` — project diary entries with INDEX.md
- `design/` — epic journal (created by `epic` at branch start)

## Git Discipline

Two git repositories are active in every session:
- **Workspace** (`/Users/mdproctor/claude/public/casehub/qhorus`) — methodology artifacts: handover, blog, specs, plans, ADRs
- **Project repo** (`/Users/mdproctor/claude/casehub/qhorus`) — source code

Before any git operation, run `git rev-parse --show-toplevel` to confirm which repo is currently active. Do not assume — the session may have opened in either. cd to the correct repo before staging:
- Source code commits → project repo
- Methodology artifacts → workspace

**Pre-push hook:** `.githooks/` (registered via `core.hooksPath .githooks`) contains a pre-push hook that unconditionally blocks every push — it is not pattern-based and does not have squash-candidate detection. Correct workflow: run `/git-squash`, get user approval on the plan, then `git push --no-verify --force-with-lease`. The `--no-verify` is required post-squash; without it the hook blocks the push again regardless of commit quality.

## Rules

- All methodology artifacts go here, not in the project repo
- Promotion to project repo is always explicit — never automatic
- Workspace branches mirror project branches — switch both together

## Routing

| Artifact   | Destination | Notes |
|------------|-------------|-------|
| adr        | project     | lands in `docs/adr/` — promoted at epic close |
| specs      | project     | lands in `docs/specs/` — promoted at epic close |
| blog       | workspace   | staged here; published to mdproctor.github.io via publish-blog |
| plans      | workspace   | stay in workspace permanently |
| design     | workspace   | epic journal stays in workspace |
| snapshots  | workspace   | stay in workspace permanently |
| handover   | workspace   | |

---

# CaseHub Qhorus — Claude Code Project Guide

## Platform Context

This repo is one component of the casehubio multi-repo platform. **Before implementing anything — any feature, SPI, data model, or abstraction — run the Platform Coherence Protocol.**

> **Platform docs:** Local paths use `../parent/docs/` as root. If a path doesn't exist, the parent repo isn't cloned locally — fetch from `https://raw.githubusercontent.com/casehubio/parent/main/docs/<path>` instead.

The protocol asks: Does this already exist elsewhere? Is this the right repo for it? Does this create a consolidation opportunity? Is this consistent with how the platform handles the same concern in other repos?

**Platform architecture (fetch before any implementation decision):**
```
../parent/docs/PLATFORM.md
```

**This repo's deep-dive:**
```
../parent/docs/repos/casehub-qhorus.md
```

**Other repo deep-dives** (fetch the relevant ones when your implementation touches their domain):
- casehub-ledger: `../parent/docs/repos/casehub-ledger.md`
- casehub-work: `../parent/docs/repos/casehub-work.md`
- casehub-engine: `../parent/docs/repos/casehub-engine.md`
- claudony: `../parent/docs/repos/claudony.md`
- casehub-connectors: `../parent/docs/repos/casehub-connectors.md`

---

## Project Type

type: java

**Stack:** Java 21 (on Java 26 JVM), Quarkus 3.32.2, GraalVM 25 (native image), quarkus-mcp-server 1.11.1

---

## What This Project Is

Qhorus is a CaseHub library — a Quarkus-based agent communication mesh and the peer-to-peer coordination layer for multi-agent AI systems. It is a permanent component of the casehubio platform (not a Quarkiverse submission), built on Quarkus and informed by research into A2A, AutoGen, LangGraph, Swarm, Letta, and CrewAI.

More precisely: Qhorus is a **governance methodology** for multi-agent AI — not middleware. It gives every agent interaction the formal status of an accountable act, grounded in speech act theory, deontic logic, defeasible reasoning, and social commitment semantics. The LLM reasons; the infrastructure enforces, records, and derives. See `docs/normative-layer.md` for the full framing.

Any Quarkus app adds `io.casehub:casehub-qhorus` as a dependency and its agents immediately get:
- **Typed channels** with declared update semantics (APPEND, COLLECT, BARRIER, EPHEMERAL, LAST_WRITE)
- **Typed messages** (query · command · response · status · decline · handoff · done · failure · event) — 9-type speech-act taxonomy; see ADR-0005
- **Shared data store** with artefact lifecycle management (claim/release, UUID refs, chunked streaming)
- **Instance registry** with capability tags and three addressing modes (by id · by capability · by role)
- **`wait_for_reply`** long-poll with correlation IDs and SSE keepalives
- **Agent Cards** at `/.well-known/agent-card.json` for A2A ecosystem discovery
- **Normative audit ledger** — every message of all 9 types creates a `MessageLedgerEntry` (via `casehub-ledger`) with SHA-256 tamper evidence. The ledger is the complete, immutable channel history. Queryable via `list_ledger_entries` (type_filter, sender, correlation_id, sort, after_id, limit — entry_id in output), `get_obligation_chain` (participants + elapsed + resolution), `get_causal_chain` (walk causedByEntryId to root), `list_stalled_obligations`, `get_obligation_stats` (fulfillment rate), `get_telemetry_summary` (per-tool EVENT aggregation), and `get_channel_timeline`
- **Channel gateway** — backend-agnostic fan-out via `ChannelGateway`; backends implement `AgentChannelBackend`, `HumanParticipatingChannelBackend`, or `HumanObserverChannelBackend` from `casehub-qhorus-api`. New MCP tools: `list_backends(channel_name)`, `deregister_backend(channel_name, backend_id)` (cannot remove `qhorus-internal`), `register_backend(channel_name, backend_id, backend_type)` (associates an existing CDI bean by its `backendId()`)

Qhorus is designed to be embedded in Claudony (`~/claude/casehub/claudony`) as part of the broader Quarkus Native AI Agent Ecosystem.

---

## CaseHub Naming

This project uses casehub naming conventions:

| Element | Value |
|---|---|
| GitHub repo | `casehubio/qhorus` |
| groupId | `io.casehub` |
| Parent artifactId | `casehub-qhorus-parent` |
| Runtime artifactId | `casehub-qhorus` |
| Deployment artifactId | `casehub-qhorus-deployment` |
| Root Java package | `io.casehub.qhorus` |
| Runtime subpackage | `io.casehub.qhorus.runtime` |
| Deployment subpackage | `io.casehub.qhorus.deployment` |
| Config prefix | `casehub.qhorus` |
| Feature name | `qhorus` |

---

## Ecosystem Context

```
casehub (orchestration engine)   casehub-qhorus (communication mesh)
           ↑                              ↑
           └──────── claudony (integration layer) ──────────┘
```

Qhorus has no dependency on CaseHub or Claudony — it is the independent communication layer.

---

## Project Structure

```
casehub-qhorus/
├── api/                                 — Extension API module (SPI contracts, no runtime deps)
│   └── src/main/java/io/casehub/qhorus/api/
│       ├── channel/
│       │   └── ChannelDetail.java       — DTO record: channel metadata (id, name, semantic, counts, limits, allowedTypes)
│       ├── instance/
│       │   └── InstanceInfo.java        — DTO record: instance metadata (instanceId, description, status, capabilities, lastSeen, readOnly)
│       ├── gateway/
│       │   ├── ChannelBackend.java, AgentChannelBackend.java
│       │   ├── HumanParticipatingChannelBackend.java, HumanObserverChannelBackend.java
│       │   ├── InboundNormaliser.java, Senders.java (HUMAN = "human")
│       │   ├── ChannelRef.java, OutboundMessage.java, InboundHumanMessage.java (externalSenderId, content, receivedAt, metadata, correlationId — nullable), ObserverSignal.java, NormalisedMessage.java (type, content, senderInstanceId, correlationId, inReplyTo, artefactRefs, target — last 4 nullable)
│       │   ├── MessageObserver.java — @FunctionalInterface SPI: onMessage(MessageReceivedEvent); scope() default=LOCAL; Scope{LOCAL,CLUSTER}; any normal CDI scope valid (@ApplicationScoped, @RequestScoped, etc.); dispatcher closes each Instance.Handle in finally
│       │   ├── MessageReceivedEvent.java — record: channelName, channelId, messageType, senderId, correlationId (nullable), content (null for EVENT per PP-20260508-90428f)
│       │   └── ChannelInitialisedEvent.java — record: channelId, channelName; fired by ChannelGateway.initChannel() on channel creation and startup recovery; observed by external backends to re-register without their own restart logic
│       ├── message/
│       │   ├── MessageResult.java       — DTO record: sent-message metadata (messageId, channelName, sender, type, correlationId, inReplyTo, artefactRefs, target)
│       │   ├── MessageType.java         — (existing, unchanged)
│       │   ├── MessageTypeViolationException.java — (existing, unchanged)
│       │   └── CommitmentState.java     — (existing, unchanged)
│       └── spi/                         — consumer-facing SPI interfaces (per consumer-spi-placement protocol); @DefaultBean impls in runtime/
│           ├── CommitmentAttestationPolicy.java — @FunctionalInterface SPI: determines LedgerAttestation for DONE/FAILURE/DECLINE; AttestationOutcome record; Refs #123
│           ├── InstanceActorIdProvider.java     — @FunctionalInterface SPI: maps instanceId → ledger actorId; Refs #124
│           ├── ObligorTrustPolicy.java          — @FunctionalInterface SPI: permits(ObligorTrustContext) — called for COMMAND + named non-prefixed target; Refs #213
│           └── ObligorTrustContext.java         — record: obligorId, channelId (UUID), channelName — passed to ObligorTrustPolicy.permits()
├── runtime/                             — Extension runtime module
│   └── src/main/java/io/casehub/qhorus/runtime/
│       ├── config/QhorusConfig.java     — @ConfigMapping(prefix = "casehub.qhorus")
│       ├── channel/
│       │   ├── Channel.java             — PanacheEntity; `allowedTypes` TEXT nullable — null = open; comma-separated MessageType names enforced by MessageTypePolicy SPI
│       │   ├── ChannelSemantic.java     — enum: APPEND|COLLECT|BARRIER|EPHEMERAL|LAST_WRITE
│       │   ├── AllowedWritersPolicy.java — @ApplicationScoped ACL check for channel write access; used by MessageService.dispatch(); unified supplier returns instance capability tags + synthetic role:actorType tag
│       │   ├── RateLimiter.java         — @ApplicationScoped in-memory sliding-window rate limiter; used by MessageService.dispatch()
│       │   └── ChannelService.java      — includes findByNamePrefix(prefix) → List<Channel> (delegates to ChannelStore.scan(ChannelQuery.byNamePrefix(prefix))); reactive parity via ReactiveChannelService.findByNamePrefix(prefix) → Uni<List<Channel>>
│       ├── message/
│       │   ├── Message.java             — PanacheEntity
│       │   ├── MessageType.java         — enum: QUERY|COMMAND|RESPONSE|STATUS|DECLINE|HANDOFF|DONE|FAILURE|EVENT (speech-act taxonomy, see ADR-0005)
│       │   ├── Commitment.java          — PanacheEntity (full obligation lifecycle: OPEN→FULFILLED/DECLINED/FAILED/DELEGATED/EXPIRED)
│       │   ├── CommitmentState.java     — enum: 7 states, isTerminal()
│       │   ├── CommitmentService.java   — state machine: open/acknowledge/fulfill/decline/fail/delegate/expireOverdue
│       │   ├── MessageService.java      — dispatches to Instance<MessageObserver> after messageStore.put(); enforcement gate: paused check, AllowedWritersPolicy ACL, RateLimiter, ObligorTrustPolicy SPI (COMMAND + named target), MessageTypePolicy, LAST_WRITE, fanOut
│       │   ├── DefaultObligorTrustPolicy.java — @DefaultBean impl of ObligorTrustPolicy (interface in api/spi/): returns true when minObligorTrust≤0 (gate disabled), otherwise delegates to TrustGateService; Refs #213
│       │   └── MessageObserverDispatcher.java — package-private static utility: iterates observers, nulls EVENT content, non-fatal per-observer try-catch; shared by MessageService + ReactiveMessageService
│       ├── instance/
│       │   ├── Instance.java            — PanacheEntity
│       │   ├── Capability.java          — PanacheEntity (capability tags)
│       │   └── InstanceService.java
│       ├── data/
│       │   ├── SharedData.java          — PanacheEntity
│       │   ├── ArtefactClaim.java       — PanacheEntity (claim/release lifecycle)
│       │   └── DataService.java
│       ├── ledger/
│       │   ├── MessageLedgerEntry.java              — JPA JOINED subclass of LedgerEntry; records all 9 message types
│       │   ├── MessageLedgerEntryRepository.java    — listEntries (7 filters + correlationId + sort), findAllByCorrelationId, findAncestorChain, findStalledCommands, countByOutcome, findByActorIdInChannel, findEventsSince; findLatestByCorrelationId for write-time causal chain resolution
│       │   ├── MessageReactivePanacheRepo.java      — @Alternative reactive Panache repo
│       │   ├── ReactiveMessageLedgerEntryRepository.java — @Alternative reactive implementation
│       │   ├── LedgerWriteService.java              — record(Channel, Message): writes entry for ALL 9 types; resolves actorId via InstanceActorIdProvider; writes LedgerAttestation on DONE/FAILURE/DECLINE via CommitmentAttestationPolicy; telemetry extracted from EVENT JSON
│       │   ├── DefaultInstanceActorIdProvider.java  — @DefaultBean no-op identity impl of InstanceActorIdProvider (interface in api/spi/); replaced by Claudony's session→persona mapping
│       │   ├── StoredCommitmentAttestationPolicy.java — @DefaultBean impl of CommitmentAttestationPolicy (interface in api/spi/): DONE→SOUND/0.7, FAILURE→FLAGGED/0.6, DECLINE→FLAGGED/0.4; config via casehub.qhorus.attestation.*
│       │   └── ReactiveLedgerWriteService.java      — @Alternative reactive mirror of LedgerWriteService
│       ├── QhorusEntityMapper.java      — @ApplicationScoped CDI bean: toChannelDetail(Channel, long), toTimelineEntry(Message); injects ObjectMapper — shared by QhorusMcpToolsBase and QhorusDashboardService
│       ├── mcp/
│       │   ├── QhorusMcpToolsBase.java  — abstract base: mappers (toLedgerEntryMap, toMessageSummary, etc.), validators; ledger query response records (ObligationChainSummary, CausalChainEntry, StalledObligation, ObligationStats, TelemetrySummary, ToolTelemetry); delegates toChannelDetail/toTimelineEntry to QhorusEntityMapper
│       │   ├── QhorusMcpTools.java      — blocking @Tool methods (~50); @UnlessBuildProperty(casehub.qhorus.reactive.enabled); create_channel now accepts allowed_types as 9th optional param
│       │   └── ReactiveQhorusMcpTools.java — reactive @Tool methods returning Uni<T>; @IfBuildProperty(casehub.qhorus.reactive.enabled); create_channel mirrors allowed_types param; delete_channel (reactive Uni<DeleteChannelResult>), get_instance and get_message (@Blocking)
│       ├── watchdog/
│       │   ├── Watchdog.java            — PanacheEntity (condition-based alert registrations)
│       │   ├── WatchdogEvaluationService.java — condition evaluation logic
│       │   └── WatchdogScheduler.java   — @Scheduled driver (delegates to service)
│       ├── gateway/
│       │   ├── ChannelGateway.java              — registration, fanOut(), inbound normalisation; fires ChannelInitialisedEvent from initChannel(); rebuilds registry from ChannelService.listAll() on @Observes StartupEvent (exception-isolated per channel)
│       │   ├── QhorusChannelBackend.java        — default AgentChannelBackend; registry anchor for qhorus-internal slot; post() is a deliberate no-op (fanOut skips this backend; persistence already happened in dispatch())
│       │   ├── DefaultInboundNormaliser.java    — @DefaultBean, always QUERY, human: sender prefix; passes correlationId through; nulls inReplyTo/artefactRefs/target
│       │   ├── InProcessMessageBus.java         — @DefaultBean MessageObserver: fires CDI Event<MessageReceivedEvent> async; LOCAL scope; fast path for embedded harnesses
│       │   └── DuplicateParticipatingBackendException.java
│       └── api/
│           ├── AgentCardResource.java   — GET /.well-known/agent-card.json (@UnlessBuildProperty)
│           ├── A2AResource.java         — POST /a2a/message:send, GET /a2a/tasks/{id}; delegates to A2AChannelBackend; getTask() uses CommitmentStore for durable state (@UnlessBuildProperty)
│           ├── A2AActorResolver.java    — 6-step sender identity resolution chain for A2A role:"user" (header, Instance registry, agentCardUrl, persona format, system, default HUMAN)
│           ├── A2AChannelBackend.java   — ChannelBackend "a2a"; protocol bridge registered via ensureRegistered(); receive() routes via QhorusMcpTools; post() = fanOut hook (#147)
│           ├── A2ATaskState.java        — package-private; maps CommitmentState and MessageType to A2A task state strings; used by A2AResource and ReactiveA2AResource
│           ├── ReactiveAgentCardResource.java — reactive Uni<Response> agent card (@IfBuildProperty)
│           └── ReactiveA2AResource.java       — reactive A2A endpoints (@IfBuildProperty)
├── deployment/                          — Extension deployment (build-time) module
│   └── src/main/java/io/casehub/qhorus/deployment/
│       ├── QhorusBuildTimeConfig.java    — @ConfigRoot(BUILD_TIME) declaring casehub.qhorus.reactive.enabled; makes @IfBuildProperty reliable for this custom property
│       └── QhorusProcessor.java         — @BuildStep: FeatureBuildItem only; reactive bean selection handled by @IfBuildProperty(casehub.qhorus.reactive.enabled=true) on reactive beans and @UnlessBuildProperty(casehub.qhorus.reactive.enabled=true, enableIfMissing=true) on conflicting blocking beans
├── testing/                             — InMemory*Store + InMemoryReactive*Store (@Alternative @Priority(1)) for consumer unit tests
├── examples/
│   ├── examples/type-system/            — Fast regression tests for the 9-type taxonomy; runs in CI with no model (MessageTaxonomyTest)
│   ├── examples/normative-layout/       — Deterministic 3-channel NormativeChannelLayout tests (CI, no LLM); canonical Layer 1 reference
│   └── examples/agent-communication/    — Real LLM agent examples (Jlama); 3 enterprise scenarios + accuracy baseline; activate with -Pwith-llm-examples
├── docs/specs/                          — Design specs
└── .github/                             — CI workflows
```

---

## Build and Test

```bash
# Build all modules
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install

# Run tests (runtime module)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test

# Run specific test
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=ClassName -pl runtime

# Native image build (requires GraalVM)
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
  mvn package -Pnative -DskipTests
```

**Use `mvn` not `./mvnw`** — maven wrapper not configured on this machine.

**After API visibility changes, always run `mvn install` from the project root** — `mvn test` scoped to a child module (e.g. `runtime/`) does not compile sibling `examples/` modules that depend on it. Compile errors in those modules are invisible until the full build runs.

**Testing conventions** — platform-wide Quarkus patterns in `../garden/docs/protocols/universal/` (`@TestTransaction` scope, CDI alternative stores, scheduler isolation, `ManagedExecutor`):
- Non-`@Tool` public methods sharing a name with a `@Tool`-annotated method in `QhorusMcpTools` or `ReactiveQhorusMcpTools` cause the `@Tool` to be silently dropped from the MCP registry with no error or warning. `ToolOverloadDiscoverabilityTest` (pure reflection, no Quarkus) guards against regressions — it fails immediately if any public non-`@Tool` overload shares a name with a `@Tool` method. Never add `public` to convenience overloads of `@Tool` methods; use package-private visibility. Refs #129.
- `LedgerWriteService.record()` uses `REQUIRES_NEW` — ledger entries from prior tests' `@BeforeEach` runs PERSIST after rollback. Always set up channels and send scenario messages inside the `@Test` method body to avoid stale ledger entries interfering with queries in subsequent tests.
- Optional modules (`a2a`, `watchdog`) require a `@TestProfile` that sets `casehub.qhorus.<module>.enabled=true`. Any `@TestProfile` that causes Quarkus to restart must also include the full `quarkus.datasource.qhorus.*` block (db-kind, jdbc.url, username, password) plus `quarkus.datasource.qhorus.reactive=false` and `quarkus.hibernate-orm.qhorus.database.generation=drop-and-create` in `getConfigOverrides()` — Quarkus restarts do not inherit test `application.properties` from prior context. Do NOT add `casehub.qhorus.reactive.enabled` to H2/JDBC profiles — that property is BUILD_TIME only and its presence in `application.properties` triggers a SmallRye Config runtime validation error (`SRCFG00050`). Reactive tests (all `@Disabled`, require Docker) set it via `ReactiveTestProfile.getConfigOverrides()`.
- `RateLimiter` is an `@ApplicationScoped` in-memory bean — its state does NOT roll back with `@TestTransaction`. Use unique channel names per test to avoid cross-test interference.
- `WatchdogScheduler` runs in its own thread/transaction and cannot see uncommitted test data. Tests calling `watchdogService.evaluateAll()` directly with the scheduler active must use `@TestTransaction` to prevent the scheduler from picking up in-flight test data and firing spurious side effects.
- `MessageStore.distinctSendersByChannel(channelId, MessageType.EVENT)` — the second parameter is the **excluded** type, not an included type. Passing `EVENT` returns senders of all non-EVENT messages; EVENTs are excluded. BARRIER_STUCK test setup must use `MessageType.STATUS` (or any non-EVENT type) as the barrier contribution message — EVENTs from agents do not count as contributions. `JpaMessageStore` query: `WHERE channelId = ?1 AND messageType != ?2`.
- `check_messages` excludes `EVENT` messages by default — use `check_messages(include_events=true, reader_instance_id=<id>)` with a `register(read_only=true)` observer instance to assert EVENT delivery in tests. `read_observer_events`, `register_observer`, and `deregister_observer` were removed in #121-G.
- `MessageService.dispatch(MessageDispatch)` is the single enforcement gate for all channel writes: paused check, `AllowedWritersPolicy` ACL check, `RateLimiter`, `ObligorTrustPolicy` SPI trust gate (COMMAND + named non-prefixed target; `DefaultObligorTrustPolicy` reads `casehub.qhorus.commitment.min-obligor-trust`; gate skipped when ≤ 0), LAST_WRITE overwrite semantics, `ChannelGateway.fanOut()`. Tests calling `messageService.dispatch()` directly get all enforcement. `ActorType` is part of `MessageDispatch` — use `ActorTypeResolver.resolve(sender)` or an explicit constant. The `WatchdogEvaluationService` uses sender `"system:watchdog"` and `ActorType.SYSTEM` — tests expecting watchdog alert messages should assert sender `"system:watchdog"`. To test the SPI override pattern use `@InjectMock ObligorTrustPolicy` (quarkus-junit5-mockito) — no profile restart needed.
- `MessageTypePolicy` is injected into both `QhorusMcpTools.sendMessage()` (client-side early rejection) and `MessageService.dispatch()` (server-side enforcement). Tests calling `messageService.dispatch()` directly on a channel with `allowedTypes` set will hit the server-side check and receive `MessageTypeViolationException`. The default `StoredMessageTypePolicy` reads `channel.allowedTypes` at call time — no caching.
- `LedgerWriteService.record(MessageDispatch, ...)` is called for **all 9 message types** via `MessageService.dispatch()` — not just EVENT. Every `dispatch()` call produces a `MessageLedgerEntry` (except LAST_WRITE overwrites, which skip the ledger write — tracked in #195). EVENT entries extract telemetry from JSON content (`tool_name`, `duration_ms`, `token_count` — all nullable); malformed or missing fields still produce an entry. Tests asserting ledger entries do NOT need structured JSON payloads; any content works.
- `LedgerWriteService` does NOT query `CommitmentStore` — attestation verdict is derived from `MessageType` directly via `CommitmentAttestationPolicy`. The CommitmentStore query inside `REQUIRES_NEW` would see stale OPEN state (outer tx's update not yet committed). Integration tests for attestation must use `@TestTransaction` + `QhorusMcpTools.sendMessage()` (not `messageService.dispatch()` directly, which calls `ledgerWriteService.record()` but bypasses the artefact lifecycle and MCP-specific enrichment in `QhorusMcpTools`).
- `*Store` interfaces are the persistence seam — six interfaces: `ChannelStore`, `MessageStore`, `InstanceStore`, `DataStore`, `WatchdogStore`, `CommitmentStore` (full obligation lifecycle: OPEN→FULFILLED/DECLINED/FAILED/DELEGATED/EXPIRED). `PendingReplyStore` was deleted — replaced by `CommitmentStore`. Services inject stores, not Panache entity statics. Integration tests (`@QuarkusTest`) inject `*Store` directly; unit tests use `InMemory*Store` from `casehub-qhorus-testing`.
- `CommitmentStore.findOpenByObligor(String obligor)` — cross-channel query returning all OPEN commitments for a given obligor (actor ID); JPA `@Override` required for `InMemoryCommitmentStore` (contract test verifies it). Used by `casehub-engine-actor-state` to populate the obligations slice of the actor state view.
- `ChannelStore.findByIds(Collection<UUID> ids)` — batch lookup emitting a single `IN(?)` query rather than N individual fetches. Used when a set of channel IDs is already known (e.g. from `CommitmentStore.findOpenByObligor`) and full `Channel` records are needed.
- `CommitmentService` unit tests live in `testing/src/test/...` (not `runtime/src/test/...`) to avoid a module cycle — the testing module provides `InMemoryCommitmentStore` used for CDI-free wiring. `service.store = store` sets the dependency directly.
- `CommitmentStoreContractTest` (abstract, in `testing/src/test/`) has two runners: `InMemoryCommitmentStoreTest` (blocking) and `InMemoryReactiveCommitmentStoreTest` (reactive, wraps with `.await().indefinitely()`).
- `quarkus.http.test-port=0` is set in test `application.properties` to use a random port — avoids conflicts when other Quarkus apps (e.g. Claudony) are running on 8081. Requires `mvn clean` to take effect after the property is added.
- Tests run against a **named 'qhorus' datasource** (`quarkus.datasource.qhorus.*`) — the Qhorus named PU. A default datasource is also configured in test properties to satisfy casehub-ledger library beans that inject `@Default EntityManager` (library code; cannot be changed). Both PUs require schema generation: `quarkus.hibernate-orm.database.generation=drop-and-create` (default PU) and `quarkus.hibernate-orm.qhorus.database.generation=drop-and-create` (qhorus PU). `quarkus.datasource.qhorus.reactive=false` suppresses `FastBootHibernateReactivePersistenceProvider` for the named PU.
- `Reactive*Store` / `InMemoryReactive*Store` follow the same seam as blocking stores. Unit tests use `InMemoryReactive*Store` from `casehub-qhorus-testing` via direct instantiation (no CDI) and `.await().indefinitely()` to unwrap. `ReactiveJpa*Store` integration tests use `@QuarkusTest @TestProfile @RunOnVertxContext UniAsserter` with `Panache.withTransaction("qhorus", ...)` wrapping mutations — always use the named-PU form; bare `Panache.withTransaction(() -> ...)` silently routes to the default PU and fails in consumer apps (e.g. Claudony) that configure only the named qhorus datasource.
- Reactive JPA integration tests require a native async driver — H2's Hibernate Reactive support is synchronous underneath and does not properly integrate with the Vert.x context management that `Panache.withTransaction()` depends on. Use the named `%reactive-pg` config profile (activated by `ReactiveTestProfile.getConfigProfile()` returning `"reactive-pg"`) with Podman ≥ 4 GB and `quarkus-reactive-pg-client` — Quarkus DevServices starts `postgres:17-alpine` automatically.
- `@IfBuildProperty` and `@UnlessBuildProperty` are BUILD-TIME annotations — setting `casehub.qhorus.reactive.enabled=true` in a `QuarkusTestProfile.getConfigOverrides()` DOES activate the reactive stack (when the profile restarts the Quarkus context, the override is applied at augmentation time for that context). The property must NOT appear in `application.properties` — BUILD_TIME-only properties there cause a SmallRye Config `SRCFG00050` runtime validation error. H2/JDBC tests omit the property entirely; `@WithDefault("false")` ensures the blocking stack is active.
- `ReactiveTestProfile` (in `runtime/src/test/.../service/`) activates the reactive stack for integration tests: `getConfigProfile()` returns `"reactive-pg"` (activates `%reactive-pg` block in `application.properties`); `getConfigOverrides()` supplies `casehub.qhorus.reactive.enabled=true`. The `%reactive-pg` profile enables PostgreSQL DevServices (`postgres:17-alpine`) on the named `qhorus` datasource with both `reactive=true` and `jdbc=true`, runs Flyway migrations, and stubs the default datasource with H2 for casehub-ledger beans. `quarkus.arc.selected-alternatives` is NOT needed.
- `ReactiveMessageServiceTest` is no longer `@Disabled` — runs with `ReactiveTestProfile` against PostgreSQL DevServices. Requires Podman ≥ 4 GB. Other `Reactive*ServiceTest` classes remain `@Disabled` until their own DevServices setup is added.
- Entity setup in `@QuarkusTest` for reactive services must use `QuarkusTransaction.requiringNew().run(() -> blockingService.create(...))` — not `Panache.withTransaction("qhorus", () -> ...)`. JUnit test threads have no Vert.x context; calling the reactive Panache API from them throws "No current Vert.x context found". The JTA `requiringNew()` approach commits the entity before the reactive service's own session starts (PP-20260529-ca7b89).
- `Panache.withTransaction("qhorus", ...)` nested inside another `withTransaction("qhorus", ...)` **joins** the enclosing transaction — it does not create `REQUIRES_NEW` semantics (Hibernate Reactive has no equivalent). The ledger entry write in `ReactiveLedgerWriteService.record()` is therefore atomic with the message insert. This is intentional; the blocking `LedgerWriteService` uses JTA `REQUIRES_NEW` (survives outer failures), the reactive path does not.
- Read-only reactive service methods (e.g. `findById`, `pollAfter`) must be wrapped in `Panache.withSession("qhorus", ...)` even without mutations — otherwise "No active session" is thrown when called outside an existing session context.
- Store contract tests use abstract base classes (`*StoreContractTest` in `testing/src/test/.../contract/`) with two concrete runners each: blocking (`InMemory*StoreTest`) and reactive (`InMemoryReactive*StoreTest`). The reactive runner wraps every factory method with `.await().indefinitely()`. Assertion code is identical across both stacks (inherited from base).
- When working in a git worktree, always use `mvn -f /absolute/path/to/worktree/pom.xml` — do not rely on `cd` since shell CWD resets between Bash tool calls.
- `examples/type-system/` runs in CI (no model, no Jlama). `examples/agent-communication/` is behind `-Pwith-llm-examples` — requires local Jlama fixes installed from `~/claude/quarkus-langchain4j` and model in `~/.jlama/` (~700MB, first run only). See `examples/agent-communication/README.md`.
- `RecordingChannelBackend` in `casehub-qhorus-testing` records `post()`, `open()`, `close()` calls; use in gateway integration and E2E tests. Cannot be used in `runtime` unit tests (would create a module cycle — use an inline helper class instead).
- `@TestTransaction` in gateway tests that persist inbound messages — prevents cross-test data leakage (search results from one test bleeding into another).
- `@Tool` methods annotated with `@WrapBusinessError` wrap `IllegalArgumentException` and `IllegalStateException` into `ToolCallException` at the CDI proxy boundary. Tests asserting on error paths must use `assertThrows(ToolCallException.class, ...)` and inspect `getCause()` — not the original exception type.
- `delete_channel` with `force=true` calls `commitmentStore.deleteAll(channelId)`, then `messageStore.deleteAll(channelId)`, before `channelStore.delete(channelId)` — required because `fk_commitment_channel` and `fk_message_channel` have no CASCADE. When testing `delete_channel` in integration tests, messages and commitments must be committed (in a separate `QuarkusTransaction.requiringNew()` block) before calling delete, because delete runs in its own transaction.
- `delete_channel` with `caller_instance_id` is required when the channel has `admin_instances` set. In tests, either omit `admin_instances` on the channel or pass a valid admin ID as `caller_instance_id` — otherwise the call returns a tool error string, not an exception.
- `send_message` with `artefact_refs` auto-claims each artefact on behalf of the sender (if the sender is a registered instance). Sending DONE/FAILURE/DECLINE on the same `correlationId` auto-releases. Tests asserting GC-eligibility (`isGcEligible`) must account for this: artefacts attached to in-flight COMMANDs are not GC-eligible until resolved.
- New chunked upload tools: `begin_artefact(key, created_by)` → `append_chunk(key, content)` → `finalize_artefact(key)`. `share_artefact` is now single-shot only (no `append`/`last_chunk` params). `share_data`/`get_shared_data`/`list_shared_data` renamed to `share_artefact`/`get_artefact`/`list_artefacts`.
- `tools.sendMessage()` with type `"handoff"` requires a non-null `target` argument (e.g. `"role:specialist"`). Passing `null` throws `IllegalArgumentException`. All other message types accept null target. Tests using HANDOFF must supply a valid target string.
- After a HANDOFF message, `CommitmentService.findByCorrelationId` returns the newly-created child OPEN commitment (for the delegate), not the parent DELEGATED commitment. `A2AResource.getTask()` accounts for this with a `commitment.state != CommitmentState.OPEN` guard, falling through to `A2ATaskState.fromMessageHistory()` for OPEN commitments. Integration tests for the DELEGATED/HANDOFF path exercise `fromMessageHistory(HANDOFF → "working")`, not `fromCommitmentState(DELEGATED)`.

- `ChannelGateway.receiveHumanMessage()` dispatches **twice** per call: once for the normalised content message, once for a normaliser telemetry EVENT (`"system:normaliser"` sender). Integration tests in `connector-backend` that call `receiveHumanMessage()` indirectly (via `gateway.fanOut()` or `backend.onInboundMessage()`) and assert `verify(messageService, times(N))` must use `N×2` — one message = two dispatches.
- `PrometheusMeterRegistry` is `@ApplicationScoped` and monotonic — counters accumulate across all tests in a `@QuarkusTest` class. Never assert absolute counter values; instead capture `before = counter.count()` before the action and assert `isEqualTo(before + 1.0)` or `isGreaterThan(before)` after it. `SimpleMeterRegistry` (used in CDI-free unit tests) starts fresh per instance, so absolute assertions are safe there.
- `MessageObserver` implementations may use any normal CDI scope. `MessageObserverDispatcher` calls `observers.handles()` and closes each `Instance.Handle` in a `finally` block — `@Dependent` beans are correctly destroyed after each dispatch. The former `@ApplicationScoped`-only constraint (PP-20260518-837246) is removed as of qhorus#167.
- Flyway migrations are in `db/qhorus/migration/` — outside the `db/migration/` namespace entirely, preventing Flyway's recursive classpath scan on the default datasource from picking up qhorus migrations when casehub-work and casehub-qhorus are on the same classpath. `quarkus.flyway.qhorus.locations=classpath:db/qhorus/migration` is set in `application.properties`. Tests using `drop-and-create` are unaffected; this only matters for migration file placement and Flyway config. Next domain migration: V16 (V10 = commitment table; V12 = channel_allowed_types + message_deadline columns; V13 = message.commitment_id; V14 = channel_connector_binding; V15 = channel.auto_created; V2000 = ledger subclass join — ledger occupies V1000–V1999, consumer joins start at V2000). Ledger migrations included via `classpath:db/ledger/migration` (#179).
- `FlywayMigrationSchemaTest` (plain Java, no Quarkus) validates that migrations produce the correct schema — bypassing the `drop-and-create` path used by `@QuarkusTest`. Pattern: create cross-module dependency tables (e.g. `ledger_entry`) before running Flyway, then use `baselineOnMigrate(true)` + `baselineVersion("0")` (must be `"0"`, not default `"1"`, so V1 runs rather than being skipped as the baseline). Use H2 in-memory with `MODE=PostgreSQL` and a unique DB name per JVM run (e.g. `System.nanoTime()` suffix) to prevent cross-run state sharing. Close `ResultSet` from `getMetaData().getTables()` explicitly in try-with-resources — it is not closed when the `Connection` closes.
- `StubReactiveLedgerEntryRepository` in `runtime/src/test/java/.../runtime/ledger/` satisfies the `ReactiveLedgerEntryRepository` CDI dependency when `quarkus.datasource.qhorus.reactive=false`. Without it, casehub-ledger beans (`LedgerVerificationService`, `KeyRotationService`) that inject the reactive repo cause all `@QuarkusTest` tests to fail at CDI discovery with `UnsatisfiedResolutionException`. Any module adding casehub-ledger as a dependency and running non-reactive `@QuarkusTest` tests needs this stub or an equivalent. See PP-20260519-1f5e2c.
- `StubReactiveCommitmentStore` in `runtime/src/test/java/.../runtime/message/` satisfies the `ReactiveCommitmentStore` CDI dependency when the reactive stack is enabled (`casehub.qhorus.reactive.enabled=true`) but no real reactive JPA datasource is configured (H2/JDBC profiles). Without it, `ReactiveCommitmentService` (which injects `ReactiveCommitmentStore`) causes `UnsatisfiedResolutionException` during CDI discovery. The stub is a `@DefaultBean @ApplicationScoped` that throws `UnsupportedOperationException` on all methods — satisfies the injection point without requiring PostgreSQL.
- `StubReactiveMessageStore` in `runtime/src/test/java/.../runtime/message/` satisfies the `ReactiveMessageStore` CDI dependency when the reactive stack is enabled but no reactive JPA datasource is configured. Same pattern as `StubReactiveCommitmentStore`: `@DefaultBean @ApplicationScoped`, all methods throw `UnsupportedOperationException`. Displaced by `ReactiveJpaMessageStore` when PostgreSQL DevServices are active.
- `ProjectionService` integration tests (`ProjectionServiceIT`) write messages via `messageStore.put()` directly — not `MessageService.dispatch()`. Direct put bypasses the ledger write (`LedgerWriteService.record()` in `REQUIRES_NEW`) and the enforcement gate, keeping projection tests focused on fold correctness. Use `dispatch()` only when testing that enforcement interacts with projection.
- `ProjectionResult<S>.isEmpty()` is `true` when `lastMessageId == null` (channel was empty). Passing an empty result to the incremental `project()` overload performs a full scan from `identity()` regardless of `previous.state()` — the service enforces this invariant, so manually constructed `ProjectionResult` instances with non-null state and null cursor are silently corrected to `identity()`.
- `ProjectionRegistry` is an `@ApplicationScoped` CDI bean that collects all `@Any Instance<RenderableProjection<?>>` at startup and validates: (1) no `projectionName()` returns null or blank — `IllegalStateException` at CDI bootstrap; (2) no two beans share a name — `IllegalStateException` at CDI bootstrap. Unit tests use the package-private `ProjectionRegistry(List<? extends RenderableProjection<?>>)` constructor (same package as registry: `io.casehub.qhorus.runtime.message`). The `stubs(String... names)` helper is needed in tests because Java's type inference fails to unify `List.of(wildcardBean...)` with `List<? extends RenderableProjection<?>>` — collect into an explicit `ArrayList<RenderableProjection<?>>` first. Refs qhorus#232.
- `resolveChannel(String channel)` in `QhorusMcpToolsBase` uses two-phase UUID parsing (`tryParseUuid` returning null on failure) to distinguish UUID strings from channel names — never inspects exception message text to control flow. For UUID paths, channel existence is validated via `ChannelService.findById()` before projecting; a valid-format but non-existent UUID would otherwise silently return `render(identity())`. Refs qhorus#232.

**Format check:** CI runs `mvn -Dno-format` to skip the enforced code formatting. Run `mvn` locally to apply formatting (via the formatter plugin in the Maven parent).

---

## Java and GraalVM on This Machine

```bash
# Java 26 (Oracle, system default) — use for dev and tests
JAVA_HOME=$(/usr/libexec/java_home -v 26)

# GraalVM 25 — use for native image builds only
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home
```

**Native gotcha:** `quarkus-mcp-server` 1.11.1 fixed sampling and elicitation in native image — they were silently broken in earlier versions. Always use ≥ 1.11.1.

---

## Design Document

`docs/specs/2026-04-13-qhorus-design.md` is the primary design spec. It incorporates research from A2A, AutoGen, LangGraph, OpenAI Swarm, Letta, and CrewAI.

---

## Project Artifacts

Paths that are project content (not workspace noise). Skills use this to avoid
filtering or dropping commits that touch these paths.

| Path | What it is |
|------|------------|
| `CLAUDE.md` | Project conventions (build, test, naming) |
| `docs/adr/` | Architecture decision records |
| `docs/DESIGN.md` | Design document |

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** casehubio/qhorus

**Automatic behaviours (Claude follows these at all times in this project):**
- **Before implementation begins** — check if an active issue exists. If not, run issue-workflow Phase 1 before writing any code.
- **Before any commit** — run issue-workflow Phase 3 to confirm issue linkage.
- **All commits should reference an issue** — `Refs #N` (ongoing) or `Closes #N` (done).
- **Code review fix commits** — when committing fixes found during a code review (superpowers:requesting-code-review or java-code-review), create or reuse an issue for that review work **before** committing. Use `Refs #N` pointing at the relevant epic even if it is already closed. Do not commit review fixes without an issue reference.

---

## Writing Style Guide

**The writing style guide at `~/claude-workspace/writing-styles/blog-technical.md` is mandatory for all blog and diary entries.** Load it in full before drafting. Complete the pre-draft voice classification (I / we / Claude-named) before generating any prose. Do not show a draft without verifying it against the style guide.

**Blog directory:** `blog/`

## Ecosystem Conventions

All casehubio projects align on these conventions:

**Quarkus version:** All projects use `3.32.2`. When bumping, bump all projects together.

**GitHub Packages — dependency resolution:** Add to `pom.xml` `<repositories>`:
```xml
<repository>
  <id>github</id>
  <url>https://maven.pkg.github.com/casehubio/*</url>
  <snapshots><enabled>true</enabled></snapshots>
</repository>
```
CI must use `server-id: github` + `GITHUB_TOKEN` in `actions/setup-java`.

**Cross-project SNAPSHOT versions:** `casehub-ledger` and `casehub-work` modules are `0.2-SNAPSHOT` resolved from GitHub Packages. Declare in `pom.xml` properties and `<dependencyManagement>` — no hardcoded versions in submodule poms.

