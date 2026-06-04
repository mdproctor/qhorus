-- denied_types on channel: comma-separated MessageType names denied on this channel.
-- Null means no types are denied. Enforced by StoredMessageTypePolicy alongside allowed_types.
-- Deny always wins when a type appears in both allowed_types and denied_types.
ALTER TABLE channel ADD COLUMN denied_types TEXT;

-- Fix existing oversight channels: clear the too-narrow allowed_types and set denied_types = EVENT.
-- Targets channels whose name ends with '/oversight' — this is the naming convention
-- established by CaseChannel.channelName(caseId, "oversight").
-- NAMING DEPENDENCY: this UPDATE is coupled to the '/oversight' suffix convention.
-- If CaseChannel.channelName() changes the suffix, this migration will silently miss rows.
-- The FlywayMigrationSchemaTest verifies the column exists but cannot verify the data migration
-- (it runs against an empty schema). Any change to the oversight naming convention must be
-- coordinated with a complementary migration.
UPDATE channel
   SET allowed_types = NULL,
       denied_types  = 'EVENT'
 WHERE name LIKE '%/oversight';
