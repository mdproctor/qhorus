# Schema Cleanup — pending_reply removal and commitment migration (#157)

**Date:** 2026-05-19  
**Issue:** casehubio/qhorus#157  
**Epic:** epic-a2a-lifecycle-cleanup

## Problem

Two schema gaps:

1. `pending_reply` table in `V1__initial_schema.sql` was created for the Phase 4 `wait_for_reply` correlation mechanism. It was superseded by `CommitmentStore` and removed from all code, but its DDL remains in V1 — a dead table in the canonical initial schema.

2. The `commitment` table (backing `Commitment.java` and `CommitmentStore`) has no Flyway migration. Tests work because `drop-and-create` is configured, but the schema is undeployable via Flyway in any non-test environment.

## Design

### V1 edit — remove `pending_reply`

Remove the `pending_reply` CREATE TABLE block (lines 68–81) from `V1__initial_schema.sql`. This is safe: the project has no production deployments, so no Flyway checksum has been validated against V1 in any live database. V1 becomes the accurate, complete initial schema with no dead tables.

Tables retained in V1: `channel`, `instance`, `capability`, `message`, `shared_data`, `artefact_claim`.

### V10__add_commitment.sql — commitment table

New migration derived directly from `Commitment.java`:

```sql
CREATE TABLE commitment (
    id                   UUID         NOT NULL,
    correlation_id       VARCHAR(255) NOT NULL,
    channel_id           UUID         NOT NULL,
    message_type         VARCHAR(50)  NOT NULL,
    requester            VARCHAR(255) NOT NULL,
    obligor              VARCHAR(255),
    state                VARCHAR(50)  NOT NULL,
    expires_at           TIMESTAMP,
    acknowledged_at      TIMESTAMP,
    resolved_at          TIMESTAMP,
    delegated_to         VARCHAR(255),
    parent_commitment_id UUID,
    created_at           TIMESTAMP    NOT NULL,
    CONSTRAINT pk_commitment PRIMARY KEY (id),
    CONSTRAINT fk_commitment_channel FOREIGN KEY (channel_id) REFERENCES channel(id),
    CONSTRAINT fk_commitment_parent  FOREIGN KEY (parent_commitment_id) REFERENCES commitment(id)
);
CREATE INDEX idx_commitment_correlation_id ON commitment(correlation_id);
CREATE INDEX idx_commitment_channel_id     ON commitment(channel_id);
```

FKs: `channel_id → channel(id)` (no CASCADE, consistent with `fk_message_channel`); `parent_commitment_id → commitment(id)` (self-referential, nullable).

Indexes: `correlation_id` (primary lookup key for `CommitmentStore.findByCorrelationId`); `channel_id` (for future cleanup by channel).

## Deferred

**#171** — `delete_channel` must call `commitmentStore.deleteAll(channelId)` before `channelStore.delete(channelId)` once `fk_commitment_channel` is live. Without this fix, deleting a channel with any associated commitments will fail with an FK violation.

## Verification

- `mvn test -pl runtime` — existing suite passes unchanged (tests use `drop-and-create`)
- SQL reviewed against all columns in `Commitment.java` — no drift
