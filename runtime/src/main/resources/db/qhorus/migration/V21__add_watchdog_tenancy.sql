ALTER TABLE watchdog
    ADD COLUMN tenancy_id VARCHAR(255) NOT NULL DEFAULT '278776f9-e1b0-46fb-9032-8bddebdcf9ce'; -- default (single-tenant) sentinel — see TenancyConstants.DEFAULT_TENANT_ID
