# Channel Type-Safe API — #247 + #246

**Date:** 2026-06-13
**Issues:** qhorus#247 (Set<MessageType> in ChannelCreateRequest), qhorus#246 (setAllowedTypes post-creation)
**Branch:** issue-276-sxs-batch

---

## Problem

`ChannelCreateRequest.allowedTypes/deniedTypes` and `ChannelService.setTypeConstraints()` accept raw `String` (comma-separated `MessageType` names). Two problems:

1. **Compile-time silence.** A misspelled type name passes through the compiler and is only caught at runtime by `Enum::valueOf` inside `MessageType.parseTypes()`, throwing `IllegalArgumentException`.
2. **Inconsistent normalization.** `"QUERY, COMMAND"` and `"QUERY,COMMAND"` are semantically equal but stored as-is in the TEXT column, making string equality on stored values unreliable.

---

## Design

**Single typed `setTypeConstraints(UUID, Set<MessageType>, Set<MessageType>)`** — keeps atomic semantics; caller cannot produce an intermediate overlap state. No separate `setAllowedTypes`/`setDeniedTypes` methods: they would require re-reading the persisted state on each call to revalidate overlap, and the single atomic method already serves all known callers.

---

### 1. Overload fate — decided per overload

`ChannelService` and `ReactiveChannelService` both have telescoping chains. The decision for each tier:

| Tier | Overload | Action | Reason |
|------|----------|--------|--------|
| 9-param: `(String,...,String allowedTypes)` | **Remove** | Fails to compile — passes String to Set<MessageType> field |
| 10-param: `(String,...,String allowedTypes, String deniedTypes)` | **Remove** | Same |
| 8-param: `(String,...,no type args)` | **Retain, fix chain** | Compiles cleanly; currently telescopes into 9-param which is removed — update to call `create(ChannelCreateRequest)` directly instead |
| 4-, 5-, 6-param: `(String,...,no type args)` | **Retain** | Compiles cleanly; 40+ call sites across `MessageOrderingTest`, `WaitForReplyTest`, `CollectAtomicityTest`, `MessageServiceTest`, `ChannelServiceTest`, and more. Removing them would require 14-arg constructor calls with 10 trailing nulls — making the API demonstrably worse for zero type-safety benefit (these overloads touch no type fields). Removing general convenience overloads is unnecessary complexity per principle 6; the smell being addressed here is the String-accepting type-constraint overloads specifically |

The telescoping smell as a whole (#218) remains open and scoped separately.

**Callers of the removed 9- and 10-param overloads must switch to `new ChannelCreateRequest(...)` directly.**

---

### 2. `MessageType.serializeTypes(Set<MessageType>) → String`

Inverse of `parseTypes`. Returns `null` for `null` or empty set. Added as a static method on `MessageType` alongside `parseTypes`.

```java
public static String serializeTypes(Set<MessageType> types) {
    if (types == null || types.isEmpty()) return null;
    return types.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
}
```

**⚠ Observable behavior change:** output is in sorted canonical order (alphabetical by enum name). `"RESPONSE,COMMAND"` is stored as `"COMMAND,RESPONSE"`. Any code or test asserting an exact String value on `ChannelDetail.allowedTypes` or `ChannelDetail.deniedTypes` must use the sorted form. Tests that coincidentally use sorted input (like `SetChannelTypeConstraintsTest.setConstraints_channelDetailReflectsUpdate()` which uses `"COMMAND,RESPONSE"`) pass silently; tests with unsorted input will fail.

The sort is intentional: canonical form ensures the same logical set always produces the same stored string, making String equality on stored values reliable.

---

### 3. `ChannelCreateRequest` — fields become `Set<MessageType>`, nullable

```java
public record ChannelCreateRequest(
    // ... all other fields unchanged ...
    Set<MessageType> allowedTypes,   // was String; null means "open"
    Set<MessageType> deniedTypes,    // was String; null means "open"
    // ... connector binding unchanged ...
) {
    public ChannelCreateRequest {
        ChannelSlugValidator.validateSlugPath(name);
        // connector binding validation unchanged

        // Defensive copy — records must be immutable; caller mutation after construction
        // must not alter the validated state. Set.copyOf(null) throws NPE, hence the guard.
        // Null is preserved — not normalized to Set.of() — because null means "open" and
        // is a meaningful contract distinct from "empty allowed set (nothing permitted)".
        allowedTypes = allowedTypes != null ? Set.copyOf(allowedTypes) : null;
        deniedTypes  = deniedTypes  != null ? Set.copyOf(deniedTypes)  : null;

        // Validate overlap — both fields may be null (null means "open", not "block all")
        if (allowedTypes != null && !allowedTypes.isEmpty()
                && deniedTypes != null && !deniedTypes.isEmpty()) {
            Set<MessageType> overlap = new HashSet<>(allowedTypes);
            overlap.retainAll(deniedTypes);
            if (!overlap.isEmpty()) {
                throw new IllegalArgumentException(
                        "allowedTypes and deniedTypes must not intersect: " + overlap);
            }
        }
    }
}
```

**null is preserved in the record.** `req.allowedTypes()` may return `null`, meaning "no constraint". `Set.of()` (empty) and `null` are semantically identical at persistence (both serialize to `null` via `serializeTypes`), but the record API preserves the caller's intent.

**`simple()` factory** passes `null` for both — unchanged semantically.

**Existing tests that will break** (not "parse" tests — null-semantics tests):
- `ChannelCreateRequestTest.bothNullConstructsSuccessfully()` — asserts `req.allowedTypes().isNull()`. After change: still null — **passes unchanged**.
- `ChannelCreateRequestTest.simpleFactoryHasNullDeniedTypes()` — asserts `req.deniedTypes().isNull()`. After change: still null — **passes unchanged**.
- `ChannelCreateRequestTest.allowedAndDeniedWithNoOverlapConstructsSuccessfully()` — asserts `req.allowedTypes().isEqualTo("QUERY,COMMAND")`. After change: `req.allowedTypes()` is `Set<MessageType>`, not String — **assertion fails; rewrite to compare Sets**.
- `ChannelCreateRequestTest.deniedOnlyWithNullAllowedConstructsSuccessfully()` — asserts `req.deniedTypes().isEqualTo("EVENT")` — **same; rewrite**.
- `ChannelCreateRequestTest.allowedOnlyWithNullDeniedConstructsSuccessfully()` — same pattern — **rewrite**.
- `ChannelCreateRequestTest.invalidTypeNameInAllowedTypesThrows()` and `invalidTypeNameInDeniedTypesThrows()` — these test runtime rejection of bad String names. With typed `Set<MessageType>`, invalid names are impossible to pass (compile-time). **Delete these tests** — the protection is now provided by the type system.

---

### 4. `populateChannel()` — the actual break point in both services

Both `ChannelService.populateChannel()` (line 331) and `ReactiveChannelService.populateChannel()` (line 95) currently call:

```java
channel.allowedTypes = blankToNull(req.allowedTypes());  // breaks — req.allowedTypes() is now Set<MessageType>
channel.deniedTypes  = blankToNull(req.deniedTypes());   // same
```

Replace with serialization:

```java
channel.allowedTypes = MessageType.serializeTypes(req.allowedTypes());
channel.deniedTypes  = MessageType.serializeTypes(req.deniedTypes());
```

`Channel.allowedTypes/deniedTypes` remain `String` (TEXT DB columns) — no migration needed. `blankToNull()` is no longer called for type fields; it remains for `allowedWriters`, `adminInstances`.

---

### 5. `ChannelService.setTypeConstraints(UUID, Set<MessageType>, Set<MessageType>)`

```java
@Transactional
public Channel setTypeConstraints(final UUID channelId,
        final Set<MessageType> allowedTypes, final Set<MessageType> deniedTypes) {
    Set<MessageType> allowed = allowedTypes != null ? allowedTypes : Set.of();
    Set<MessageType> denied  = deniedTypes  != null ? deniedTypes  : Set.of();
    Set<MessageType> overlap = new HashSet<>(allowed);
    overlap.retainAll(denied);
    if (!overlap.isEmpty()) {
        throw new IllegalArgumentException(
                "allowed_types and denied_types must not overlap: " + overlap);
    }
    Channel ch = channelStore.find(channelId)
            .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelId));
    ch.allowedTypes = MessageType.serializeTypes(allowed);
    ch.deniedTypes  = MessageType.serializeTypes(denied);
    return ch;
}
```

---

### 6. `ReactiveChannelService.setTypeConstraints(UUID, Set<MessageType>, Set<MessageType>)`

Validation runs synchronously **before** `Panache.withTransaction()` — intentional, so it fires before the Vert.x thread switch. This structure is preserved:

```java
public Uni<Channel> setTypeConstraints(final UUID channelId,
        final Set<MessageType> allowedTypes, final Set<MessageType> deniedTypes) {
    // Validation before withTransaction — synchronous, no thread switch yet
    Set<MessageType> allowed = allowedTypes != null ? allowedTypes : Set.of();
    Set<MessageType> denied  = deniedTypes  != null ? deniedTypes  : Set.of();
    Set<MessageType> overlap = new HashSet<>(allowed);
    overlap.retainAll(denied);
    if (!overlap.isEmpty()) {
        throw new IllegalArgumentException(
                "allowed_types and denied_types must not overlap: " + overlap);
    }
    return Panache.withTransaction("qhorus", () -> channelStore.find(channelId)
            .map(opt -> opt.orElseThrow(
                    () -> new IllegalArgumentException("Channel not found: " + channelId)))
            .map(ch -> {
                ch.allowedTypes = MessageType.serializeTypes(allowed);
                ch.deniedTypes  = MessageType.serializeTypes(denied);
                return ch;
            }));
}
```

---

### 7. MCP tools — parse Strings at boundary

`@Tool` parameters in `create_channel` and `set_channel_type_constraints` remain `String` (nullable) — LLMs compose them as text and invalid names must be caught at the boundary with a user-facing error. Both blocking (`QhorusMcpTools`) and reactive (`ReactiveQhorusMcpTools`) tools call `MessageType.parseTypes()` before constructing the request or calling the service:

```java
// In create_channel @Tool:
Set<MessageType> allowedSet = MessageType.parseTypes(allowed_types);   // String @Tool param
Set<MessageType> deniedSet  = MessageType.parseTypes(denied_types);
// Then: new ChannelCreateRequest(..., allowedSet, deniedSet, ...)

// In set_channel_type_constraints @Tool:
Set<MessageType> allowedSet = MessageType.parseTypes(allowed_types);
Set<MessageType> deniedSet  = MessageType.parseTypes(denied_types);
channelService.setTypeConstraints(ch.id, allowedSet, deniedSet);
```

`SetChannelTypeConstraintsTest.unknownTypeName_throws()` remains valid — it tests LLM sending an unknown string through the MCP tool.

---

## Callers that break

| Location | What breaks | Fix |
|----------|-------------|-----|
| `ChannelService.create(String,...,String,String)` (10-param) | Won't compile after removing overload | **Remove.** Callers: 3 tests in `ChannelServiceTest` — update to `ChannelCreateRequest` |
| `ChannelService.create(String,...,String)` (9-param) | Same | **Remove.** Callers: `MessageServiceTypeEnforcementTest`, `NormativeLayoutRobustnessTest` (2×), `SecureCodeReviewScenario` (3×), `ChannelServiceTest` (3×), `ReactiveMessageServiceTest` (disabled), `ReactiveChannelTimelineTest` |
| `ReactiveChannelService` equivalent overloads (9-, 10-param) | Same | **Remove.** Update reactive callers similarly |
| `ChannelService.populateChannel()` line 331 | `blankToNull(req.allowedTypes())` — arg is `Set<MessageType>` | Replace with `MessageType.serializeTypes(req.allowedTypes())` |
| `ReactiveChannelService.populateChannel()` line 95 | Same | Same fix |
| `ChannelService.setTypeConstraints(UUID, String, String)` | Signature change | Replace with typed signature |
| `ReactiveChannelService.setTypeConstraints(UUID, String, String)` | Signature change | Replace with typed signature |
| `QhorusMcpTools.create_channel()` | Constructs `ChannelCreateRequest` with String fields | Parse at boundary → `Set<MessageType>` |
| `QhorusMcpTools.set_channel_type_constraints()` | Calls `setTypeConstraints(UUID, String, String)` | Parse at boundary → `Set<MessageType>` |
| `ReactiveQhorusMcpTools.create_channel()` | Same | Same |
| `ReactiveQhorusMcpTools.set_channel_type_constraints()` | Same | Same |
| `ChannelCreateRequestTest` (string-equality assertions) | 3 tests assert `.isEqualTo("TYPE_NAME")` on now-typed fields | Rewrite to compare `Set.of(MessageType.X)` |
| `ChannelCreateRequestTest` (invalid-name tests) | 2 tests test runtime rejection of invalid String names | Delete — protection is compile-time now |
| `SetChannelTypeConstraintsTest.setConstraints_channelDetailReflectsUpdate()` | Asserts `"COMMAND,RESPONSE"` — coincidentally sorted, passes | **Update:** pass `"RESPONSE,COMMAND"` as input and assert output is `"COMMAND,RESPONSE"` — this explicitly tests the normalization invariant rather than relying on alphabetically-lucky input |
| Any test asserting unsorted `ChannelDetail.allowedTypes/deniedTypes` as a String | Will see canonical sorted form | Update to sorted order |

---

## Not in scope

- `Channel.allowedTypes/deniedTypes` JPA field type — stays `String` (TEXT), no migration
- MCP `@Tool` parameter types — stay `String` (LLM interface)
- `StoredMessageTypePolicy.validate()` — reads `Channel.allowedTypes` String via `MessageType.parseTypes()`; no change needed
- 4-, 5-, 6-param `create()` overloads — retained (see §1)
- 8-param `create()` overload — retained but chain updated to call `create(ChannelCreateRequest)` directly (see §1)
- `ChannelCreateRequestSlugTest` — tests slug validation, unaffected

---

## Testing

- `ChannelCreateRequestTest` — rewrite string-equality assertions to Set equality; delete invalid-name tests; null-asserting tests (`bothNullConstructsSuccessfully`, `simpleFactoryHasNullDeniedTypes`) pass unchanged
- `ChannelServiceTest` — update removed-overload callers to `ChannelCreateRequest`; update `setTypeConstraints` callers to typed sets; add `setTypeConstraints_withNullSets_clearsConstraints`
- `SetChannelTypeConstraintsTest` — update `setConstraints_channelDetailReflectsUpdate` to pass unsorted input `"RESPONSE,COMMAND"` and assert sorted output `"COMMAND,RESPONSE"`; all other tests pass unchanged
- `MessageServiceTypeEnforcementTest`, `NormativeLayoutRobustnessTest`, `SecureCodeReviewScenario` — update to `ChannelCreateRequest`
- `ChannelCreateRequestSlugTest` — unaffected
