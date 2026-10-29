# 第一阶段账户与权限 Schema 计划

- 状态：Flyway 实施前基线
- 日期：2026-09-16
- 当前实现说明：登录已改为仅账号密码，MFA/step-up 已移除；本文涉及 MFA/step-up 的内容为长期设计，不代表当前行为，见 [ADR-0005](../architecture/decisions/0005-password-only-login-and-incremental-persistence.md)。
- 数据库：PostgreSQL
- 说明：本文列出第一阶段必须落地的表和约束；完整审计表见 [权限与审计实现级数据模型](access-control-and-audit-model.md)。医保外部项目的业务表不纳入 Shtong 迁移。

## 1. Schema 与所有权

| Schema | 所有者模块 | 第一阶段内容 |
| --- | --- | --- |
| `iam` | account / authorization | 账号、登录身份、密码、MFA、会话、角色、管辖上限、grant |
| `org` | organization | 组织树、闭包表、账户当前归属 |
| `catalog` | module-catalog | 模块契约运行时投影和版本 |
| `admin` | admin | 导入、导出与批量命令任务，不保存 IAM 事实 |
| `audit` | audit | 只追加权威事件、子表、manifest、outbox、完整性控制 |

运行时数据库角色不能直接拥有其他模块 Schema 的写权限。跨模块变更由同一应用进程中的应用接口和受控数据库过程编排。

## 2. 迁移顺序

建议 Flyway 按依赖拆分：

```text
V001__extensions_and_schemas.sql
V002__iam_account_and_identity.sql
V003__iam_credentials_mfa_and_session.sql
V004__organization_tree_and_membership.sql
V005__authorization_templates.sql
V006__role_assignment_scope_ceiling.sql
V007__authorization_grants.sql
V008__module_catalog_projection.sql
V009__audit_append_ledger.sql
V010__audit_manifest_outbox_integrity.sql
V011__admin_import_export_jobs.sql
V012__database_roles_and_privileges.sql
V013__baseline_contract_seed.sql
V017__student_profile_and_enrollment_change.sql
V018__required_student_contact_profile.sql
```

迁移只注册内置角色、capability、模块和事件 Schema，不插入固定密码或可登录默认账号。当前 `medical-insurance` 模块额外注册其工作区 exposure 和 SSO 适配器配置引用；适配器密钥只来自部署密钥存储。

## 3. 账户与登录身份

### 3.1 `iam.account`

```text
id UUIDv7 PK
lifecycle_status ACTIVE / GRADUATED / DELETED NOT NULL
security_status NORMAL / TEMPORARILY_LOCKED / MANUALLY_LOCKED NOT NULL
display_name VARCHAR(100) NOT NULL
person_no VARCHAR(100) NOT NULL
gender MALE / FEMALE / OTHER nullable
id_card_number VARCHAR(18) nullable
mobile_number VARCHAR(11) nullable
current_address JSONB nullable
household_address JSONB nullable
profile_completed_at TIMESTAMPTZ nullable
authz_version BIGINT NOT NULL DEFAULT 1
credential_version BIGINT NOT NULL DEFAULT 1
created_at, created_by
updated_at, updated_by
graduated_at, graduated_by, graduated_reason nullable
deleted_at, deleted_by, deleted_reason nullable
row_version BIGINT NOT NULL
```

约束：

- `person_no` 的规范化值唯一；原值和规范化值分列保存。
- `display_name` 不唯一，允许重名；`gender` 可空，仅在账户具有有效 `USER` 身份时作为学生资料展示。
- `person_no` 创建后不可修改；系统管理员资料更正可覆盖登录账号、显示姓名、学生性别，以及已由本人完成首次填写的身份证号、手机号和两处地址。
- 身份证号在非 `DELETED` 账户中使用部分唯一索引；服务端另外验证18位校验位和出生日期。手机号按 `1[3-9]` 号段与11位格式约束。
- 四类必填资料与 `profile_completed_at` 必须全部为空或全部非空；地址 JSON 固化经国家地名信息库复核后的12位省、市、区代码与名称快照，以及用户填写的详细地址。
- 运行时普通读取只返回脱敏投影；数据库角色不得绕过应用读取敏感列，审计 changes 不保存这些字段的明文。
- `GRADUATED` 必须有毕业时间、操作者和原因；`DELETED` 必须有删除时间、操作者和原因。
- lifecycle 非 `ACTIVE` 时任何认证都失败。
- 生命周期不能通过普通 `UPDATE` 修改，只能调用受控命令过程。

### 3.2 `iam.login_identity`

```text
id UUIDv7 PK
account_id UUID NOT NULL
identity_type USERNAME NOT NULL
identifier_original TEXT NOT NULL
identifier_normalized TEXT NOT NULL
status ACTIVE / REVOKED NOT NULL
created_at, revoked_at
UNIQUE(identity_type, identifier_normalized) WHERE status = ACTIVE
```

登录名不是外键。规范化采用契约中固定的 Unicode/大小写规则，规则升级必须版本化并先做冲突扫描。

## 4. 密码、MFA 与会话

### 4.1 `iam.password_credential`

```text
account_id UUID PK
state TEMPORARY / ACTIVE / REVOKED NOT NULL
algorithm ARGON2ID NOT NULL
algorithm_parameters JSONB NOT NULL
password_hash BYTEA NOT NULL
password_version BIGINT NOT NULL
changed_at TIMESTAMPTZ NOT NULL
temporary_expires_at TIMESTAMPTZ nullable  -- 首期保留旧库兼容列，业务实现始终为空
failed_attempt_count INTEGER NOT NULL DEFAULT 0
locked_until TIMESTAMPTZ nullable
```

- `TEMPORARY` 不设置时间到期；`temporary_expires_at` 仅为历史库兼容列，首期创建、导入和重置均写入 NULL。临时密码只能使用一次，首次认证后必须改密。
- 哈希参数只允许注册表中的版本，不信任客户端提交。
- 密码历史保存独立不可逆哈希和参数版本，默认保留 5 个；运行时角色无读取 hash 权限。

### 4.2 MFA

```text
iam.mfa_factor(
  id, account_id, factor_type, encrypted_secret, key_version,
  status, enrolled_at, last_used_at, revoked_at
)

iam.mfa_recovery_code(
  factor_id, code_hash, consumed_at
)
```

secret 使用 KMS envelope encryption；恢复码只保存哈希。任何查询 DTO 都不能包含密文字段。

### 4.3 会话

```text
iam.session(
  id, account_id, channel, device_pseudonym, token_family_id,
  assurance_level, created_at, expires_at, revoked_at, revoke_reason
)

iam.refresh_token(
  id, session_id, token_hash, issued_at, expires_at,
  rotated_to_id, consumed_at, revoked_at
)

iam.session_context(
  id, session_id, workspace, audience, authz_version,
  policy_revision, organization_revision, assurance_level,
  restriction NORMAL / PASSWORD_CHANGE_ONLY / PROFILE_COMPLETION_ONLY,
  created_at, expires_at, revoked_at
)
```

refresh token 哈希带服务端 pepper。logout、重置密码、毕业、删除和凭据风险事件必须可按账号或 token family 原子撤销。

## 5. 角色、管辖上限与 grant

### 5.1 内置角色

```text
USER
TEACHER_ADMIN
STUDENT_ADMIN
SYSTEM_ADMIN
```

角色模板不可删除。`TEACHER_ADMIN` 替代尚未进入代码阶段的旧名称 `COUNSELOR_ADMIN`。

### 5.2 `iam.role_assignment`

沿用完整模型中的不可变/撤销式记录。一个账号可有多个角色赋予，同一角色的有效期重叠由受控过程拒绝。

### 5.3 `iam.assignment_scope_ceiling`

```text
id UUIDv7 PK
role_assignment_id UUID NOT NULL
scope_type ORG_SUBTREE NOT NULL
org_unit_id UUID NOT NULL
org_unit_type_snapshot COLLEGE / GRADE / CLASS NOT NULL
created_at TIMESTAMPTZ NOT NULL
UNIQUE(role_assignment_id, org_unit_id)
```

提交时约束触发器验证：

- `TEACHER_ADMIN` 至少一条 ceiling，且实时组织节点类型全部为 `GRADE`。
- `STUDENT_ADMIN` 至少一条 ceiling，且类型只为 `COLLEGE`、`GRADE` 或 `CLASS`。
- `USER` 和 `SYSTEM_ADMIN` 不允许写组织 ceiling。
- ceiling 有效期不能独立超过所属 role assignment；撤销 assignment 即停止使用全部 ceiling。
- 快照用于审计显示，运行时必须同时验证实时节点和组织 revision。

ceiling 聚合不可原地修改。范围变化通过撤销旧 role assignment 并创建新 assignment、ceiling 和所需 grants 完成，随后递增账号 `authz_version`。

### 5.4 grant 子集约束

`authorization_grant`、`grant_capability`、`grant_scope` 和审计 clearance 沿用完整模型。新增提交时不变量：

```text
对于 ADMIN 角色的每一条 GENERAL/ORG_SUBTREE grant_scope：
EXISTS assignment_scope_ceiling c
WHERE c.role_assignment_id = grant.role_assignment_id
  AND org_unit_closure(c.org_unit_id, grant_scope.org_unit_id) exists
```

即 grant scope 可以等于 ceiling 节点或位于其后代，但不能超出 ceiling。多个 ceiling 按并集求值，不能把不同 assignment 的 ceiling 拼接使用。

## 6. 组织表

### 6.1 `org.org_unit`

```text
id UUIDv7 PK
type SCHOOL / COLLEGE / GRADE / CLASS NOT NULL
parent_id UUID nullable
code_original TEXT NOT NULL
code_normalized TEXT NOT NULL
name TEXT NOT NULL
status ACTIVE / DELETED NOT NULL
valid_from, valid_to
version BIGINT NOT NULL
created_by, created_at, updated_by, updated_at
```

父子类型用延迟约束触发器验证。`code_normalized + type` 在有效节点中唯一。任何移动、删除都重建闭包、生成影响 manifest 并递增 organization revision；删除保留历史引用，活动组织树不再返回该节点。

### 6.2 `org.org_unit_closure`

包含自身 depth 0 行。运行时范围检查和 grant 子集检查只用受控查询，不允许客户端上传祖先路径作为证据。

### 6.3 `org.account_membership`

第一阶段普通用户只有一个当前主班级 membership：

学籍变动必须关闭原主班级 membership 并创建新 membership，不原地改写历史；同时递增账户授权版本、撤销现有会话并写入 `ACCOUNT.ENROLLMENT_CHANGED` 字段级审计。当前运行时 checkpoint 实现保持相同行为语义，规范化仓储接管后必须沿用该历史约束。

```text
id, account_id, class_org_unit_id
membership_type STUDENT
is_primary TRUE
valid_from, valid_to
created_at, created_by, version
```

组织路径通过闭包表获取；历史 membership 只结束有效期，不覆盖或删除。

## 7. 管理任务表

### 7.1 `admin.import_job`

```text
id UUIDv7 PK
state UPLOADED / VALIDATING / VALID / INVALID / COMMITTED / EXPIRED / FAILED
template_version
source_object_key, source_sha256
row_count
organization_revision_at_validation
created_by, created_at, expires_at
committed_at nullable
result_object_key nullable
result_sha256 nullable
row_version
```

逐行校验结果存入 `admin.import_job_error`，不保存临时密码。提交后的一次性凭据文件引用使用单独严格权限列/表，并记录下载次数和到期时间。

### 7.2 `admin.export_job`

```text
id UUIDv7 PK
state PENDING / RUNNING / READY / FAILED / EXPIRED
selection_mode SELECTED / FILTERED_ALL
filter_snapshot JSONB nullable
target_manifest_id UUID nullable
authorization_version, policy_revision, organization_revision
purpose TEXT NOT NULL
created_by, created_at, started_at, completed_at, expires_at
result_object_key, result_sha256, result_count nullable
failure_code nullable
row_version
```

`filter_snapshot` 必须通过版本化 JSON Schema 校验和规范化，不允许保存可执行 SQL。`SELECTED` 在任务创建事务中封口目标 manifest；`FILTERED_ALL` 在同一授权快照下解析并封口集合。

### 7.3 批量命令

重置、删除和毕业使用统一命令记录：

```text
admin.bulk_command(
  id, command_type, idempotency_key, target_manifest_id,
  reason, requested_by, requested_at, state, completed_at,
  result_digest, row_version
)
```

唯一约束为 `requested_by + command_type + idempotency_key`。业务状态与命令、审计和 outbox 在同一事务提交；不能先标记成功再异步修改账户。

### 7.4 `admin.security_control_request`

```text
id UUIDv7 PK
command_type
canonical_command_hash BYTEA NOT NULL
target_manifest_id UUID NOT NULL
risk_policy_revision BIGINT NOT NULL
state PENDING / APPROVED / REJECTED / EXPIRED / EXECUTED / INVALIDATED
requested_by, requested_at, expires_at
decided_by, decided_at, decision_reason nullable
executed_at nullable
row_version
```

请求者不能批准自己的请求，批准者也不能是目标账户。批准过程在同一事务核对 command hash、目标 manifest、当前 revision、请求有效期和职责分离；实际执行与 `EXECUTED` 状态、权威审计和 outbox 同事务提交。两个初始系统管理员由一次性 bootstrap 仪式共同创建，该过程不伪造批准记录，而使用独立控制事件。

## 8. 审计最小落地集

第一阶段必须先实现完整模型中的：

- `event_identity`、按月分区的 `audit_event`。
- actor、subject、resource、change、scope snapshot、authorization evidence 子表。
- manifest、manifest item 密文/摘要和事件关联。
- outbox、完整性 batch、WORM archive catalog 的最小闭环。
- 只读脱敏查询视图和审计访问本身的追加事件。

所有 IAM 写操作、组织写操作和受保护读取遵守“审计先提交，再释放响应”。不允许先用普通日志过渡，之后再补审计。

## 9. 关键数据库约束

- 最后一个可用 `SYSTEM_ADMIN` 不能被删除、毕业、锁定或撤销角色。
- 操作者不能给自己新增角色、扩大 ceiling 或授予 capability。
- `GRADUATED` 账户不能存在有效管理员或系统管理员角色。
- 非 `ACTIVE` 账户不能创建 session/context。
- `PASSWORD_CHANGE_ONLY` context 不能访问白名单之外 capability。
- role/grant/ceiling 变化和 `authz_version` 递增必须同事务。
- 密码变化和 `credential_version` 递增、旧会话撤销必须同事务。
- grant 的每个 capability x scope 组合都符合角色模板，且 scope 不超过 assignment ceiling。
- 审计基表禁止 UPDATE/DELETE，所有外键显式 `ON DELETE RESTRICT`。
- 外部请求提供的 account ID、org ID、role code、过滤器和预计数量都必须在事务中重新解析。

## 10. 索引基线

- `login_identity(identity_type, identifier_normalized)` 活动唯一索引。
- `account(person_no_normalized)` 唯一索引；`account(lifecycle_status, created_at, id)` 列表索引。
- `role_assignment(account_id, role_id, status, valid_to)`。
- `assignment_scope_ceiling(role_assignment_id, org_unit_id)` 和反向 `org_unit_id` 索引。
- `org_unit(parent_id, status, type)`、`org_unit(type, code_normalized)`。
- `account_membership(account_id, valid_to)`、`account_membership(class_org_unit_id, valid_to)`。
- `session(account_id, revoked_at, expires_at)`、`refresh_token(token_hash)` 唯一索引。
- `import_job(created_by, created_at)`、`export_job(state, created_at)`、`bulk_command(requested_by, requested_at)`。
- 审计索引沿用完整模型，禁止任意 JSONB 全量 GIN。

## 11. 验证清单

- 每个约束都定义允许与拒绝条件，并说明违反条件时的数据库行为。
- Flyway 从空库完整执行、重复启动不修改已应用迁移、向前升级可验证。
- 运行时账号直接 UPDATE grant、account 生命周期或 audit 表均被数据库权限拒绝。
- 并发删除/撤权不能同时越过最后一个系统管理员保护。
- 两个并发导入不能创建重复规范化登录名或人员编号。
- 批量命令锁顺序稳定，没有账户集合顺序导致的死锁。
- 审计/manifest/outbox 任一写入失败时账户事务整体回滚。
## 外部模块身份投影扩展

外部模块适配表按“账号映射 + 身份代码”保存多身份投影，范围记录必须带身份维度，避免同一账号的学生范围和医保管理员范围交叉。模块本地业务表中的共享资料属于 Shtong 投影，新增/编辑业务请求不得把这些字段作为可写字段；历史列保留用于兼容读取和迁移核对。
