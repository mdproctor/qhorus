CREATE TABLE delivery_cursor (
    id                UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    channel_id        UUID         NOT NULL,
    backend_id        VARCHAR(255) NOT NULL,
    last_delivered_id BIGINT,
    updated_at        TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_delivery_cursor_channel_backend UNIQUE (channel_id, backend_id),
    CONSTRAINT fk_delivery_cursor_channel FOREIGN KEY (channel_id) REFERENCES channel(id) ON DELETE CASCADE
);
