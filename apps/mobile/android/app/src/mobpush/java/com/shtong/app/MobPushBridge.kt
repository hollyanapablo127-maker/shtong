package com.shtong.app

import android.content.Context
import com.mob.MobSDK
import com.mob.OperationCallback
import com.mob.pushsdk.MobPush

/** Compiled only when the MobTech SDK binaries are present in app/libs. */
internal object MobPushBridge {
    fun start(context: Context, appKey: String, onDone: (Boolean) -> Unit) {
        // MobSDK is deliberately initialized only after the host policy has been accepted.
        MobSDK.init(context, appKey)
        MobSDK.submitPolicyGrantResult(true, object : OperationCallback<Void>() {
            override fun onComplete(result: Void?) {
                onDone(runCatching { MobPush.initMobPush() }.isSuccess)
            }

            override fun onFailure(error: Throwable?) {
                // Keep push disabled when the privacy grant cannot be acknowledged.
                onDone(false)
            }
        })
    }
}
