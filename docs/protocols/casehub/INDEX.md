# Qhorus Protocol Index

## Flyway / Schema

| Protocol | Summary | Applies to |
|----------|---------|------------|
| [qhorus-flyway-consumer-versioning.md](qhorus-flyway-consumer-versioning.md) | Consumer migrations start at V2000; V1000–V1999 reserved for casehub-ledger | db/qhorus/migration/ |

## Reactive Stack

| Protocol | Summary | Applies to |
|----------|---------|------------|
| [qhorus-reactive-gating.md](qhorus-reactive-gating.md) | Use @IfBuildProperty per-bean (not ExcludedTypeBuildItem) for reactive stack gating | Runtime reactive beans, QhorusProcessor |
| [reactive-blocking-spi-worker-pool.md](reactive-blocking-spi-worker-pool.md) | Reactive services calling blocking SPI must shift to Infrastructure.getDefaultWorkerPool() via runSubscriptionOn | runtime/ reactive services invoking blocking SPI (ObligorTrustPolicy, etc.) |

## Channels

| Protocol | Summary | Applies to |
|----------|---------|------------|
| [channel-dual-identity.md](channel-dual-identity.md) | Every channel has UUID (machine, immutable) + slug (semantic, immutable after creation); MCP tools accept either, resolve at boundary, always return both | QhorusMcpTools, ReactiveQhorusMcpTools, ChannelService, all cross-repo channel references |

## Gateway / Channel Lifecycle

| Protocol | Summary | Applies to |
|----------|---------|------------|
| [startup-event-handler-exception-isolation.md](startup-event-handler-exception-isolation.md) | Startup handlers firing CDI events must catch per-item so one broken observer doesn't abort remaining initialisations | @Observes StartupEvent handlers that loop and fire |
| [channel-initialised-event-observer-idempotency.md](channel-initialised-event-observer-idempotency.md) | Observers of ChannelInitialisedEvent must guard against duplicate registration — event fires unconditionally on creation and startup recovery | Consuming modules implementing @Observes ChannelInitialisedEvent |

## HTTP / Identity

| Protocol | Summary | Applies to |
|----------|---------|------------|
| [http-principal-applicationscoped-pattern.md](http-principal-applicationscoped-pattern.md) | HTTP-aware CDI principal reading from @RequestScoped holder must be @ApplicationScoped — @RequestScoped makes try-catch unreachable via CDI proxy | runtime/identity/ — any CDI bean reading a @RequestScoped holder for background-safe fallback |
| [http-tenancy-header-not-security-boundary.md](http-tenancy-header-not-security-boundary.md) | X-Tenancy-ID is a routing header, not a security boundary — document in Javadoc; production isolation requires casehub-platform-oidc | runtime/identity/, runtime/api/ — any HTTP-layer tenant routing code |

## JPA Stores

| Protocol | Summary | Applies to |
|----------|---------|------------|
| [jpa-like-prefix-metachar-escaping.md](jpa-like-prefix-metachar-escaping.md) | LIKE prefix branches must escape !, %, _ and declare ESCAPE '!' — in-memory path uses startsWith() (exact) and JPA must match | JpaChannelStore.scan(), ReactiveJpaChannelStore.scan() |
| [scheduled-service-cross-tenant-stores.md](scheduled-service-cross-tenant-stores.md) | @Scheduled / no-request-context services must use @CrossTenant stores + explicit tenancyId param — never inject CurrentPrincipal | @Scheduled, @Observes StartupEvent, async observers touching entity stores |

## Ledger

| Protocol | Summary | Applies to |
|----------|---------|------------|
| [ledger-entry-repository-cross-dtype-jpql.md](ledger-entry-repository-cross-dtype-jpql.md) | LedgerEntryRepository implementations must use FROM LedgerEntry (not a subtype) in all JPQL | LedgerEntryJpaRepository, ReactiveLedgerEntryJpaRepository |
| [ledger-sequence-table-test-init.md](ledger-sequence-table-test-init.md) | Modules with casehub.ledger.enabled=true + Flyway disabled must provide import-qhorus-test.sql and sql-load-script | runtime/src/test, examples/*/src/test |
| [ledger-no-credentials-or-pii-in-content.md](ledger-no-credentials-or-pii-in-content.md) | Credentials (webhook URLs, API keys) and PII (phone, email) must never appear in MessageDispatch.content — the immutable ledger cannot be redacted | All MessageService.dispatch() callers handling external delivery destinations; ConnectorMeshBridge implementations |

## MCP Tools

| Protocol | Summary | Applies to |
|----------|---------|------------|
| [mcp-tool-channel-resolution-boundary.md](mcp-tool-channel-resolution-boundary.md) | Resolve channel at @Tool boundary; UUID-first service methods receive ch.id; private helpers get ch.name for read-only only | QhorusMcpTools, ReactiveQhorusMcpTools |

## Message Dispatch

| Protocol | Summary | Applies to |
|----------|---------|------------|
| [event-content-free-signal-type.md](event-content-free-signal-type.md) | Informatory role defines observe channel membership — STATUS for content-bearing observations (no expected reply), EVENT for content-free signals; observe channel accepts EVENT and STATUS per PLATFORM.md | All MessageDispatch.Builder call sites; connector-backend |

## A2A / SSE

| Protocol | Summary | Applies to |
|----------|---------|------------|
| [sse-sink-async-close.md](sse-sink-async-close.md) | Close sink only after awaiting send completion — thenRun (passive model) or .get(5s)+finally (VT active model) | runtime/api/ — any JAX-RS SSE endpoint using SseEventSink |
| [sse-active-model-virtual-thread.md](sse-active-model-virtual-thread.md) | SSE handlers with blocking queue loops MUST use @RunOnVirtualThread, not @Blocking | runtime/api/ — SSE endpoints using active blocking model |
| [sse-keepalive-named-event.md](sse-keepalive-named-event.md) | SSE keepalives MUST use named events (event: keepalive), not SSE comment lines | runtime/api/ — SSE keepalive sends; tests using SseEventSource |
| [a2a-decline-maps-to-cancelled.md](a2a-decline-maps-to-cancelled.md) | A2A task state for DECLINE is "cancelled" (explicit refusal), not "failed" (infrastructure error) | runtime/api/A2ATaskState — all A2A state derivation paths |

## Testing

| Protocol | Summary | Applies to |
|----------|---------|------------|
| [observer-test-transaction-discipline.md](observer-test-transaction-discipline.md) | Tests asserting MessageObserver invocation must use QuarkusTransaction.requiringNew(), not @TestTransaction | @QuarkusTest classes dispatching messages and asserting observer state |
