-- The SSO ticket state is part of the encrypted authoritative runtime
-- checkpoint. This migration registers only the医保 module vocabulary in the
-- relational catalog; account/profile schema belongs to earlier migrations.
INSERT INTO iam.role(code, identity_kind, built_in, status)
VALUES ('MEDICAL_INSURANCE_ADMIN','MEDICAL_INSURANCE',true,'ACTIVE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO iam.capability(code,resource_type,action,risk_level,scope_resolver,authorization_profile,step_up_required,status)
VALUES ('module.medical-insurance.access','MODULE','ENTER','HIGH','MODULE_SSO','GENERAL',false,'ACTIVE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO catalog.module_definition(code,title_key,icon_key,status,contract_version)
VALUES ('medical-insurance','module.medicalInsurance','shield-plus','ACTIVE','account-access-baseline.v1')
ON CONFLICT (code) DO NOTHING;

INSERT INTO catalog.module_exposure(module_code,workspace,channel,display_order,feature_flag,runtime_status,catalog_version)
SELECT 'medical-insurance', workspace, channel, display_order, true, 'ACTIVE', 'account-access-baseline.v1'
FROM (VALUES
  ('USER','WEB_USER',50),('USER','ANDROID',50),('USER','IOS',50),
  ('ADMIN','WEB_ADMIN',50),('ADMIN','ANDROID',50),('ADMIN','IOS',50),
  ('SYSTEM_ADMIN','WEB_ADMIN',50),('SYSTEM_ADMIN','ANDROID',50),('SYSTEM_ADMIN','IOS',50)
) AS exposure(workspace,channel,display_order)
WHERE NOT EXISTS (
  SELECT 1 FROM catalog.module_exposure existing
  WHERE existing.module_code = 'medical-insurance'
    AND existing.workspace = exposure.workspace
    AND existing.channel = exposure.channel
);

-- SSO audit event codes are validated against the frozen JSON contract and
-- appended to audit.audit_event by the runtime; that table intentionally has
-- no mutable event-catalog table.
