# 生化通 iOS

原生 Swift 5.10 / SwiftUI 第一阶段客户端，最低 iOS 17。实现账号密码登录、首次改密、
空工作台、个人中心、医保信息系统入口，以及系统管理员账户、导入导出、批量命令、逐账户授权、双人复核、
组织和审计功能。

- Access/refresh token 只存入 Keychain；refresh token 每次使用由服务端轮换。
- 登出始终删除本机凭据并替换认证根，不保留受保护导航、sheet 或内存数据。
- 场景进入后台时覆盖受保护内容，避免应用切换器快照泄露。
- 自定义 URL 和 Universal Link 都先经过认证门及 `/session/bootstrap`。
- Debug 连接 `http://127.0.0.1:8080/v1/`；Release 默认 HTTPS。部署前必须替换
  `API_BASE_URL` 和 `ASSOCIATED_DOMAIN`，并发布对应 `apple-app-site-association`。

macOS 上可直接打开已提交的 `Shtong.xcodeproj`，也可安装 XcodeGen 后在本目录执行
`xcodegen generate`，以 `project.yml` 重新生成工程。签名团队通过本地 Xcode 配置提供，
不得写入仓库。
