CREATE TABLE message_content_erasure_entry (
    id                UUID NOT NULL PRIMARY KEY,
    erased_entry_id   UUID NOT NULL,
    erased_message_id BIGINT NOT NULL,
    channel_id        UUID NOT NULL,
    original_digest   VARCHAR(255) NOT NULL,
    erasure_reason    VARCHAR(50) NOT NULL,
    CONSTRAINT fk_mce_ledger_entry FOREIGN KEY (id) REFERENCES ledger_entry(id)
);

CREATE INDEX idx_mce_erased_entry ON message_content_erasure_entry(erased_entry_id);
CREATE INDEX idx_mce_channel ON message_content_erasure_entry(channel_id);
