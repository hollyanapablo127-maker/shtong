ALTER TABLE iam.account
  ADD COLUMN IF NOT EXISTS gender varchar(16);

ALTER TABLE iam.account
  DROP CONSTRAINT IF EXISTS ck_iam_account_gender;
ALTER TABLE iam.account
  ADD CONSTRAINT ck_iam_account_gender
  CHECK (gender IS NULL OR gender IN ('MALE', 'FEMALE', 'OTHER'));

UPDATE iam.capability
SET status = 'INACTIVE'
WHERE code = 'profile.update';

INSERT INTO iam.capability(
  code, resource_type, action, risk_level, scope_resolver,
  authorization_profile, step_up_required, status
)
VALUES
  ('account.update', 'ACCOUNT', 'UPDATE', 'HIGH', 'SYSTEM_RESOURCE', 'GENERAL', true, 'ACTIVE'),
  ('account.enrollment.change', 'ACCOUNT', 'CHANGE_ENROLLMENT', 'HIGH', 'SYSTEM_RESOURCE', 'GENERAL', true, 'ACTIVE')
ON CONFLICT (code) DO UPDATE SET
  resource_type = EXCLUDED.resource_type,
  action = EXCLUDED.action,
  risk_level = EXCLUDED.risk_level,
  scope_resolver = EXCLUDED.scope_resolver,
  authorization_profile = EXCLUDED.authorization_profile,
  step_up_required = EXCLUDED.step_up_required,
  status = EXCLUDED.status;
