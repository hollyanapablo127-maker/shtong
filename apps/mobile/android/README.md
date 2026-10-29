# 生化通 Android

原生 Kotlin/Jetpack Compose 客户端。调试构建默认使用 Android 模拟器宿主机
`http://10.0.2.2:18081/v1`，也可通过 Gradle 参数覆盖；发布构建仅允许 HTTPS。Access/refresh
token 使用 Android Keystore AES-GCM 加密存储，退出时清除。已登录用户从工作台进入医保模块时
复用 Shtong 当前会话，不建立第二套登录。

需要 Android SDK 35、受支持的 Android Gradle Plugin JDK 与仓库 Gradle Wrapper。MobTech 推送
SDK 是可选依赖，官方二进制不包含在仓库中：缺失时应用正常构建、运行，仅不启用消息推送。如需推送，
按 [`app/libs/README.md`](app/libs/README.md) 获取文件并放入 `app/libs/`，同时提供 `-Pshtong.mobPushAppKey=...`。用户不同意隐私政策时同样只关闭推送。

Release 构建必须提供 `-Pshtong.releaseApiBaseUrl=https://...` 与
`-Pshtong.associatedHost=...`；跨源工件域按需通过 `-Pshtong.trustedDownloadHost=...`
提供，任何空值或 `.example` 占位都会使发布前置任务失败。

在本目录执行 `./gradlew.bat --no-daemon lintDebug assembleDebug`。调试构建产物位于
`app/build/outputs/apk/debug/app-debug.apk`，安装要求 Android 8.0 及以上。调试签名和 HTTP
配置不能作为生产发布包。
