# Channel Gateway Init Event and Channel Prefix Query
**Date:** 2026-05-22
**Issues:** #181, #161 (also closes #145 — already resolved by #135)

---

## Context

Two small infrastructure gaps closed on a single branch:

1. **#181** — `ChannelGateway`'s in-memory registry is not rebuilt on restart. Channels persisted in the DB have no backend registered until re-created or accessed. External backends (Claudony) each implement their own restart recovery today.
2. **#161** — No indexed prefix query exists for channels. Claudony's `ClaudonyReactiveCaseChannelProvider.listChannels(caseId)` must load the entire channel table and filter in Java.

**#145 closed:** `A2AChannelBackend.receive()` already routes through `QhorusMcpTools.sendMessage()` (which applies rate limiting) as a result of #135. Filed before #135 landed; no additional work needed.

---

## #181 — `ChannelInitialisedEvent` and Startup Recovery

### New type: `ChannelInitialisedEvent`

Plain record in `casehub-qhorus-api`, package `io.casehub.qhorus.api.gateway`:

```java
public record ChannelInitialisedEvent(UUID channelId, String channelName) {}
```

No CDI imports. External modules observe it via `@Observes ChannelInitialisedEvent` in their own runtime beans. Lives in `api` so claudony, casehub-engine, and any future consumer can depend on it without pulling the runtime JAR.

### `ChannelGateway` changes

Two additions:

**1. Fire `ChannelInitialisedEvent` from `initChannel()`**

Inject `@Inject Event<ChannelInitialisedEvent> channelInitialisedEvents` via the existing constructor. After the `computeIfAbsent` block, fire unconditionally:

```java
channelInitialisedEvents.fire(new ChannelInitialisedEvent(channelId, ref.name()));
```

Firing unconditionally is safe — observers such as `ensureRegistered()` are idempotent. The event fires on both channel creation (existing `create_channel` path) and startup recovery (new path below).

**2. Startup recovery hook**

```java
void onStart(@Observes StartupEvent ev) {
    channelService.listAll().forEach(ch ->
        initChannel(ch.id, new ChannelRef(ch.id, ch.name)));
}
```

Inject `ChannelService` (already in the runtime; no new dependency direction). Synchronous `@Observes` ensures all backends are registered before the HTTP server accepts requests. `initChannel()` uses `computeIfAbsent` — re-entrant calls are safe and idempotent.

### External backend contract

Backends that need eager channel registration implement `@Observes ChannelInitialisedEvent` and call their own registration logic. `A2AChannelBackend` is **not** changed — its lazy registration via `ensureRegistered()` on first inbound message remains correct. Claudony's `ClaudonyChannelBackend` will implement the observer in a separate issue (claudony#101 area).

### Testing

- Unit test: verify `ChannelInitialisedEvent` is fired by `initChannel()` using a CDI observer mock
- Integration test (`@QuarkusTest`): create a channel, restart the application context (via `@QuarkusTest` with a fresh context), assert the channel's backend is registered without calling `create_channel` again

---

## #161 — `ChannelQuery.byNamePrefix` and Service Convenience Methods

### `ChannelQuery` extension

Add `namePrefix` field alongside the existing `namePattern`:

```java
public static ChannelQuery byNamePrefix(String prefix) {
    return new Builder().namePrefix(prefix).build();
}
```

`matches()` (in-memory path):
```java
if (namePrefix != null && (ch.name == null || !ch.name.startsWith(namePrefix))) return false;
```

### JPA `scan()` additions

Both `JpaChannelStore.scan()` and `ReactiveJpaChannelStore.scan()` get a new branch:

```java
if (q.namePrefix() != null) {
    jpql.append(" AND name LIKE ?").append(idx++);
    params.add(q.namePrefix() + "%");
}
```

`LIKE 'prefix%'` uses a leading-wildcard-free pattern — eligible for a B-tree index on `channel.name`. Index tracked in #182.

### Store interface: no changes

`ChannelStore` and `ReactiveChannelStore` stay at their current surface. The existing `scan(ChannelQuery)` carries the new capability.

### Service convenience methods

```java
// ChannelService
public List<Channel> findByNamePrefix(String prefix) {
    return channelStore.scan(ChannelQuery.byNamePrefix(prefix));
}

// ReactiveChannelService
public Uni<List<Channel>> findByNamePrefix(String prefix) {
    return channelStore.scan(ChannelQuery.byNamePrefix(prefix));
}
```

### Testing

`ChannelStoreContractTest` (abstract base) gets `scan_byNamePrefix_*` tests — inherited by both `InMemoryChannelStoreTest` and `InMemoryReactiveChannelStoreTest`. JPA path covered by `JpaChannelStoreTest` and the disabled reactive equivalent.

---

## Deferred

- **#182** — `idx_channel_name` B-tree index for `LIKE 'prefix%'` performance (V11 migration).
- **claudony#101 area** — Claudony implements `@Observes ChannelInitialisedEvent` to replace its current `bootstrapRegistry()` startup recovery logic.
