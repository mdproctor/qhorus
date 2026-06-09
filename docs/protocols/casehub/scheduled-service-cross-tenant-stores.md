---
id: PP-20260609-67996e
title: "@Scheduled and other no-request-context services must use @CrossTenant stores and explicit tenancyId — never inject CurrentPrincipal"
type: rule
scope: repo
applies_to: "casehub-qhorus runtime — any @Scheduled, @Observes StartupEvent, or async observer that reads or writes channel, message, commitment, or watchdog data"
severity: critical
refs:
  - docs/specs/2026-06-08-multi-tenancy-design.md
  - ../../../garden/docs/protocols/casehub/tenancy-repository-pattern.md
violation_hint: "A @Scheduled method injecting MessageStore, ChannelStore, CommitmentStore, or WatchdogStore directly — these inject CurrentPrincipal and throw ContextNotActiveException in scheduler threads"
garden_ref: GE-20260531-446fea
created: 2026-06-09
---

Quarkus `@Scheduled` methods, `@Observes StartupEvent` handlers, and any async observer run on threads with no CDI `@RequestScoped` context. Any JPA store that injects `CurrentPrincipal` (i.e. the tenant-filtered stores: `JpaChannelStore`, `JpaMessageStore`, `JpaCommitmentStore`, `JpaWatchdogStore`) will throw `ContextNotActiveException` when called from these threads. The correct pattern: inject `@CrossTenant`-qualified stores (produced by `CrossTenantProducer`) for all reads; pass the tenancyId as an explicit method parameter derived from the entity's stored field (e.g. `watchdog.tenancyId`, `channel.tenancyId`) rather than from `CurrentPrincipal`. When dispatching messages from a scheduler, pass `.tenancyId(entity.tenancyId)` on the `MessageDispatch.Builder` so `MessageService` does not attempt to read `currentPrincipal.tenancyId()`.
