-- Phase 11 — Access control: per-channel rate limiting
-- rate_limit_per_channel: max messages per minute across all senders (NULL = unlimited)
-- rate_limit_per_instance: max messages per minute per sender (NULL = unlimited)
-- Counts are tracked in-memory (sliding 60-second window); limits reset on restart.
ALTER TABLE channel ADD COLUMN rate_limit_per_channel INTEGER;
ALTER TABLE channel ADD COLUMN rate_limit_per_instance INTEGER;
