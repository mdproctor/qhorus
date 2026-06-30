# Delivery Metrics for AT_LEAST_ONCE Backends

**Issue:** #312  
**Date:** 2026-06-30  
**Status:** Approved  

## Problem

The delivery pump (#132) has no observability. Operators cannot see delivery
throughput, failure rates, backend health, or cursor lag.

## Design

All metrics owned by `DeliveryService`. `MeterRegistry` injected via CDI
constructor. CDI-free tests use `SimpleMeterRegistry`.

### Metrics

| Metric | Type | Tags | Source |
|--------|------|------|--------|
| `qhorus.delivery.messages.delivered` | Counter | backendId | `deliverPending()` — accumulated from batch results |
| `qhorus.delivery.failures` | Counter | backendId | `recordFailure()` |
| `qhorus.delivery.backends.unhealthy` | Gauge | (none) | `unhealthy.size()` — supplier gauge registered at startup |
| `qhorus.delivery.cursor.lag` | Gauge | backendId | `reconcileAll()` — `headId - cursor.lastDeliveredId` |

### Changes

1. `BatchResult` enum → record `BatchResult(Status status, int deliveredCount)`
2. `DeliveryService` constructor gains `MeterRegistry`
3. `deliverPending()` accumulates delivered count from batch results, increments counter
4. `recordFailure()` increments failure counter
5. `start()` registers `backends.unhealthy` supplier gauge
6. `reconcileAll()` computes cursor lag per backend via `findLastMessage()`

### Testing

CDI-free unit tests with `SimpleMeterRegistry`. Assertions use absolute
values (SimpleMeterRegistry starts fresh per instance — no monotonic
accumulation).
