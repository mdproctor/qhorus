-- Phase 11 — Access control: per-channel admin role
-- admin_instances: nullable TEXT — comma-separated instance IDs permitted to invoke
-- management operations (pause/resume/force_release/clear_channel).
-- NULL = open governance (any caller permitted, existing behaviour).
ALTER TABLE channel ADD COLUMN admin_instances TEXT;
