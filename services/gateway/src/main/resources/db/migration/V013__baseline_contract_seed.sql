INSERT INTO iam.role(code, identity_kind, built_in, status)
VALUES ('USER','USER',true,'ACTIVE'),('TEACHER_ADMIN','TEACHER',true,'ACTIVE'),
       ('STUDENT_ADMIN','STUDENT',true,'ACTIVE'),('SYSTEM_ADMIN','SYSTEM',true,'ACTIVE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO iam.capability(code,resource_type,action,risk_level,scope_resolver,authorization_profile,step_up_required,status)
VALUES
('profile.read','PROFILE','READ','LOW','SELF_RESOURCE','GENERAL',false,'ACTIVE'),
('profile.update','PROFILE','UPDATE','MEDIUM','SELF_RESOURCE','GENERAL',false,'ACTIVE'),
('credential.password.change','CREDENTIAL','CHANGE_PASSWORD','MEDIUM','SELF_RESOURCE','GENERAL',false,'ACTIVE'),
('session.logout','SESSION','LOGOUT','LOW','SELF_RESOURCE','GENERAL',false,'ACTIVE'),
('account.read','ACCOUNT','READ','HIGH','SYSTEM_RESOURCE','GENERAL',false,'ACTIVE'),
('account.create','ACCOUNT','CREATE','HIGH','SYSTEM_RESOURCE','GENERAL',true,'ACTIVE'),
('account.import','ACCOUNT_IMPORT','IMPORT','HIGH','SYSTEM_RESOURCE','GENERAL',true,'ACTIVE'),
('account.export','ACCOUNT_EXPORT','EXPORT','HIGH','SYSTEM_RESOURCE','GENERAL',true,'ACTIVE'),
('account.password.reset','CREDENTIAL','RESET_PASSWORD','CRITICAL','SYSTEM_RESOURCE','GENERAL',true,'ACTIVE'),
('account.delete','ACCOUNT','DELETE_LOGICALLY','CRITICAL','SYSTEM_RESOURCE','GENERAL',true,'ACTIVE'),
('account.graduate','ACCOUNT','GRADUATE','CRITICAL','SYSTEM_RESOURCE','GENERAL',true,'ACTIVE'),
('authorization.read','AUTHORIZATION','READ','HIGH','SYSTEM_RESOURCE','GENERAL',false,'ACTIVE'),
('authorization.grant','AUTHORIZATION','GRANT','CRITICAL','SYSTEM_RESOURCE','GENERAL',true,'ACTIVE'),
('authorization.revoke','AUTHORIZATION','REVOKE','CRITICAL','SYSTEM_RESOURCE','GENERAL',true,'ACTIVE'),
('control.read','SECURITY_CONTROL_REQUEST','READ','HIGH','SYSTEM_RESOURCE','GENERAL',true,'ACTIVE'),
('control.approve','SECURITY_CONTROL_REQUEST','DECIDE','CRITICAL','SYSTEM_RESOURCE','GENERAL',true,'ACTIVE'),
('organization.read','ORG_UNIT','READ','MEDIUM','SYSTEM_RESOURCE','GENERAL',false,'ACTIVE'),
('organization.manage','ORG_UNIT','MANAGE','CRITICAL','SYSTEM_RESOURCE','GENERAL',true,'ACTIVE'),
('audit.search','AUDIT_EVENT','SEARCH','HIGH','AUDIT_SUBJECT','AUDIT_QUERY',true,'ACTIVE'),
('audit.detail.read','AUDIT_EVENT','READ_DETAIL','HIGH','AUDIT_SUBJECT','AUDIT_QUERY',true,'ACTIVE'),
('audit.export','AUDIT_EXPORT','EXPORT','CRITICAL','AUDIT_SUBJECT','AUDIT_QUERY',true,'ACTIVE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO catalog.module_definition(code,title_key,icon_key,status,contract_version)
VALUES ('system.accounts','module.system.accounts','users','ACTIVE','account-access-baseline.v1'),
       ('system.authorization','module.system.authorization','shield-check','ACTIVE','account-access-baseline.v1'),
       ('system.organization','module.system.organization','building-2','ACTIVE','account-access-baseline.v1'),
       ('system.audit','module.system.audit','scroll-text','ACTIVE','account-access-baseline.v1')
ON CONFLICT (code) DO NOTHING;
