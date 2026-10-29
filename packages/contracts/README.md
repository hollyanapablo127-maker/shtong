# 跨端契约

本目录是服务端、Web、Android、iOS 和未来小程序共享的语言无关事实源。运行时代码、数据库 seed 和客户端枚举从这里生成或校验，不能各自维护同名代码。

## 第一阶段已冻结契约

| 目录 | 文件 | 内容 |
| --- | --- | --- |
| `openapi/` | `account-access-baseline.v1.json` | HTTP 路径、核心 DTO、错误与安全声明 |
| `capabilities/` | `account-access-baseline.v1.json` | 五个内置角色、capability 和角色上限 |
| `modules/` | `account-access-baseline.v1.json` | 系统管理模块、医保模块、渠道 exposure 和空工作区 |
| `audit-events/` | `account-access-baseline.v1.json` | 账户与授权事件代码、envelope 字段和禁记字段 |

其他目录在对应能力进入开发前再增加契约：

- `analytics-events/`：非权威产品遥测。
- `integration-events/`：outbox 消息 Schema。
- `metrics/`：指标代码和计算口径。

## 规则

- JSON 必须可解析并通过对应 Schema/OpenAPI lint。
- 稳定代码只能新增或弃用，不能原地改变含义。
- breaking change 提升主版本，并保留旧客户端兼容窗口。
- OpenAPI 生成 TypeScript、Kotlin 和 Swift 客户端；生成物不得反向覆盖源契约。
- capability、模块或事件代码变更必须同时更新产品规则、授权定义和审计契约。
- 小程序不消费第一阶段契约；服务端仍须拒绝小程序 audience 调用系统管理 API。
- 当前医保模块只增加模块目录、SSO 票据和外部角色/范围映射；外部项目的业务页面、业务表和业务规则不由本契约覆盖。
