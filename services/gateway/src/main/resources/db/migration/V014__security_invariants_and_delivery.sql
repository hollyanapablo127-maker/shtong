CREATE TABLE IF NOT EXISTS iam.password_history (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), account_id uuid NOT NULL REFERENCES iam.account(id) ON DELETE RESTRICT,
  password_hash bytea NOT NULL, algorithm_parameters jsonb NOT NULL, changed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS iam.refresh_token (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), session_id uuid NOT NULL REFERENCES iam.session(id) ON DELETE RESTRICT,
  token_family_id uuid NOT NULL, token_hash bytea NOT NULL UNIQUE, audience varchar(32) NOT NULL,
  issued_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL, consumed_at timestamptz,
  revoked_at timestamptz, replaced_by_id uuid REFERENCES iam.refresh_token(id) ON DELETE RESTRICT,
  CHECK (expires_at > issued_at)
);

CREATE TABLE IF NOT EXISTS admin.idempotency_record (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), actor_account_id uuid, command_type varchar(100) NOT NULL,
  idempotency_key varchar(200) NOT NULL, request_digest bytea NOT NULL, response_status integer,
  response_content_type varchar(100), response_body bytea, created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL, UNIQUE(actor_account_id, command_type, idempotency_key),
  CHECK (octet_length(request_digest)=32)
);

CREATE TABLE IF NOT EXISTS admin.credential_result (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), source_type varchar(32) NOT NULL, source_id uuid NOT NULL,
  object_key text NOT NULL, sha256 bytea NOT NULL, created_by uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL, downloaded_at timestamptz, UNIQUE(source_type, source_id),
  CHECK (octet_length(sha256)=32)
);

CREATE TABLE IF NOT EXISTS admin.download_ticket (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), artifact_type varchar(32) NOT NULL, artifact_id uuid NOT NULL,
  token_hash bytea NOT NULL UNIQUE, issued_to uuid NOT NULL, issued_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL, consumed_at timestamptz, CHECK(expires_at > issued_at)
);

ALTER TABLE audit.audit_event ADD COLUMN IF NOT EXISTS actor_account_id uuid;
ALTER TABLE audit.audit_event ADD COLUMN IF NOT EXISTS reason_code text;
ALTER TABLE audit.audit_event ADD COLUMN IF NOT EXISTS previous_payload_hash bytea;
ALTER TABLE audit.audit_event ADD COLUMN IF NOT EXISTS authorization_evidence jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE audit.audit_event ADD COLUMN IF NOT EXISTS resources jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE audit.audit_event ADD COLUMN IF NOT EXISTS changes jsonb NOT NULL DEFAULT '[]'::jsonb;

CREATE OR REPLACE FUNCTION audit.reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'audit ledger is append-only';
END $$;

DROP TRIGGER IF EXISTS audit_event_append_only ON audit.audit_event;
CREATE TRIGGER audit_event_append_only BEFORE UPDATE OR DELETE ON audit.audit_event
FOR EACH ROW EXECUTE FUNCTION audit.reject_mutation();
DROP TRIGGER IF EXISTS audit_subject_append_only ON audit.audit_event_subject;
CREATE TRIGGER audit_subject_append_only BEFORE UPDATE OR DELETE ON audit.audit_event_subject
FOR EACH ROW EXECUTE FUNCTION audit.reject_mutation();

CREATE INDEX IF NOT EXISTS idx_account_lifecycle_created ON iam.account(lifecycle_status, created_at, id);
CREATE INDEX IF NOT EXISTS idx_session_account_active ON iam.session(account_id, expires_at) WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_refresh_family_active ON iam.refresh_token(token_family_id, expires_at) WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_role_assignment_account_active ON iam.role_assignment(account_id, valid_from) WHERE status='ACTIVE';
CREATE INDEX IF NOT EXISTS idx_audit_event_occurred ON audit.audit_event(occurred_at DESC, event_id);
CREATE INDEX IF NOT EXISTS idx_audit_event_actor ON audit.audit_event(actor_account_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_subject_subject ON audit.audit_event_subject(subject_id, recorded_date, event_id);

REVOKE UPDATE, DELETE, TRUNCATE ON audit.audit_event, audit.audit_event_subject FROM PUBLIC, shtong_runtime, shtong_audit_writer, shtong_audit_reader;
