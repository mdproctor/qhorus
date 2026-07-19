CREATE TABLE channel_summary (
    id UUID PRIMARY KEY,
    channel_id UUID NOT NULL UNIQUE REFERENCES channel(id) ON DELETE CASCADE,
    content TEXT,
    updated_at TIMESTAMP,
    updated_by VARCHAR(255),
    last_updated_message_id BIGINT,
    update_after_messages INTEGER,
    update_after_seconds INTEGER,
    tenancy_id VARCHAR(255) NOT NULL DEFAULT '278776f9-e1b0-46fb-9032-8bddebdcf9ce'
);
