---
id: PP-20260612-ch-dual-id
title: "Channel dual identity — UUID (machine) + slug (semantic)"
type: rule
scope: repo
applies_to: "QhorusMcpTools, ReactiveQhorusMcpTools, ChannelService, all call sites that reference channels"
severity: important
refs:
  - runtime/src/main/java/io/casehub/qhorus/runtime/mcp/QhorusMcpToolsBase.java
  - runtime/src/main/java/io/casehub/qhorus/runtime/channel/ChannelService.java
  - docs/protocols/casehub/mcp-tool-channel-resolution-boundary.md
created: 2026-06-12
---

# Protocol: Channel Dual Identity (UUID + Slug)

Every Qhorus channel has two identities that serve different purposes:

| Identity | Type | Properties | Use |
|----------|------|------------|-----|
| **UUID** | `java.util.UUID` | Opaque, generated at creation, immutable forever | Machine-to-machine, cross-repo references, JPA foreign keys |
| **slug** | `String` | Semantic, human-readable, unique per tenant, immutable after creation | Human/LLM use, MCP tool parameters, logging, agent card |

---

## Slug format

Slugs follow `[a-z][a-z0-9-]{0,79}` — enforced by the `chk_channel_name_slug` CHECK constraint (V17 migration). Any channel creation with a non-conforming name is rejected at the DB level.

**Immutability:** Slugs are immutable after creation. There is no rename operation — if a channel's semantic meaning changes, create a new channel and migrate participants. Mutable slugs would invalidate cross-session references held by LLMs and invalidate the dual-identity contract.

---

## MCP tool resolution — accept either, resolve at the boundary

All `@Tool`-annotated methods that take a `channel` parameter call `resolveChannel(String)` (or `resolveChannelAsync` in the reactive stack) at the tool boundary before passing anything to the service layer.

`resolveChannel` implements two-phase parsing:
1. Try to parse the input as a UUID (`tryParseUuid`). If it succeeds, look up by ID.
2. If UUID parse fails, treat the input as a slug and look up by name.

Both paths return the full `Channel` entity. Call sites use `ch.id` (UUID) for all mutation service calls and `ch.name` (slug) for display/logging only.

**Rule:** Resolution happens exactly once — at the `@Tool` boundary. Never parse UUID or look up by name inside service methods. Service mutations accept `UUID` only.

---

## Responses — always include both

All channel-referencing tool responses must surface both identities:

```json
{
  "channelId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "channelName": "pr-review-42-work",
  ...
}
```

This allows callers to use the UUID in subsequent machine calls and the slug in human-readable output without a second lookup.

---

## Cross-repo references

When storing a reference to a channel in another repo (e.g. `casehub-engine` storing a channel reference for an agent case), always store the UUID. Slugs are ergonomic and tenant-scoped; UUIDs are globally stable opaque identifiers. A stored slug that is never renamed is still the wrong type for a FK-style reference.

---

## Common mistakes

| Mistake | Why it's wrong |
|---------|----------------|
| Passing slug to `ChannelService.findById(UUID)` | Won't compile — but callers sometimes build the UUID from a string without validating format |
| Resolving channel inside a service method | Violates the resolution-boundary rule; service methods accept `UUID` only |
| Omitting `channelName` from a tool response | Forces the LLM to do a second lookup; tool responses must include both |
| Storing a slug as a cross-repo reference | Slugs are tenant-scoped and logically immutable but semantically drift; UUIDs are the right FK type |
