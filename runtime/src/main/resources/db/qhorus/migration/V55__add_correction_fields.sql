ALTER TABLE message ADD COLUMN corrects_message_id BIGINT;
ALTER TABLE message ADD COLUMN retraction BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE message ADD CONSTRAINT fk_message_corrects
    FOREIGN KEY (corrects_message_id) REFERENCES message(id);
CREATE INDEX idx_message_corrects ON message(corrects_message_id)
    WHERE corrects_message_id IS NOT NULL;
