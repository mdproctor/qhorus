CREATE TABLE slack_bot_binding (
    channel_id       UUID         NOT NULL,
    slack_channel_id VARCHAR(32)  NOT NULL,
    workspace_id     VARCHAR(32)  NOT NULL,
    created_at       TIMESTAMP    NOT NULL,
    CONSTRAINT pk_slack_bot_binding      PRIMARY KEY (channel_id),
    CONSTRAINT fk_slack_binding_channel  FOREIGN KEY (channel_id) REFERENCES channel(id),
    CONSTRAINT uq_slack_bot_slack_id     UNIQUE (slack_channel_id)
);
