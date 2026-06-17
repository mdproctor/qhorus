---
id: PP-20260617-a01c9d
title: "SSE keepalives MUST use named events (event: keepalive), not SSE comment lines"
type: rule
scope: repo
applies_to: "runtime/api/ — any SSE endpoint sending keepalive frames; tests using SseEventSource"
severity: important
refs:
  - runtime/src/main/java/io/casehub/qhorus/runtime/api/A2AResource.java
  - runtime/src/test/java/io/casehub/qhorus/runtime/api/A2AStreamIntegrationTest.java
violation_hint: "Using sse.newEventBuilder().comment('keepalive') causes RESTEasy SseEventSource (used in integration tests) to fire the event handler with empty name and data, corrupting event lists and breaking keepalive assertions"
garden_ref: "GE-20260617-cb0731"
created: 2026-06-17
---

SSE keepalive frames in this codebase MUST use a named event rather than an SSE comment
line. RESTEasy's `SseEventSource` client is non-compliant with the SSE spec: it fires the
registered event handler for comment-only frames (`sse.newEventBuilder().comment(...)`)
with empty name and data `""`, corrupting integration test event lists. Use
`sse.newEventBuilder().name("keepalive").data("").build()` instead — named events send
bytes over the wire (satisfying proxy idle-timeout requirements) and are filterable by
name in test event handlers (`if (!"keepalive".equals(event.getName()))`). Real SSE
clients (browsers, curl) correctly ignore unknown event names.
