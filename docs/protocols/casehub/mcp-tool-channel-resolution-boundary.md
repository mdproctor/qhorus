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

Any `@Tool` method that accepts a `channel` parameter (UUID or name) must resolve it to a `Channel` entity at the `@Tool` method boundary — via `resolveChannel()` (blocking) or `resolveChannelAsync()` (reactive Category A). Private helpers (`blockingXxx`) receive a `String channelName` that is already the resolved name and must not perform their own channel lookup. For compound tools such as `request_approval`, a single resolution at the `@Tool` boundary threads the resolved `ch.name` into all internal calls — preventing double-lookup and ensuring that UUID inputs are transparently handled regardless of how many private helpers chain off the entry point.
