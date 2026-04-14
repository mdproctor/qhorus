-- Phase 6: Add target field to message table for instance/capability/role addressing
ALTER TABLE message ADD COLUMN target VARCHAR(255);
