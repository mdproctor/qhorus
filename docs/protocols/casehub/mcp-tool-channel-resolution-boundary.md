---
id: PP-20260606-f899bc
title: "Resolve channel identifier at the @Tool boundary, not inside private helpers"
type: rule
scope: repo
applies_to: "QhorusMcpTools, ReactiveQhorusMcpTools, any future @Tool class in casehub-qhorus"
severity: important
violation_hint: "A private blockingXxx() helper calls channelService.findByName() or findById() directly on a raw input that may be a UUID string"
created: 2026-06-06
---

Any `@Tool` method that accepts a `channel` parameter (UUID or name) must resolve it to a `Channel` entity at the `@Tool` method boundary — via `resolveChannel()` (blocking) or `resolveChannelAsync()` (reactive Category A). After resolution, all six UUID-first service mutation methods (`setRateLimits`, `setAllowedWriters`, `setAdminInstances`, `pause`, `resume`, `delete`) receive `ch.id` (UUID), not `ch.name`. Private helpers that do not call a UUID-first service method may receive `ch.name` for read-only lookups. For compound tools such as `request_approval`, a single resolution at the `@Tool` boundary threads the resolved entity into all internal calls — preventing double-lookup and ensuring UUID inputs are transparently handled. Refs qhorus#237, qhorus#252.
