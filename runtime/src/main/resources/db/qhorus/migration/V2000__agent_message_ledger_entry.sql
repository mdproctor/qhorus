-- message_ledger_entry subclass table (JPA JOINED inheritance from ledger_entry)
-- Stores Qhorus-specific fields per speech-act message.
-- Compatible with H2 (dev/test) and PostgreSQL (production).
--
-- Version gap explained: V1-V10 = qhorus domain tables (named qhorus datasource).
-- V1000-V1999 = casehub-ledger base schema range.
-- V2000+ = consumer-owned ledger subclass join migrations. V2000 is the first qhorus join.
-- Next qhorus domain migration: V11.

CREATE TABLE message_ledger_entry (
    id            UUID         NOT NULL,
    channel_id    UUID         NOT NULL,
    message_id    BIGINT       NOT NULL,
    message_type  VARCHAR(50)  NOT NULL,
    target        VARCHAR(255),
    content       TEXT,
    correlation_id VARCHAR(255),
    commitment_id UUID,
    tool_name     VARCHAR(255),
    duration_ms   BIGINT,
    token_count   BIGINT,
    context_refs  TEXT,
    source_entity TEXT,
    CONSTRAINT pk_message_ledger_entry PRIMARY KEY (id),
    CONSTRAINT fk_message_ledger_entry FOREIGN KEY (id) REFERENCES ledger_entry (id)
);

CREATE INDEX idx_mle_channel       ON message_ledger_entry (channel_id);
CREATE INDEX idx_mle_message_id    ON message_ledger_entry (message_id);
CREATE INDEX idx_mle_correlation   ON message_ledger_entry (correlation_id);
