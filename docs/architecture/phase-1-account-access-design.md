# 第一阶段账户与权限实施设计

- 状态：账户底座已实现；医保信息系统接入补充已确认
- 日期：2026-09-18
- 上游需求：[第一阶段账户、基础权限与审计基线](../product/phase-1-account-access-baseline.md)

## 1. 实施边界

本阶段仍采用模块化单体。`gateway` 组合并启动下列模块，`jobs` 作为唯一独立 worker：

```text
gateway
|-- account
|-- authorization
|-- organization
|-- module-catalog
|-- audit
`-- admin                 # 只编排系统管理用例

jobs
|-- import validation/result cleanup
|-- export generation
|-- audit outbox delivery
`-- integrity seal/archive preparation
```

`domain`、`workflow`、`reporting` 和 `notification` 只保留模块边界，不实现业务。小程序不建立工程、不生成 SDK、不参加联调。

## 2. 模块职责

| 模块 | 第一阶段职责 | 不得拥有 |
| --- | --- | --- |
| `account` | 账号、密码凭据、登录锁定、会话、生命周期 | 角色权限和组织树 |
| `authorization` | 角色模板、角色赋予、管辖上限、grant、capability 求值、授权版本 | 密码凭据和业务数据 |
| `organization` | 学校/学院/年级/班级、闭包关系、当前成员归属 | 账号认证和 grant |
| `module-catalog` | 按上下文返回空工作台、系统管理模块或医保信息系统入口 | 任意动态代码/URL |
| `audit` | 权威只追加事件、授权证据、主体/资源、manifest、查询 | 修改业务事实 |
| `admin` | 跨模块编排账户创建、批量命令、授权和审计查询用例 | IAM/组织/审计核心表 |
| `gateway` | 认证门、会话上下文、统一鉴权、限流、错误和响应前审计 | 领域状态 |
| `jobs` | 已提交任务的异步执行与受控清理 | 首次创建权威审计事实 |

模块之间只通过应用接口和事件调用。禁止 `admin` 直接写其他模块表。

## 3. 第一阶段工作区和模块

### 3.1 所有已认证上下文

应用壳始终提供以下固定动作，它们不作为远程动态模块：

- 空工作台。
- 个人中心。
- 主动修改密码。
- 退出登录。

### 3.2 系统管理员上下文

服务端仅向 `SYSTEM_ADMIN` 返回：

| 模块代码 | 本地路由键 | 所需能力 |
| --- | --- | --- |
| `system.accounts` | `system.accounts` | `account.read` |
| `system.authorization` | `system.authorization` | `authorization.read` |
| `system.organization` | `system.organization` | `organization.read` |
| `system.audit` | `system.audit` | `audit.search` |

`USER` 和 `ADMIN` 工作区除满足医保模块角色的上下文外保持空模块。`SYSTEM_ADMIN` 继续返回系统管理模块，并在当前系统管理员上下文显示医保入口。个人中心和退出由壳层注册，不依赖模块目录，服务端仍校验自助 capability。

### 3.3 医保信息系统接入

`medical-insurance` 是本阶段唯一新增业务模块。Shtong gateway 仅签发一次性 60 秒 server-ticket；适配器通过服务端凭据调用 exchange、introspect 和 revoke。映射严格绑定当前会话 workspace：`USER -> student`、`ADMIN` 中的 `MEDICAL_INSURANCE_ADMIN -> admin`、`SYSTEM_ADMIN -> super_admin`。多角色账号默认 `USER`，不得从账号拥有的其他角色推断当前医保角色。

`_yb` 侧只增加独立 SSO 适配层和影子账户/范围映射表，不重写既有业务逻辑、页面、业务数据库规则或功能；受保护页面和 API 仍由 `_yb` 原有业务服务执行对象级范围校验。父会话失效、账号停用、授权版本变化或角色/范围不再匹配时适配器立即失效；短时上游不可用只允许有界 grace。

每个系统管理员必须被显式授予版本化的 `SYSTEM_ACCOUNT_MANAGEMENT_V1` 和 `SYSTEM_AUDIT_REVIEW_V1` grant 模板。后者只覆盖本阶段系统事件和默认脱敏字段，不自动扩展到未来业务审计；审计导出仍要求用途、step-up 和双人控制。

## 4. 请求链

```text
client
-> gateway: authenticate session + audience + context
-> authorization: capability + scope + assurance + authz revision
-> admin application service: coordinate use case
-> account/organization/authorization owner interfaces
-> audit.append_event in the same transaction
-> outbox
-> COMMIT
-> gateway releases response
```

受保护读取也必须先完成查询和权威审计提交，再返回数据。审计不可用时关闭失败。

## 5. 管辖上限设计

角色赋予 `role_assignment` 表示账号在一个工作区中的身份。新增 `assignment_scope_ceiling` 表示该角色赋予可使用的最大组织范围：

- `TEACHER_ADMIN`：一个或多个 `GRADE`。
- `STUDENT_ADMIN`：一个或多个 `COLLEGE`、`GRADE`、`CLASS`。
- `USER`：隐含 `SELF`，不写组织 ceiling。
- `SYSTEM_ADMIN`：系统管理域隐含 `GLOBAL`，不覆盖未来业务域。

以后新增模块能力时，`authorization_grant` 仍把 capability 和实际 grant scope 放在同一个不可变聚合内。授权提交过程必须证明每个 grant scope 都被所属 assignment 的至少一个 ceiling scope 覆盖。

父子 ceiling 重叠可以保留直接授权意图，但有效范围计算去重。组织树变化可能扩大 ceiling 时，先冻结影响清单、要求确认并递增相关授权版本。

## 6. 账户命令编排

### 6.1 创建账户

单事务执行：

1. 规范化并检查登录名、人员编号唯一性。
2. 校验角色组合、当前班级和 assignment ceiling。
3. 创建账户和随机临时密码哈希。
4. 创建角色赋予与管辖上限。
5. 创建本阶段所需自助/系统管理 grant。
6. 写入审计事件、字段差异、目标账户和 outbox。
7. 提交后一次性返回临时密码。

创建 `SYSTEM_ADMIN` 使用单独高风险命令，禁止通过普通批量导入。

### 6.2 重置密码

校验完整目标集后在同一事务更新每个目标的凭据版本、撤销会话、写审计与 manifest。非系统管理员凭据设为 `TEMPORARY`；系统管理员保持 `ACTIVE`。临时密码只在事务提交后组装一次性交付结果。

### 6.3 删除与毕业

- 删除写入生命周期 `DELETED`；毕业写入 `GRADUATED`。
- 两者都撤销会话、撤销或终止有效角色赋予、递增授权版本，并保留历史记录。
- 命令提交前锁定所有目标账号行，按稳定 ID 排序取锁，避免批量死锁。
- 任一目标包含操作者自身、最后一个系统管理员或不符合毕业条件的角色时，整批拒绝。

### 6.4 授权变更

- 旧角色赋予、ceiling 和 grant 不原地改写；通过撤销旧记录并创建新版本表达变化。
- 禁止自我提权。
- `SYSTEM_ADMIN` 赋予/撤销、权限扩大和敏感审计能力包执行更高 assurance 与双人控制。
- 变更与 `authz_version` 递增、会话撤销、审计、outbox 同事务。

高风险命令通过轻量 `security_control_request` 实现双人控制，不引入通用业务审批引擎。请求保存规范化命令 hash、目标 manifest、请求者、风险策略版本和到期时间；另一名系统管理员批准时重新鉴权并重新校验目标，随后由原用例在同一事务执行。请求者、批准者和目标账户不得是同一人。

## 7. 导入导出任务

导入分“校验任务”和“确认提交”两步。校验任务不改变账号事实；确认提交时重新校验组织 revision、唯一性和操作者授权，并以单事务创建最多 5,000 个账户。

导出任务状态：

```text
PENDING -> RUNNING -> READY -> EXPIRED
                 `-> FAILED
```

任务保存经过规范化的选择 ID 或过滤器快照、预计数量、授权版本、用途、结果 manifest 和文件 hash。worker 不能沿用过期授权；生成与下载前都重新鉴权。

## 8. API-first 同步策略

- `packages/contracts/openapi/account-access-baseline.v1.json` 是第一阶段 HTTP 契约事实源。
- `capabilities`、`modules` 和 `audit-events` 目录保存稳定代码，服务端与各客户端均不得手写另一套枚举。
- OpenAPI 生成 TypeScript、Kotlin 和 Swift DTO/客户端；生成物放入 `packages/api-sdk` 对应产物目录，业务工程不能直接修改生成代码。
- CI 检查 OpenAPI、JSON Schema 和代码生成结果是否同步。
- 错误响应统一包含 `code`、本地化 message key、`requestId` 和可选字段错误，不返回内部异常。

详细端点见 [第一阶段账户与权限 API 契约](../api/phase-1-account-access-api.md)。

## 9. Web 实施准备

### 9.1 `apps/web/user`

- 登录、强制改密、空工作台、个人中心、退出。
- Nuxt 登录后页面使用 CSR，不持久化受保护查询。

### 9.2 `apps/web/admin`

- 同样的认证壳；高权限工作区直接切换，无 MFA/step-up。
- 账户列表支持服务端分页、筛选、跨页选择和异步任务状态。
- 授权页使用组织树选择器；教师只显示年级，学生管理员显示学院/年级/班级。
- 审计详情是只读页面，不提供编辑/删除控件。
- 导入采用“模板 -> 上传预检 -> 错误修复 -> 确认提交 -> 一次性结果”步骤。

共享登录协议、SDK、设计令牌和错误处理；不要把两个应用仅靠前端路由隔离。

## 10. Android 实施准备

首期启用这些 feature：

```text
authentication
workspace
profile
account-management
authorization
master-data
audit
```

- Compose 使用自适应列表/详情布局；手机上为列表到详情导航，大屏可双栏。
- 批量选择进入明确的 contextual action mode，并始终显示已选数量。
- 文件选择和分享使用系统接口，临时敏感文件放入应用私有缓存并按期清除。
- 登出重置导航图，进程恢复不得恢复已撤销工作区。

## 11. iOS 实施准备

启用与 Android 相同的概念 feature，各自实现 SwiftUI 原生界面：

- `NavigationStack` 与协调器维护认证根、工作区根和系统管理子路径。
- 多选使用平台标准 Edit/selection 模式；iPad 采用 `NavigationSplitView`。
- 文件导入使用 Document Picker，导出使用 Share Sheet/Files。
- 登出替换根协调器并清空导航 path、sheet 和敏感状态。

## 12. 配置与环境

至少准备：

- PostgreSQL 连接、Flyway 迁移账号和运行时最小权限账号。
- Redis/Valkey 会话撤销、限流和短期任务状态配置。
- S3 私有 bucket、服务端加密、对象过期规则和短期签名地址。
- KMS 签名密钥与独立 WORM 归档目标；本地开发使用明确标注的替代实现。
- 初始系统管理员 bootstrap 命令；不得在仓库、镜像或迁移脚本中硬编码默认密码。
- Web Cookie 域、CSRF、CORS；Android/iOS API audience 和深链白名单。

## 13. 初始系统管理员

部署时通过一次性、仅运维可执行的 bootstrap 仪式创建两个相互独立的初始系统管理员，避免系统上线后出现无法满足双人控制的过渡状态：

1. 要求数据库中不存在系统管理员，并持有部署环境的一次性 bootstrap secret。
2. 从安全输入读取两个不同自然人的登录名和独立随机密码，不写命令历史或日志。
3. 在一个受控事务中创建两个账户、`SYSTEM_ADMIN` 角色、系统管理 grant 和完整审计控制记录。
4. 两个账户均就绪后永久关闭该环境的 bootstrap secret，后续系统管理员操作全部进入普通双人控制。

该流程不使用普通公开 API，也不在 Flyway 中插入固定账号。

## 14. 推荐开发顺序

1. 冻结角色、capability、模块、事件和 OpenAPI 契约。
2. 建立 Maven 多模块、Web 双应用、Android 和 iOS 空工程及契约生成流水线。
3. 实现 Flyway Schema、受控数据库过程和初始系统管理员 bootstrap。
4. 实现登录、强制改密、session、bootstrap、context 和 logout。
5. 实现组织树、账户查询/创建与角色管辖上限。
6. 实现批量导入、导出、重置、删除和毕业。
7. 实现审计查询、详情、manifest、outbox 与完整性作业。
8. 完成 Web，再以相同契约并行完成 Android/iOS 原生体验。
9. 执行契约、安全、审计故障、跨端 E2E 和容量测试。

每一步都以可运行的纵向流程和测试完成，不提前进入业务模块。

## 15. 完成定义

- 五个内置身份与三种工作区行为符合产品基线，且已接入的医保模块按当前角色与范围返回。
- Web、Android、iOS 使用同一版本契约并通过契约测试。
- 所有高风险命令幂等、可解释、可审计，禁止物理删除。
- 审计失败关闭策略、授权串权测试和 logout 返回栈测试通过。
- 导入导出与临时密码文件生命周期经过故障测试。
- 在 1,000 虚拟用户容量模型下认证、列表和审计写入达到技术栈文档目标。
- 小程序无实现改动，除医保信息系统外的未定义业务模块仍为空。
