# 生化通（shtong）

生化通是一个多端系列项目。本仓库统一管理移动端、Web 端、微信小程序端、服务端、共享包、文档、设计资源和基础设施。

## 当前阶段

第一阶段建设统一账户、五个内置角色、学院/年级/班级管辖上限、系统管理员账户操作和权威审计，并接入“医保信息系统”统一会话模块。教师管理员、学生管理员和普通用户除已授权医保入口外保留空工作台、个人中心和退出；小程序暂不开发。

当前仓库包含 Spring Boot、Vue/Nuxt、Android Compose、iOS SwiftUI 与 HarmonyOS ArkTS 实现；冻结契约仍是跨端行为的唯一事实来源。范围入口见 [文档导航](docs/README.md)、[第一阶段账户、基础权限与审计基线](docs/product/phase-1-account-access-baseline.md) 和 [第一阶段实施 Backlog](docs/product/phase-1-delivery-backlog.md)。

## 产品形态

| 端 | 用户工作区 | 管理员工作区 | 系统管理员工作区 |
| --- | :---: | :---: | :---: |
| Android / iOS | 支持 | 支持 | 支持 |
| Web | 支持 | 支持 | 支持 |
| 微信小程序 | 支持 | 不提供 | 不提供 |

> 微信小程序不提供管理员或系统管理员入口，但服务端接口仍须执行身份认证与权限校验。

## 身份与权限

- 系统提供 `USER`、`ADMIN`、`SYSTEM_ADMIN` 三个工作区；工作区用于组织界面，不是可直接放行请求的角色。
- 同一自然人只使用一个账号，一个账号可同时拥有 `USER`、`TEACHER_ADMIN`、`STUDENT_ADMIN`、`MEDICAL_INSURANCE_ADMIN`、`SYSTEM_ADMIN` 等具体角色。
- 管理员包含教师管理员（含辅导员场景）与学生管理员：教师管理员只按任意多个年级管辖；学生管理员可被授予学院、年级、班级任意组合的多个范围。
- 系统管理员负责账号、授权、组织与字典配置和审计，不自动继承业务审批权限。
- 系统管理员可查看的系统统计仅限账号、安全、授权、组织配置和审计运行统计；业务数据聚合仍需独立的业务管理员授权。
- 登录后由服务端按当前工作区和有效授权返回可用模块；当前唯一业务模块为“医保信息系统”，客户端只渲染本地已知模块，模块是否显示不代替服务端 capability + scope 鉴权，审计查询还必须联合校验 dimension 与字段 clearance。
- 所有受保护的认证、读取、查询、导出、变更、授权、审批和审计访问均进入不可变审计链路。

用户端与管理端共用统一账号体系和同一 PostgreSQL 业务事实库，不复制用户数据或维护两套账号。服务端模块分别拥有自己的 Schema/表和最小数据库权限；审计账本使用独立 Schema、只追加权限及独立安全域归档。

产品规则见 [身份、角色与数据管辖范围](docs/product/roles-and-data-scopes.md)，技术设计见 [统一身份与分级授权架构](docs/architecture/identity-and-authorization.md) 和 [全链路审计追踪架构](docs/architecture/audit-trail.md)。

## 技术栈基线

- 移动端：Android 使用 Kotlin + Jetpack Compose，iOS 使用 Swift + SwiftUI/UIKit，双端原生 UI。
- Web：用户端使用 Vue 3 + Nuxt，管理端使用 Vue 3 + Vite + Element Plus。
- 微信小程序：原生 WXML/WXSS/TypeScript，不使用跨端壳。
- 服务端：Java 21 LTS + Spring Boot 的模块化单体，任务进程按需独立部署。
- 数据与基础设施：PostgreSQL、Valkey/Redis、按需 RabbitMQ、S3 兼容对象存储、Docker。

完整说明见 [技术栈与架构基线](docs/architecture/technology-stack.md)，移动端决策见 [ADR-0001](docs/architecture/decisions/0001-native-mobile.md)。

## 目录结构

```text
shtong/
|-- apps/                       # 可独立运行和发布的各端应用
|   |-- mobile/
|   |   |-- android/           # Kotlin + Jetpack Compose
|   |   |   |-- app/
|   |   |   |-- features/
|   |   |   |   |-- authentication/
|   |   |   |   |-- workspace/
|   |   |   |   |-- profile/
|   |   |   |   |-- business/
|   |   |   |   |-- approval/
|   |   |   |   |-- reporting/
|   |   |   |   |-- account-management/
|   |   |   |   |-- authorization/
|   |   |   |   |-- master-data/
|   |   |   |   |-- notifications/
|   |   |   |   `-- audit/
|   |   |   `-- core/
|   |   `-- ios/               # Swift + SwiftUI/UIKit
|   |       |-- app/
|   |       |-- features/
|   |       |   |-- authentication/
|   |       |   |-- workspace/
|   |       |   |-- profile/
|   |       |   |-- business/
|   |       |   |-- approval/
|   |       |   |-- reporting/
|   |       |   |-- account-management/
|   |       |   |-- authorization/
|   |       |   |-- master-data/
|   |       |   |-- notifications/
|   |       |   `-- audit/
|   |       `-- core/
|   |-- web/
|   |   |-- user/              # Web 用户侧
|   |   |-- admin/             # Web 管理员与系统管理员后台
|   |   `-- shared/            # Web 共享代码
|   `-- miniprogram/
|       `-- user/              # 微信小程序用户侧
|-- services/                  # 服务端模块与任务进程
|   |-- gateway/
|   |-- account/
|   |-- authorization/
|   |-- organization/
|   |-- module-catalog/
|   |-- domain/
|   |-- workflow/
|   |-- reporting/
|   |-- notification/
|   |-- audit/
|   |-- admin/
|   `-- jobs/
|-- packages/                  # 跨端共享包
|   |-- api-sdk/
|   |-- types/
|   |-- constants/
|   |-- auth/
|   |-- utils/
|   |-- ui/
|   |-- config/
|   `-- contracts/             # 跨语言、版本化契约的事实源
|       |-- openapi/
|       |-- modules/
|       |-- capabilities/
|       |-- audit-events/
|       |-- analytics-events/
|       |-- integration-events/
|       `-- metrics/
|-- docs/                      # 产品、架构、接口及发布文档
|   |-- product/
|   |-- architecture/
|   |   `-- decisions/         # 架构决策记录
|   |-- api/
|   |-- database/
|   |-- standards/
|   `-- release/
|-- design/                    # 原型、设计稿和设计资源
|   |-- prototype/
|   |-- ui/
|   `-- assets/
|-- infra/                     # 部署与运维配置
|   |-- docker/
|   |-- k8s/
|   |-- nginx/
|   |-- ci-cd/
|   `-- env/
|-- scripts/                   # 构建、发布和批处理脚本
|-- tools/                     # 内部工具、脚手架和生成器
`-- .github/
    |-- workflows/
    `-- ISSUE_TEMPLATE/
```

## 仓库约定

- 各应用放入 `apps/`，并保持可独立运行和发布。
- 服务端能力按职责放入 `services/`，鉴权与权限校验不能只依赖客户端入口。
- 稳定且可复用的跨端能力放入 `packages/`，避免复制业务实现。
- 跨语言只共享 API 契约、设计令牌和规则定义；移动端、小程序与 Web 不强行共享 UI。
- 客户端功能按业务模块组织，身份和范围只控制模块展示与操作授权。
- 审计账本、运维日志和产品埋点相互独立；任何秘密和完整请求响应不得进入审计。
- 环境变量和密钥不得提交；仅提交脱敏的示例文件，例如 `.env.example`。
- 当前空目录使用 `.gitkeep` 占位，加入实际文件后可删除对应占位文件。

第一阶段依赖版本已在各端工程清单中固定；仓库不包含生产密钥、默认账号或可登录密码。
