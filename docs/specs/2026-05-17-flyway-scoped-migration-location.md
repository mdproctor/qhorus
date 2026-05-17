# Flyway Scoped Migration Location

**Issue:** #142  
**Date:** 2026-05-17  
**Status:** Approved

---

## Problem

`casehub-qhorus` places its Flyway migrations in `db/migration/` — the same classpath location as `casehub-work`. When both extensions are on the same classpath, Flyway's scan finds two scripts at V2 and refuses to start:

```
FlywayException: Found more than one migration with version 2
```

## Fix

Move all qhorus migration files from `db/migration/` to `db/migration/qhorus/` and update the Flyway location config:

```properties
quarkus.flyway.qhorus.locations=classpath:db/migration/qhorus
```

Version numbers are unchanged (V1–V9, V1003). The directory scope eliminates the conflict — each Flyway instance scans only its own subdirectory, and version numbers are module-local.

## Files

**Move** (rename only — content unchanged):

| From | To |
|------|----|
| `runtime/src/main/resources/db/migration/V1__initial_schema.sql` | `runtime/src/main/resources/db/migration/qhorus/V1__initial_schema.sql` |
| `runtime/src/main/resources/db/migration/V2__add_message_target.sql` | `runtime/src/main/resources/db/migration/qhorus/V2__add_message_target.sql` |
| `runtime/src/main/resources/db/migration/V3__add_channel_paused.sql` | `runtime/src/main/resources/db/migration/qhorus/V3__add_channel_paused.sql` |
| `runtime/src/main/resources/db/migration/V4__add_watchdog.sql` | `runtime/src/main/resources/db/migration/qhorus/V4__add_watchdog.sql` |
| `runtime/src/main/resources/db/migration/V5__add_channel_acl.sql` | `runtime/src/main/resources/db/migration/qhorus/V5__add_channel_acl.sql` |
| `runtime/src/main/resources/db/migration/V6__add_channel_admin_instances.sql` | `runtime/src/main/resources/db/migration/qhorus/V6__add_channel_admin_instances.sql` |
| `runtime/src/main/resources/db/migration/V7__add_channel_rate_limits.sql` | `runtime/src/main/resources/db/migration/qhorus/V7__add_channel_rate_limits.sql` |
| `runtime/src/main/resources/db/migration/V8__add_instance_read_only.sql` | `runtime/src/main/resources/db/migration/qhorus/V8__add_instance_read_only.sql` |
| `runtime/src/main/resources/db/migration/V9__add_actor_type_to_message.sql` | `runtime/src/main/resources/db/migration/qhorus/V9__add_actor_type_to_message.sql` |
| `runtime/src/main/resources/db/migration/V1003__agent_message_ledger_entry.sql` | `runtime/src/main/resources/db/migration/qhorus/V1003__agent_message_ledger_entry.sql` |

**Update** `runtime/src/main/resources/application.properties`:

```properties
# before
quarkus.flyway.qhorus.locations=db/migration

# after
quarkus.flyway.qhorus.locations=classpath:db/migration/qhorus
```

## Testing

The existing test suite validates Flyway runs correctly. After the move, the full `mvn test -pl runtime` suite must pass with 0 failures — confirming migrations apply cleanly from the new location.

No new tests are needed: Flyway startup failure is immediate and the existing tests already exercise the full schema.

## Deferred

- #155 — formalize directory-scoping as platform convention for all casehubio extensions
