---
id: PP-20260610-9487d3
title: "X-Tenancy-ID is a routing header, not a security boundary — document this explicitly in any HTTP tenant routing code"
type: rule
scope: repo
applies_to: "runtime/identity/ and runtime/api/ — any HTTP-layer component that reads X-Tenancy-ID or exposes tenant-routed endpoints"
severity: important
refs:
  - runtime/src/main/java/io/casehub/qhorus/runtime/identity/QhorusInboundCurrentPrincipal.java
  - runtime/src/main/java/io/casehub/qhorus/runtime/api/AgentCardResource.java
  - docs/specs/2026-06-10-a2a-agentcard-ledger-tenant-scoping-design.md
violation_hint: "HTTP tenant routing code that does not document the non-security nature of X-Tenancy-ID leaves callers with a false impression that it provides isolation."
created: 2026-06-10
---

Any code that reads the `X-Tenancy-ID` request header for tenant routing must clearly document — in Javadoc, class-level comment, or Javadoc on the relevant method — that this header is **not a security boundary**. Any HTTP caller can claim any tenant by including this header. The mechanism is appropriate only when network isolation (firewall, mTLS, gateway policy) enforces trust. Production multi-tenant deployments requiring genuine cross-tenant isolation must use `casehub-platform-oidc`, which provides `OidcCurrentPrincipal @Priority(100)` that reads tenancyId from a JWT claim and displaces `QhorusInboundCurrentPrincipal`. This rule prevents future developers from incorrectly assuming `X-Tenancy-ID` is authenticated.
