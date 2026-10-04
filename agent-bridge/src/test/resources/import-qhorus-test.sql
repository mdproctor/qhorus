-- Creates native SQL tables that are not JPA entities and therefore
-- not created by Hibernate drop-and-create. Required for LedgerSequenceAllocator
-- which executes MERGE INTO ledger_subject_sequence. Refs qhorus#256.
CREATE TABLE IF NOT EXISTS ledger_subject_sequence (
    subject_id UUID        PRIMARY KEY,
    next_seq   BIGINT      NOT NULL
);
