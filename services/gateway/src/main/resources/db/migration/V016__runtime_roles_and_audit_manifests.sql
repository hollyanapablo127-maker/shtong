ALTER ROLE shtong_runtime INHERIT;
GRANT shtong_audit_writer TO shtong_runtime;

GRANT SELECT, INSERT, UPDATE ON iam.password_history, iam.refresh_token TO shtong_runtime;
GRANT SELECT, INSERT, UPDATE ON admin.idempotency_record, admin.credential_result, admin.download_ticket TO shtong_runtime;

-- V014 introduced the link column after audit rows could already exist. Build the
-- deterministic chain before making the invariant mandatory. The append-only
-- trigger is disabled only inside this migration transaction.
ALTER TABLE audit.audit_event DISABLE TRIGGER audit_event_append_only;
WITH ordered AS (
  SELECT recorded_date,
         event_id,
         COALESCE(
           lag(payload_hash) OVER (ORDER BY occurred_at, event_id),
           decode(repeat('00', 32), 'hex')
         ) AS expected_previous_hash
  FROM audit.audit_event
)
UPDATE audit.audit_event AS event
SET previous_payload_hash = ordered.expected_previous_hash
FROM ordered
WHERE event.recorded_date = ordered.recorded_date
  AND event.event_id = ordered.event_id
  AND event.previous_payload_hash IS NULL;
ALTER TABLE audit.audit_event ENABLE TRIGGER audit_event_append_only;

ALTER TABLE audit.audit_event
  ADD CONSTRAINT ck_audit_previous_payload_hash_length
  CHECK (previous_payload_hash IS NOT NULL AND octet_length(previous_payload_hash) = 32) NOT VALID;
ALTER TABLE audit.audit_event VALIDATE CONSTRAINT ck_audit_previous_payload_hash_length;

DROP TRIGGER IF EXISTS audit_manifest_append_only ON audit.manifest;
CREATE TRIGGER audit_manifest_append_only BEFORE UPDATE OR DELETE ON audit.manifest
FOR EACH ROW EXECUTE FUNCTION audit.reject_mutation();

REVOKE UPDATE, DELETE, TRUNCATE ON audit.manifest FROM PUBLIC, shtong_runtime, shtong_audit_writer, shtong_audit_reader;
