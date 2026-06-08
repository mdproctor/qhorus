# Qhorus Protocol Index

## Flyway / Schema

| Protocol | Summary | Applies to |
|----------|---------|------------|
| [qhorus-flyway-consumer-versioning.md](qhorus-flyway-consumer-versioning.md) | Consumer migrations start at V2000; V1000–V1999 reserved for casehub-ledger | db/qhorus/migration/ |

## Reactive Stack

| Protocol | Summary | Applies to |
|----------|---------|------------|
| [qhorus-reactive-gating.md](qhorus-reactive-gating.md) | Use @IfBuildProperty per-bean (not ExcludedTypeBuildItem) for reactive stack gating | Runtime reactive beans, QhorusProcessor |

## Gateway / Channel Lifecycle

| Protocol | Summary | Applies to |
|----------|---------|------------|
| [startup-event-handler-exception-isolation.md](startup-event-handler-exception-isolation.md) | Startup handlers firing CDI events must catch per-item so one broken observer doesn't abort remaining initialisations | @Observes StartupEvent handlers that loop and fire |
| [channel-initialised-event-observer-idempotency.md](channel-initialised-event-observer-idempotency.md) | Observers of ChannelInitialisedEvent must guard against duplicate registration — event fires unconditionally on creation and startup recovery | Consuming modules implementing @Observes ChannelInitialisedEvent |

## JPA Stores

| Protocol | Summary | Applies to |
|----------|---------|------------|
| [jpa-like-prefix-metachar-escaping.md](jpa-like-prefix-metachar-escaping.md) | LIKE prefix branches must escape !, %, _ and declare ESCAPE '!' — in-memory path uses startsWith() (exact) and JPA must match | JpaChannelStore.scan(), ReactiveJpaChannelStore.scan() |

## Ledger

| Protocol | Summary | Applies to |
|----------|---------|------------|
| [ledger-entry-repository-cross-dtype-jpql.md](ledger-entry-repository-cross-dtype-jpql.md) | LedgerEntryRepository implementations must use FROM LedgerEntry (not a subtype) in all JPQL | LedgerEntryJpaRepository, ReactiveLedgerEntryJpaRepository |

## MCP Tools

| Protocol | Summary | Applies to |
|----------|---------|------------|
| [mcp-tool-channel-resolution-boundary.md](mcp-tool-channel-resolution-boundary.md) | Resolve channel at @Tool boundary; UUID-first service methods receive ch.id; private helpers get ch.name for read-only only | QhorusMcpTools, ReactiveQhorusMcpTools |

## Message Dispatch

| Protocol | Summary | Applies to |
|----------|---------|------------|
| [event-content-free-signal-type.md](event-content-free-signal-type.md) | EVENT must not carry content — use STATUS for broadcasts, .telemetry() for internal ledger data | All MessageDispatch.Builder call sites |

## Testing

| Protocol | Summary | Applies to |
|----------|---------|------------|
| [observer-test-transaction-discipline.md](observer-test-transaction-discipline.md) | Tests asserting MessageObserver invocation must use QuarkusTransaction.requiringNew(), not @TestTransaction | @QuarkusTest classes dispatching messages and asserting observer state |
