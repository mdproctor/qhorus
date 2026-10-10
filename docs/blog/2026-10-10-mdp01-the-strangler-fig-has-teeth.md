---
layout: post
title: "The Strangler Fig Has Teeth"
date: 2026-10-10
entry_type: note
subtype: diary
projects: [casehubio/qhorus]
tags: [cdi, registry, migration, strangler-fig, quarkus]
---

# The Strangler Fig Has Teeth

The idea was simple: wrap the platform's `RegistryService` behind qhorus's `InstanceManager` interface, gate it with `@IfBuildProperty`, and let CDI displacement do the rest. A thin adapter — maybe 80 lines of mapping code. Scale: S, Complexity: Low.

The mapping code was exactly that simple. The CDI ecosystem around it was not.

Three things went wrong on the path to a green integration test, and each one was invisible until it happened. First: the adapter had two constructors — a CDI one taking `RegistryService` plus two `Event<>` parameters, and a test convenience constructor delegating to it. With a single constructor, Quarkus ArC auto-selects it. With two, neither has `@Inject`, and ArC silently drops the bean. No error, no warning. The `@Alternative @Priority(100)` annotation becomes decoration on a class that doesn't exist in the container.

Second: the `InMemoryRegistryService` that backs the test lives in `casehub-platform-registry-inmem`, which ships without a Jandex index. Without `META-INF/jandex.idx`, Quarkus can't discover the CDI producer class inside the jar. The `NoOpRegistryService` — a `@DefaultBean` that silently drops every registration — wins by default. Registrations go in and vanish. Queries return empty. No error.

Third: the `@IfBuildProperty` gate uses `casehub.qhorus.instance.registry-backed` as its property name. That prefix — `casehub.qhorus` — is already owned by a `@ConfigMapping` interface. SmallRye Config validation rejects any property under a mapped prefix that doesn't correspond to a declared field. The property has to be registered in `QhorusConfig` before SmallRye will accept it, even though `@IfBuildProperty` consumes it independently at build time.

Each failure mode shares a signature: silent success. The adapter compiles. The tests compile. The application starts. Nothing throws. The old implementation just stays. You only discover the adapter isn't active when an integration test asserts `instanceof` and finds `InstanceService` instead.

The less obvious gap was behavioural, not mechanical. `InstanceService.register()` fires `InstanceRegisteredEvent` with a capability diff — previous versus current capabilities. `BroadcastMembershipManager` observes this to auto-create broadcast channels for each capability tag. The first version of the adapter didn't fire these events. Registrations would work, queries would return results, but the broadcast channel infrastructure would silently not materialise. A strangler fig adapter that delegates storage but forgets side effects isn't a drop-in replacement — it's a data-loss bug waiting for someone to enable it in production.

The fixed adapter resolves existing capabilities from the registry before registering (for the diff), fires both `InstanceRegisteredEvent` and `InstanceDeregisteredEvent`, and uses `Optional.isPresent()` rather than `!caps.isEmpty()` as the deregister event guard — because an instance with zero capabilities still deserves a lifecycle event.

What the issue description called a "thin adapter" turned out to need: a `@ConfigMapping` registration, an `@Inject` annotation on the right constructor, a `quarkus.index-dependency` directive, capability-diff computation, CDI event firing with null guards for the test path, and a `@TestProfile` with full datasource overrides. The mapping logic — the part described in the issue — is 40 lines. The ecosystem wiring that makes it actually work in a live CDI container is the other 100.

Strangler fig migrations in CDI frameworks carry a hidden cost that the pattern's name doesn't advertise. The old implementation isn't just doing storage — it's firing events, triggering observers, maintaining invariants. The adapter has to replicate all of that, or the displacement creates gaps that are invisible until a downstream consumer notices something missing.
