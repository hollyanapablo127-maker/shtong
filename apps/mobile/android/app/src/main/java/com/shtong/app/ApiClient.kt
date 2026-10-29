package com.shtong.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URI
import java.util.UUID

class SessionInvalidException : RuntimeException("Session is no longer valid")

class ApiClient(
    @PublishedApi internal val secureStore: TokenStore,
    @PublishedApi internal val baseUrl: String = BuildConfig.API_BASE_URL,
    @PublishedApi internal val client: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
    private val trustedDownloadHosts:Set<String> = BuildConfig.TRUSTED_DOWNLOAD_HOST.split(',').map(String::trim).filter(String::isNotBlank).map{it.lowercase()}.toSet()
) {
    @PublishedApi internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val refreshMutex = Mutex()

    suspend inline fun <reified T> get(path: String): T = request(path, "GET")
    suspend inline fun <reified T, reified B> post(path: String, body: B): T = request(path, "POST", json.encodeToString(body))
    suspend inline fun <reified T, reified B> patch(path: String, body: B): T = request(path, "PATCH", json.encodeToString(body))

    suspend fun issueMedicalInsuranceTicket(): MedicalInsuranceTicket {
        val ticket: MedicalInsuranceTicket = request("/modules/medical-insurance/sso/ticket", "POST")
        return ticket.copy(redirectUrl = normalizeMedicalRedirect(resolveRedirect(ticket.redirectUrl)))
    }

    suspend fun switchContext(workspace:String): SessionEstablished = request("/session/context", "POST", kotlinx.serialization.json.Json.encodeToString(ContextRequest(workspace)))

    suspend inline fun <reified T> request(path: String, method: String, body: String? = null): T {
        val requestId = UUID.randomUUID().toString()
        val idempotencyKey = if (method == "GET" || method == "HEAD") null else UUID.randomUUID().toString()
        val text = requestText(path, method, body, requestId, idempotencyKey, true)
        @Suppress("UNCHECKED_CAST")
        return if (T::class == Unit::class) Unit as T else json.decodeFromString(serializer<T>(), text)
    }

    @PublishedApi
    internal suspend fun requestText(path: String, method: String, body: String?, requestId: String, idempotencyKey: String?, canRefresh: Boolean): String = withContext(Dispatchers.IO) {
        val failedAccess = secureStore.access()
        fun build(): Request {
            val builder = Request.Builder().url(resolve(path)).header("X-Channel", ANDROID_CHANNEL).header("X-Request-Id", requestId).header("Cache-Control", "no-store")
            idempotencyKey?.let { builder.header("Idempotency-Key", it) }
            secureStore.access()?.let { builder.header("Authorization", "Bearer $it") }
            val requestBody = body?.toRequestBody("application/json".toMediaType())
            builder.method(method, if (method == "GET" || method == "HEAD") null else requestBody ?: ByteArray(0).toRequestBody())
            return builder.build()
        }
        val first = execute(build())
        if (first.failure != null && canRefresh && shouldRefresh(path, first.failure, first.status)) {
            refreshTokens(failedAccess)
            val replay = execute(build())
            if (replay.failure != null) throw ApiException(replay.failure, replay.status)
            replay.text
        } else {
            if (first.failure != null) {
                if (first.status == 401 || first.failure.code == "AUTHORIZATION_VERSION_STALE") throw SessionInvalidException()
                throw ApiException(first.failure, first.status)
            }
            first.text
        }
    }

    suspend fun upload(path: String, file: File): JobAccepted {
        val requestId = UUID.randomUUID().toString()
        val idempotencyKey = UUID.randomUUID().toString()
        val failedAccess = secureStore.access()
        fun build(): Request {
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.asRequestBody("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".toMediaType())).build()
            return Request.Builder().url(resolve(path)).post(multipart).header("X-Channel", ANDROID_CHANNEL).header("X-Request-Id", requestId)
                .header("Idempotency-Key", idempotencyKey).header("Cache-Control", "no-store")
                .apply { secureStore.access()?.let { header("Authorization", "Bearer $it") } }.build()
        }
        return withContext(Dispatchers.IO) {
            var result = execute(build())
            if (result.failure != null && shouldRefresh(path, result.failure, result.status)) { refreshTokens(failedAccess); result = execute(build()) }
            result.failure?.let { throw ApiException(it, result.status) }
            json.decodeFromString(result.text)
        }
    }

    suspend fun download(path: String): ByteArray = withContext(Dispatchers.IO) {
        val url = resolve(path)
        val target=URI(url)
        if(!isApiOrigin(url)&&(target.scheme!="https"||target.host?.lowercase() !in trustedDownloadHosts))throw IllegalArgumentException("Untrusted download URL")
        val requestId = UUID.randomUUID().toString()
        fun build(): Request {
            val builder = Request.Builder().url(url).get().header("X-Channel", ANDROID_CHANNEL).header("X-Request-Id", requestId).header("Cache-Control", "no-store")
            if (isApiOrigin(url)) secureStore.access()?.let { builder.header("Authorization", "Bearer $it") }
            return builder.build()
        }
        val failedAccess = secureStore.access()
        var result = execute(build())
        if (result.failure != null && isApiOrigin(url) && shouldRefresh(path, result.failure, result.status)) { refreshTokens(failedAccess); result = execute(build()) }
        result.failure?.let { throw ApiException(it, result.status) }
        result.bytes
    }

    private suspend fun refreshTokens(failedAccess: String?) = refreshMutex.withLock {
        if (failedAccess != null && secureStore.access() != failedAccess) return@withLock
        val refresh = secureStore.refresh() ?: throw SessionInvalidException()
        val body = json.encodeToString(RefreshRequest(channel = ANDROID_CHANNEL, refreshToken = refresh))
        val request = Request.Builder().url(resolve("/auth/token/refresh")).post(body.toRequestBody("application/json".toMediaType()))
            .header("X-Channel", ANDROID_CHANNEL).header("X-Request-Id", UUID.randomUUID().toString()).header("Idempotency-Key", UUID.randomUUID().toString()).header("Cache-Control", "no-store").build()
        val result = execute(request)
        if (result.failure != null) { secureStore.clear(); throw SessionInvalidException() }
        val established = json.decodeFromString<SessionEstablished>(result.text)
        val tokens = established.mobileTokens ?: run { secureStore.clear(); throw SessionInvalidException() }
        secureStore.save(tokens.accessToken, tokens.refreshToken)
    }

    private fun execute(request: Request): RawResponse = client.newCall(request).execute().use { response ->
        val bytes = response.body?.bytes() ?: byteArrayOf()
        val text = bytes.toString(Charsets.UTF_8)
        val failure = if (response.isSuccessful) null else runCatching { json.decodeFromString<ApiFailure>(text) }.getOrDefault(ApiFailure())
        RawResponse(response.code, bytes, text, failure)
    }

    private fun shouldRefresh(path: String, failure: ApiFailure, status: Int) = !path.contains("/auth/login") && !path.contains("/auth/token/refresh") && (status == 401 || failure.code == "AUTHORIZATION_VERSION_STALE")
    private fun resolve(path: String): String = when { path.startsWith("https://") || path.startsWith("http://") -> path; path.startsWith("/v1/") -> URI(baseUrl).resolve(path).toString(); else -> baseUrl.trimEnd('/') + "/" + path.trimStart('/') }
    private fun resolveRedirect(path: String): String = when {
        path.startsWith("https://") || path.startsWith("http://") -> path
        else -> URI(baseUrl).resolve(if (path.startsWith("/")) path else "/$path").toString()
    }
    private fun normalizeMedicalRedirect(value: String): String {
        val target = URI(value)
        val base = URI(baseUrl)
        val host = target.host?.lowercase()
        val resolvedHost = if (host == "localhost" || host == "127.0.0.1") base.host else target.host
        val authority = buildString {
            if (target.userInfo != null) append(target.userInfo).append('@')
            append(resolvedHost ?: target.rawAuthority ?: "")
            if (target.port >= 0) append(':').append(target.port)
        }
        val query = target.rawQuery?.let { if (it.contains("embedded=")) it else "$it&embedded=1" } ?: "embedded=1"
        return URI(target.scheme, authority, target.path, query, target.fragment).toString()
    }
    private fun isApiOrigin(url: String): Boolean {
        val api=URI(baseUrl)
        val target=URI(url)
        fun port(uri:URI):Int {
            return if(uri.port>=0)uri.port else if(uri.scheme=="https")443 else 80
        }
        return api.scheme.equals(target.scheme,true)&&api.host.equals(target.host,true)&&port(api)==port(target)
    }
    private data class RawResponse(val status:Int,val bytes:ByteArray,val text:String,val failure:ApiFailure?)
}
