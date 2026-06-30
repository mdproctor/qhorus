-- #313: Version counter for LAST_WRITE content-change detection
ALTER TABLE message ADD COLUMN version INT NOT NULL DEFAULT 0;
ALTER TABLE delivery_cursor ADD COLUMN last_delivered_version INT NOT NULL DEFAULT 0;
