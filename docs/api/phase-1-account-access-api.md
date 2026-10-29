# 第一阶段账户与权限 API 契约

- 状态：开发前冻结基线
- 日期：2026-09-16
- 协议：HTTPS + REST/JSON，OpenAPI 3.1
- 机器契约：`packages/contracts/openapi/account-access-baseline.v1.json`

## 1. 通用约定

- 基础路径为 `/v1`，所有 ID 使用服务端生成的不可复用 UUIDv7 字符串。
- 时间使用 UTC ISO 8601，例如 `2026-09-16T08:30:00Z`。
- 列表使用不透明 cursor 分页；客户端不得解析或修改 cursor。
- 写命令要求 `Idempotency-Key`，有效范围为操作者 + 路由 + key。
- 高风险命令要求非空 `reason`；登录只使用账号和密码，不再有 MFA 或 step-up（见 ADR-0005）。
- Web 使用 HttpOnly Cookie + CSRF token；移动端使用 Authorization access token。
- 服务端从签发的 session context 读取 workspace/audience，不接受请求体声明角色或 scope。
- 每个响应返回 `X-Request-Id`；错误正文也包含相同 `requestId`。

统一错误：

```json
{
  "code": "ACCOUNT_NOT_FOUND",
  "messageKey": "error.account.notFound",
  "requestId": "req_01...",
  "fieldErrors": [
    {"field": "loginName", "code": "DUPLICATE"}
  ]
}
```

错误信息不得泄露密码、Token、账号存在性或越权资源细节。

## 2. 认证与会话

| 方法 | 路径 | 用途 | 匿名可用 |
| --- | --- | --- | :---: |
| `POST` | `/auth/login` | 账号密码认证；可返回正常、强制改密或资料完善状态 | 是 |
| `POST` | `/auth/token/refresh` | 使用幂等键轮换 refresh token 并刷新短期会话 | 条件 |
| `POST` | `/auth/password/change` | 正常主动改密或限制会话强制改密 | 否 |
| `POST` | `/auth/logout` | 幂等撤销当前会话 | 否 |
| `GET` | `/session/bootstrap` | 返回账号摘要、当前上下文、可用工作区和模块 | 否 |
| `POST` | `/session/context` | 建立或切换工作区上下文并轮换 token | 否 |

### 2.1 登录结果

`POST /auth/login` 只返回以下状态之一：

```text
AUTHENTICATED            -> 可以 bootstrap
PASSWORD_CHANGE_REQUIRED -> 只可读取密码策略、change 或 logout
PROFILE_COMPLETION_REQUIRED -> 仅可读取本人基础资料、行政区划、提交资料或 logout
DENIED                    -> 外部统一错误，内部审计具体原因
```

`AUTHENTICATED` 同时返回 session/context。Web 通过 Secure HttpOnly 会话 Cookie 接收凭据，浏览器关闭或手动退出后失效；响应正文不含 token。Android/iOS 在响应正文接收 access token 和轮换 refresh token，并立即写入平台安全存储；每次重新启动应用时清理上次会话。bootstrap 只返回非秘密的 context 摘要，不重复返回 token。

示例：

```json
{
  "status": "PASSWORD_CHANGE_REQUIRED",
  "challengeId": "chl_01...",
  "expiresAt": "2026-09-16T09:00:00Z",
  "allowedActions": ["PASSWORD_CHANGE", "LOGOUT"]
}
```

### 2.2 强制改密

```http
POST /v1/auth/password/change
Idempotency-Key: 01...
```

```json
{
  "currentPassword": "<redacted>",
  "newPassword": "<redacted>"
}
```

成功后旧 session/context/token family 全部失效，响应建立一个全新会话。密码字段在任何访问日志、审计或错误回显中必须由框架级过滤器移除。

### 2.3 Bootstrap

非系统管理员第一阶段示例：

```json
{
  "contractVersion": "account-access-baseline.v1",
  "authorizationVersion": 3,
  "profileCompletionRequired": false,
  "activeContext": {
    "id": "0199...",
    "workspace": "ADMIN",
    "assuranceLevel": "AAL2",
    "expiresAt": "2026-09-16T09:00:00Z"
  },
  "activeModules": [],
  "shellActions": ["PROFILE", "CHANGE_PASSWORD", "LOGOUT"],
  "availableWorkspaces": [{"code": "ADMIN", "requiresStepUp": true}]
}
```

系统管理员返回 `system.accounts`、`system.authorization`、`system.organization`、`system.audit` 和当前上下文允许的 `medical-insurance`。`USER` 或 `ADMIN` 只有在对应角色、能力和范围满足时返回 `medical-insurance`。

### 2.4 医保信息系统 SSO

| 方法 | 路径 | 调用方 | 说明 |
| --- | --- | --- | --- |
| `POST` | `/modules/medical-insurance/sso/ticket` | 已认证 Shtong 会话 | 签发一次性 60 秒跳转票据，绑定当前 workspace、角色和授权版本 |
| `POST` | `/modules/medical-insurance/sso/exchange` | `_yb` 服务端适配器 | 使用适配器 ID/密钥交换票据，返回最小身份、范围和父会话投影 |
| `POST` | `/modules/medical-insurance/sso/introspect` | `_yb` 服务端适配器 | 每次受保护请求校验父会话、生命周期、角色和授权版本 |
| `POST` | `/modules/medical-insurance/sso/revoke` | `_yb` 服务端适配器 | `_yb` 登出时撤销父会话/token family |

角色映射固定为 `SYSTEM_ADMIN -> super_admin`、`MEDICAL_INSURANCE_ADMIN -> admin`、`USER -> student`，且按当前 workspace 解析。多角色账号默认 `USER`，不能通过票据请求体自行声明角色或范围。`_yb` 允许增加独立适配层，但不得改动其业务逻辑、页面、数据库业务规则或既有功能。生产启用 SSO-only 后，旧密码登录、旧 Bearer API 和无 SSO 标记的本地会话均拒绝。

## 3. 本人资料

| 方法 | 路径 | capability | 说明 |
| --- | --- | --- | --- |
| `GET` | `/profile` | `profile.read` + `SELF` | 返回只读本人字段、身份、组织与管辖摘要 |
| `GET` | `/reference/administrative-divisions` | 已认证会话 | 按 `PROVINCE/CITY/DISTRICT` 返回国家地名信息库选项 |
| `POST` | `/profile/completion` | `profile.update` + `SELF` | 首次提交身份证号、手机号和两处地址，成功后轮换会话 |
| `POST` | `/profile/sensitive-fields/{field}/reveal` | `profile.read` + `SELF` | 当前密码复核成功后单次返回一个本人敏感字段明文 |

行政区划代理按官方文档调用 `GET https://dmfw.mca.gov.cn/9095/xzqh/getList`；`9095` 是 URL 路径段而非网络端口。服务端发送固定产品 `User-Agent`，使用默认 JDK 根证书并仅为该独立客户端附加指纹固定的 `CFCA EV ROOT`，继续执行证书有效期、证书链和主机名校验，禁止 trust-all。官方线上响应的 `data` 为根对象、下级位于 `children`；24 小时缓存过期后若上游临时失败，可使用最后一次成功的官方数据，服务重启且无缓存时仍返回 `REFERENCE_DATA_UNAVAILABLE`。

本人资料只提供一次性的必填资料完善，不提供日常编辑。姓名、登录名、人员编号、性别、角色、组织归属和账号状态均不能由本人修改；修改密码只使用 `/auth/password/change`。有效 `USER` 身份（包括同时担任管理员的学生）返回可空性别、学校至班级路径、按当前年级实时推导的辅导员，以及四个敏感字段的脱敏值；教师/辅导员返回管辖年级，其他管理员返回对应管辖范围。敏感读取只允许 `ID_CARD_NUMBER`、`MOBILE_NUMBER`、`CURRENT_ADDRESS`、`HOUSEHOLD_ADDRESS`，验证失败限流并审计，响应禁止缓存。

## 4. 账户查询与创建

所有本节端点要求 `SYSTEM_ADMIN` context。

| 方法 | 路径 | capability |
| --- | --- | --- |
| `GET` | `/system/accounts` | `account.read` |
| `POST` | `/system/accounts` | `account.create` |
| `GET` | `/system/accounts/{accountId}` | `account.read` |
| `PATCH` | `/system/accounts/{accountId}` | `account.update` |
| `POST` | `/system/accounts/{accountId}/enrollment-change` | `account.enrollment.change` |
| `GET` | `/system/accounts/{accountId}/authorization` | `authorization.read` |

### 4.1 列表查询

允许的过滤器：

```text
q                   登录名/显示名/人员编号的受控搜索
lifecycleStatus     ACTIVE | GRADUATED | DELETED
securityStatus      NORMAL | TEMPORARILY_LOCKED | MANUALLY_LOCKED
roleCode            五个内置角色之一
collegeId
gradeId
classId
passwordChangeRequired
createdFrom / createdTo
cursor / limit      limit 默认 50，最大 200
```

列表响应每项只返回展示所需字段，不返回密码/session 数据。服务端记录实际返回账户主体清单或 manifest。

### 4.2 创建账户

```json
{
  "loginName": "20260001",
  "displayName": "示例学生",
  "personNo": "20260001",
  "gender": "FEMALE",
  "classId": "0199...",
  "roleAssignments": [
    {"roleCode": "USER", "scopeCeilings": []}
  ],
  "reason": "新生账户创建"
}
```

- `TEACHER_ADMIN` 的 `scopeCeilings` 必须全部为年级 ID。
- `STUDENT_ADMIN` 可混合学院、年级和班级 ID。
- 只要 `roleAssignments` 包含 `USER`，`classId` 就必填且必须引用有效班级；不含 `USER` 时 `classId` 可为空。
- 创建账户接口不接受 `SYSTEM_ADMIN`。应先创建普通账户，再通过受双人安全控制的角色赋予接口添加 `SYSTEM_ADMIN`，避免把提权藏在普通创建或批量导入中。
- 成功响应仅一次包含服务端生成的临时密码交付对象；重试同一幂等键返回同一命令结果，但不得再次返回已确认交付的明文。

### 4.3 资料更正与学籍变动

系统管理员可通过 `PATCH /system/accounts/{accountId}` 更正登录账号、姓名、学生性别，以及已完成首次资料填写学生的身份证号、手机号和两处地址，请求必须包含 `expectedVersion` 与 `reason`。学工号不在请求 Schema 中，未知字段会被拒绝；姓名允许重名，登录账号仍全局唯一。性别为可空的 `MALE | FEMALE | OTHER`，仅有效 `USER` 身份适用。四类敏感资料必须成组提交、重新执行格式/唯一性/行政区划校验，且不得为尚未完成首次填写的学生代填。

学生班级只能通过 `POST /system/accounts/{accountId}/enrollment-change` 修改，请求包含新 `classId`、`expectedVersion` 和 `reason`。服务端验证目标为有效班级，递增账户授权版本、撤销旧会话，并以 `ACCOUNT.ENROLLMENT_CHANGED` 记录原班级、新班级及完整路径。普通资料更正记录 `ACCOUNT.UPDATED`；两个事件都保存字段级前后值，不依赖客户端展示完成鉴权或审计。

## 5. 导入

| 方法 | 路径 | capability | 说明 |
| --- | --- | --- | --- |
| `GET` | `/system/account-import/template` | `account.import` | 获取当前模板 |
| `POST` | `/system/account-import/jobs` | `account.import` | multipart 上传并创建校验任务 |
| `GET` | `/system/account-import/jobs/{jobId}` | `account.import` | 查询校验/提交状态 |
| `GET` | `/system/account-import/jobs/{jobId}/errors` | `account.import` | 分页读取行错误 |
| `POST` | `/system/account-import/jobs/{jobId}/commit` | `account.import` | 确认全量提交 |
| `POST` | `/system/account-import/jobs/{jobId}/credential-ticket` | `account.password.reset` | 签发一次性结果下载票据 |

commit 要求 body：

```json
{
  "validatedRevision": 31,
  "expectedAccountCount": 800,
  "reason": "2026 级新生账户导入"
}
```

若组织 revision、文件 hash、预计数量或任务状态不匹配，返回 `409 IMPORT_REVALIDATION_REQUIRED`，不创建任何账户。

## 6. 导出

| 方法 | 路径 | capability |
| --- | --- | --- |
| `POST` | `/system/account-export/jobs` | `account.export` |
| `GET` | `/system/account-export/jobs/{jobId}` | `account.export` |
| `POST` | `/system/account-export/jobs/{jobId}/download-ticket` | `account.export` |

有选择：

```json
{
  "selection": {
    "mode": "SELECTED",
    "accountIds": ["0199...", "0199..."]
  },
  "purpose": "核对账户"
}
```

无选择：

```json
{
  "selection": {
    "mode": "FILTERED_ALL",
    "filter": {
      "lifecycleStatus": ["ACTIVE"],
      "gradeIds": ["0199..."]
    },
    "expectedCount": 320
  },
  "purpose": "年级账户核对"
}
```

`expectedCount` 与服务端解析结果不一致时返回 `409 EXPORT_SELECTION_CHANGED`，客户端刷新数量后再次确认。下载 ticket 只返回短期地址，不把对象存储永久 URL 写入任务或审计。

## 7. 批量账户命令

使用同一端点表达单个和批量目标：

| 方法 | 路径 | capability |
| --- | --- | --- |
| `POST` | `/system/accounts/commands/reset-password` | `account.password.reset` |
| `POST` | `/system/accounts/commands/delete` | `account.delete` |
| `POST` | `/system/accounts/commands/graduate` | `account.graduate` |
| `GET` | `/system/account-commands/{commandId}` | 对应命令 capability |
| `POST` | `/system/account-commands/{commandId}/credential-ticket` | `account.password.reset`，仅重置命令 |

请求：

```json
{
  "accountIds": ["0199...", "0199..."],
  "expectedCount": 2,
  "reason": "学籍状态批量处理"
}
```

- `accountIds` 必须 1 至 5,000 个并去重；不支持空数组代表全部。
- 服务端按 ID 排序锁定并验证完整集合，默认全成全败。
- 响应使用 `202 Accepted` 返回 `commandId`；小集合即使已经完成，也通过同一状态资源返回结果。
- reset 成功后可签发一次性临时密码结果 ticket；delete/graduate 没有文件结果。

## 8. 授权与管辖上限

| 方法 | 路径 | capability |
| --- | --- | --- |
| `GET` | `/system/roles` | `authorization.read` |
| `GET` | `/system/accounts/{accountId}/role-assignments` | `authorization.read` |
| `POST` | `/system/accounts/{accountId}/role-assignments` | `authorization.grant` |
| `POST` | `/system/role-assignments/{assignmentId}/revoke` | `authorization.revoke` |

创建教师角色：

```json
{
  "roleCode": "TEACHER_ADMIN",
  "scopeCeilingOrgUnitIds": ["grade-a-id", "grade-b-id"],
  "validFrom": "2026-09-16T00:00:00Z",
  "validTo": null,
  "reason": "负责 2025、2026 级"
}
```

变更管辖上限不使用 PATCH。客户端先提交撤销旧 assignment + 创建新 assignment 的替换命令；服务端在一个事务中完成并返回新 ID。接口实现可提供复合 `replace` 用例，但审计事实必须保留旧、新集合及有效范围变化。

本阶段普通管理员只有工作区访问和本人自助能力；接口不得为其自动创建未来业务 capability。

### 8.1 双人安全控制

创建/撤销系统管理员、删除仍有管理员角色的账户、审计导出及策略标记的高风险扩大操作不立即执行，而是创建安全控制请求：

| 方法 | 路径 | capability |
| --- | --- | --- |
| `GET` | `/system/control-requests` | `control.read` |
| `GET` | `/system/control-requests/{controlRequestId}` | `control.read` |
| `POST` | `/system/control-requests/{controlRequestId}/approve` | `control.approve` |
| `POST` | `/system/control-requests/{controlRequestId}/reject` | `control.approve` |

- 原命令返回 `202 PENDING_APPROVAL` 和 `controlRequestId`。
- 批准者必须是另一名当前可用系统管理员，不能是请求者或目标账户。
- 批准前重新校验请求者、批准者、目标状态、revision 和风险策略；任何变化使请求失效或要求重建。
- 批准不修改原命令 payload；request hash 不一致即拒绝。
- 控制请求默认 24 小时过期，批准/拒绝/过期/执行均生成独立审计事件。
- 两个初始系统管理员由一次性运维 bootstrap 仪式共同建立；普通 API 不提供单人过渡绕过双人规则。

## 9. 组织树

| 方法 | 路径 | capability |
| --- | --- | --- |
| `GET` | `/system/org-units` | `organization.read` |
| `POST` | `/system/org-units` | `organization.manage` |
| `PATCH` | `/system/org-units/{orgUnitId}` | `organization.manage` |
| `DELETE` | `/system/org-units/{orgUnitId}` | `organization.manage` |

查询支持按上一级组织惰性加载或按 revision 获取完整树。写入必须携带 `expectedVersion`，使用乐观锁；删除前会检查下级组织、活动账户和权限引用，存在引用时拒绝删除。删除成功后可再次创建相同名称和组织编号，审计记录仍保留。

## 10. 审计查询

| 方法 | 路径 | capability |
| --- | --- | --- |
| `GET` | `/system/audit/events` | `audit.search` |
| `GET` | `/system/audit/events/{eventId}` | `audit.detail.read` |
| `POST` | `/system/audit/export-jobs` | `audit.export` |
| `GET` | `/system/audit/export-jobs/{jobId}` | `audit.export` |
| `POST` | `/system/audit/export-jobs/{jobId}/download-ticket` | `audit.export` |

允许过滤：事件时间范围、事件代码、操作者 account ID、涉及账户 account ID、资源类型/ID、结果、workspace、channel、request ID、trace ID。默认最大查询范围 31 天，更大范围要求更高权限或异步导出。

详情按当前 grant 的 field clearance 脱敏。审计查询与详情查看本身先追加独立审计事件，再释放结果。

## 11. 状态码与稳定错误代码

| HTTP | 代码示例 | 语义 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 字段、模板或集合格式错误 |
| 401 | `AUTHENTICATION_REQUIRED` | 无有效会话 |
| 401 | `PASSWORD_CHANGE_REQUIRED` | 限制会话访问了非白名单端点 |
| 401 | `SESSION_REVOKED` | 登出、重置、删除或毕业后旧会话 |
| 403 | `ACCESS_DENIED` | 没有完整 capability + scope 匹配 |
| 403 | `STEP_UP_REQUIRED` | 高风险操作认证强度不足 |
| 403 | `SEPARATION_OF_DUTIES_VIOLATION` | 请求者尝试批准自己的高风险操作 |
| 404 | `RESOURCE_NOT_FOUND` | 不存在或不可见，避免 ID 枚举 |
| 409 | `VERSION_CONFLICT` | 乐观锁或 revision 不一致 |
| 409 | `LAST_SYSTEM_ADMIN_PROTECTED` | 最后系统管理员保护 |
| 409 | `IMPORT_REVALIDATION_REQUIRED` | 导入快照已变化 |
| 409 | `EXPORT_SELECTION_CHANGED` | 无选择导出的预期数量变化 |
| 409 | `CONTROL_REQUEST_STALE` | 双人控制请求的目标或安全 revision 已变化 |
| 422 | `ROLE_SCOPE_TYPE_INVALID` | 教师/学生管理员 scope 类型错误 |
| 422 | `GRADUATE_ROLE_CONFLICT` | 目标仍有管理员角色，不能毕业 |
| 429 | `RATE_LIMITED` | 登录或接口限流 |
| 503 | `AUDIT_UNAVAILABLE` | 权威审计无法提交，操作关闭失败 |

客户端按稳定 `code` 处理流程，不能解析 message 文本。

## 12. 幂等、并发与重试

- 客户端可以安全重试带同一 `Idempotency-Key` 的写命令，但请求摘要不一致时返回冲突。
- 查询只在网络错误和幂等条件下有限重试；登录、改密、下载 ticket 不做盲目自动重试。
- 账户详情、角色 assignment 和组织节点写入使用 `expectedVersion`。
- 批量命令保存目标 manifest hash，状态查询不重新解释原始选择。
- 任务状态轮询使用退避；未来可增加推送，但不能改变事实来源。

## 13. 契约测试

- OpenAPI 响应 Schema 对后端、TypeScript、Kotlin 和 Swift 生成客户端一致。
- 每个端点至少覆盖成功、未认证、过期、无能力、错误 scope、审计失败和幂等重放。
- 任何未在 OpenAPI 中声明的响应字段不得成为客户端必需依赖。
- breaking change 发布 `/v2` 或明确的新契约版本，不原地改变枚举语义。
## 模块 SSO 会话语义补充

`POST /modules/medical-insurance/sso/ticket` 的票据绑定当前 Shtong 会话、工作区和授权版本。模块适配层通过 exchange 得到当前有效身份投影；同一账号的 USER 与 ADMIN 投影彼此独立，不能互相覆盖。模块退出只清理模块会话，不调用父会话 revoke；全局退出仍由 Shtong `auth/logout` 完成。
