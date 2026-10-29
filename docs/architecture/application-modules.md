# 应用工作区与业务模块架构

- 状态：已采用
- 日期：2026-09-15
- 当前实现说明：登录已改为仅账号密码，MFA/step-up 已移除；本文涉及 MFA/step-up 的内容为长期设计，不代表当前行为，见 [ADR-0005](decisions/0005-password-only-login-and-incremental-persistence.md)。

## 1. 目标

应用统一采用“认证门 -> 身份工作区 -> 模块首页 -> 具体业务”的结构。模块按业务能力组织，不按用户、管理员目录复制实现；角色和数据范围决定模块是否出现以及其中哪些操作可用。

## 2. 启动流程

```text
应用启动
  -> 认证门
     -> 无有效会话：登录 / MFA / 设备校验
     -> 有有效会话：按策略解锁并刷新短期令牌
  -> GET /v1/session/bootstrap
     -> 返回仍有效的当前上下文（如有）、可选工作区摘要和当前模块
  -> 选择或恢复工作区
     -> USER：建立或继续使用当前低权限上下文
     -> ADMIN / SYSTEM_ADMIN：
        -> POST /v1/session/step-up (MFA)
        -> POST /v1/session/context
        -> 服务端轮换为绑定 workspace、context、audience 和授权版本的短期令牌
        -> 返回或重新获取当前上下文的模块清单
  -> 本地模块注册表与服务端目录取交集
  -> 展示模块首页
  -> 每次业务请求重新执行服务端 capability + scope 鉴权
     -> 审计查询额外联合校验 dimension + field clearance
```

账号只有用户工作区时直接进入用户首页。账号拥有 USER 和其他工作区时默认进入低权限用户工作区，并提供显式工作区切换；没有 USER 角色时不伪造用户上下文，必须先选择可用管理工作区并完成 step-up 与 MFA。切换到管理员或系统管理员必须由服务端检查 step-up/MFA 的时效与强度，不能只修改客户端状态。最近一次认证仍满足策略时可以免去重复交互，但仍必须调用上下文切换接口并轮换令牌或服务端会话。

`bootstrap` 中未激活的高权限工作区只返回名称、类型和是否需要 step-up 等摘要，不把其模块清单当作当前授权结果。`POST /v1/session/context` 成功后返回 `contextId`、`authorizationVersion`、`assuranceLevel`、`contextExpiresAt` 和当前上下文模块；后续 API 从已签发上下文取得工作区，不接受客户端自行声明角色或 scope。高权限上下文过期、撤权或切换后必须清理页面栈和敏感缓存。

## 3. 工作区

| 工作区 | 面向身份 | 典型模块 |
| --- | --- | --- |
| `USER` | 用户 | 个人资料、业务模块、申请、本人进度 |
| `ADMIN` | 教师管理员、学生管理员 | 业务管理、审批待办、范围内统计 |
| `SYSTEM_ADMIN` | 系统管理员 | 账户、授权、组织主数据、审计 |

同一个模块可以出现在多个工作区。例如用户可在审批模块提交申请，管理员可在同一模块处理任务；模块内部按 capability 呈现不同动作。

### 3.1 第一阶段裁剪

第一阶段只接入已确认的“医保信息系统”一个业务模块。`USER` 和 `ADMIN` 仅在当前上下文满足医保角色、capability 和范围时显示 `medical-insurance`，其他未定义业务保持为空；`SYSTEM_ADMIN` 返回四个系统管理模块并显示医保入口。应用壳始终保留空工作台、个人中心、修改密码和退出登录。表中的其他模块均是后续架构位置，不代表已经排期、授权或发布。

### 3.2 医保模块的 SSO 边界

工作台入口调用 `POST /v1/modules/medical-insurance/sso/ticket`，由 gateway 生成一次性、60 秒有效的服务端票据并跳转到 `_yb` callback。票据不携带密码或长期凭据，仅绑定 Shtong session、当前 workspace、模块角色和授权版本；`_yb` 使用服务端适配器凭据交换后建立本地 Django session。

接入模块每次受保护请求 introspect 父 Shtong session，登出时 revoke 父 session。生产 `SHTONG_SSO_ONLY=true` 时，旧 HTML 密码登录、旧 Bearer 入口和无 SSO 标记的本地 session 均拒绝；短时网络故障最多使用适配器配置的 grace 窗口。生产地址由部署环境配置，不写入源码或文档。

## 4. 模块目录协议

模块目录拆分为定义、渠道/工作区暴露和能力要求，避免同一个模块出现在多个工作区时复制或串用配置：

```text
module_definition
  code
  title_key
  icon_key
  status
  catalog_version

module_exposure
  exposure_id
  module_code
  workspace
  channel
  minimum_client_version
  order
  feature_flag

module_exposure_requirement
  exposure_id
  capability_code
  match_mode: ANY | ALL
```

`module_definition` 是稳定模块身份；`module_exposure` 允许同一模块在 USER、ADMIN、SYSTEM_ADMIN 及 Android、iOS、Web、小程序使用不同排序、最低版本和开关；`module_exposure_requirement` 表达该次暴露所需能力。同一个 exposure 的 requirement 首期统一使用一种 `match_mode`，不允许混用；`ANY` 表示至少一项要求在当前上下文具有有效授权，`ALL` 表示每项都具有有效授权。该判断只决定入口是否有意义，不代表模块内资源已经获准访问。

`packages/contracts/modules/` 是上述稳定定义的版本控制事实源。`services/module-catalog` 消费这些契约，结合运行时启停、灰度和 feature flag 生成查询投影；不得在数据库中另建一套可独立修改的模块代码或 capability 规则。feature flag 只能关闭或缩小暴露范围，不能授予权限。每次生成结果都绑定 `catalogVersion` 和 `authorizationVersion`。

客户端维护编译期白名单：

```text
module code -> 本地路由/页面工厂
```

服务端不能下发任意 URL、类名、脚本或动态二进制。客户端忽略未知模块并上报兼容性指标；这既避免远程代码风险，也符合移动应用发布要求。

模块清单只负责首页可见性。模块内部按钮、字段、数据行和操作结果仍以服务端授权为准；服务端必须以当前 context 中同一条有效 grant 的 capability + scope 求值，审计查询还要在该 grant 内匹配 dimension 与字段 clearance。不能因为模块已显示而跳过鉴权。

## 5. 客户端目录

Android 与 iOS 使用相同的概念模块，但各自实现原生 UI：

```text
features/
|-- authentication/       # 登录、MFA、会话解锁
|-- workspace/            # 工作区切换、模块目录和首页
|-- profile/              # 本人资料
|-- business/             # 具体业务模块的父目录
|-- approval/             # 申请、待办和审批历史
|-- notifications/        # 站内消息、审批提醒和通知偏好
|-- reporting/            # 授权范围内统计与导出
|-- account-management/   # 系统管理员账户操作
|-- authorization/        # 角色、capability 与 scope 授权
|-- master-data/          # 学院、年级、班级及字典
`-- audit/                # 审计查询、详情与导出
```

`business/` 下每个确定的业务域建立独立子模块，不建立万能页面模块。跨模块基础设施进入平台自己的 `core/`，不能把业务逻辑堆入 core。

Web 管理端开始代码落地后，按 `accounts / authorization / master-data / audit / operations / approvals / reports` 组织功能；当前骨架仍只保留应用根目录。微信小程序只编译用户需要的模块，不能把管理模块打包后仅靠菜单隐藏。

## 6. 模块契约

语言无关的稳定标识放在 `packages/contracts/`：

- `openapi/`：OpenAPI 3.1 源契约。
- `modules/`：模块定义、工作区/渠道暴露、能力要求和兼容版本的事实源。
- `capabilities/`：权限代码、资源类型和风险级别。
- `audit-events/`：审计事件类型和字段 Schema。
- `analytics-events/`：非权威产品遥测事件。
- `integration-events/`：outbox 事件信封和业务、流程、投影事件的版本化 Schema。
- `metrics/`：指标代码、公式、时间口径、维度、敏感级别和定义版本。

CI 从这些契约生成 TypeScript、Kotlin 和 Swift 常量/SDK，并检查模块引用的 capability、审计事件和集成事件均已注册。集成事件信封至少包含 `eventId`、`eventType`、`schemaVersion`、`aggregateId`、`occurredAt`、`traceId` 和 `auditEventId`。

## 7. 审批流

`services/workflow` 提供稳定的 `WorkflowService`，业务模块仍拥有申请单和业务数据，工作流模块只拥有：

- 流程定义及版本。
- 流程实例。
- 当前任务与候选处理人规则。
- 提交、通过、驳回、退回、撤回、转办、委托、超时和取消。
- 每次状态迁移的原因与审计关联。

流程定义发布后不可原地修改；已启动实例固定使用启动时版本。审批处理同时要求：

```text
具备节点 capability
AND 目标业务数据在授权 scope 内
AND 当前账号属于该节点候选处理人
AND 满足职责分离规则
```

业务应用服务负责事务编排：申请单或业务记录、流程实例/任务、权威审计事件和 transactional outbox 必须在同一本地数据库事务内写入。业务模块是业务内容的事实源，workflow 是流程实例和任务状态的事实源；任何为查询保存的冗余状态都是可重建投影，不允许两个模块通过独立事务分别推进同一次流转。

所有提交、审批、撤回、转办、委托和取消命令携带 `commandId` 或 `Idempotency-Key`。活动任务保存版本号，状态迁移使用乐观锁或带当前状态条件的更新；用户决定、自动超时和重复请求竞争时只能有一个迁移成功，其余返回确定的冲突或既有结果。

候选处理规则随流程定义版本保存，但真正执行操作时必须重新检查当前账号的 capability、scope、候选资格、代理有效期和职责分离条件。授权撤销、组织迁移或账号停用后，未完成任务重新解析候选人；无人可处理时进入明确的升级或异常队列并通知责任人，不能继续信任创建任务时的账号快照。

不自行开发通用 BPMN 引擎。出现多级动态配置、会签、条件网关、转办和超时升级等需求时，在 `WorkflowService` 后接入与 Spring Boot 兼容的受支持 Flowable 稳定版本。首个简单流程也必须通过该接口，避免业务模块直接绑定引擎。

引擎迁移按流程定义版本进行：新发布定义可以切换到 Flowable 适配器，已运行实例默认由原适配器完成；只有经过状态映射、回滚和审计验证的迁移工具才能搬迁在途实例，不能仅因替换 `WorkflowService` 实现而直接解释旧数据。

流程状态事件遵循 `packages/contracts/integration-events/` 的版本化 Schema，通过 transactional outbox 发布，消费者以事件 ID 幂等处理。每次迁移都记录流程定义版本、引擎/适配器版本、实例、任务、前后状态、操作者、代理关系、理由和所有涉及用户，并由 `services/notification` 消费已提交事件发送站内消息或推送。

## 8. 信息统计

`services/reporting` 负责只读投影、统计查询和导出：

- 首期使用 PostgreSQL 投影表或物化视图。
- `services/jobs` 按版本化业务事件幂等更新投影或定时刷新，为每个投影保存 checkpoint、水位和构建版本。
- 每个投影支持从权威业务数据或完整事件序列全量 rebuild；新旧投影并行校验后原子切换，并定期按数量、摘要或抽样与事实源 reconcile。
- 授权服务先把当前 grant 规范化为服务端 scope 集合，再从最细授权粒度过滤事实行，以资源或涉及用户 ID 去重后聚合。禁止把相互重叠的学院、年级、班级预聚合结果直接相加。
- scope 摘要只能由服务端依据规范化 grant、组织版本和授权版本生成，不能采用客户端传入值。缓存键包含授权版本、该摘要、查询条件、指标版本和投影数据版本。
- 指标定义以 `packages/contracts/metrics/` 为事实源，固定指标代码、公式、时区、统计周期、允许维度、敏感级别和版本，避免各端自行解释口径。
- API 返回 `asOf`、投影版本和必要的延迟状态，使客户端明确数据的新鲜度。
- 导出采用异步任务、不可变 subject manifest、短期下载链接和水印。创建导出任务时鉴权，worker 执行前重新校验任务发起者和快照策略，下载时再次按当前授权鉴权；停用或撤权后未下载链接立即失效。

只有数据量或聚合延迟经监控证明 PostgreSQL 不足时，才考虑只读副本或 ClickHouse；约 1000 人同时在线不构成提前引入分析数据库的理由。

## 9. 服务端模块

```text
gateway          API 入口、认证上下文与统一错误
account          账号、凭据、MFA 和会话
authorization    角色、capability、grant 和策略求值
organization     组织树、成员关系、字典和字段选项
module-catalog   工作区与模块目录
domain           具体业务域
workflow         审批定义、实例和任务
reporting        统计投影、查询和导出
audit            不可变审计账本和查询
notification     站内消息、推送、审批提醒和投递偏好
admin            管理用例编排，不拥有上述模块的核心表
jobs             异步、归档、完整性校验和定时任务
```

这些目录是代码所有权边界，不意味着独立网络服务。首期仍由 `gateway` 组合为一个 API 进程，`jobs` 是唯一可选的独立 worker。

## 10. 审计与遥测

- 登录、登出、MFA、step-up、bootstrap、模块清单授权结果、工作区切换、令牌轮换及允许/拒绝决定必须生成服务端权威审计事件。
- 服务端对读取、查询、导出、变更、授权、组织配置、审批和通知偏好操作生成权威审计事件，记录实际释放或处理的资源、涉及用户、context、授权版本、结果与失败原因。
- 客户端上报的模块打开、页面停留、按钮点击和未知模块属于不可信产品遥测，不能证明用户已读取数据或完成操作；模块打开触发服务端读取时，由对应 API 另行生成权威审计。
- 审计事件和产品遥测使用不同 Schema、权限、存储和保留策略。

## 11. 兼容与失败行为

- `catalogVersion` 与每个 channel 暴露记录的 `minimumClientVersion` 用于兼容判断，不用于绕过商店升级策略。
- 客户端不认识模块时忽略该模块，不崩溃、不跳转到通用 WebView。
- bootstrap、step-up 或 context 切换失败时不缓存提升后的权限；如账号仍有 USER 角色则回退到该上下文，否则展示重试或安全退出。
- 离线模式不得进入管理员或系统管理员工作区。
- 工作区切换、模块隐藏和登出后清理相关页面栈及敏感缓存。

## 12. 必测流程

- 首次登录、会话恢复、MFA、上下文切换、撤权后强制退出。
- 单身份、多身份、无可用模块、未知模块和各渠道最低版本不满足。
- 同一 module code 跨工作区/渠道暴露、ANY/ALL 能力要求及 feature flag 只能收窄访问。
- step-up 后令牌绑定正确 context/audience，旧高权限令牌和客户端自报 workspace 均不可用。
- 相同审批模块在用户与管理员工作区显示不同动作。
- 隐藏模块后直接调用 API 仍被服务端拒绝。
- 审批自审、跨范围任务、转办、重复提交、乐观锁冲突、超时竞争和撤权后的候选重算。
- 报表缓存不能在不同 scope 或授权版本之间复用；重叠 scope 不重复计数，投影可重放、重建和对账。
- 创建导出后撤权时 worker 或下载必须拒绝，短期链接不得绕过当前授权。
- 小程序 bootstrap 永远不返回管理工作区。

相关权限模型见 [统一身份与分级授权架构](identity-and-authorization.md)，审计要求见 [全链路审计追踪架构](audit-trail.md)。
## 医保模块的内置承载边界

工作台模块入口属于 Shtong 导航的一部分，但外部业务页面仍由 `_yb` 服务提供。Web 端在同一窗口内进入模块并提供“返回工作台”，移动端使用受限 WebView/WKWebView 容器加载一次性票据入口，不使用系统浏览器，也不把 `_yb` 页面代码打包进安装包。容器必须处理加载失败、重试、系统返回、域名白名单和模块退出时的 Cookie/缓存清理。
