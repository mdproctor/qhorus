---
id: PP-20260612-bd6f8c
title: "Credentials and PII must not appear in MessageDispatch.content or any ledger-persisted field"
type: rule
scope: repo
applies_to: "All callers of MessageService.dispatch() that handle external delivery destinations, connector parameters, or user-provided contact data; ConnectorMeshBridge implementations"
severity: critical
refs:
  - connector-backend/src/main/java/io/casehub/qhorus/connector/backend/ConnectorQhorusMeshBridge.java
violation_hint: "MessageDispatch.builder().content(webhookUrl + ': ' + body) — webhook URL (a credential) written into immutable MessageLedgerEntry.content; phone/email addresses included verbatim in STATUS content"
created: 2026-06-12
---

The qhorus audit ledger is immutable and Merkle-chained — once a `MessageLedgerEntry` is written, it cannot be corrected or redacted. Credentials (webhook URLs, API keys, tokens) and PII (phone numbers, email addresses, user-identifiable data) must never appear in `MessageDispatch.content` or any other ledger-persisted field. Sanitize before dispatching: omit the sensitive value entirely, or replace it with a non-identifying identifier (e.g. connector type only, not destination URL). The `connectorId` alone is sufficient for categorisation and ledger queryability; the destination adds no audit value and introduces permanent credential or PII exposure risk.
