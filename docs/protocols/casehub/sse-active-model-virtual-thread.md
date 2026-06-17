---
id: PP-20260617-e4ee6d
title: "SSE endpoints using blocking queue loops MUST use @RunOnVirtualThread, not @Blocking"
type: rule
scope: repo
applies_to: "runtime/api/ — JAX-RS SSE endpoints that use an active blocking model (BlockingQueue poll loop)"
severity: critical
refs:
  - runtime/src/main/java/io/casehub/qhorus/runtime/api/A2AResource.java
violation_hint: "Using @Blocking on an SSE handler with a long-lived blocking loop exhausts the Vert.x worker pool (default ~20 threads) under real load; without any blocking annotation the Vert.x I/O thread is blocked and the server freezes entirely"
garden_ref: "GE-20260617-c2ceb3"
created: 2026-06-17
---

SSE handlers that hold an open connection using a `BlockingQueue.poll()` loop for the
connection lifetime (up to `casehub.qhorus.a2a.sse.max-duration-seconds`, default 1800s)
MUST be dispatched on a Loom virtual thread via `@RunOnVirtualThread`
(`io.smallrye.common.annotation.RunOnVirtualThread`). `@Blocking` dispatches to the
finite Vert.x worker pool (default ~20 OS threads); a handler held open for 1800s
occupies one worker thread for that duration, exhausting the pool under any real SSE load.
No blocking annotation at all defaults to the Vert.x I/O thread, which freezes the
entire server when `poll()` blocks. Virtual threads are cheap and purpose-built for
long-lived blocking I/O — one per open SSE connection is the correct model.
