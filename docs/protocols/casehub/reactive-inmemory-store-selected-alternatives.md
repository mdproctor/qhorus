---
id: PP-20260618-131cdf
title: "Consumers enabling the reactive stack must list InMemoryReactive*Store beans in quarkus.arc.selected-alternatives"
type: rule
scope: repo
applies_to: "Any @QuarkusTest consumer that both: (1) enables casehub.qhorus.reactive.enabled=true and (2) adds casehub-qhorus-persistence-memory as a test dependency"
severity: important
refs:
  - examples/type-system/src/test/resources/application.properties
  - CLAUDE.md
violation_hint: "ReactiveJpaChannelStore (or sibling) is injected in tests instead of InMemoryReactiveChannelStore — dispatch triggers a reactive datasource call and fails with QueryParameterException or UnsatisfiedResolutionException. Refs claudony#155, qhorus#288."
created: 2026-06-18
---

When `casehub.qhorus.reactive.enabled=true` is set in a test profile, the `ReactiveJpa*Store` beans become active CDI beans (their `@IfBuildProperty` gate passes). `@Alternative @Priority(1)` on `InMemoryReactive*Store` beans from `casehub-qhorus-persistence-memory` is not sufficient to override them in the Quarkus CDI container when both are present — the InMemory stores must be explicitly declared in `quarkus.arc.selected-alternatives`. The canonical reference list (covering all five reactive InMemory stores) lives in `examples/type-system/src/test/resources/application.properties`. Copy that list into your consumer's test `application.properties` when enabling the reactive stack.
