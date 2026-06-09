ALTER TABLE channel
    ADD COLUMN tenancy_id VARCHAR(255) NOT NULL DEFAULT '278776f9-e1b0-46fb-9032-8bddebdcf9ce'; -- default (single-tenant) sentinel — see TenancyConstants.DEFAULT_TENANT_ID

ALTER TABLE channel DROP CONSTRAINT uq_channel_name;
ALTER TABLE channel ADD CONSTRAINT uq_channel_name_tenancy UNIQUE (tenancy_id, name);
