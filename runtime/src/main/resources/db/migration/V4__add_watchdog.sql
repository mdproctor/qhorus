-- Phase 10: Watchdog table for condition-based alerting (optional module)
-- Active only when quarkus.qhorus.watchdog.enabled=true, but schema always created.
CREATE TABLE watchdog (
    id                   UUID         NOT NULL,
    condition_type       VARCHAR(50)  NOT NULL,
    target_name          VARCHAR(255) NOT NULL,
    threshold_seconds    INT,
    threshold_count      INT,
    notification_channel VARCHAR(255) NOT NULL,
    created_by           VARCHAR(255),
    created_at           TIMESTAMP    NOT NULL,
    last_fired_at        TIMESTAMP,
    CONSTRAINT pk_watchdog PRIMARY KEY (id)
);
