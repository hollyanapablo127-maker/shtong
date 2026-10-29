# ADR-0001：移动端采用双端原生 UI

- 状态：已接受
- 日期：2026-09-15
- 决策人：生化通项目组

## 背景

生化通同时面向 Android 和 iOS，包含用户、管理员与系统管理员工作区，并将逐步增加业务、审批和统计模块。原始目录同时表达了两种互斥方向：顶层 `user/admin/shared` 暗示共享业务与 UI，`platform/android/ios` 又暗示由两个原生壳承载。

项目预计约 1000 名用户同时在线。该数字主要影响服务端容量，不决定客户端框架。客户端决策以平台体验、无障碍、稳定性、系统能力适配和长期维护为准。

## 决策

采用两个独立原生客户端：

- Android 使用 Kotlin + Jetpack Compose。
- iOS 使用 Swift + SwiftUI，必要时使用 UIKit。
- 每个平台首期维护一个 App，代码按业务模块组织，而不是按身份复制页面。
- 登录后通过用户、管理员、系统管理员工作区展示已授权模块。
- 所有角色、capability 和数据范围由服务端校验，客户端只负责呈现已授权模块。
- 不使用 Flutter、React Native、uni-app、Ionic、Capacitor 或 WebView 统一 UI。
- 首版不引入 Kotlin Multiplatform。

目录按平台优先组织：

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

## 共享内容

允许共享：

- OpenAPI 3.1 契约以及生成的 Kotlin、Swift 客户端。
- 设计令牌、模块码、capability、错误码、审计事件、集成事件、指标和埋点事件定义。
- 跨端验收场景和测试数据规范。

不共享：

- 页面与 UI 组件。
- 导航、ViewModel 和页面状态。
- 生命周期、推送、存储、相机、文件、后台任务等平台封装。

## 结果

正面影响：

- 可以遵循各平台的交互、导航、输入、动态字体和无障碍规范。
- 新系统能力和第三方原生 SDK 可直接接入。
- 性能问题和生命周期问题的定位路径更短。
- 不受跨端渲染层和插件升级节奏限制。

代价与风险：

- UI 和状态层需要开发两次。
- 必须长期配置 Android 与 iOS 工程责任人，并进行跨端体验验收。
- API、行为和视觉可能漂移，需要用契约生成、设计令牌和共同验收用例约束。

## 重新评估条件

出现下列情况时，在大量业务页面落地前重新评估 Flutter：

- 无法长期保证 Android 和 iOS 各自有明确维护责任人。
- 交付速度或成本的优先级明确高于平台原生体验。
- 产品交互高度统一且原生平台能力很少。

经过一到两个稳定版本后，若非 UI 领域逻辑在双端出现大量、稳定、可测量的重复，可试点 Kotlin Multiplatform；试点不得共享 UI、导航和平台服务。

管理员或系统管理员功能只有在需要独立分发、MDM、不同安全策略或独立发布节奏时，才拆为 Android 独立 application/product flavor 与 iOS 独立 target/scheme。身份空间和模块边界不会因此改变。
