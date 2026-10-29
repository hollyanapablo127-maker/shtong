package com.shtong.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

internal const val MEDICAL_SIDEBAR_BRIDGE_NAME = "ShtongMedicalBridge"

internal class MedicalSidebarStateBridge(private val onStateChanged: (Boolean) -> Unit) {
    @JavascriptInterface
    fun onSidebarStateChanged(value: String) {
        sidebarBridgeState(value)?.let(onStateChanged)
    }
}

internal class MedicalFileChooserChromeClient(
    private val launchPicker: (Intent) -> Boolean,
    private val transformUris: (Array<Uri>) -> Array<Uri>? = { it },
    private val onInvalidSelection: () -> Unit = {},
) : WebChromeClient() {
    private var pendingCallback: ValueCallback<Array<Uri>>? = null

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean {
        if (filePathCallback == null) return false
        cancelPending()
        pendingCallback = filePathCallback
        val intent = runCatching { fileChooserParams?.createIntent() }.getOrNull()
            ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
        if (launchPicker(constrainMedicalUploadPicker(intent))) return true
        cancelPending()
        return false
    }

    fun deliverResult(resultCode: Int, data: Intent?) {
        val callback = pendingCallback ?: return
        pendingCallback = null
        val selected = FileChooserParams.parseResult(resultCode, data)
        if (selected == null) {
            callback.onReceiveValue(null)
            return
        }
        val transformed = transformUris(selected)
        if (transformed == null || transformed.isEmpty()) {
            onInvalidSelection()
            callback.onReceiveValue(null)
            return
        }
        callback.onReceiveValue(transformed)
    }

    fun cancelPending() {
        pendingCallback?.onReceiveValue(null)
        pendingCallback = null
    }
}

private const val MEDICAL_UPLOAD_PROVIDER_SUFFIX = ".medical-upload"
private const val MEDICAL_UPLOAD_CACHE_DIRECTORY = "medical-upload"
private const val MAX_MEDICAL_UPLOAD_SOURCE_BYTES = 20L * 1024L * 1024L

internal data class MedicalUploadFormat(val extension: String, val mimeType: String)

private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

internal fun detectMedicalUploadFormat(header: ByteArray): MedicalUploadFormat? = when {
    header.hasPrefix(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) -> MedicalUploadFormat("jpg", "image/jpeg")
    header.hasPrefix(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> MedicalUploadFormat("png", "image/png")
    header.hasPrefix("%PDF-".encodeToByteArray()) -> MedicalUploadFormat("pdf", "application/pdf")
    header.hasPrefix("RIFF".encodeToByteArray()) && header.copyOfRange(8, minOf(header.size, 12)).contentEquals("WEBP".encodeToByteArray()) -> MedicalUploadFormat("webp", "image/webp")
    else -> null
}

internal fun constrainMedicalUploadPicker(intent: Intent): Intent = Intent(intent).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = "*/*"
    putExtra(
        Intent.EXTRA_MIME_TYPES,
        arrayOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "application/pdf"),
    )
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

internal fun normalizeMedicalUploadUris(context: Context, uris: Array<Uri>): Array<Uri>? {
    val normalized = uris.map { uri -> normalizeMedicalUploadUri(context, uri) ?: return null }
    return normalized.toTypedArray()
}

internal fun clearMedicalUploadCache(context: Context) {
    File(context.cacheDir, MEDICAL_UPLOAD_CACHE_DIRECTORY)
        .listFiles()
        ?.forEach { file -> file.delete() }
}

private fun normalizeMedicalUploadUri(context: Context, uri: Uri): Uri? {
    val directory = File(context.cacheDir, MEDICAL_UPLOAD_CACHE_DIRECTORY)
    if (!directory.exists() && !directory.mkdirs()) return null
    val source = File.createTempFile("source-", ".upload", directory)
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            source.outputStream().use(input::copyTo)
        } ?: return null
        if (source.length() !in 1..MAX_MEDICAL_UPLOAD_SOURCE_BYTES) return null
        val header = source.inputStream().use { input ->
            val bytes = ByteArray(16)
            bytes.copyOf(input.read(bytes).coerceAtLeast(0))
        }
        val format = detectMedicalUploadFormat(header)
        val output = File(directory, "receipt-${UUID.randomUUID()}.${format?.extension ?: "jpg"}")
        if (format != null) {
            source.copyTo(output, overwrite = true)
        } else {
            // Android decodes HEIF/HEIC from the scoped source and emits a
            // conventional JPEG. The server still validates the JPEG magic
            // bytes; unknown or undecodable sources are never relabelled.
            val bitmap = BitmapFactory.decodeFile(source.absolutePath) ?: return null
            try {
                output.outputStream().use { stream ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)) return null
                }
            } finally {
                bitmap.recycle()
            }
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}$MEDICAL_UPLOAD_PROVIDER_SUFFIX",
            output,
        )
    } catch (_: Exception) {
        return null
    } finally {
        source.delete()
    }
}

internal const val MEDICAL_SIDEBAR_READY_SCRIPT = """
(function () {
    try {
        return document.documentElement &&
            document.documentElement.dataset.yibaoSidebarReady === 'true' &&
            window.YibaoUI &&
            typeof window.YibaoUI.toggleSidebar === 'function' &&
            document.querySelector('.sidebar') ? 1 : 0;
    } catch (error) {
        return 0;
    }
}());
"""

internal const val MEDICAL_SIDEBAR_TOGGLE_SCRIPT = """
(function () {
    try {
        var sidebar = document.querySelector('.sidebar');
        if (!sidebar) return 0;
        if (window.YibaoUI && typeof window.YibaoUI.toggleSidebar === 'function') {
            window.YibaoUI.toggleSidebar();
        } else {
            var opening = !sidebar.classList.contains('is-open');
            var backdrop = document.querySelector('.mobile-nav-backdrop');
            sidebar.classList.toggle('is-open', opening);
            sidebar.setAttribute('aria-hidden', opening ? 'false' : 'true');
            if (opening) sidebar.removeAttribute('inert'); else sidebar.setAttribute('inert', '');
            document.body.classList.toggle('nav-open', opening);
            if (backdrop) {
                backdrop.classList.toggle('is-visible', opening);
                backdrop.setAttribute('aria-hidden', opening ? 'false' : 'true');
            }
        }
        return sidebar.classList.contains('is-open') ? 1 : 2;
    } catch (error) {
        return 0;
    }
}());
"""

internal fun sidebarOpenState(result: String?): Boolean? = when(result?.trim()) {
    "1" -> true
    "2" -> false
    else -> null
}

internal fun sidebarReadyState(result: String?): Boolean = result?.trim() == "1"

internal fun sidebarBridgeState(value: String?): Boolean? = when(value?.trim()?.lowercase()) {
    "open", "true", "1" -> true
    "closed", "false", "0" -> false
    else -> null
}
