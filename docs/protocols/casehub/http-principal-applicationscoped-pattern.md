---
id: PP-20260610-85e6a4
title: "HTTP-aware CDI principal reading from @RequestScoped holder must be @ApplicationScoped"
type: rule
scope: repo
applies_to: "runtime/identity/ — any CDI bean that reads from a @RequestScoped holder and must handle background-thread callers gracefully"
severity: critical
refs:
  - runtime/src/main/java/io/casehub/qhorus/runtime/identity/QhorusInboundCurrentPrincipal.java
  - runtime/src/main/java/io/casehub/qhorus/runtime/identity/InboundTenancyContext.java
  - docs/protocols/casehub/scheduled-service-cross-tenant-stores.md
violation_hint: "Outer CDI bean annotated @RequestScoped — background callers get ContextNotActiveException even though the method body has a try-catch. The CDI proxy throws before method entry."
garden_ref: "GE-20260610-f1982c"
created: 2026-06-10
---

When a CDI bean reads from a `@RequestScoped` holder and must fall back gracefully for background-thread callers (Scheduled, ObservesAsync, StartupEvent handlers), the **outer bean must be `@ApplicationScoped`** — never `@RequestScoped`. With `@RequestScoped` on the outer bean, the CDI client proxy throws `ContextNotActiveException` before the method body is entered; any `try-catch` inside the method is unreachable and the exception propagates. With `@ApplicationScoped`, the proxy always resolves, the method body IS entered, and the `catch(ContextNotActiveException)` fires when the inner `@RequestScoped` holder is accessed outside a request scope. Pattern: `QhorusInboundCurrentPrincipal @ApplicationScoped` delegates to `InboundTenancyContext @RequestScoped` — the outer bean is stateless; per-request state lives in the holder.
