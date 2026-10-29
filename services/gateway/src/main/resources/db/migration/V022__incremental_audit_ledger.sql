-- Incremental persistence: a monotonic sequence lets each request append only its own
-- audit rows and lets every gateway instance catch up without rereading the ledger.
-- Adding the column rewrites the table without firing the append-only row triggers.
ALTER TABLE audit.audit_event ADD COLUMN IF NOT EXISTS ledger_seq bigint GENERATED ALWAYS AS IDENTITY;
CREATE UNIQUE INDEX IF NOT EXISTS ux_audit_event_ledger_seq ON audit.audit_event(ledger_seq);

-- Version 1 hashed only the event summary. Version 2 covers every stored field
-- (event id, occurrence time, subjects, resources, field changes and authorization evidence).
ALTER TABLE audit.audit_event ADD COLUMN IF NOT EXISTS hash_version smallint NOT NULL DEFAULT 1;
ALTER TABLE audit.audit_event DROP CONSTRAINT IF EXISTS ck_audit_hash_version;
ALTER TABLE audit.audit_event ADD CONSTRAINT ck_audit_hash_version CHECK (hash_version IN (1, 2));
