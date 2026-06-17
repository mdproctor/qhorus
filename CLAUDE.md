# qhorus Workspace

**Name:** casehub-qhorus
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
| design     | project     | journal file lives in workspace design/; DESIGN.md merge target is project docs/DESIGN.md |
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
- **Channel gateway** — backend-agnostic fan-out via `ChannelGateway`; backends implement `AgentChannelBackend`, `HumanParticipatingChannelBackend`, or `HumanObserverChannelBackend` from `casehub-qhorus-api`. New MCP tools: `list_backends(channel)`, `deregister_backend(channel, backend_id)` (cannot remove `qhorus-internal`), `register_backend(channel, backend_id, backend_type)` (associates an existing CDI bean by its `backendId()`)

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
│       │   └── ChannelDetail.java       — DTO record: channel metadata (id, name, semantic, counts, limits, allowedTypes, deniedTypes)
│       ├── instance/
│       │   └── InstanceInfo.java        — DTO record: instance metadata (instanceId, description, status, capabilities, lastSeen, readOnly)
│       ├── gateway/
│       │   ├── ChannelBackend.java, AgentChannelBackend.java
│       │   ├── HumanParticipatingChannelBackend.java, HumanObserverChannelBackend.java
│       │   ├── InboundNormaliser.java, Senders.java (HUMAN = "human")
│       │   ├── ChannelRef.java, OutboundMessage.java, InboundHumanMessage.java (externalSenderId, content, receivedAt, metadata, correlationId — nullable), ObserverSignal.java, NormalisedMessage.java (type, content, senderInstanceId, correlationId, inReplyTo, artefactRefs, target — last 4 nullable)
│       │   ├── MessageObserver.java — @FunctionalInterface SPI: onMessage(MessageReceivedEvent); scope() default=LOCAL; Scope{LOCAL,CLUSTER}; any normal CDI scope valid (@ApplicationScoped, @RequestScoped, etc.); dispatcher uses @Any Instance<MessageObserver> — implementations with additional CDI qualifiers (e.g. @Named, custom qualifiers) are discovered correctly; dispatcher closes each Instance.Handle in finally
│       │   ├── MessageReceivedEvent.java — record: channelName, channelId, messageType, senderId, correlationId (nullable), content (null for EVENT — Builder.build() enforces at call site per PP-20260608-054090; casehub-ledger#126 will lift this constraint once telemetry is decoupled from content)
│       │   └── ChannelInitialisedEvent.java — record: channelId, channelName; fired by ChannelGateway.initChannel() on channel creation and startup recovery; observed by external backends to re-register without their own restart logic
│       ├── message/
│       │   ├── MessageResult.java       — DTO record: sent-message metadata (messageId, channelName, sender, type, correlationId, inReplyTo, artefactRefs, target)
│       │   ├── MessageType.java         — (existing, unchanged)
│       │   ├── MessageTypeViolationException.java — (existing, unchanged)
│       │   ├── CommitmentDeclinedEvent.java — CDI event record fired by CommitmentService.decline() on DECLINED transition; carries commitmentId, correlationId, channelId, obligor, requester; Refs #251
│       │   ├── CommitmentExpiredEvent.java — CDI event record fired by CommitmentService.expireOverdue() once per expired commitment; carries commitmentId, correlationId, channelId, obligor (nullable), requester, expiresAt (stall duration signal); Refs #281
│       │   └── CommitmentState.java     — (existing, unchanged)
│       └── spi/                         — consumer-facing SPI interfaces (per consumer-spi-placement protocol); @DefaultBean impls in runtime/
│           ├── CommitmentAttestationPolicy.java — @FunctionalInterface SPI: determines LedgerAttestation for DONE/FAILURE/DECLINE; AttestationOutcome record; Refs #123
│           ├── InstanceActorIdProvider.java     — @FunctionalInterface SPI: maps instanceId → ledger actorId; Refs #124
│           ├── ObligorTrustPolicy.java          — @FunctionalInterface SPI: permits(ObligorTrustContext) — called for COMMAND + named non-prefixed target; Refs #213
│           └── ObligorTrustContext.java         — record: obligorId, channelId (UUID), channelName — passed to ObligorTrustPolicy.permits()
├── runtime/                             — Extension runtime module
│   └── src/main/java/io/casehub/qhorus/runtime/
│       ├── config/QhorusConfig.java     — @ConfigMapping(prefix = "casehub.qhorus"); A2a.SseSettings: heartbeat-interval-seconds (default 15s), max-duration-seconds (default 1800s)
│       ├── channel/
│       │   ├── Channel.java             — PanacheEntity; `allowedTypes` TEXT nullable — null = open; `deniedTypes` TEXT nullable — null = no denial; comma-separated MessageType names evaluated by MessageTypePolicy SPI — COMMAND/QUERY violations are hard-enforced (throw); all other violations produce advisories in DispatchResult.advisories(); denial-first within COMMAND/QUERY
│       │   ├── ChannelSemantic.java     — enum: APPEND|COLLECT|BARRIER|EPHEMERAL|LAST_WRITE
│       │   ├── FindOrCreateResult.java  — record(Channel channel, boolean wasCreated); returned by ChannelService.findOrCreateWithBinding() to distinguish create-new (wasCreated=true) from find-existing (wasCreated=false); callers must not increment creation metrics when wasCreated=false. Refs #248.
│       │   ├── AllowedWritersPolicy.java — @ApplicationScoped ACL check for channel write access; used by MessageService.dispatch(); unified supplier returns instance capability tags + synthetic role:actorType tag
│       │   ├── RateLimiter.java         — @ApplicationScoped in-memory sliding-window rate limiter; used by MessageService.dispatch()
│       │   └── ChannelService.java      — includes findByNamePrefix(prefix) → List<Channel>; findOrCreateWithBinding(ChannelCreateRequest) → FindOrCreateResult (REQUIRES_NEW); setTypeConstraints(UUID, Set<MessageType> allowedTypes, Set<MessageType> deniedTypes) — full-replacement with typed Sets, validates overlap, prospective only; setRateLimits/setAllowedWriters/setAdminInstances/pause/resume/delete all take UUID (not name) since #252; reactive parity via ReactiveChannelService; create(ChannelCreateRequest) calls channelGateway.initChannel() after persist — ChannelBackend implementations self-register without MCP tool involvement; Refs #254
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
│       ├── identity/
│       │   ├── QhorusSystemCurrentPrincipal.java  — @ApplicationScoped @QhorusSystem; isCrossTenantAdmin()=true; used by CrossTenantProducer for background/scheduled contexts; not a @DefaultBean
│       │   ├── CrossTenantProducer.java            — produces @CrossTenant-qualified stores; guards each with isCrossTenantAdmin() assertion; used by WatchdogEvaluationService and ChannelGateway startup
│       │   ├── InboundTenancyContext.java          — @RequestScoped holder populated by TenancyContextFilter from X-Tenancy-ID header; null/blank → DEFAULT_TENANT_ID; Refs #265
│       │   ├── TenancyContextFilter.java           — @Provider @PreMatching @Priority(100) @ApplicationScoped JAX-RS filter; reads X-Tenancy-ID, calls ctx.set(); runs for ALL HTTP requests; Refs #265
│       │   └── QhorusInboundCurrentPrincipal.java  — @ApplicationScoped @DefaultBean CurrentPrincipal; reads from InboundTenancyContext; ContextNotActiveException catch → DEFAULT_TENANT_ID (background-thread safety); actorId()="anonymous"; displaced by any @Alternative (e.g. FixedCurrentPrincipal in tests, OidcCurrentPrincipal @Priority(100) in OIDC deployments); X-Tenancy-ID is NOT a security boundary; Refs #265, #269
│       ├── ledger/
│       │   ├── MessageLedgerEntry.java              — JPA JOINED subclass of LedgerEntry; records all 9 message types
│       │   ├── MessageLedgerEntryRepository.java    — qhorus-scoped queries only (FROM MessageLedgerEntry); does NOT implement LedgerEntryRepository; no @Priority; all query methods except findByMessageId and findByMessageIds accept String tenancyId (null → DEFAULT_TENANT_ID); methods: listEntries (7-param 6-param-compat + 9-param; both gain tenancyId), findAllByCorrelationId, findAncestorChain, findStalledCommands, countByOutcome, findByActorIdInChannel, findEventsSince, findLatestByCorrelationId, findEarliestWithSubjectByCorrelationId, findByCorrelationIdAcrossChannels (all gain tenancyId); findByMessageId and findByMessageIds unchanged (PK-based); Refs #253, #262, #263
│       │   ├── QhorusSequenceAllocator.java         — package-private @ApplicationScoped; MERGE INTO ledger_subject_sequence in @REQUIRES_NEW — commits immediately so concurrent callers see the allocated row before entering their own MERGE; H2 does not serialise concurrent inserts at the DB level; Refs #256
│       │   ├── QhorusLedgerEntryRepository.java     — @ApplicationScoped implements LedgerEntryRepository (DEFAULT CDI bean — not @Alternative); uses QhorusSequenceAllocator for atomic sequence, actorId tokenisation via ActorIdentityProvider, Merkle hash chain + frontier update when hash-chain.enabled=true; null tenancyId → DEFAULT_TENANT_ID; all query methods delegate to @LedgerPersistenceUnit EntityManager with tenancy filtering; synchronized save() holds lock until QhorusSequenceAllocator REQUIRES_NEW commits; Refs #255, #256
│       │   ├── QhorusLedgerMerkleFrontierRepository.java — @ApplicationScoped thin subclass of @Alternative JpaLedgerMerkleFrontierRepository; provides DEFAULT CDI bean for LedgerMerkleFrontierRepository without quarkus.arc.selected-alternatives; injected by QhorusLedgerEntryRepository for frontier read/replace; Refs #255
│       │   ├── ReactiveLedgerEntryJpaRepository.java — @IfBuildProperty implements ReactiveLedgerEntryRepository; cross-dtype reactive queries via repo.getSession() raw JPQL; injects ActorIdentityProvider, LedgerConfig, LedgerMerklePublisher; save() now: MERGE sequence (REQUIRES_NEW equivalent via chained Uni steps + session.flush()), actorId tokenisation before leafHash, digest computation, session.persist, Merkle frontier update via createMutationQuery (JPQL DELETE); Refs #253, #256
│       │   ├── MessageReactivePanacheRepo.java      — @Alternative reactive Panache repo (typed to MessageLedgerEntry — used for session access only by reactive repos)
│       │   ├── ReactiveMessageLedgerEntryRepository.java — @IfBuildProperty; qhorus-scoped reactive queries only; does NOT implement ReactiveLedgerEntryRepository; methods: findByChannelId(channelId, tenancyId), findLatestByCorrelationId(channelId, corrId, tenancyId), findEarliestWithSubjectByCorrelationId(corrId, tenancyId) (all tenant-scoped), findByMessageId(messageId) (PK — unchanged); Refs #253, #263
│       │   ├── LedgerWriteService.java              — record(Channel, Message): writes entry for ALL 9 types; injects LedgerEntryRepository ledger (cross-dtype: save, findEntryById, saveAttestation; sequence now assigned by QhorusLedgerEntryRepository.save() — findLatestBySubjectId removed) + MessageLedgerEntryRepository messageRepo (findByMessageId, findEarliestWithSubjectByCorrelationId); instanceof guard in writeAttestation(); Refs #253, #255, #256
│       │   ├── DefaultInstanceActorIdProvider.java  — @DefaultBean no-op identity impl of InstanceActorIdProvider (interface in api/spi/); replaced by Claudony's session→persona mapping
│       │   ├── StoredCommitmentAttestationPolicy.java — @DefaultBean impl of CommitmentAttestationPolicy (interface in api/spi/): DONE→SOUND/0.7, FAILURE→FLAGGED/0.6, DECLINE→FLAGGED/0.4; config via casehub.qhorus.attestation.*
│       │   └── ReactiveLedgerWriteService.java      — reactive mirror of LedgerWriteService; two injections: ReactiveLedgerEntryRepository ledger + ReactiveMessageLedgerEntryRepository messageRepo; sequence assignment removed — now delegated to ReactiveLedgerEntryJpaRepository.save(); Refs #253, #256
│       ├── QhorusEntityMapper.java      — @ApplicationScoped CDI bean: toChannelDetail(Channel, long), toTimelineEntry(Message); injects ObjectMapper — shared by QhorusMcpToolsBase and QhorusDashboardService
│       ├── mcp/
│       │   ├── QhorusMcpToolsBase.java  — abstract base: mappers (toLedgerEntryMap, toMessageSummary, etc.), validators; ledger query response records; resolveChannel(String) accepts UUID or name (dual-identity); projectAndRender(UUID, RenderableProjection<S>) and projectAndRender(UUID, RenderableProjection<S>, Integer maxMessages) — package-private, never @Tool
│       │   ├── QhorusMcpTools.java      — blocking @Tool methods (~53); @UnlessBuildProperty(casehub.qhorus.reactive.enabled); create_channel (allowed_types + denied_types); set_channel_type_constraints(channel, allowed_types?, denied_types?) — full-replacement, UUID-or-name; list_projections() — sorted names from ProjectionRegistry; project_channel(channel, projection_name, max_messages?) — max_messages bounds fold depth (null = unlimited)
│       │   └── ReactiveQhorusMcpTools.java — reactive @Tool methods returning Uni<T>; @IfBuildProperty(casehub.qhorus.reactive.enabled); set_channel_type_constraints (pure reactive — resolveChannelAsync, no @Blocking); list_projections; project_channel(max_messages?) (@Blocking); delete_channel (reactive Uni<DeleteChannelResult>), get_instance and get_message (@Blocking)
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
│           ├── AgentCardResource.java   — GET /.well-known/agent-card.json (@UnlessBuildProperty); AgentCard record has tenancyId field reflecting CurrentPrincipal.tenancyId() — Qhorus-specific extension to the A2A spec; Refs #264
│           ├── A2AResource.java         — POST /a2a/message:send, GET /a2a/tasks/{id}, GET /a2a/tasks/{id}/stream (SSE — text/event-stream; @RunOnVirtualThread active model: LinkedBlockingQueue<OutboundMessage> consumer = queue::offer; poll(heartbeatMs) drives named keepalive events (event: keepalive); sink.isClosed() orphan detection; deadline enforces max-duration; two QuarkusTransaction.requiringNew() reads for validation + re-check; DECLINE→"cancelled" per A2ATaskState; casehub.qhorus.a2a.sse.heartbeat-interval-seconds=15, max-duration-seconds=1800); delegates to A2AChannelBackend; getTask() uses CommitmentStore for durable state (@UnlessBuildProperty). HTTP 200+event:error when A2A disabled (void SSE method cannot return 501). Refs #147, #278.
│           ├── A2AActorResolver.java    — 6-step sender identity resolution chain for A2A role:"user" (header, Instance registry, agentCardUrl, persona format, system, default HUMAN)
│           ├── A2AChannelBackend.java   — ChannelBackend "a2a"; protocol bridge registered via ensureRegistered(); receive() routes via QhorusMcpTools; post() dispatches to Consumer<OutboundMessage> SSE registry (ConcurrentHashMap<UUID, Set<Consumer<OutboundMessage>>>) keyed by correlationId. registerStream()/deregisterStream() (race-free via compute())/streamCount() (test accessor). Deregister uses compute() — atomic empty-removal prevents TOCTOU orphan. post() uses Set.copyOf() snapshot + per-consumer try-catch. streamTask() auto-calls ensureRegistered() — SSE stream self-registers so fanOut() reaches the queue without a prior POST /a2a/message:send. Known constraint: SSE subscriptions do not survive server restart (lazy registration, no @Observes ChannelInitialisedEvent). Refs #135, #147, #278.
│           ├── A2ATaskState.java        — package-private; maps CommitmentState and MessageType to A2A task state strings; TERMINAL_TYPES Set<MessageType> constant; TERMINAL_STATES Set<String> constant (= {"completed","failed","cancelled"}) for string-level terminal checks; fromMessageType(MessageType) for SSE events; fromCommitmentState() and fromMessageHistory() priority system (DONE/RESPONSE=4, FAILURE=3, DECLINE=2, STATUS/HANDOFF=1); DECLINE→"cancelled" (explicit refusal, not infrastructure failure). Used by A2AResource and ReactiveA2AResource. Refs #147.
│           ├── ReactiveAgentCardResource.java — reactive Uni<Response> agent card (@IfBuildProperty)
│           └── ReactiveA2AResource.java       — reactive A2A endpoints (@IfBuildProperty)
├── deployment/                          — Extension deployment (build-time) module
│   └── src/main/java/io/casehub/qhorus/deployment/
│       ├── QhorusBuildTimeConfig.java    — @ConfigRoot(BUILD_TIME) declaring casehub.qhorus.reactive.enabled; makes @IfBuildProperty reliable for this custom property
│       └── QhorusProcessor.java         — @BuildStep: FeatureBuildItem only; reactive bean selection handled by @IfBuildProperty(casehub.qhorus.reactive.enabled=true) on reactive beans and @UnlessBuildProperty(casehub.qhorus.reactive.enabled=true, enableIfMissing=true) on conflicting blocking beans
├── connector-backend/                   — Optional bridge module; activates by classpath presence
│   └── src/main/java/io/casehub/qhorus/connector/backend/
│       ├── ConnectorChannelBackend.java — HumanParticipatingChannelBackend; bridges InboundMessage CDI events from casehub-connectors into Qhorus channel dispatch; self-registers for channels with ChannelBackend binding
│       └── ConnectorQhorusMeshBridge.java — @ApplicationScoped impl of ConnectorMeshBridge SPI; posts STATUS to casehub.qhorus.connector-backend.delivery-channel after each MCP connector delivery; activates by classpath presence; sender format "system:connector:{connectorId}"; destination excluded from content (credential/PII risk); channel ID cached per tenancyId in ConcurrentHashMap. **ACL requirement:** STATUS dispatches go through the full `MessageService.dispatch()` enforcement gate — if the delivery channel has `allowedWriters` set, include `"role:system"` or leave it null (open); omitting it silently drops every `notifyDelivered` call (WARN logged with the ACL exception detail). **Rate limiting:** if the delivery channel has per-channel or per-instance rate limits configured, high-frequency connector deliveries may be throttled; the delivery channel should not have tight rate limits in production. Refs #249, #273.
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
- `ledger_subject_sequence` is NOT a JPA entity — Hibernate `drop-and-create` does not create it. All test modules that enable the ledger (`casehub.ledger.enabled=true`) must include `import-qhorus-test.sql` (one `CREATE TABLE IF NOT EXISTS ledger_subject_sequence ...` statement) and set `quarkus.hibernate-orm.qhorus.sql-load-script=import-qhorus-test.sql`. Rows survive context restarts because H2 uses `DB_CLOSE_DELAY=-1`; tests must use fresh random UUIDs per run as subjectIds to avoid cross-context sequence pollution. Refs #256.
- Optional modules (`a2a`, `watchdog`) require a `@TestProfile` that sets `casehub.qhorus.<module>.enabled=true`. Any `@TestProfile` that causes Quarkus to restart must also include the full `quarkus.datasource.qhorus.*` block (db-kind, jdbc.url, username, password) plus `quarkus.datasource.qhorus.reactive=false` and `quarkus.hibernate-orm.qhorus.database.generation=drop-and-create` in `getConfigOverrides()` — Quarkus restarts do not inherit test `application.properties` from prior context. Do NOT add `casehub.qhorus.reactive.enabled` to H2/JDBC profiles — that property is BUILD_TIME only and its presence in `application.properties` triggers a SmallRye Config runtime validation error (`SRCFG00050`). Reactive tests (all `@Disabled`, require Docker) set it via `ReactiveTestProfile.getConfigOverrides()`.
- `RateLimiter` is an `@ApplicationScoped` in-memory bean — its state does NOT roll back with `@TestTransaction`. Use unique channel names per test to avoid cross-test interference.
- `WatchdogScheduler` runs in its own thread/transaction and cannot see uncommitted test data. Tests calling `watchdogService.evaluateAll()` directly with the scheduler active must use `@TestTransaction` to prevent the scheduler from picking up in-flight test data and firing spurious side effects.
- `MessageStore.distinctSendersByChannel(channelId, MessageType.EVENT)` — the second parameter is the **excluded** type, not an included type. Passing `EVENT` returns senders of all non-EVENT messages; EVENTs are excluded. BARRIER_STUCK test setup must use `MessageType.STATUS` (or any non-EVENT type) as the barrier contribution message — EVENTs from agents do not count as contributions. `JpaMessageStore` query: `WHERE channelId = ?1 AND messageType != ?2`.
- `check_messages` excludes `EVENT` messages by default — use `check_messages(include_events=true, reader_instance_id=<id>)` with a `register(read_only=true)` observer instance to assert EVENT delivery in tests. `read_observer_events`, `register_observer`, and `deregister_observer` were removed in #121-G.
- `MessageService.dispatch(MessageDispatch)` is the single enforcement gate for all channel writes: paused check, `AllowedWritersPolicy` ACL check, `RateLimiter`, `ObligorTrustPolicy` SPI trust gate (COMMAND + named non-prefixed target; `DefaultObligorTrustPolicy` reads `casehub.qhorus.commitment.min-obligor-trust`; gate skipped when ≤ 0), LAST_WRITE overwrite semantics, `ChannelGateway.fanOut()`. Tests calling `messageService.dispatch()` directly get all enforcement. `ActorType` is part of `MessageDispatch` — use `ActorTypeResolver.resolve(sender)` or an explicit constant. `MessageDispatch` has 14 fields: channelId, sender, type, content, correlationId, inReplyTo, artefactRefs, target, actorType, deadline, telemetry, tenancyId (+ reserved fields); `tenancyId` (14th, nullable) is resolved by `MessageService.dispatch()` from `CurrentPrincipal.tenancyId()` when null — system actors that run outside a request context (e.g. `WatchdogEvaluationService`) must set it explicitly via `MessageDispatch.builder().tenancyId(tenancyId)`. The `WatchdogEvaluationService` uses sender `"system:watchdog"` and `ActorType.SYSTEM` — tests expecting watchdog alert messages should assert sender `"system:watchdog"`. To test the SPI override pattern use `@InjectMock ObligorTrustPolicy` (quarkus-junit5-mockito) — no profile restart needed.
- `MessageService.dispatch()` is the single enforcement point for MessageTypePolicy; the validate() call was removed from `QhorusMcpTools.sendMessage()` — MCP tools no longer do client-side early rejection. `StoredMessageTypePolicy.validate()` hard-enforces COMMAND and QUERY only (both call commitmentService.open(); wrong-channel advisory dispatch causes orphan Commitments). advisory() returns warning text for all other types. validate() uses denial-first logic for COMMAND/QUERY. Tests calling `messageService.dispatch()` directly hit both gates: validate() (hard-block for COMMAND/QUERY violations) and advisory() (WARN log + DispatchResult.advisories() for all other type violations). `ChannelCreateRequest` compact constructor takes `Set<MessageType> allowedTypes, Set<MessageType> deniedTypes` (typed, not String) and asserts `allowedTypes ∩ deniedTypes = ∅` — constructing an invalid request throws `IllegalArgumentException` before any DB interaction. `MessageType.serializeTypes(Set<MessageType>)` produces sorted canonical CSV for DB storage (deterministic, String equality reliable). MCP tools parse String params at the boundary via `MessageType.parseTypes()` before constructing the request. Refs #247, #246.
- `LedgerWriteService.record(MessageDispatch, ...)` is called for **all 9 message types** via `MessageService.dispatch()` — not just EVENT. Every `dispatch()` call produces a `MessageLedgerEntry` (except LAST_WRITE overwrites, which skip the ledger write — tracked in #195). EVENT entries extract telemetry from `dispatch.telemetry()` (not `dispatch.content()`) — `tool_name`, `duration_ms`, `token_count`, `context_refs`, `source_entity` columns (all nullable). `dispatch.content()` is always null for EVENTs (Builder.build() enforces this — PP-20260608-054090). `dispatch.tenancyId()` is populated by `MessageService.dispatch()` before `LedgerWriteService.record()` is called — the ledger entry always carries the real tenancyId (not null). Tests dispatching EVENTs via the Builder must use `.telemetry(json)` not `.content(json)`; tests using the canonical constructor (bypassing the Builder) should auto-reroute EVENT content to the telemetry arg. Refs #257, #260.
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
- `ReactiveMessageServiceTest` is `@Disabled` (requires PostgreSQL DevServices — Podman ≥ 4 GB). Other `Reactive*ServiceTest` classes remain `@Disabled` for the same reason.
- `ReactiveMessageService` trust gate (COMMAND + named obligor) calls `TrustGateService.meetsThresholdAsync()` directly — `ObligorTrustPolicy` SPI is bypassed in the reactive path. The blocking `MessageService` continues to call `ObligorTrustPolicy.permits()`. Custom `ObligorTrustPolicy` beans are honoured in the blocking stack only; see qhorus#235 for the reactive SPI track. The `minObligorTrust <= 0` fast-path short-circuits before any repo call. Refs qhorus#234, casehubio/ledger#106.
- `ReactiveLedgerWriteService.record()` writes `LedgerAttestation` entries for DONE/FAILURE/DECLINE (no longer deferred). Attestation failure is non-fatal: logged at WARN and swallowed — the message insert is unaffected. `ReactiveLedgerEntryJpaRepository.saveAttestation()` persists via `repo.getSession().flatMap(session -> session.persist(attestation).replaceWith(attestation))` — atomic with the message insert because it joins the enclosing `Panache.withTransaction("qhorus", ...)`. Refs qhorus#234, casehubio/ledger#105.
- Entity setup in `@QuarkusTest` for reactive services must use `QuarkusTransaction.requiringNew().run(() -> blockingService.create(...))` — not `Panache.withTransaction("qhorus", () -> ...)`. JUnit test threads have no Vert.x context; calling the reactive Panache API from them throws "No current Vert.x context found". The JTA `requiringNew()` approach commits the entity before the reactive service's own session starts (PP-20260529-ca7b89).
- Tests asserting `MessageObserver.onMessage()` was invoked must use `QuarkusTransaction.requiringNew().run(() -> messageService.dispatch(...))` — NOT `@TestTransaction`. `MessageObserverDispatcher` defers dispatch to `afterCompletion(STATUS_COMMITTED)` via JTA `TransactionSynchronizationRegistry`. In a `@TestTransaction` test the transaction rolls back, so `afterCompletion` fires with `STATUS_ROLLEDBACK` and observers are silently skipped. Entity setup must also be committed first in a separate `requiringNew()` block. See GE-20260608-038af4 and PP-20260608-07daa6.
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
- `ConnectorQhorusMeshBridge.clearCache()` is a package-private test helper that clears the internal `channelIdCache` between test methods. Call it in `@BeforeEach` for any `@QuarkusTest` class that calls `notifyDelivered()` — the `@ApplicationScoped` bean is shared across the entire test class lifecycle and the channelId cache bleeds between test methods.
- `@InjectMock ManagedExecutor executor` works in `@QuarkusTest` — `ManagedExecutor` is registered as a CDI bean by `quarkus-smallrye-context-propagation` and is fully mockable. Use `doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; }).when(executor).execute(any())` to run async tasks synchronously on the test thread (eliminates async race, no Awaitility needed).
- `PrometheusMeterRegistry` is `@ApplicationScoped` and monotonic — counters accumulate across all tests in a `@QuarkusTest` class. Never assert absolute counter values; instead capture `before = counter.count()` before the action and assert `isEqualTo(before + 1.0)` or `isGreaterThan(before)` after it. `SimpleMeterRegistry` (used in CDI-free unit tests) starts fresh per instance, so absolute assertions are safe there.
- `MessageObserver` implementations may use any normal CDI scope. `MessageObserverDispatcher` calls `observers.handles()` and closes each `Instance.Handle` in a `finally` block — `@Dependent` beans are correctly destroyed after each dispatch. The former `@ApplicationScoped`-only constraint (PP-20260518-837246) is removed as of qhorus#167.
- Flyway migrations are in `db/qhorus/migration/` — outside the `db/migration/` namespace entirely, preventing Flyway's recursive classpath scan on the default datasource from picking up qhorus migrations when casehub-work and casehub-qhorus are on the same classpath. `quarkus.flyway.qhorus.locations=classpath:db/qhorus/migration` is set in `application.properties`. Tests using `drop-and-create` are unaffected; this only matters for migration file placement and Flyway config. Next domain migration: V23 (V10 = commitment table; V12 = channel_allowed_types + message_deadline columns; V13 = message.commitment_id; V14 = channel_connector_binding; V15 = channel.auto_created; V16 = channel.denied_types; V17 = chk_channel_name_slug CHECK constraint; V18 = channel.tenancy_id + uq_channel_name_tenancy; V19 = message.tenancy_id; V20 = commitment.tenancy_id; V21 = watchdog.tenancy_id; V22 = idx_commitment_obligor; V2000 = ledger subclass join — ledger occupies V1000–V1999, consumer joins start at V2000). Ledger migrations included via `classpath:db/ledger/migration` (#179).
- `FlywayMigrationSchemaTest` (plain Java, no Quarkus) validates that migrations produce the correct schema — bypassing the `drop-and-create` path used by `@QuarkusTest`. Pattern: create cross-module dependency tables (e.g. `ledger_entry`) before running Flyway, then use `baselineOnMigrate(true)` + `baselineVersion("0")` (must be `"0"`, not default `"1"`, so V1 runs rather than being skipped as the baseline). Use H2 in-memory with `MODE=PostgreSQL` and a unique DB name per JVM run (e.g. `System.nanoTime()` suffix) to prevent cross-run state sharing. Close `ResultSet` from `getMetaData().getTables()` explicitly in try-with-resources — it is not closed when the `Connection` closes.
- Multi-tenancy (#260) added `CurrentPrincipal` injection to `JpaChannelStore`, `JpaCommitmentStore`, `MessageService`, and `QhorusMcpTools`. Any module running `@QuarkusTest` against the qhorus runtime must add `casehub-platform` (test scope) to its pom — this brings in `MockCurrentPrincipal` (`@DefaultBean @ApplicationScoped`) which satisfies the CDI requirement. Affected modules: `runtime` (already included), `connector-backend`, `examples/type-system`, `examples/normative-layout`. Without it, augmentation fails with 8 `UnsatisfiedResolutionException` errors at CDI validation.
- `QhorusInboundCurrentPrincipal @ApplicationScoped` (#265, #276) is the default HTTP-layer principal — plain `@ApplicationScoped` (Tier 2 per CDI priority ladder), NOT `@DefaultBean`. Displaces `MockCurrentPrincipal @DefaultBean` automatically; displaced by `FixedCurrentPrincipal @Alternative @Priority(1)` in test fixtures and `OidcCurrentPrincipal @Alternative @Priority(100)` in OIDC deployments. Do NOT add `quarkus.arc.exclude-types=MockCurrentPrincipal` — with plain `@ApplicationScoped`, no CDI ambiguity arises. CDI-free unit tests that set `principal.ctx = stub` (package-private field) bypass CDI entirely. `StubMessageLedgerEntryRepository` overrides (`findEarliestWithSubjectByCorrelationId`, `findLatestByCorrelationId`) now filter by tenancyId using the same null→DEFAULT normalisation as production — pass `null` in tests to target DEFAULT_TENANT_ID. `MessageLedgerEntryTestFactory` (moved to `casehub-qhorus-testing` in #280 — import from `io.casehub.qhorus.testing`) sets `tenancyId = DEFAULT_TENANT_ID` explicitly on every entry. Runtime module tests use a local `buildEntry()` helper instead (adding `casehub-qhorus-testing` as a test dep to `runtime` would create a build cycle). Refs #265, #276, #280.
- `StubReactiveLedgerEntryRepository` in `runtime/src/test/java/.../runtime/ledger/` satisfies the `ReactiveLedgerEntryRepository` CDI dependency when `quarkus.datasource.qhorus.reactive=false`. Without it, casehub-ledger beans (`LedgerVerificationService`, `KeyRotationService`) that inject the reactive repo cause all `@QuarkusTest` tests to fail at CDI discovery with `UnsatisfiedResolutionException`. Any module adding casehub-ledger as a dependency and running non-reactive `@QuarkusTest` tests needs this stub or an equivalent. See PP-20260519-1f5e2c.
- `StubReactiveCommitmentStore` in `runtime/src/test/java/.../runtime/message/` satisfies the `ReactiveCommitmentStore` CDI dependency when the reactive stack is enabled (`casehub.qhorus.reactive.enabled=true`) but no real reactive JPA datasource is configured (H2/JDBC profiles). Without it, `ReactiveCommitmentService` (which injects `ReactiveCommitmentStore`) causes `UnsatisfiedResolutionException` during CDI discovery. The stub is a `@DefaultBean @ApplicationScoped` that throws `UnsupportedOperationException` on all methods — satisfies the injection point without requiring PostgreSQL.
- `StubReactiveMessageStore` in `runtime/src/test/java/.../runtime/message/` satisfies the `ReactiveMessageStore` CDI dependency when the reactive stack is enabled but no reactive JPA datasource is configured. Same pattern as `StubReactiveCommitmentStore`: `@DefaultBean @ApplicationScoped`, all methods throw `UnsupportedOperationException`. Displaced by `ReactiveJpaMessageStore` when PostgreSQL DevServices are active.
- `ProjectionService` integration tests (`ProjectionServiceIT`) write messages via `messageStore.put()` directly — not `MessageService.dispatch()`. Direct put bypasses the ledger write (`LedgerWriteService.record()` in `REQUIRES_NEW`) and the enforcement gate, keeping projection tests focused on fold correctness. Use `dispatch()` only when testing that enforcement interacts with projection.
- `ProjectionResult<S>.isEmpty()` is `true` when `lastMessageId == null` (channel was empty). Passing an empty result to the incremental `project()` overload performs a full scan from `identity()` regardless of `previous.state()` — the service enforces this invariant, so manually constructed `ProjectionResult` instances with non-null state and null cursor are silently corrected to `identity()`.
- `ProjectionRegistry` is an `@ApplicationScoped` CDI bean that collects all `@Any Instance<RenderableProjection<?>>` at startup and validates: (1) no `projectionName()` returns null or blank — `IllegalStateException` at CDI bootstrap; (2) no two beans share a name — `IllegalStateException` at CDI bootstrap. Unit tests use the package-private `ProjectionRegistry(List<? extends RenderableProjection<?>>)` constructor (same package as registry: `io.casehub.qhorus.runtime.message`). The `stubs(String... names)` helper is needed in tests because Java's type inference fails to unify `List.of(wildcardBean...)` with `List<? extends RenderableProjection<?>>` — collect into an explicit `ArrayList<RenderableProjection<?>>` first. Refs qhorus#232.
- `resolveChannel(String channel)` in `QhorusMcpToolsBase` returns `Channel` (not UUID) via two-phase UUID parsing (`tryParseUuid` returning null on failure) — callers use `ch.id` or `ch.name` as needed. A matching `resolveChannelAsync(String) → Uni<Channel>` exists in `ReactiveQhorusMcpTools` for Category A reactive tools. Resolve at the `@Tool` boundary; all six UUID-first mutation service methods (setRateLimits, setAllowedWriters, setAdminInstances, pause, resume, delete) receive `ch.id` (UUID) after #252 (PP-20260606-f899bc updated). For UUID paths, channel existence validated via `ChannelService.findById()` before returning; a valid-format but non-existent UUID would otherwise silently return `render(identity())`. Refs qhorus#232, qhorus#237.

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

