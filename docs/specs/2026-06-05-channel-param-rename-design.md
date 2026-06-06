# Channel Parameter Rename — Design Spec
**Issue:** qhorus#237
**Date:** 2026-06-05 (revised after review)
**Branch:** issue-237-channel-param-rename

---

## Problem

All channel-targeting MCP tools accept `channel_name` (String) and resolve internally
by name only. Agents holding a channel UUID must look up the name before calling.
`set_channel_type_constraints` and `project_channel` already use `channel` (UUID-or-name)
— the rest of the surface is inconsistent.

The slug enforcement shipped in #236 makes detection unambiguous: a valid slug cannot
parse as a UUID, so `UUID.fromString()` succeeds if and only if the input is a UUID.

This is a **breaking MCP protocol change**: `channel_name=` becomes `channel=`. Any
caller using named arguments with the old key will receive null and get a ToolCallException.
No migration window. No backward-compatibility shim. Clean break.

---

## Architectural Change — `resolveChannel()` returns `Channel`

The most important change in this PR is to the base class resolver.

**Current:**
```java
// QhorusMcpToolsBase
UUID resolveChannel(final String channel) { ... returns channel id ... }
```

**New:**
```java
// QhorusMcpToolsBase
Channel resolveChannel(final String channel) {
    final UUID parsed = ChannelSlugValidator.tryParseUuid(channel);
    if (parsed == null) {
        return channelService.findByName(channel)
            .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channel));
    }
    return channelService.findById(parsed)
        .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channel));
}
```

Returning `Channel` means:
- One lookup for any caller — no second `findById()` after resolution
- Callers use `ch.id` or `ch.name` as needed — no conversion ceremony
- True symmetry with `resolveChannelAsync() → Uni<Channel>` in the reactive class

Existing callers of `resolveChannel()` that previously used the UUID directly
(`UUID channelId = resolveChannel(channel)`) adapt as:
```java
Channel ch = resolveChannel(channel);
UUID channelId = ch.id;
```
This applies to `set_channel_type_constraints` and `project_channel` in both tool classes.
The adaptation is mechanical and trivially correct.

---

## `resolveChannelAsync()` — reactive resolution

New private method in `ReactiveQhorusMcpTools` (cannot go in base class — reactive
`channelService` is build-gated and unavailable when reactive stack is off):

```java
private Uni<Channel> resolveChannelAsync(String channel) {
    UUID parsed = ChannelSlugValidator.tryParseUuid(channel);
    if (parsed != null) {
        return channelService.findById(parsed)
            .map(opt -> opt.orElseThrow(() ->
                new IllegalArgumentException("Channel not found: " + channel)));
    }
    return channelService.findByName(channel)
        .map(opt -> opt.orElseThrow(() ->
            new IllegalArgumentException("Channel not found: " + channel)));
}
```

`tryParseUuid` returns null on parse failure — no `catch (IllegalArgumentException)` needed,
so the IAE catch-scope contamination gotcha (GE-20260517-11dd6b) does not apply.

---

## Complete Tool Inventory

### `QhorusMcpTools` (blocking) — 27 tools renamed

All tools in this class are synchronous. Pattern for each: `Channel ch = resolveChannel(channel);`
replaces any inline `channelService.findByName(channelName).orElseThrow(...)` call.
`ch.id` or `ch.name` used as needed downstream.

| Tool | Special notes |
|------|--------------|
| `update_channel_binding` | |
| `set_channel_rate_limits` | |
| `set_channel_writers` | |
| `set_channel_admins` | |
| `pause_channel` | |
| `resume_channel` | |
| `delete_channel` | |
| `list_backends` | |
| `deregister_backend` | |
| `register_backend` | |
| `force_release_channel` | |
| `send_message` | |
| `check_messages` | |
| `search_messages` | nullable — see below |
| `wait_for_reply` | |
| `request_approval` | compound — see below |
| `respond_to_approval` | |
| `list_my_commitments` | |
| `clear_channel` | |
| `get_channel_digest` | |
| `list_ledger_entries` | |
| `get_obligation_chain` | |
| `get_causal_chain` | |
| `list_stalled_obligations` | |
| `get_obligation_stats` | |
| `get_telemetry_summary` | |
| `get_channel_timeline` | |

Already done (no change): `set_channel_type_constraints`, `project_channel`.

Exceptions (not renamed): `create_channel` (`name` param — creation, not reference);
`register_watchdog` (`notification_channel` — destination address, not an identifier).

### `ReactiveQhorusMcpTools` (reactive) — 26 tools renamed

Split into Category A (pure reactive) and Category B (`@Blocking` wrappers).

**Category A — no `@Blocking`, use `resolveChannelAsync()`:**

| Tool | Pattern |
|------|---------|
| `set_channel_rate_limits` | `resolveChannelAsync(channel)` → `service.setRateLimits(ch.name, ...)` |
| `set_channel_writers` | `resolveChannelAsync(channel)` → `service.setAllowedWriters(ch.name, ...)` |
| `set_channel_admins` | `resolveChannelAsync(channel)` → `service.setAdminInstances(ch.name, ...)` |
| `pause_channel` | replaces inline `findByName` chain with `resolveChannelAsync(channel)` |
| `resume_channel` | replaces inline `findByName` chain with `resolveChannelAsync(channel)` |
| `delete_channel` | replaces inline `findByName` chain — see scope note below |
| `list_backends` | replaces inline `findByName` chain with `resolveChannelAsync(channel)` |
| `deregister_backend` | replaces inline `findByName` chain with `resolveChannelAsync(channel)` |
| `register_backend` | replaces inline `findByName` chain with `resolveChannelAsync(channel)` |

Example — `pause_channel` before/after:
```java
// Before:
return channelService.findByName(channelName)
    .map(opt -> opt.orElseThrow(...))
    .invoke(ch -> checkAdminAccess(ch, callerId, "pause_channel"))
    .flatMap(ignored -> channelService.pause(channelName))
    ...

// After:
return resolveChannelAsync(channel)
    .invoke(ch -> checkAdminAccess(ch, callerId, "pause_channel"))
    .flatMap(ch -> channelService.pause(ch.name))
    ...
```

For `setRateLimits`, `setAllowedWriters`, `setAdminInstances` — the reactive service methods
take `String name` internally (qhorus#252 will make them UUID-first). For now, pass `ch.name`
after resolution. For name inputs this means `findByName` runs twice (once in `resolveChannelAsync`,
once inside the service method). For UUID inputs it means `findById` then `findByName` — still
two indexed lookups. Acceptable pending qhorus#252.

**Category B — `@Blocking`, use `resolveChannel()` (base class, now returns Channel):**

These tools call `blockingChannelService` or blocking helpers for their core operation.
`resolveChannel(channel)` (blocking) gives the entity in one shot. `@Blocking` is retained
on all of them — channel resolution is not the reason for `@Blocking` here.

| Tool | Notes |
|------|-------|
| `update_channel_binding` | `resolveChannel(channel)` + blocking ops |
| `force_release_channel` | `@Blocking`; delegates to `blockingForceReleaseChannel(ch.name, ...)` |
| `search_messages` | `@Blocking`, nullable — see below; delegates to `blockingSearchMessages(query, ch.name, ...)` |
| `send_message` | delegates to `blockingSendMessage(ch.name, ...)` |
| `check_messages` | delegates to `blockingCheckMessages(ch.name, ...)` |
| `wait_for_reply` | delegates to `blockingWaitForReply(ch.name, ...)` |
| `request_approval` | compound — see below |
| `respond_to_approval` | `resolveChannel(channel)` → `ch.name` for blocking ops |
| `clear_channel` | `resolveChannel(channel)` |
| `get_channel_digest` | `resolveChannel(channel)` |
| `get_channel_timeline` | `resolveChannel(channel)` |
| `list_ledger_entries` | `resolveChannel(channel)` → `ch.id` for ledger query |
| `get_obligation_chain` | `resolveChannel(channel)` → `ch.id` for ledger query |
| `get_causal_chain` | `resolveChannel(channel)` → `ch.id` for ledger query |
| `list_stalled_obligations` | `resolveChannel(channel)` → `ch.id` for ledger query |
| `get_obligation_stats` | `resolveChannel(channel)` → `ch.id` for ledger query |
| `get_telemetry_summary` | `resolveChannel(channel)` → `ch.id` for ledger query |

Special: `set_channel_type_constraints` — already has `channel` param; currently `@Blocking`
solely because of the old `resolveChannel()` call. After the change: switch to
`resolveChannelAsync(channel)` and remove `@Blocking`.

---

## Special Cases

### `search_messages` — nullable channel param

`search_messages` accepts `channel` as `required = false`. Resolution must be guarded.
Both the blocking class and the reactive class (`@Blocking`, delegates to
`blockingSearchMessages`) use the blocking `resolveChannel()` from the base class:

```java
Channel ch = null;
if (channel != null && !channel.isBlank()) {
    ch = resolveChannel(channel);   // blocking in both classes — reactive search_messages is @Blocking
}
// pass ch.name (or null) to downstream lookup, ch.id (or null) for ledger filter
```

Null/blank channel = search across all channels (existing behaviour preserved).

### `delete_channel` — Uni scope: nest terminal map inside flatMap

The current reactive `delete_channel` body terminates with a `.map()` outside the
`.flatMap()` that changes the stream type:

```java
.flatMap(ch -> channelService.delete(channelName, ...)
        .invoke(ignored -> channelGateway.closeChannel(ch.id, ...)))
.map(deleted -> new DeleteChannelResult(channelName, deleted, "deleted"));
// ↑ channelName is the raw String parameter — works today, breaks after rename
```

After renaming the parameter to `channel`, if a UUID is passed, `new DeleteChannelResult(channel, ...)`
would put the UUID string in the result name. `ch` is also out of scope at the outer `.map()`.

Fix: nest the terminal `.map()` inside the `.flatMap()` closure so `ch` stays in scope:

```java
return resolveChannelAsync(channel)
    .invoke(ch -> checkAdminAccess(ch, callerInstanceId, "delete_channel"))
    .invoke(ch -> commitmentStore.deleteAll(ch.id))
    .flatMap(ch -> channelService.delete(ch.name, Boolean.TRUE.equals(force))
            .invoke(ignored -> channelGateway.closeChannel(ch.id, new ChannelRef(ch.id, ch.name)))
            .map(deleted -> new DeleteChannelResult(ch.name, deleted, "deleted")));
```

General rule: where `channelName` (the old parameter) appears in a `.map()` downstream of
a `.flatMap()` that changes the stream type, nest the terminal map inside the `flatMap`
closure to keep `ch` in scope and use `ch.name` for the result.

### `request_approval` — compound tool, resolve once

`request_approval` internally calls `sendMessage` then `waitForReply` (via private blocking
helpers). Both helpers need the channel. Resolution must happen once at the `@Tool` boundary;
the resolved `ch.name` is threaded into private helpers as a plain String.

The private helpers (`blockingSendMessage`, `blockingWaitForReply`) remain name-only
internally — they are not public `@Tool` methods and never receive UUID-or-name input
from external callers. Resolution at the boundary prevents double lookup and keeps
the contract clean.

```java
// public @Tool method — resolves once
public String requestApproval(
        @ToolArg(name = "channel", ...) String channel, ...) {
    Channel ch = resolveChannel(channel);   // both classes — request_approval is @Blocking (Category B) in reactive
    return blockingRequestApproval(ch.name, content, timeoutS);
}

// private helper — name-only, unchanged internally
private String blockingRequestApproval(String channelName, ...) {
    blockingSendMessage(channelName, ...);
    blockingWaitForReply(channelName, ...);
    ...
}
```

---

## Private Helper Methods

Private helpers (`blockingSendMessage`, `blockingCheckMessages`, `blockingWaitForReply`, etc.)
accept `String channelName` and call `blockingChannelService.findByName(channelName)` internally.
They stay name-only. Resolution happens once in the public `@Tool` method at the boundary;
`ch.name` is passed in. This is the **resolve-at-boundary, propagate-resolved** pattern.

---

## `@Blocking` Changes

| Tool | Class | Before | After | Reason |
|------|-------|--------|-------|--------|
| `set_channel_type_constraints` | Reactive | `@Blocking` | remove | sole reason was blocking `resolveChannel()` |
| All other Category B tools | Reactive | `@Blocking` | keep | blocking service calls unrelated to channel resolution |
| All Category A tools | Reactive | none | none | fully reactive; `resolveChannelAsync` is reactive |
| All tools | Blocking | n/a | n/a | QhorusMcpTools is synchronous throughout |

---

## Testing

**Representative tool coverage (unit tests):**

| Test | Purpose |
|------|---------|
| `send_message` with UUID input | Blocking class, most-called tool, covers UUID path |
| `send_message` with name input | Regression: name inputs still work after structural change |
| `pause_channel` with UUID input | Category A reactive, entity-chain pattern |
| `pause_channel` with name input | Category A reactive regression |
| `list_ledger_entries` with UUID input | Category B reactive, ledger code path |
| `list_ledger_entries` with name input | Ledger regression |
| `search_messages` with UUID channel | Nullable + resolution |
| `search_messages` with null channel | Nullable fast path (no resolution attempted) |
| `request_approval` with UUID | Compound tool, resolve-once pattern |
| Non-existent UUID | → `ToolCallException("Channel not found: <uuid>")` |
| Non-existent name | → `ToolCallException("Channel not found: <name>")` (regression) |

The "representative tool" approach is deliberate: all 53 affected parameters follow one
of three code patterns (Category A reactive, Category B blocking-in-reactive, QhorusMcpTools
blocking). One test per pattern per input type gives full branch coverage of the resolution
logic. Exhaustive per-tool tests would be redundant.

---

## Follow-up

**qhorus#252** — `ReactiveChannelService` UUID-first refactor: service methods that take
`String name` (`setRateLimits`, `setAllowedWriters`, `setAdminInstances`, `pause`, `resume`)
should accept `UUID channelId` to eliminate the residual double lookup when a UUID input
flows through Category A tools. This is an optimization; #237 is correct without it.
