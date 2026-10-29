# Android 隐私政策与 MobPush 接入说明

Android 首次冷启动由 `apps/mobile/android/app/src/main/java/com/shtong/app/PrivacyPolicy.kt` 提供阻断式政策页面。用户未同意时不能进入登录后的工作区，MobSDK 不初始化，且不会调用 `MobSDK.submitPolicyGrantResult`；用户同意后才记录版本化授权、回传 MobTech 授权结果，并在回传成功后启动 MobPush。

政策正文包含 MobTech MobPushSDK 以及小米、华为 HMS、荣耀、OPPO、vivo、魅族厂商通道的主体、信息类型、用途、场景、处理方式和官方隐私链接。Android 13 及以上在政策同意后再申请 `POST_NOTIFICATIONS` 系统权限。该权限用于消息推送通知，不是短信验证码接收。

## 发布前必填配置

正式 Release 构建必须通过 Gradle 属性提供以下值；构建任务会拒绝空值或占位值：

```text
-Pshtong.mobPushAppKey=<MobTech AppKey>
-Pshtong.privacyOperator=<应用运营主体全称>
-Pshtong.privacyContact=<隐私政策联系渠道>
```

仅 AppKey 可以进入客户端。Mob AppSecret、厂商 Secret、服务端推送密钥只能放在受控服务端配置中，不得进入 APK、源码、`gradle.properties` 或普通日志。

`MobSDK.spEdition=FP` 已写入 Android `gradle.properties`。MobTech 官方 MobPush 3.3.9、MobCommons 和 MobTools 二进制未随源码分发；构建前请按 `apps/mobile/android/app/libs/README.md` 获取官方文件、核验 SHA-256 并放入本地依赖目录。

正式发布前还必须补齐运营主体、联系方式、MobTech AppKey、厂商通道控制台配置、服务端设备令牌绑定和消息发送权限，并重新做隐私合规审查。
