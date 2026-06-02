-- denied_types on channel: comma-separated MessageType names denied on this channel.
-- Null means no types are denied. Enforced by StoredMessageTypePolicy alongside allowed_types.
-- Deny always wins when a type appears in both allowed_types and denied_types.
ALTER TABLE channel ADD COLUMN denied_types TEXT;

-- Fix existing oversight channels: clear the too-narrow allowed_types and set denied_types = EVENT.
-- Targets channels whose name ends with '/oversight' — this is the naming convention
-- established by CaseChannel.channelName(caseId, "oversight").
UPDATE channel
   SET allowed_types = NULL,
       denied_types  = 'EVENT'
 WHERE name LIKE '%/oversight';
