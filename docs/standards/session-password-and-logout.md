# 会话、密码与退出安全基线

- 状态：第一阶段必须实现
- 日期：2026-09-16
- 适用端：服务端、Web、Android、iOS

## 1. 安全目标

- 客户端不能仅凭本地页面状态判断已登录或已授权。
- 密码重置后旧凭据和旧会话立即失效。
- 强制改密用户在完成改密前不能读取个人中心以外的数据，也不能进入任何工作区模块。
- 登出后服务端拒绝旧会话，客户端也不从历史栈、持久缓存或系统快照恢复敏感页面。
- 所有认证与会话状态变化均有服务端权威审计。
- 后续模块必须复用 Shtong 统一会话；当前“医保信息系统”通过一次性票据进入，模块期间不因页面切换、刷新、重试或耗时操作自动退出。

## 2. 密码规则

### 2.1 正式密码

- 长度 8 至 128 个字符，且至少包含一个大写字母（A-Z）、一个小写字母（a-z）和一个数字（0-9）；可以包含其他字符。服务端是最终裁决，客户端使用同一规则即时提示。
- 不做无理由的周期性强制更换；凭据泄露、系统管理员重置或风险策略命中时才强制更换。
- 保存最近 5 次密码哈希摘要，历史记录同样不得可逆。
- 服务端使用带独立随机 salt 的 Argon2id 哈希；参数由性能测试确定并版本化，目标是生产认证节点单次校验约 100 至 250 ms，且不低于当时 OWASP 推荐下限。
- 比较、编码和错误返回不得泄露账号是否存在、密码长度或哈希参数差异。

### 2.2 临时密码

- 由密码学安全随机源生成，至少 16 个字符，不能采用学号、生日、手机号后几位或统一默认密码。
- 只保存 Argon2id 哈希，明文最多在创建/重置成功后交付一次。
- 新建、导入和重置产生的初始密码不设置有效期；首次登录仍必须立即修改为正式密码。
- 批量临时密码结果文件必须使用对象存储服务端加密、短时下载票据和下载次数限制，最长保留 24 小时。
- 临时密码不得进入审计、应用日志、链路属性、错误信息、埋点或崩溃报告。

## 3. 凭据状态与强制改密

密码凭据包含：

```text
credential_state: TEMPORARY | ACTIVE | REVOKED
password_version
password_changed_at
failed_attempt_count
locked_until
```

规则：

1. 创建或导入非系统管理员账户时写入 `TEMPORARY`。
2. 重置非系统管理员密码时递增 `password_version`，写入新哈希并回到 `TEMPORARY`。
3. 临时密码认证成功只签发 audience 为 `PASSWORD_CHANGE_ONLY` 的限制会话。
4. 网关对限制会话只放行：读取改密策略、提交新密码、退出登录。
5. 改密成功后将凭据设为 `ACTIVE`，撤销该账号全部旧会话，再签发新的正常会话。
6. 具有有效 `SYSTEM_ADMIN` 角色的账号按产品规则不进入强制改密；其创建、重置和密码变更仍要求高强度认证和完整审计。
7. 具有有效 `USER` 身份且尚未完成必填资料的账号，改密后签发 `PROFILE_COMPLETION_ONLY` 会话；该会话仅放行本人基本资料、行政区划、资料完善和退出。资料保存成功后再次撤销旧会话并签发正常会话。

客户端不得以路由守卫代替第 4 条服务端限制。

## 4. 登录与防护

- 登录错误统一返回“账号或凭据无效”，不暴露账号是否存在、已毕业或已删除。
- 同时按账号标识、来源 IP、设备信号和接口维度限流。
- 连续 5 次失败后进入渐进式临时锁定；具体时长由配置控制，初始建议 15 分钟。
- 成功登录后清零短期失败计数，但保留审计记录。
- 登录只需要账号和密码；进入任何已拥有角色的工作区都不需要验证码或 step-up（见 ADR-0005）。
- 认证成功后仍必须检查账号生命周期、安全锁定、凭据状态、会话撤销状态和授权版本。
- 不提供公开注册；账号只能由系统管理员的受控用例创建或导入。

## 5. 会话模型

- 首期 Web 使用浏览器会话 Cookie：登录期间保持有效，手动退出或关闭浏览器后失效；服务端仍在密码、角色、组织或安全事件发生时立即撤销会话。
- 首期 Android/iOS 在每次重新启动应用时清理上次会话，应用运行期间保持有效；手动退出、密码变更或服务端撤销后立即失效。
- 移动端 refresh token 仍按每次使用轮换，只保存哈希；检测到旧 token 重放时撤销整个 token family。
- refresh 请求必须携带幂等键；同一旧 token + 同一幂等键在极短重试窗口内可安全返回同一次轮换结果，窗口外或不同幂等键复用旧 token 视为重放并撤销 token family。短时重试结果必须加密保存且到期清除。
- access token 至少绑定 `sub`、`session_id`、`context_id`、`workspace`、`audience`、`authz_version`、`assurance_level` 和过期时间。
- 每个受保护请求检查 session 未撤销、账号仍为 `ACTIVE`、authorization version 未过期。
- 医保适配器每次受保护请求 introspect Shtong 父会话；父会话撤销、账号停用、角色/范围变化或授权版本变化立即失效。短时上游不可用只可使用有界 grace，不能转为独立本地登录。
- Web 使用 `Secure`、`HttpOnly`、合适 `SameSite` 的 Cookie，并使用 CSRF 防护；Token 不进入 `localStorage`。
- Android 使用系统 Keystore 支持的安全存储，iOS 使用 Keychain；高权限数据不写入普通偏好存储。
- 密码重置、删除、毕业、角色或管辖上限变更必须撤销受影响的高权限上下文；密码重置、删除和毕业撤销全部会话。

生产类 `_yb` 环境启用 `SHTONG_SSO_ONLY=true`，拒绝旧 HTML 密码登录、旧 Bearer 业务 API 和没有 Shtong 父会话标记的本地 session；`django-admin` 仅保留内部运维边界。

## 6. 退出登录协议

`POST /v1/auth/logout` 必须幂等，并按以下顺序完成：

1. 服务端在权威短事务中撤销当前 session/token family，写入登出审计和 outbox。
2. 事务提交后清除 Web 会话 Cookie或返回移动端清除凭据指令。
3. 客户端清空账号资料、模块目录、查询缓存、临时文件引用、通知内容和内存中的 token。
4. 客户端用登录页替换受保护导航根，而不是向历史栈再压入一个登录页。
5. 后续旧 token 请求由服务端拒绝，并产生 `SESSION.REVOKED_TOKEN_USED` 审计事件。

客户端即使因网络失败未收到响应，也必须清理本地会话并回到登录页；服务端撤销失败时显示“本机已退出，服务端会话撤销待确认”，并在网络恢复后仅重试幂等登出，不恢复旧 UI。

## 7. Web 返回与缓存

Web 两个应用必须同时实现：

- 受保护 API 响应使用 `Cache-Control: no-store`；敏感下载使用短期一次性票据。
- 退出成功使用路由 `replace` 或页面 `location.replace`，不保留受保护路由作为可返回页面。
- 清空 Pinia、Vue Query/Nuxt 数据缓存、内存对象和应用持久化状态。
- 受保护应用壳在首次渲染、`pageshow`（含 BFCache 恢复）和重新获得可见性时验证会话；验证完成前不绘制敏感内容。
- 全局路由守卫在没有已验证 bootstrap 时只显示认证门。
- `401 SESSION_REVOKED`、`401 SESSION_EXPIRED`、`403 AUTHORIZATION_VERSION_STALE` 统一清理状态并替换到登录/认证门。
- 禁止把账户列表、账户详情、审计详情或导出内容持久化到浏览器存储。

仅靠禁用浏览器返回键是不可靠且破坏可用性的；验收标准是“返回后不显示受保护内容，也无法调用接口”。

## 8. Android 返回栈

- 登出成功或本机清理完成后，将认证图设为新的导航根，清除所有受保护 destination。
- Compose Navigation 使用 `popUpTo` 清除工作区图并禁止保存/恢复敏感 destination state。
- 系统返回键在登录页不得返回之前的工作台；可留在登录页或按平台习惯退出应用。
- 最近任务缩略图、后台切换和进程重建不得显示账户或审计敏感内容；系统管理员页面启用必要的安全窗口策略。
- 深链接进入受保护页面前必须重新通过认证门和服务端 bootstrap。

## 9. iOS 返回栈

- 登出后替换根 `NavigationStack`/根协调器，清空原 path、sheet、full-screen cover 和敏感 ViewModel。
- 登录页不能通过导航返回手势回到工作台。
- 场景进入后台时遮盖系统管理员敏感页面，避免应用切换器快照泄露。
- Universal Link 或通知深链必须先经过认证门和 bootstrap，不能恢复已撤销的导航状态。
- Keychain 中的 refresh token 和本地会话元数据在本机退出时删除。

## 10. 审计事件

至少包含：

- `AUTH.LOGIN.ATTEMPTED`、`AUTH.LOGIN.SUCCEEDED`、`AUTH.LOGIN.FAILED`
- `AUTH.PASSWORD.CHANGE_REQUIRED`、`AUTH.PASSWORD.CHANGED`、`AUTH.PASSWORD.RESET`
- `AUTH.PASSWORD.CHANGE_FAILED`（`AUTH.MFA.*` 已弃用，仅用于解释历史记录）
- `AUTH.LOGOUT.SUCCEEDED`、`AUTH.LOGOUT.FAILED`
- `SESSION.REVOKED`、`SESSION.REVOKED_TOKEN_USED`
- `SESSION.CONTEXT_SWITCHED`、`SESSION.CONTEXT_SWITCH_FAILED`

失败事件不能包含提交的密码、Cookie、Token 或完整 Authorization header。

## 11. 必测场景

- 首次登录限制、临时密码一次使用、改密后旧限制 token 失效。
- 重置密码后 Web、Android、iOS 的全部旧会话立即失效。
- `ACTIVE`、`GRADUATED`、`DELETED` 和锁定账户返回相同外部错误但记录准确内部原因。
- refresh token 轮换和重放检测。
- 浏览器普通后退、BFCache 恢复、刷新和复制受保护 URL。
- Android 系统返回、进程回收恢复、深链接和最近任务预览。
- iOS 返回手势、Scene 恢复、Universal Link 和应用切换器快照。
- 登出请求重复提交、响应丢失和服务端审计失败。
