---
id: PP-20260607-d83ba5
title: "LedgerEntryRepository implementations must query FROM LedgerEntry, not a subtype"
type: rule
scope: repo
applies_to: "LedgerEntryJpaRepository, ReactiveLedgerEntryJpaRepository, any future LedgerEntryRepository or ReactiveLedgerEntryRepository implementation in casehub-qhorus"
severity: critical
violation_hint: "JPQL string contains FROM MessageLedgerEntry (or any subtype) inside a class that implements LedgerEntryRepository or ReactiveLedgerEntryRepository; or em.find(MessageLedgerEntry.class, id) used for a cross-dtype lookup"
refs:
  - docs/specs/2026-06-07-ledger-dtype-scope-and-uuid-first-channels.md
created: 2026-06-07
---

Any class implementing `LedgerEntryRepository` or `ReactiveLedgerEntryRepository` must use
`FROM LedgerEntry` (the JPA base class) in all JPQL queries and `em.find(LedgerEntry.class, id)`
for primary-key lookups — never a concrete subtype. Using `FROM MessageLedgerEntry` silently
excludes all other `LedgerEntry` subtypes (domain entries such as `AmlCaseOpenedLedgerEntry`,
`PlainLedgerEntry`, etc.) from cross-dtype operations including sequence assignment, subject
history, and entry-by-ID resolution. This directly causes `IDX_LEDGER_ENTRY_SUBJECT_SEQ`
constraint violations when consuming applications write domain entries to the same `subjectId`
before dispatching a qhorus message. Qhorus-specific repositories (`MessageLedgerEntryRepository`,
`ReactiveMessageLedgerEntryRepository`) that do NOT implement the interface may use subtypes
intentionally. Refs qhorus#253.
