-- Phase 10: Add paused flag to channel table for human-in-the-loop flow control
ALTER TABLE channel ADD COLUMN paused BOOLEAN NOT NULL DEFAULT FALSE;
