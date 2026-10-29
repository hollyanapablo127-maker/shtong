# 生化通文档导航

- 更新日期：2026-10-28
- 管理员操作入口：[系统管理员详细使用手册](manuals/system-administrator-guide.md)。
- 当前代码覆盖第一阶段账户底座与“医保信息系统”SSO 适配层。

按职责进入产品、架构、API、数据库或标准文档。

## 1. 当前开发基线

| 文档 | 内容 | 使用时机 |
| --- | --- | --- |
| [第一阶段账户、基础权限与审计基线](product/phase-1-account-access-baseline.md) | 首期做什么、不做什么、角色、状态、页面、批量语义和验收摘要 | 产品与研发确认范围 |
| [第一阶段账户与权限实施设计](architecture/phase-1-account-access-design.md) | 服务端模块、请求链、跨端落地、环境准备、开发顺序和完成定义 | 开始搭工程和拆任务前 |
| [第一阶段账户与权限 API 契约](api/phase-1-account-access-api.md) | 登录、账户、导入导出、批量命令、授权、组织和审计端点 | 后端与客户端联调 |
| [第一阶段账户与权限 Schema 计划](database/phase-1-account-access-schema.md) | 首期表、约束、Flyway 顺序、事务和索引 | 编写迁移前 |
| [会话、密码与退出安全基线](standards/session-password-and-logout.md) | 强制改密、密码策略、Token、Cookie、logout 和返回栈清理 | 实现认证壳前 |
| [账户导入导出规范](standards/account-import-export.md) | XLSX 模板、预检、无选择导出全部、文件生命周期 | 实现批量文件功能前 |
| [第一阶段实施 Backlog](product/phase-1-delivery-backlog.md) | M1-M5、P1-01 至 P1-22 的任务、依赖和完成条件 | 创建 Issue 和安排开发顺序时 |

机器可校验的第一阶段事实源：

- `packages/contracts/openapi/account-access-baseline.v1.json`
- `packages/contracts/capabilities/account-access-baseline.v1.json`
- `packages/contracts/modules/account-access-baseline.v1.json`
- `packages/contracts/audit-events/account-access-baseline.v1.json`

## 2. 产品文档

| 文档 | 内容 |
| --- | --- |
| [身份、角色与数据管辖范围](product/roles-and-data-scopes.md) | 长期角色模型、职责矩阵、组织范围、授权约束和工作区 |
| [第一阶段账户、基础权限与审计基线](product/phase-1-account-access-baseline.md) | 当前要开发的最小系统管理与个人自助功能 |
| [第一阶段实施 Backlog](product/phase-1-delivery-backlog.md) | 可直接拆为 Issue 的阶段里程碑和任务 |

`docs/product/` 后续放需求说明、角色权限矩阵、用例、状态机和版本范围。UI 原型文件仍放 `design/prototype/`，文档只保存可审查规则。

## 3. 架构文档

| 文档 | 内容 |
| --- | --- |
| [技术栈与架构基线](architecture/technology-stack.md) | 双端原生、Vue、Java/Spring、PostgreSQL、部署与容量选型 |
| [统一身份与分级授权架构](architecture/identity-and-authorization.md) | 统一账户、角色赋予、管辖上限、grant、scope、会话与鉴权算法 |
| [应用工作区与业务模块架构](architecture/application-modules.md) | 认证门、工作区、模块目录和未来业务模块组织方式 |
| [全链路审计追踪架构](architecture/audit-trail.md) | 审计保证、事件 envelope、可靠写入、防篡改、归档和查询权限 |
| [第一阶段账户与权限实施设计](architecture/phase-1-account-access-design.md) | 当前阶段对长期架构的具体裁剪和实施顺序 |

### 架构决策记录

| ADR | 决策 |
| --- | --- |
| [ADR-0001](architecture/decisions/0001-native-mobile.md) | Android Kotlin/Compose 与 iOS Swift/SwiftUI 双端原生 |
| [ADR-0002](architecture/decisions/0002-unified-account-scoped-authorization.md) | 统一账户、角色与组织范围联合授权 |
| [ADR-0003](architecture/decisions/0003-authoritative-audit-ledger.md) | PostgreSQL 权威只追加审计、outbox、签名和 WORM |
| [ADR-0004](architecture/decisions/0004-phase-one-account-access-baseline.md) | 首期仅账户/权限/审计，教师角色命名与管辖上限分层 |
| [ADR-0005](architecture/decisions/0005-password-only-login-and-incremental-persistence.md) | 仅账号密码登录（移除 MFA/step-up）、密码策略、增量持久化与审计哈希 v2 |

`docs/architecture/decisions/` 只记录已经作出的长期影响决策；不要把每日任务或临时方案写成 ADR。

## 4. API 文档

| 文档 | 内容 |
| --- | --- |
| [第一阶段账户与权限 API 契约](api/phase-1-account-access-api.md) | 人类可读端点、请求语义和状态码 |

`docs/api/` 放人类可读说明；`packages/contracts/openapi/` 是机器事实源。实现与说明冲突时应先修正并评审契约，不能让各客户端私下兼容后端偶然行为。

## 5. 数据库文档

| 文档 | 内容 |
| --- | --- |
| [权限与审计实现级数据模型](database/access-control-and-audit-model.md) | 长期 IAM、组织、grant、审计账本、manifest、outbox、归档和 legal hold 完整模型 |
| [第一阶段账户与权限 Schema 计划](database/phase-1-account-access-schema.md) | 第一批 Flyway 迁移所需表、约束、权限和验证清单 |

`docs/database/` 后续放 Schema 说明、ER 图、数据字典、迁移约束、备份恢复和数据保留规则。真实结构只能由 Flyway 迁移产生。

## 6. 标准文档

| 文档 | 内容 |
| --- | --- |
| [会话、密码与退出安全基线](standards/session-password-and-logout.md) | 认证凭据、会话和退出后的安全行为 |
| [账户导入导出规范](standards/account-import-export.md) | 文件格式、字段、任务、临时密码交付和导出集合 |

`docs/standards/` 后续补充分支、提交、编码、API 兼容、错误代码、发布和安全编码规范。

## 7. 发布文档

| 目录 | 后续用途 |
| --- | --- |
| [Android 隐私政策](release/android-privacy-policy.md) | Android 客户端隐私与数据处理说明 |

## 8. 阅读顺序

新参与者建议按以下顺序阅读：

1. 第一阶段产品基线。
2. 角色与数据范围。
3. 第一阶段实施设计。
4. API 契约与 Schema 计划。
5. 会话与导入导出标准。
6. 需要实现审计或权限内核时，再完整阅读长期审计和数据库模型。
