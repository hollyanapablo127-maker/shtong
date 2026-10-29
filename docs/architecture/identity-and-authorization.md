# 统一身份与分级授权架构

- 状态：已采用
- 日期：2026-09-15
- 当前实现说明：登录已改为仅账号密码，MFA/step-up 已移除；本文涉及 MFA/step-up 的内容为长期设计，不代表当前行为，见 [ADR-0005](decisions/0005-password-only-login-and-incremental-persistence.md)。

## 1. 不变量

- 一个自然人对应一个 `account`，身份不得通过复制账号实现。
- 一个账号可拥有多条角色赋予记录，支持用户、教师管理员、学生管理员、医保系统管理员和系统管理员同时存在。
- 三个身份空间用于组织体验，不构成权限数值继承。
- 用户端和管理端共享统一账号及业务事实数据，入口或工作区不得形成第二套身份库。
- 授权结论由 capability、数据范围、渠道、身份上下文、认证强度和业务约束共同决定。
- 客户端展示结果不可信，所有 API、导出、统计、审批和批量任务均在服务端鉴权。
- 每次允许或拒绝都必须能解释命中的角色赋予、授权、范围和策略版本。

## 2. 模块边界

```text
account          账号、凭据、MFA、会话和账号状态
authorization    角色、capability、授权、策略求值与授权版本
organization     学院/年级/班级树、成员关系、字典和字段选项
module-catalog   工作区和模块目录，按 capability 生成可见模块
gateway          认证上下文、渠道约束、API 入口和统一拒绝处理
audit            授权解释和业务操作的不可变审计记录
```

这些是模块化单体中的边界，首期除 worker 外不独立部署。模块之间只通过应用接口和事件协作。

## 3. 角色与授权

### 3.1 角色模板

内置角色是不可删除的模板：

- `USER`，身份空间 `USER`。
- `TEACHER_ADMIN`，身份空间 `ADMIN`，管理员类型 `TEACHER`，包含辅导员场景。
- `STUDENT_ADMIN`，身份空间 `ADMIN`，管理员类型 `STUDENT_ADMIN`。
- `MEDICAL_INSURANCE_ADMIN`，身份空间 `ADMIN`，仅用于“医保信息系统”的范围受限管理能力。
- `SYSTEM_ADMIN`，身份空间 `SYSTEM_ADMIN`。

角色模板定义可以授予的 capability 上限和数据范围类型，不直接代表某账号当前拥有全部权限。

### 3.2 赋予与授权

`role_assignment` 表示账号在一段有效期内拥有某个角色；`assignment_scope_ceiling` 保存该账号在此管理员角色下的最大组织管辖范围。每条 `authorization_grant` 隶属于一条角色赋予，并绑定 capability 集合、带维度的实际 scope 集合，以及审计查询专用的字段可见分类集合。

```text
role_assignment
|-- assignment_scope_ceiling[]   # ADMIN 角色的账号级管辖上限
`-- authorization_grant
    |-- grant_capability[]
    |-- grant_scope[]             # 必须在所属 assignment ceiling 内
    `-- grant_field_clearance[]   # 仅审计查询 grant
```

同一 grant 内 capability、scope dimension、scope 与可选 clearance 的组合是有意授权；不同权限范围或字段可见级别必须拆成不同 grant。普通业务 grant 只使用 `GENERAL` scope 且不携带审计 clearance；每条审计查询 grant 从 `ACTOR`、`SUBJECT`、`RESOURCE` 中选择恰好一个 dimension，并显式列出允许释放的字段分类。两类 capability 不得混入同一 grant。授权服务必须对 grant 内所有组合逐项确认未超过角色模板上限；管理员 grant 的 scope 还必须是所属 role assignment 管辖上限的子集。

管辖上限不是权限。模块 grant 必须明确选择 capability 和不超过 ceiling 的 scope；当前医保模块的 `module.medical-insurance.access` 只对 `USER`、`MEDICAL_INSURANCE_ADMIN`、`SYSTEM_ADMIN` 在相应上下文开放。禁止把 ceiling 自动套用到其他模块。

首期仅实现 ALLOW grant，不实现 DENY。拒绝由“没有任何完整匹配的有效 grant”得出，避免 allow/deny 优先级造成难以解释的策略。

### 3.3 capability 命名

采用稳定的 `resource.action` 形式，例如：

```text
account.read
account.create
account.disable
session.revoke
authorization.grant
organization.manage
dictionary.manage
student.read
student.update
approval.task.read
approval.task.decide
report.view
report.export
audit.search
audit.detail.read
audit.sensitive.read
audit.export
```

查询、导出、审批和敏感字段查看必须使用不同 capability，不能用一个宽泛的 `manage` 覆盖全部动作。

## 4. 组织与数据范围

组织结构由 `organization` 模块维护为有类型的树：`SCHOOL -> COLLEGE -> GRADE -> CLASS`。节点使用不可复用内部 ID，改名不改变 ID。

scope 类型：

- `SELF`：仅本人及明确属于本人的资源。
- `GLOBAL`：只用于角色模板允许的系统资源。
- `ORG_SUBTREE`：指定组织节点及其所有后代。

角色约束：

- `TEACHER_ADMIN` 的 `assignment_scope_ceiling` 根节点必须为 `GRADE`，可以多选。
- `STUDENT_ADMIN` 的 assignment ceiling 根节点可以为 `COLLEGE`、`GRADE` 或 `CLASS`，可任意组合多选。
- `SYSTEM_ADMIN` 的 `GLOBAL` 只覆盖账户、授权、组织配置和审计等系统域。
- `USER` 默认使用 `SELF`。

系统管理员为学生管理员设置管辖上限时可任意组合多选学院、年级和班级节点；为教师管理员设置管辖上限时只能多选年级节点。授权写入必须通过受控命令和数据库提交时约束验证角色模板上限、账号级 ceiling、真实组织节点类型、有效期以及 grant 至少各含一个 capability 和 scope。

组织范围通过闭包表或等价的预计算关系在 SQL 中求值。列表查询必须把授权条件下推到数据库，禁止先读取全部数据再在 Java 中过滤。

### 4.1 资源范围解析契约

每个 capability 的 `scope_resolver` 必须绑定唯一、可测试的资源范围解析规则：

- 本人资源使用不可变 `owner_account_id` 与 `SELF` 判断。
- 账号/学生当前管理操作使用目标账号的当前有效 `account_membership`。
- 历史业务记录使用写入时固定的 `security_scope_org_unit_id`，不随用户后来转班而改变。
- 审批同时使用业务记录的安全范围、当前任务候选资格和节点状态。
- 统计先生成经授权且去重的主体集合，再执行聚合。

跨 Schema 范围过滤通过拥有者提供的受控只读视图或授权查询接口完成，业务模块不得自行猜测资源范围。

## 5. 授权算法

对每个受保护用例执行以下判断：

```text
输入：account、active workspace、channel、assurance level、capability、target、所需 scope dimension 与字段分类

1. 校验账号和会话有效，token audience 与渠道匹配。
2. 读取账号当前 authorization version，拒绝已撤销上下文。
3. 找到当前身份空间内有效且未过期的 role_assignment。
4. 在同一 assignment 下寻找一条有效 authorization_grant：
   a. grant 包含目标 capability；
   b. grant 中至少一个相同 dimension 的 scope 覆盖 target；
   c. 审计查询所需的每个字段分类均包含在该 grant 的 clearance 集合中；
   d. grant 的 capability、所有 scope 与所有 clearance 组合均符合角色模板限制；
   e. ADMIN 角色的每个 grant scope 都被所属 assignment 的至少一个 ceiling 覆盖。
5. 校验 MFA/二次认证、业务字段规则、工作流节点、职责分离等附加规则。
6. 对批量目标逐项求值；默认任一目标失败则整批拒绝。
7. 记录 ALLOW 或 DENY 的审计解释；批量操作按目标或同质目标组保存命中的 assignment、grant、scope dimension、scope 与 clearance evidence。
```

严禁使用下面的简化判断：

```text
capability in union(all account capabilities)
AND target in union(all account scopes)
```

该写法会把不同角色或不同 grant 的权限与范围交叉组合，造成横向越权。审计查询同样不得把一条 grant 的 scope dimension 与另一条 grant 的字段 clearance 拼接使用。

## 6. 会话与身份上下文

登录只确认账号身份。服务端随后建立可切换的工作区上下文：

- `USER`：账号显式拥有 USER 角色时可用；多身份账号默认进入该低权限工作区。
- `ADMIN`：包含教师管理员或学生管理员的有效角色赋予。
- `SYSTEM_ADMIN`：账户、授权、组织配置和系统运行审计；敏感解密、全局业务审计、导出、保留策略等需额外审计能力包。

移动端每次启动进入认证门：无有效会话时登录；有有效会话时按安全策略解锁。管理员或系统管理员不会自动拥有用户工作区；账号没有 USER 角色时停留在工作区选择/认证门，完成 step-up 和 MFA 后才签发最低权限的有效管理上下文。进入管理员或系统管理员工作区均需要 step-up 与 MFA；系统管理员及高风险操作还必须满足更强认证等级和双人复核规则。

高权限 access token 只携带以下上下文标识；首期 Web 会话保持到浏览器会话结束，移动端会话保持到应用重新启动或手动退出，服务端仍可按授权版本即时撤销：

```text
sub, session_id, workspace, context_id, authz_version, assurance_level, audience
```

不要把完整角色、权限和组织范围塞入长期 JWT。权限结果只可按 `account_id + context_id + authz_version + policy_revision + org_revision` 短期缓存，且不能缓存脱离这些版本的 target/组织闭包判定。

失效规则：

1. 账号停用或角色/grant 变化时，原子递增受影响账号的 `authz_version`。
2. 角色模板、capability 或全局策略变化时递增 `policy_revision`。
3. 组织结构变化时递增 `org_revision`，并按影响清单递增相关账号的 `authz_version`。
4. 清除授权与组织闭包缓存，撤销受影响的管理员和系统管理员上下文。
5. 通过可靠事件通知其他 API 实例；实例同时在每次高风险请求校验当前 revision。
6. 记录变更前后授权、直接 scope、有效 scope 和全部受影响账号。

## 7. 启动协议

推荐接口：

```http
POST /v1/auth/login
GET  /v1/session/bootstrap
POST /v1/session/context
POST /v1/session/step-up
POST /v1/auth/logout
```

`GET /v1/session/bootstrap` 示例：

```json
{
  "catalogVersion": "2026-09-15.1",
  "authorizationVersion": 17,
  "policyRevision": 8,
  "organizationRevision": 31,
  "account": {
    "id": "acc_01...",
    "displayName": "示例用户"
  },
  "activeContext": {
    "id": "ctx_01...",
    "workspace": "USER",
    "assuranceLevel": "AAL1",
    "contextExpiresAt": "2026-09-15T12:00:00Z"
  },
  "activeModules": [
    {"code": "profile", "routeKey": "profile", "order": 10},
    {"code": "business.home", "routeKey": "business.home", "order": 20}
  ],
  "availableWorkspaces": [
    {
      "code": "USER",
      "requiresStepUp": false
    },
    {
      "code": "ADMIN",
      "requiresStepUp": true,
      "adminKinds": ["TEACHER", "STUDENT_ADMIN"]
    },
    {
      "code": "SYSTEM_ADMIN",
      "requiresStepUp": true
    }
  ]
}
```

bootstrap 只返回当前已建立上下文的模块，高权限工作区在 step-up 前只返回存在性摘要。切换管理员或系统管理员时，客户端先调用 `POST /v1/session/step-up`，再调用 `POST /v1/session/context`；服务端轮换为限定 audience/context 的短期令牌后，重新获取该上下文模块。医保模块入口也必须使用当前上下文，不能以账号任意角色替换当前角色。

上例是拥有 `USER` 角色时的响应。账号没有 USER 角色且尚未建立高权限上下文时，`activeContext` 为空、`activeModules` 为空，只返回可选工作区摘要；完成 step-up 和 context 切换之前不得签发或返回管理模块。

真实响应使用本地化 key，不由服务端下发任意 HTML、组件类名或可执行 URL。服务端从已签发上下文读取 workspace，不接受业务请求自行声明身份空间。

小程序的 audience 固定为 `MINIPROGRAM_USER`，bootstrap 只能返回 `USER` 工作区。Web 管理端使用独立域名和 audience。

## 8. 模块可见性与操作权限

`packages/contracts/modules` 是模块定义、工作区/渠道暴露和 capability 要求的版本控制事实源；`module-catalog` 只维护加载后的运行时投影、启停、灰度和目录版本。服务端只返回当前上下文满足该 exposure 要求的模块，客户端只渲染本地白名单中的返回结果，不自行根据 capability 拼装清单。

客户端维护 `module code -> native route` 白名单，只显示双方都认识的模块。模块可见表示“可能执行其中某些操作”，不代表对模块内所有资源有权限。

每次 API 调用仍传递目标资源，由服务端重新执行第 5 节算法。客户端选择的学院、年级或班级过滤器只用于交互，不是安全边界。`medical-insurance` 入口由服务端签发一次性 60 秒票据；外部 `_yb` 适配器通过 introspection 重新校验父会话、角色、范围和授权版本。

## 9. 高风险控制

- 禁止账号给自己增加角色、capability 或 scope。
- 禁止删除或停用最后一个可用系统管理员。
- 创建/撤销系统管理员、修改自身权限和最后一个系统管理员保护必须双人复核或进入受控应急流程。
- 其他权限扩大和批量账号操作按风险级别决定是否强制双人复核。
- 管理员不能审批自己的申请；代理和转办必须显式记录原处理人与代理人。
- 导出使用独立 capability、明确目的、短期下载链接和水印。
- 系统管理员的 `GLOBAL` 统计只覆盖账号、安全、会话、授权、组织配置和审计运行指标；业务聚合仍要求额外业务角色中的 capability 与 scope。
- 所有拒绝、上下文切换、step-up、授权变更和缓存失效都写入审计。

## 10. 必测场景

- 同账号兼具角色时 capability 与 scope 不串权。
- 审计查询的 capability、scope dimension、scope 与字段 clearance 不跨 grant 拼接，普通业务和审计查询授权不能混入同一 grant。
- 教师管理员不能获得学院或班级 ceiling 或 grant scope。
- 学生管理员多范围按子树并集求值。
- 越权 ID、分页、搜索、统计、导出、批量接口均被拒绝。
- 系统管理员没有业务管理员角色时不能审批。
- 撤权、停用和组织迁移后旧高权限会话立即失效。
- 小程序不能创建或切换到管理工作区。
- 前端隐藏模块后直接调用 API 仍被拒绝并产生审计事件。

概念数据模型见 [权限与审计数据模型](../database/access-control-and-audit-model.md)，产品规则见 [身份、角色与数据管辖范围](../product/roles-and-data-scopes.md)，决策记录见 [ADR-0002](decisions/0002-unified-account-scoped-authorization.md)。
## 外部模块的多身份会话投影

同一 Shtong 账号可以同时拥有 `USER` 与 `MEDICAL_INSURANCE_ADMIN` 等多个身份。外部模块适配层必须保存账号映射下的多条身份投影（身份代码、外部角色、授权版本和范围），并在一次模块会话中保存当前工作区对应的有效身份。持久化的业务兼容角色只能作为旧数据兼容字段，不能作为 SSO 授权事实，也不能因工作区切换而被覆盖。

模块权限检查必须使用服务端校验后的当前身份投影和该身份的范围并集；客户端提交的角色、工作区或范围一律不可信。模块会话退出是本地会话清理，不等同于 Shtong 全局退出；只有 Shtong 全局退出或授权版本变化才撤销父会话。
