-- Binds a Qhorus channel to an external connector for bi-directional message routing.
-- Exactly one binding per channel (channel_id is PK). One binding per connector+key pair
-- (uq_binding_key). external_key holds the per-conversation identifier: Slack channel ID
-- for Slack, sender's phone/email for SMS/WhatsApp/Email.
CREATE TABLE channel_connector_binding (
    channel_id            UUID         NOT NULL,
    inbound_connector_id  VARCHAR(64)  NOT NULL,
    external_key          VARCHAR(255) NOT NULL,   -- 255 covers RFC 5321 email (practical max 254)
    outbound_connector_id VARCHAR(64)  NOT NULL,
    outbound_destination  VARCHAR(512) NOT NULL,

    CONSTRAINT pk_channel_connector_binding
        PRIMARY KEY (channel_id),
    CONSTRAINT fk_binding_channel
        FOREIGN KEY (channel_id) REFERENCES channel(id),
    CONSTRAINT uq_binding_key
        UNIQUE (inbound_connector_id, external_key)
);
