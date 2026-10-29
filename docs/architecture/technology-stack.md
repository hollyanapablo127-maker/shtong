# 生化通技术栈与架构基线

- 状态：已采用
- 日期：2026-09-15
- 当前实现说明：登录已改为仅账号密码，MFA/step-up 已移除；本文涉及 MFA/step-up 的内容为长期设计，不代表当前行为，见 [ADR-0005](decisions/0005-password-only-login-and-incremental-persistence.md)。
- 适用范围：移动端、Web、微信小程序、服务端、数据层和基础设施

## 1. 前提与目标

本方案以约 1000 名用户同时在线为首期容量口径，假定常态请求量明显低于 1000 RPS。并发在线人数、同时执行中的请求数和每秒请求数不是同一指标；上线容量必须由真实用户旅程压测确认。

选型优先级如下：

1. 用户体验、稳定性与安全性。
2. 可维护性和成熟生态。
3. 团队交付效率。
4. 在有证据时扩展，避免为 1000 并发提前引入微服务和复杂编排。

所有框架使用实施时仍受官方支持的稳定版本，并通过锁文件固定；除长期支持运行时外，本文件不锁定易过时的补丁版本。

## 2. 选型总览

| 范围 | 技术选择 | 关键理由 |
| --- | --- | --- |
| Android | Kotlin、Jetpack Compose、原生 Android | 平台体验、性能与系统能力接入最可控 |
| iOS | Swift、SwiftUI，必要时 UIKit | 原生交互、无障碍和系统演进适配最佳 |
| Web 用户端 | Vue 3、TypeScript strict、Nuxt、Vite | 首屏策略灵活，兼顾公开内容与登录后应用 |
| Web 管理端 | Vue 3、TypeScript strict、Vite、Element Plus | 适合高密度表格、表单和后台工作流 |
| 微信小程序 | 原生 WXML/WXSS/TypeScript | 启动、包体、调试和微信新能力最可控 |
| 服务端 | Java 21 LTS、Spring Boot、Spring MVC/Security | 事务、权限、可观测性和人才生态成熟 |
| 服务组织 | Maven 多模块的模块化单体 + 独立 worker | 该规模下边界清楚且运维成本低 |
| API | REST/JSON、OpenAPI 3.1 | 多语言客户端可生成并进行契约校验 |
| 身份授权 | 统一账号、RBAC capability + 组织数据范围 | 支持账号兼任身份且避免权限串用 |
| 主数据库 | PostgreSQL、Flyway、MyBatis、HikariCP | 事务可靠、SQL 可控、迁移可追溯 |
| 缓存 | Valkey 或兼容 Redis 的托管服务 | 热点缓存、限流和短期状态 |
| 异步任务 | RabbitMQ，仅在确有异步解耦时启用 | 复杂度低于事件流平台，适合业务任务 |
| 审批 | 稳定 WorkflowService，复杂流程接入 Flowable | 不自行实现通用流程引擎 |
| 审计 | PostgreSQL 分区账本 + Outbox + WORM/KMS 签名归档 | 全链路可追溯并可检测篡改 |
| 文件 | S3 兼容对象存储 + CDN | 避免应用服务转发大文件 |
| 可观测性 | OpenTelemetry、Micrometer、Prometheus、Grafana、Loki、Tempo | 指标、日志和链路使用统一上下文 |
| CI/CD | GitHub Actions、Docker | 与现有仓库结构一致，易于分端流水线 |

## 3. 移动端决策

### 3.1 采用双端原生 UI

Android 与 iOS 独立开发，不使用 Flutter、React Native、uni-app、Ionic 或 WebView 作为统一 UI 壳。原因不是这些框架无法流畅运行，而是体验优先时，原生方案在文本输入、系统导航、手势、动态字体、无障碍、键盘、后台生命周期、推送、相机和新系统能力上最稳定。

| 方案 | 平台体验 | 代码复用 | 主要风险 | 结论 |
| --- | --- | --- | --- | --- |
| Kotlin + Swift 双原生 | 最高 | 低 | 需要长期维护两支工程能力 | 采用 |
| KMP + 原生 UI | 高 | 中 | Gradle/Xcode 集成与 Swift 互操作复杂 | 稳定后按需试点 |
| Flutter | 较高且一致 | 高 | 平台语义、插件和原生 SDK 仍需双端能力 | 人力不足时的备选 |
| React Native | 较高 | 高 | 依赖升级和复杂原生模块维护 | 不采用 |
| WebView 类方案 | 一般 | 很高 | 体验与平台能力上限明显 | 不采用 |

原生方案成立的组织前提是 Android 和 iOS 均有持续维护责任人。若项目无法长期保证这一点，应在开始业务开发前统一改选 Flutter，而不是让任一原生端长期无人维护。

### 3.2 Android

- Kotlin、Jetpack Compose、Navigation Compose。
- MVVM + 单向数据流，按功能模块组织。
- Coroutines / Flow、Hilt。
- Retrofit、OkHttp、kotlinx.serialization。
- Room、DataStore、WorkManager、系统安全存储。
- Coil；复杂兼容场景允许使用 Android Views。

### 3.3 iOS

- Swift、SwiftUI、NavigationStack。
- MVVM + 单向数据流，按 Swift Package/功能模块组织。
- Swift Concurrency、URLSession、Codable。
- Core Data 或 GRDB、Keychain。
- Nuke；SwiftUI 不足时桥接 UIKit。

### 3.4 身份与工作区

首期每个平台维护一个 App。同一账号可同时拥有用户、管理员和系统管理员身份，登录后由服务端返回可用工作区、模块代码与授权版本。客户端只渲染服务端返回且在本地白名单中的模块；隐藏入口不能替代服务端鉴权。

管理员包含教师管理员（包含辅导员场景）和学生管理员。教师管理员只允许多个年级数据范围；学生管理员可由系统管理员授予学院、年级、班级任意组合的多个范围。系统管理员负责账号、授权、组织主数据和审计，不自动继承业务审批权限。

出现下列任一条件时，再将管理员端拆为 Android 独立 application/product flavor 和 iOS 独立 target/scheme：

- 需要企业内部发布、MDM 或独立商店审核。
- 管理端有不同的设备权限、加固或合规要求。
- 用户端与管理端发布节奏和团队所有权长期不同。

### 3.5 共享边界

双端共享 OpenAPI 契约、生成的 Kotlin/Swift SDK、设计令牌、错误码、权限目录、埋点字典和验收用例，不共享 UI、导航、ViewModel 或平台服务封装。

Kotlin Multiplatform 不进入首版。只有当账号状态、权限计算、校验、缓存或同步规则已经稳定且出现大量重复时，才小范围试点共享领域与数据逻辑。

移动端目录固定为平台优先：

```text
apps/mobile/
|-- android/
|   |-- app/
|   |-- features/
|   |   |-- authentication/
|   |   |-- workspace/
|   |   |-- profile/
|   |   |-- business/
|   |   |-- approval/
|   |   |-- reporting/
|   |   |-- account-management/
|   |   |-- authorization/
|   |   |-- master-data/
|   |   |-- notifications/
|   |   `-- audit/
|   `-- core/
`-- ios/
    |-- app/
    |-- features/
    |   |-- authentication/
    |   |-- workspace/
    |   |-- profile/
    |   |-- business/
    |   |-- approval/
    |   |-- reporting/
    |   |-- account-management/
    |   |-- authorization/
    |   |-- master-data/
    |   |-- notifications/
    |   `-- audit/
    `-- core/
```

详见 [ADR-0001：移动端采用双端原生 UI](decisions/0001-native-mobile.md)。

## 4. Web 端

### 4.1 用户端

- Vue 3、TypeScript strict、Nuxt、Vite。
- 公共且需要搜索的内容使用预渲染或缓存；登录后的高交互页面以 CSR 为主。
- Tailwind CSS + Reka UI 等无样式可访问性组件，建立产品自己的轻量设计系统。
- Nuxt `useAsyncData`/`useFetch` 或 TanStack Vue Query 管理服务端状态；Pinia 只管理必要的客户端状态。
- 表单使用基于 Schema 的校验，接口 DTO 不直接充当页面状态模型。

若产品最终完全登录后可用且没有 SEO 页面，应关闭 SSR，避免维护不产生价值的 Node 服务。

### 4.2 管理端

Web 管理端同时承载管理员和系统管理员工作区，按服务端返回的模块清单显示，不建立两套账号体系；每个 API 仍独立执行 capability + scope 鉴权，审计查询额外联合校验 dimension 与字段 clearance。

- Vue 3、TypeScript strict、Vite、Vue Router。
- Element Plus 用于表格、表单和后台操作控件，ECharts 按需用于数据展示。
- 管理端单独构建、使用独立域名和部署单元，不与用户端仅靠前端路由隔离。
- 所有敏感写操作均要求服务端权限校验、幂等控制、必要的二次确认和审计。

### 4.3 Web 工程化

Node 使用当前 Active LTS，JavaScript/TypeScript 工作区采用 pnpm workspace。

## 5. 微信小程序

- 微信原生 WXML/WXSS/TypeScript，不采用 Taro 或 uni-app。
- 官方组件优先，TDesign Miniprogram 仅按需引入基础控件，产品组件自行实现。
- 页面局部状态优先；确有复杂跨页状态时再使用 `mobx-miniprogram`。
- Skyline 只在长列表、复杂手势或动画页面经兼容性和真机矩阵验证后启用。

小程序只提供用户功能。客户端不展示管理员入口，服务端仍必须拒绝无权限的管理员接口请求。

## 6. 共享包

共享遵循“契约、纯规则、设计令牌”原则：

- `packages/contracts`：保存 OpenAPI、模块码、capability、审计事件、产品遥测、集成事件和指标目录的语言无关源契约。
- `packages/api-sdk`：保存由契约生成的 TypeScript/Kotlin/Swift 客户端。
- `packages/types`、`constants`、`auth`、`utils`、`config`：TypeScript 消费者可直接复用；跨语言内容从契约或语言无关清单生成。
- `packages/ui`：仅共享 Web 用户端与管理端的 Vue 基础组件和设计令牌，不宣称跨原生移动端或小程序复用组件。
- SDK 的传输层必须可注入。Web、SSR、小程序和原生客户端分别适配 `$fetch`/`fetch`、`wx.request`、URLSession 和 OkHttp。

不得强行共享页面组件、路由守卫、Token 存储、登录跳转、平台生命周期、推送、支付、文件选择或上传实现。

## 7. 服务端

### 7.1 部署模型

首期采用模块化单体，不把现有目录部署成多个网络微服务：

- `services/gateway`：唯一 API 可执行程序和组合根，不额外引入 Spring Cloud Gateway/Kong。
- `services/account`：账号、凭据、MFA 和会话模块。
- `services/authorization`：角色、capability、授权范围和策略求值模块。
- `services/organization`：学院、年级、班级、成员关系、字典和字段选项模块。
- `services/module-catalog`：身份工作区与客户端模块目录。
- `services/domain`：核心业务模块。
- `services/workflow`：审批定义、实例、任务和流转记录。
- `services/reporting`：统计投影、查询和导出。
- `services/notification`：站内信、推送、审批提醒及渠道投递；不拥有审批状态。
- `services/audit`：只追加审计账本、查询和完整性证据。
- `services/admin`：管理用例与接口模块，和 API 同进程部署。
- `services/jobs`：独立 worker，执行异步任务、审计归档和完整性校验。

用户端与管理端共用一个 PostgreSQL 业务事实库和统一账号，不维护两套数据库。各模块拥有自己的应用接口、Schema/表和最小数据库角色，禁止绕过公开接口依赖其他模块内部实现；跨 Schema 安全过滤只使用拥有者提供的受控只读视图/投影。同一数据库内优先使用本地事务。`services/admin` 只编排管理用例，不拥有账号、授权、组织或审计核心表。

### 7.2 技术组件

- Java 21 LTS、当前受支持的 Spring Boot 稳定版本。
- Spring MVC、Spring Security、Bean Validation、MapStruct。
- Maven 多模块。
- MyBatis、HikariCP、Flyway；生产环境禁止 ORM 自动建表。
- REST/JSON、OpenAPI 3.1，客户端从契约生成。
- 简单定时任务使用 Spring Scheduler + ShedLock；动态且大量的调度任务才考虑 Quartz。
- 异步业务使用 RabbitMQ + transactional outbox；消费者按至少一次投递设计并保证幂等。
- 审批先通过自有 `WorkflowService` 抽象；出现多级动态配置、会签、条件网关或超时升级时接入受支持的 Flowable 稳定版本。

WebFlux、Kafka、分布式事务、服务网格、分库分表和微服务均不作为首期默认组件。

## 8. 数据与基础设施

- PostgreSQL 使用主备、自动备份和时间点恢复；所有结构变更由 Flyway 管理。
- Valkey/Redis 只保存可重建缓存、限流状态和必要的短期会话数据，不作为事实数据源。
- 审计账本在 PostgreSQL 中按时间分区，并快速归档到独立安全域的 Object Lock/WORM 对象存储；PITR 不等于防篡改。
- 文件由客户端通过预签名 URL 直传 S3 兼容对象存储，并通过 CDN 分发。
- 本地与测试环境使用 Docker Compose；生产使用托管容器平台或多台 VM。
- 约 1000 并发不构成引入 Kubernetes 的理由；只有服务数量、发布频率和运维能力都达到需要时才启用 `infra/k8s`。
- Nginx 或云负载均衡负责 TLS 终止、静态资源、基础限流和 API 反向代理。
- 国家地名信息库由服务端通过其官方 HTTPS 路径 `/9095/xzqh/getList` 代理访问。行政区划客户端在默认 JDK 信任库之外仅附加指纹固定的 `CFCA EV ROOT`，不修改全局 JVM、不关闭证书或主机名校验；证书到期前必须随发布更新并重新验证指纹。请求使用明确的产品 `User-Agent` 通过官方 WAF，成功响应缓存 24 小时，上游故障时只允许降级到曾成功校验的官方缓存。

## 9. 容量基线

首轮压测模型以 1000 个虚拟用户、真实思考时间、持续约 200 RPS、短时突发约 500 RPS 为起点。这是验证模型，不是未经测试的容量承诺。若“1000 并发”实际指 1000 个同时执行中的请求，必须重新建模和扩容。

可用于首轮验证的生产起点：

- 3 个无状态 API 副本，每个 2-4 vCPU、4-8 GB 内存。
- 1-2 个 worker 副本，按任务积压扩缩。
- PostgreSQL 高可用实例、Valkey/Redis 高可用实例、对象存储和 CDN。
- 数据库连接池总连接数必须受控，不能随 API 副本数无上限增长。

验收目标：

- 普通 API 服务端耗时 p95 小于 300 ms，写操作 p95 小于 500 ms；第三方调用和文件处理单列。
- 错误率低于 0.1%，可用性目标 99.9%。
- 峰值压测至少 30 分钟，并执行 4-8 小时稳定性测试。
- 峰值时应用 CPU、数据库连接池、数据库 CPU/IO 保留约 30% 余量。
- 超时、限流和并发隔离有明确配置；仅对幂等请求执行有限重试。

## 10. 安全基线

- 采用 OAuth 2.1/OIDC 语义；首期 Web 使用浏览器会话 Cookie，Android/iOS 使用应用运行期间的安全会话与轮换 refresh token，服务端只保存 refresh token 哈希并可按授权版本即时撤销。
- 一个账号可拥有多个角色赋予；capability 与数据 scope 必须在同一授权中联合求值，禁止分别合并后产生笛卡尔串权。
- 教师管理员 scope 仅允许年级；学生管理员可多选学院、年级和班级；小程序固定为用户 audience。
- Web 凭据放入 `Secure`、`HttpOnly`、合理 `SameSite` 的 Cookie；移动端使用 Keychain/系统安全存储。
- 微信登录的临时 code 与应用密钥只允许服务端交换和保存。
- 每个接口同时校验 RBAC/capability 与资源归属，不能只检查菜单或路由是否可见。
- 管理员和系统管理员启用 MFA；禁止自我提权和删除最后一个系统管理员，敏感操作二次确认或双人复核。
- 所有认证、读写、查询、导出、授权、组织配置、审批、任务和审计访问均生成服务端权威审计事件。
- 全链路 TLS、参数化 SQL、日志脱敏、密钥托管、按用户/IP/接口限流。
- 按最少必要原则采集个人信息，定义保留期限；数据库备份必须定期执行恢复演练。

## 11. 演进边界

- API CPU 持续超过 70%：先优化并横向扩 API。
- 数据库成为瓶颈：先检查 SQL、索引和连接池，再考虑缓存、只读副本或分区。
- 某模块确实需要独立发布、扩容、故障隔离且有明确团队所有权时，才拆为微服务。
- 只有出现可重放事件流、多消费者订阅或持续高吞吐需求时才引入 Kafka。
- PostgreSQL 无法满足复杂全文检索和聚合需求后，才引入 OpenSearch。
- 两端出现大量稳定且重复的非 UI 逻辑后，才试点 Kotlin Multiplatform。
- 审计查询拖慢业务库或留存规模明显增长后，才迁移到独立审计查询存储；权威事件和完整性链保持不变。

技术复杂度必须由监控、压测或组织边界证明，不能仅由预计用户数驱动。

## 12. 相关文档

- [身份、角色与数据管辖范围](../product/roles-and-data-scopes.md)
- [统一身份与分级授权架构](identity-and-authorization.md)
- [应用工作区与业务模块架构](application-modules.md)
- [全链路审计追踪架构](audit-trail.md)
- [权限与审计概念数据模型](../database/access-control-and-audit-model.md)
