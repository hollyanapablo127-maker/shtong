package com.shtong.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal const val PRIVACY_POLICY_VERSION = "2026-09-22.mobpush.v1"
internal const val MOB_PUSH_PRIVACY_URL = "https://policy.zztfly.com/sdk/mobpush/privacy"

internal class PrivacyConsentStore(context: Context) {
    private val preferences = context.getSharedPreferences("privacy_consent", Context.MODE_PRIVATE)

    fun isAccepted(): Boolean = preferences.getString("acceptedVersion", null) == PRIVACY_POLICY_VERSION

    /** Accepted or declined for the current policy version; declining only disables push. */
    fun isDecided(): Boolean = isAccepted() || preferences.getString("declinedVersion", null) == PRIVACY_POLICY_VERSION

    fun decline() {
        preferences.edit {
            putString("declinedVersion", PRIVACY_POLICY_VERSION)
            putLong("declinedAtEpochMs", System.currentTimeMillis())
        }
    }

    fun accept() {
        preferences.edit {
            putString("acceptedVersion", PRIVACY_POLICY_VERSION)
            putLong("acceptedAtEpochMs", System.currentTimeMillis())
        }
    }

    fun clear() {
        preferences.edit { clear() }
    }
}

internal object MobPushConsentController {
    @Volatile
    private var started = false
    @Volatile
    private var starting = false

    fun startAfterConsent(context: Context) {
        if (started || starting || !pushAvailable()) return
        synchronized(this) {
            if (started || starting) return
            starting = true
            MobPushBridge.start(context.applicationContext, BuildConfig.MOB_PUSH_APP_KEY) { success ->
                started = success
                starting = false
            }
        }
    }
}

/** Push needs both the bundled MobTech SDK and a configured app key. */
internal fun pushAvailable(): Boolean = BuildConfig.MOB_PUSH_BUNDLED && BuildConfig.MOB_PUSH_APP_KEY.isNotBlank()

internal fun notificationPermissionNeeded(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        pushAvailable() &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

@Composable
internal fun PrivacyPolicyGate(
    accepted: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    if (accepted) return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text("生化通隐私政策") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("首次使用前，请阅读《隐私政策》。不同意时不会初始化消息推送 SDK，也不会向 MobTech 或厂商通道提交设备信息，其他功能可正常使用。")
                run {
                    Text("生化通隐私政策", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(privacyPolicyText())
                    TextButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, MOB_PUSH_PRIVACY_URL.toUri()))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "打开 MobPush 隐私政策" },
                    ) { Text("查看 MobPush 隐私政策") }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("同意并继续") }
        },
        dismissButton = {
            TextButton(
                onClick = onReject,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("不同意，不使用消息推送") }
        },
    )
}

internal val PRIVACY_POLICY_TEXT = """
版本：2026-09-22

一、我们如何保护你的信息
生化通由统一账户、权限和工作台提供校园服务。我们只在实现登录、账户安全、已授权业务模块和消息通知所必需的范围内处理个人信息，并通过访问控制、加密传输、最小权限和审计记录保护信息。账号、认证凭据、消息通知标识和业务资料不会用于与本应用无关的目的。

二、消息推送与第三方服务
为向你提供消息推送功能，我们使用第三方上海掌之淘信息技术有限公司（以下称“MobTech”）的 MobPushSDK。经你同意后，MobPushSDK可能处理设备标识符、应用基本信息、网络和推送注册信息，用于建立推送通道、发送和维护通知。具体收集种类、用途、保护规则和退出机制详见：
$MOB_PUSH_PRIVACY_URL

小米推送 SDK：设备标识符（如 Android ID、OAID、GAID）、设备信息；用于推送消息；在小米终端推送时使用。第三方主体：北京小米移动软件有限公司。通过去标识化、加密传输及其他安全方式处理。官网：https://dev.mi.com/console/appservice/push.html；隐私政策：https://dev.mi.com/console/doc/detail?pId=1822。

华为 HMS SDK：应用基本信息、应用内设备标识符、设备硬件信息、系统基本信息和系统设置信息；用于推送消息；在华为终端推送时使用。第三方主体：华为软件技术有限公司。通过去标识化、加密传输及其他安全方式处理。官网：https://developer.huawei.com/consumer/cn/；隐私政策：https://developer.huawei.com/consumer/cn/doc/development/HMSCore-Guides/sdk-data-security-0000001050042177。

荣耀推送 SDK：应用匿名标识（AAID）、应用 Token；用于推送消息；在荣耀终端推送时使用。第三方主体：荣耀终端有限公司。通过去标识化、加密传输及其他安全方式处理。官网：https://developer.hihonor.com/cn/promoteService；隐私政策：https://www.hihonor.com/cn/privacy/privacy-policy/。

OPPO 推送 SDK：设备标识符（如 IMEI、ICCID、IMSI、Android ID、GAID）、应用信息（如包名、版本号和运行状态）、网络信息（如 IP 或域名连接结果、当前网络类型）；用于推送消息；在 OPPO 终端推送时使用。第三方主体：广东欢太科技有限公司。通过加密传输和处理的安全方式处理。官网：https://open.oppomobile.com/new/introduction?page_name=oppopush；隐私政策：https://open.oppomobile.com/wiki/doc#id=10288。

vivo 推送 SDK：设备信息；用于推送消息；在 vivo 终端推送时使用。第三方主体：广东天宸网络科技有限公司及将来受让运营 vivo 开放平台的公司。通过去标识化、加密传输及其他安全方式处理。官网：https://dev.vivo.com.cn/promote/pushNews；隐私政策：https://www.vivo.com.cn/about-vivo/privacy-policy。

魅族推送 SDK：设备标识信息、位置信息、网络状态信息、运营商信息；用于推送消息；在魅族终端推送时使用。第三方主体：珠海市魅族通讯设备有限公司。通过去标识化、加密传输及其他安全方式处理。官网：https://open.flyme.cn/service?type=push；隐私政策：https://www.meizu.com/legal.html。

三、你的选择与控制
你可以拒绝本政策；拒绝后不会初始化 MobPushSDK，也不会收到消息推送，其他功能可正常使用。你可以在系统通知设置中关闭通知权限，或通过生化通管理员申请停止消息推送。账号注销、退出登录或推送服务停止后，我们会停止使用对应的推送关联信息，并按适用法律和业务留存要求处理必要记录。

四、其他
本政策不替代具体业务模块的专项说明。政策版本更新后，应用会在首次启动时再次请求你的确认。运营主体、联系方式和正式发布备案信息以应用发布页面的最终版本为准。
""".trimIndent()

internal fun privacyPolicyText(): String {
    val operator = BuildConfig.PRIVACY_OPERATOR.ifBlank { "开发测试包未配置正式运营主体" }
    val contact = BuildConfig.PRIVACY_CONTACT.ifBlank { "开发测试包未配置隐私联系方式" }
    return "$PRIVACY_POLICY_TEXT\n\n五、运营主体与联系方式\n运营主体：$operator\n隐私联系方式：$contact"
}
