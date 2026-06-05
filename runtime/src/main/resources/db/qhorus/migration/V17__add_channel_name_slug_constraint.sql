-- Enforce slug format on channel names.
-- Every /-delimited segment must match [a-z][a-z0-9]*(-[a-z0-9]+)*.
-- Per-segment max length (80 chars) is enforced by Java.
-- REGEXP_LIKE used instead of SIMILAR TO for H2 compatibility (H2 2.x supports REGEXP_LIKE natively;
-- PostgreSQL 15+ also supports it). SIMILAR TO in CHECK constraints is not supported by H2.
-- Constraint is prospective: existing non-conformant rows are not validated retroactively.
ALTER TABLE channel
    ADD CONSTRAINT chk_channel_name_slug
    CHECK (REGEXP_LIKE(name, '^[a-z][a-z0-9]*(-[a-z0-9]+)*(/[a-z][a-z0-9]*(-[a-z0-9]+)*)*$')
           AND LENGTH(name) <= 200);
