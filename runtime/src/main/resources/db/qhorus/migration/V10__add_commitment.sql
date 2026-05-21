-- Commitment: full obligation lifecycle for QUERY and COMMAND messages.
-- correlationId is the business key; id matches Message#commitmentId (same UUID, no join needed).
-- On HANDOFF, parent transitions to DELEGATED and a child is created with the same correlationId.
-- Compatible with H2 (test) and PostgreSQL (production).

CREATE TABLE commitment (
    id                   UUID         NOT NULL,
    correlation_id       VARCHAR(255) NOT NULL,
    channel_id           UUID         NOT NULL,
    message_type         VARCHAR(50)  NOT NULL,
    requester            VARCHAR(255) NOT NULL,
    obligor              VARCHAR(255),
    state                VARCHAR(50)  NOT NULL DEFAULT 'OPEN',
    expires_at           TIMESTAMP,
    acknowledged_at      TIMESTAMP,
    resolved_at          TIMESTAMP,
    delegated_to         VARCHAR(255),
    parent_commitment_id UUID,
    created_at           TIMESTAMP    NOT NULL,
    CONSTRAINT pk_commitment        PRIMARY KEY (id),
    CONSTRAINT fk_commitment_channel FOREIGN KEY (channel_id)           REFERENCES channel(id),
    CONSTRAINT fk_commitment_parent  FOREIGN KEY (parent_commitment_id) REFERENCES commitment(id)
);

CREATE INDEX idx_commitment_correlation_id ON commitment (correlation_id);
CREATE INDEX idx_commitment_channel_id     ON commitment (channel_id);
CREATE INDEX idx_commitment_state_expires  ON commitment (state, expires_at); -- list_stalled_obligations
