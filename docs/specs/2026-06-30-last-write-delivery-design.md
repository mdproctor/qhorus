# AT_LEAST_ONCE Delivery for LAST_WRITE Semantics

**Issue:** #313  
**Date:** 2026-06-30  
**Status:** Approved  

## Problem

LAST_WRITE channels overwrite message content in-place (same row, same ID).
The cursor-based delivery pump tracks by `lastDeliveredId` — once the cursor
passes a message ID, content changes to that ID are invisible. Tracked
backends on LAST_WRITE channels never see updates.

## Design

### Version Counter

Add `version` (int, default 0) to `Message`. LAST_WRITE overwrites increment
it; normal inserts remain at 0. This is the minimal signal that in-place
content changed.

### Cursor Extension

Add `lastDeliveredVersion` (int, default 0) to `DeliveryCursor`. After
delivering a message, the cursor stores both `lastDeliveredId` and
`lastDeliveredVersion`.

### Delivery Signal on Overwrite

LAST_WRITE overwrite in `MessageService` signals the delivery pump via
`DeliverySignalQueue.signal(channelId)` — same path as normal message
dispatch. This gives event-driven delivery instead of waiting for the
30s reconciler.

`fanOut()` is NOT called on overwrite — BEST_EFFORT backends still don't
see overwrites. Only the cursor-based pump (AT_LEAST_ONCE backends) picks
up the change via the version signal.

### Batch Query Extension

`DeliveryBatchExecutor.deliverBatch()` query becomes:

```
id > cursor.lastDeliveredId
OR (id = cursor.lastDeliveredId AND version > cursor.lastDeliveredVersion)
```

The second clause detects in-place updates to the cursor's current position.

### Migration

V26: add `version` (int, default 0) to `message` table and
`last_delivered_version` (int, default 0) to `delivery_cursor` table.

### Changes

| File | Change |
|------|--------|
| `Message.java` | Add `int version` field (default 0) |
| `DeliveryCursor.java` | Add `int lastDeliveredVersion` field (default 0) |
| `MessageService.java` | LAST_WRITE overwrite: increment `version`, signal delivery pump |
| `ReactiveMessageService.java` | Same as above for reactive path |
| `DeliveryBatchExecutor.java` | Extended query: version comparison clause; update cursor version |
| `MessageQuery.java` | Add `afterVersion` parameter for the version clause |
| `InMemoryMessageStore` | Support version-aware query in `scan()` |
| `V26__message_version.sql` | Add both columns |

### Testing

- CDI-free unit test in `DeliveryServiceTest`: LAST_WRITE overwrite → cursor version check → re-delivery
- `MessageService` integration test: LAST_WRITE overwrite signals delivery pump
- InMemory store contract test: version-aware scan
