---
id: PP-20260521-0ba358
title: "qhorus consumer migrations start at V2000; V1000–V1999 reserved for casehub-ledger"
type: rule
scope: repo
applies_to: "db/qhorus/migration/ — all Flyway migration files in the qhorus named datasource"
severity: critical
refs:
  - runtime/src/main/resources/application.properties
  - runtime/src/main/resources/db/qhorus/migration/V2000__agent_message_ledger_entry.sql
created: 2026-05-21
violation_hint: "V1001–V1999 filenames in db/qhorus/migration/ — these collide with casehub-ledger's base schema range"
---

The qhorus named datasource scans both `classpath:db/qhorus/migration` and `classpath:db/ledger/migration`. casehub-ledger owns V1000–V1999 in this combined namespace; any qhorus migration in that range causes a Flyway "found more than one migration with version N" failure. Consumer-owned migrations — subclass join tables and qhorus-specific schema extensions — must start at V2000. The standard qhorus domain sequence (V1–V10) is unaffected; this constraint applies only to migrations that depend on or extend casehub-ledger's base schema.
