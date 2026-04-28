# Issues #125 + #126 — MCP Tool Gaps: delete_channel + get_instance + get_message

**Date:** 2026-04-28
**Issues:** casehubio/quarkus-qhorus#125, casehubio/quarkus-qhorus#126
**Epic:** casehubio/quarkus-qhorus#119 (MCP consistency)

---

## Overview

Three new MCP tools closing lifecycle and lookup gaps in the Qhorus tool surface:

1. **`delete_channel`** (#125) — delete a named channel, with a `force` guard when messages exist
2. **`get_instance`** (#126) — direct lookup by instance id; replaces filtering `list_instances`
3. **`get_message`** (#126) — direct lookup by message id; replaces filtering `search_messages`

All three are additive (non-breaking). All plumbing already exists in the service and store layers.

---

## 1. `delete_channel` (#125)

### `ChannelService.delete(String name, boolean force)`

New method. `ChannelService` gains an `@Inject MessageStore messageStore` dependency.

```java
@Transactional
public void delete(String name, boolean force) {
    Channel ch = findByName(name)
        .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + name));
    long messageCount = messageStore.countByChannel(ch.id);
    if (messageCount > 0 && !force) {
        throw new IllegalStateException(
            "Channel '" + name + "' has " + messageCount + " messages. " +
            "Pass force=true to delete anyway.");
    }
    if (messageCount > 0) {
        messageStore.deleteAll(ch.id);  // MessageStore.deleteAll(UUID channelId) already exists
    }
    channelStore.delete(ch.id);
}
```

`fk_message_channel` is a hard FK with no CASCADE — `channelStore.delete` would fail if messages remain. `messageStore.deleteAll(channelId)` removes them first.

`JpaChannelStore.delete(UUID id)` already exists (`Channel.deleteById(id)`).
`MessageStore.countByChannel(UUID channelId)` already exists.

### `ReactiveChannelService.delete(String name, boolean force)`

Mirror: uses `reactiveRepo` for find, `messageStore.countByChannel` for count (blocking — no reactive count method exists), `channelStore.delete` for removal. Wrapped in `Panache.withTransaction()`.

### Response record

`DeleteChannelResult(String channelName, long messagesDeleted, String status)` — added to `QhorusMcpToolsBase`.

### MCP tools

`QhorusMcpTools.deleteChannel` and `ReactiveQhorusMcpTools.deleteChannel` — new `@Tool` methods:

```
delete_channel(channel_name, force=false)
```

- `channel_name` — required
- `force` — optional boolean, default false; when false, rejects if messages exist

---

## 2. `get_instance` (#126)

### Service

`instanceService.findByInstanceId(String instanceId)` already exists — returns `Optional<Instance>`.

### MCP tools

New `@Tool` in `QhorusMcpTools` and `ReactiveQhorusMcpTools`:

```
get_instance(instance_id)
```

- Throws `IllegalArgumentException` if not found
- Returns `InstanceInfo` (already defined in `QhorusMcpToolsBase`)
- Uses existing `buildInstanceInfoList()` or constructs `InstanceInfo` directly from the `Instance` entity

---

## 3. `get_message` (#126)

### Service

`messageService.findById(Long id)` already exists — returns `Optional<Message>`.

### MCP tools

New `@Tool` in `QhorusMcpTools` and `ReactiveQhorusMcpTools`:

```
get_message(message_id)
```

- Throws `IllegalArgumentException` if not found
- Returns `MessageSummary` (already defined in `QhorusMcpToolsBase`)
- Uses existing `toMessageSummary(Message m)` protected mapper

---

## 4. Testing

### Unit / service tests

| Class | What |
|---|---|
| `ChannelServiceTest` (extend) | `delete` found+empty → succeeds; found+messages+force=false → throws; found+messages+force=true → succeeds; not found → throws |

### Integration tests (`@QuarkusTest`)

| Class | What |
|---|---|
| `DeleteChannelToolTest` | delete empty channel → success + not listable; delete with messages + force=false → error with count; delete with messages + force=true → success; delete non-existent → error |
| `GetInstanceToolTest` | get known instance → correct InstanceInfo; get unknown → error |
| `GetMessageToolTest` | get known message → correct MessageSummary; get unknown → error |

### Robustness

- `delete_channel` on already-deleted channel → `IllegalArgumentException` (not found)
- `get_instance` / `get_message` with unknown id → descriptive `IllegalArgumentException`
- `delete_channel` with force=true calls `messageStore.deleteAll(channelId)` before `channelStore.delete(channelId)` — required because `fk_message_channel` has no CASCADE

---

## 5. Scope

**In scope:**
- `ChannelService.delete(name, force)` + `ReactiveChannelService.delete(name, force)`
- `DeleteChannelResult` record in `QhorusMcpToolsBase`
- `QhorusMcpTools`: `deleteChannel`, `getInstance`, `getMessage`
- `ReactiveQhorusMcpTools`: mirror all three
- Tests as above
- CLAUDE.md + design doc updates (tool count, new tools listed)

**Out of scope:**
- Cascade-deleting messages on channel delete (DB cascade or explicit — leave to existing FK behaviour; document)
- `delete_instance` or `delete_message` (not in this issue)
- Reactive count method in `ReactiveMessageStore` (use blocking count as a workaround)
