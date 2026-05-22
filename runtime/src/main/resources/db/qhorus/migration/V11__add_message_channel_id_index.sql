-- Composite index for per-channel timeline scans:
-- WHERE channel_id = ? AND id > ? ORDER BY id ASC
CREATE INDEX idx_message_channel_id ON message (channel_id, id);
