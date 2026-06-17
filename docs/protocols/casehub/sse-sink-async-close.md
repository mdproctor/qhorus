---
id: PP-20260613-3a569e
title: "SseEventSink.send() is async — close only after awaiting completion, not before"
type: rule
scope: repo
applies_to: "runtime/api/ — any JAX-RS SSE endpoint using SseEventSink"
severity: important
refs:
  - runtime/src/main/java/io/casehub/qhorus/runtime/api/A2AResource.java
violation_hint: "Calling sink.close() synchronously immediately after sink.send() (without awaiting) logs IllegalStateException: Response has already been written at ERROR level on every terminal SSE send"
garden_ref: "GE-20260613-c29bb8"
created: 2026-06-13
---

`SseEventSink.send()` in Quarkus RESTEasy Reactive returns a `CompletionStage<?>` whose
completion signals that the HTTP write has finished. `sink.close()` must only be called
after that completion — calling it before the write finishes causes `IllegalStateException:
Response has already been written`.

### Pattern A — passive model (Vert.x I/O thread or @Blocking)

Chain `sink.close()` inside `.thenRun()` or `.whenComplete()`:
```java
sink.send(event).thenRun(() -> { if (!sink.isClosed()) sink.close(); });
```

### Pattern B — active model (@RunOnVirtualThread blocking loop)

On a virtual thread, `.get(5, SECONDS)` awaits the write synchronously (parks the VT, no
OS thread blocked). Call `sink.close()` in a `finally` block after the await:
```java
try {
    sink.send(event).toCompletableFuture().get(5, TimeUnit.SECONDS);
} finally {
    if (!sink.isClosed()) sink.close();
}
```

Pattern B is the correct form for `sendStatusEvent` and `sendErrorEvent` helpers since
`streamTask()` is `@RunOnVirtualThread`. The old Pattern A form (`thenRun`) is only correct
on I/O-thread or `@Blocking` dispatched methods where `.get()` would block an OS thread.
