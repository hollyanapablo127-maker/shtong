CREATE TABLE IF NOT EXISTS admin.runtime_checkpoint (
  checkpoint_key varchar(40) PRIMARY KEY,
  payload bytea NOT NULL,
  payload_sha256 bytea NOT NULL,
  state_version bigint NOT NULL DEFAULT 1,
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (octet_length(payload_sha256) = 32)
);

CREATE TABLE IF NOT EXISTS admin.account_command (
  id uuid PRIMARY KEY, command_type varchar(32) NOT NULL, state varchar(24) NOT NULL,
  requested_by uuid NOT NULL, reason text NOT NULL, target_manifest jsonb NOT NULL,
  result_count integer, failure_code varchar(100), created_at timestamptz NOT NULL,
  completed_at timestamptz, row_version bigint NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS admin.control_request (
  id uuid PRIMARY KEY, command_type varchar(100) NOT NULL, state varchar(24) NOT NULL,
  requested_by uuid NOT NULL, requested_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
  decided_by uuid, decided_at timestamptz, reason text NOT NULL, target_manifest jsonb NOT NULL,
  command_hash bytea NOT NULL, organization_revision bigint NOT NULL, authorization_revision bigint NOT NULL,
  row_version bigint NOT NULL DEFAULT 1, CHECK (octet_length(command_hash) = 32)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_audit_outbox_event ON audit.outbox(event_recorded_date,event_id);
CREATE INDEX IF NOT EXISTS idx_control_request_pending ON admin.control_request(state,expires_at) WHERE state='PENDING';
CREATE INDEX IF NOT EXISTS idx_account_command_requester ON admin.account_command(requested_by,created_at DESC);

GRANT SELECT, INSERT, UPDATE ON admin.runtime_checkpoint, admin.account_command, admin.control_request TO shtong_runtime;
