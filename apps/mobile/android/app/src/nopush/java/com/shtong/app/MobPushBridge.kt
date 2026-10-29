package com.shtong.app

import android.content.Context

/** Used when the MobTech SDK binaries are not in app/libs: the app builds and runs without push. */
internal object MobPushBridge {
    @Suppress("UNUSED_PARAMETER")
    fun start(context: Context, appKey: String, onDone: (Boolean) -> Unit) = onDone(false)
}
