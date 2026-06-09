---
id: PP-20260609-e5ac14
title: "Every test module with casehub.ledger.enabled=true and Flyway disabled must provide import-qhorus-test.sql"
type: rule
scope: repo
applies_to: "runtime/src/test, examples/*/src/test — any module that enables the ledger and uses drop-and-create without Flyway"
severity: critical
refs:
  - runtime/src/test/resources/import-qhorus-test.sql
  - runtime/src/test/resources/application.properties
violation_hint: "SQLGrammarException: Table 'LEDGER_SUBJECT_SEQUENCE' not found when any ledger write is executed in @QuarkusTest"
garden_ref: "GE-20260607-ad3d62"
created: 2026-06-09
---

`ledger_subject_sequence` is not a JPA entity — Hibernate `drop-and-create` does not create it. When `casehub.ledger.enabled=true` and `quarkus.flyway.qhorus.migrate-at-start=false`, there is no path to create this table unless a SQL init script is provided. Every such test module must include `import-qhorus-test.sql` (containing `CREATE TABLE IF NOT EXISTS ledger_subject_sequence (subject_id UUID PRIMARY KEY, next_seq BIGINT NOT NULL)`) and set `quarkus.hibernate-orm.qhorus.sql-load-script=import-qhorus-test.sql` in its test `application.properties`. Tests must use fresh random UUIDs per run as subjectIds: rows in this table are not reset by `drop-and-create` because the table is invisible to Hibernate and survives Quarkus context restarts when `DB_CLOSE_DELAY=-1`.
