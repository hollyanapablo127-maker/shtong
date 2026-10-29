ALTER TABLE iam.account
  ADD COLUMN IF NOT EXISTS id_card_number varchar(18),
  ADD COLUMN IF NOT EXISTS mobile_number varchar(11),
  ADD COLUMN IF NOT EXISTS current_address jsonb,
  ADD COLUMN IF NOT EXISTS household_address jsonb,
  ADD COLUMN IF NOT EXISTS profile_completed_at timestamptz;

ALTER TABLE iam.account
  DROP CONSTRAINT IF EXISTS ck_iam_account_id_card_number;
ALTER TABLE iam.account
  ADD CONSTRAINT ck_iam_account_id_card_number
  CHECK (id_card_number IS NULL OR id_card_number ~ '^[0-9]{17}[0-9X]$');

ALTER TABLE iam.account
  DROP CONSTRAINT IF EXISTS ck_iam_account_mobile_number;
ALTER TABLE iam.account
  ADD CONSTRAINT ck_iam_account_mobile_number
  CHECK (mobile_number IS NULL OR mobile_number ~ '^1[3-9][0-9]{9}$');

ALTER TABLE iam.account
  DROP CONSTRAINT IF EXISTS ck_iam_account_required_profile_complete;
ALTER TABLE iam.account
  ADD CONSTRAINT ck_iam_account_required_profile_complete
  CHECK (
    (profile_completed_at IS NULL AND id_card_number IS NULL AND mobile_number IS NULL AND current_address IS NULL AND household_address IS NULL)
    OR
    (profile_completed_at IS NOT NULL AND id_card_number IS NOT NULL AND mobile_number IS NOT NULL AND current_address IS NOT NULL AND household_address IS NOT NULL)
  );

CREATE UNIQUE INDEX IF NOT EXISTS uq_iam_account_active_id_card
  ON iam.account (id_card_number)
  WHERE id_card_number IS NOT NULL AND lifecycle_status <> 'DELETED';

UPDATE iam.capability SET status='ACTIVE' WHERE code='profile.update';
