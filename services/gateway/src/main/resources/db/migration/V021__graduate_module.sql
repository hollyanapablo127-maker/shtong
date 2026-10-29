INSERT INTO catalog.module_definition(code,title_key,icon_key,status,contract_version)
VALUES ('system.graduates','module.system.graduates','graduation-cap','ACTIVE','account-access-baseline.v1')
ON CONFLICT (code) DO NOTHING;

INSERT INTO catalog.module_exposure(module_code,workspace,channel,display_order,feature_flag,runtime_status,catalog_version)
SELECT 'system.graduates','SYSTEM_ADMIN',channel,15,true,'ACTIVE','account-access-baseline.v1'
FROM (VALUES ('WEB_ADMIN'),('ANDROID'),('IOS')) AS exposure(channel)
WHERE NOT EXISTS (SELECT 1 FROM catalog.module_exposure e WHERE e.module_code='system.graduates' AND e.workspace='SYSTEM_ADMIN' AND e.channel=exposure.channel);
