package com.fuguang.lueying

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 颂拓允许原生客户端动态注册 127.0.0.1 回调，使用 OAuth 公开客户端 + PKCE。 */
object SuuntoOAuth {
    const val ID = "suunto"
    private const val ORIGIN = "https://mcp.suunto.cn"
    const val URL = "$ORIGIN/open/mcp"
    private const val SCOPE = "mcp:tools"
    private const val CALLBACK = "/callback"
    private const val KEY_ALIAS = "vignette-suunto-oauth"
    private val random = SecureRandom()
    private val refreshLock = Mutex()
    private val storeLock = Any()
    private val http = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    data class Credentials(
        val clientId: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
    )

    suspend fun authorize(openBrowser: (String) -> Unit): Credentials =
        withTimeoutOrNull(180_000) {
            withContext(Dispatchers.IO) {
                ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { listener ->
                    listener.soTimeout = 500
                    val redirect = "http://127.0.0.1:${listener.localPort}$CALLBACK"
                    val registration = JSONObject()
                        .put("client_name", "Vignette")
                        .put("redirect_uris", JSONArray().put(redirect))
                        .put("grant_types", JSONArray(listOf("authorization_code", "refresh_token")))
                        .put("response_types", JSONArray().put("code"))
                        .put("token_endpoint_auth_method", "none")
                        .put("scope", SCOPE)
                    val clientId = request(
                        Request.Builder().url("$ORIGIN/mcp/oauth2/register")
                            .post(registration.toString().toRequestBody("application/json".toMediaType())).build(),
                        "颂拓客户端注册失败",
                    ).getString("client_id")
                    val verifier = nonce()
                    val state = nonce()
                    val challenge = encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
                    val authorization = Uri.parse("$ORIGIN/oauth2/authorize").buildUpon()
                        .appendQueryParameter("response_type", "code")
                        .appendQueryParameter("client_id", clientId)
                        .appendQueryParameter("redirect_uri", redirect)
                        .appendQueryParameter("scope", SCOPE)
                        .appendQueryParameter("resource", URL)
                        .appendQueryParameter("state", state)
                        .appendQueryParameter("code_challenge", challenge)
                        .appendQueryParameter("code_challenge_method", "S256")
                        .build().toString()
                    withContext(Dispatchers.Main) { openBrowser(authorization) }
                    val code = waitForCallback(listener, state)
                    token(clientId, FormBody.Builder()
                        .add("grant_type", "authorization_code")
                        .add("code", code)
                        .add("redirect_uri", redirect)
                        .add("code_verifier", verifier))
                }
            }
        } ?: error("颂拓授权超时，请重新连接")

    private suspend fun waitForCallback(listener: ServerSocket, state: String): String {
        while (true) {
            currentCoroutineContext().ensureActive()
            val socket = try {
                listener.accept()
            } catch (_: SocketTimeoutException) {
                continue
            }
            socket.use {
                socket.soTimeout = 1_500
                val line = try {
                    val input = socket.getInputStream().buffered()
                    val requestLine = StringBuilder()
                    val deadline = android.os.SystemClock.elapsedRealtime() + 1_500
                    var firstLine = true
                    var lineLength = 0
                    var headersComplete = false
                    // 公共回调只能用 GET，限制请求头长度与读取时间。
                    for (index in 0 until 16_384) {
                        if (android.os.SystemClock.elapsedRealtime() >= deadline) break
                        when (val byte = input.read()) {
                            -1 -> break
                            13 -> Unit
                            10 -> {
                                if (lineLength == 0) {
                                    headersComplete = true
                                    break
                                }
                                firstLine = false
                                lineLength = 0
                            }
                            else -> {
                                lineLength++
                                if (firstLine) requestLine.append(byte.toChar())
                            }
                        }
                    }
                    if (!headersComplete) return@use
                    requestLine.toString()
                } catch (_: java.io.IOException) {
                    return@use
                }
                val target = line.removePrefix("GET ").substringBefore(' ')
                val callback = Uri.parse(target)
                val matches = line.startsWith("GET ") && !callback.isAbsolute &&
                    callback.path == CALLBACK && callback.getQueryParameter("state") == state
                val code = if (matches) callback.getQueryParameter("code").orEmpty() else ""
                val message = when {
                    !matches -> "Invalid callback."
                    code.isBlank() -> "颂拓授权未完成，请回到 Vignette 重试。"
                    else -> "已收到颂拓授权，请返回 Vignette 完成连接。"
                }.toByteArray(Charsets.UTF_8)
                runCatching {
                    val status = if (matches) "200 OK" else "400 Bad Request"
                    val header = "HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                        "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
                        "Content-Length: ${message.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply { write(header.toByteArray()); write(message); flush() }
                }
                if (matches) return code.ifBlank { error("颂拓授权未完成，请重新连接") }
            }
        }
    }

    suspend fun accessToken(context: Context, serverId: String, rejectedToken: String? = null): String =
        refreshLock.withLock {
            val current = synchronized(storeLock) { load(context, serverId) }
                ?: error("请在 MCP 服务里重新连接颂拓")
            val rejected = current.accessToken == rejectedToken
            if (!rejected && (current.expiresAt == 0L || System.currentTimeMillis() + 60_000 < current.expiresAt)) {
                return@withLock current.accessToken
            }
            if (current.refreshToken.isBlank()) {
                if (!rejected && System.currentTimeMillis() < current.expiresAt) return@withLock current.accessToken
                error("颂拓授权已过期，请重新连接")
            }
            val renewed = token(current.clientId, FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", current.refreshToken), current.refreshToken)
            synchronized(storeLock) {
                val latest = load(context, serverId)
                if (latest != current) return@synchronized latest?.accessToken ?: error("颂拓连接已移除")
                save(context, serverId, renewed)
                renewed.accessToken
            }
        }

    private fun token(clientId: String, form: FormBody.Builder, previousRefreshToken: String = ""): Credentials {
        val json = request(
            Request.Builder().url("$ORIGIN/oauth2/token")
                .post(form.add("client_id", clientId).add("resource", URL).build()).build(),
            "颂拓令牌请求失败",
        )
        val ttl = json.optLong("expires_in")
        return Credentials(
            clientId,
            json.getString("access_token").also { check(it.isNotBlank()) { "颂拓未返回访问令牌" } },
            json.optString("refresh_token", previousRefreshToken),
            if (ttl > 0) System.currentTimeMillis() + ttl * 1000 else 0L,
        )
    }

    private fun request(request: Request, message: String): JSONObject =
        http.newCall(request).execute().use { reply ->
            if (!reply.isSuccessful) {
                if (reply.code == 400 && runCatching { JSONObject(reply.body?.string().orEmpty()).optString("error") }.getOrNull() == "invalid_grant") {
                    error("颂拓授权已失效，请重新连接")
                }
                error("$message（HTTP ${reply.code}）")
            }
            JSONObject(reply.body?.string().orEmpty())
        }

    fun save(context: Context, serverId: String, credentials: Credentials) = synchronized(storeLock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val json = JSONObject().put("clientId", credentials.clientId)
            .put("accessToken", credentials.accessToken).put("refreshToken", credentials.refreshToken)
            .put("expiresAt", credentials.expiresAt)
        val file = file(context, serverId)
        val stream = file.startWrite()
        try {
            stream.write(cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8)))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    fun remove(context: Context, serverId: String) = synchronized(storeLock) { file(context, serverId).delete() }

    private fun load(context: Context, serverId: String): Credentials? = runCatching {
        val bytes = file(context, serverId).readFully()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONObject(String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8))
        Credentials(json.getString("clientId"), json.getString("accessToken"), json.getString("refreshToken"), json.getLong("expiresAt"))
    }.getOrNull()

    private fun file(context: Context, serverId: String) = AtomicFile(File(context.noBackupFilesDir, "suunto-$serverId"))

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun nonce() = encode(ByteArray(32).also(random::nextBytes))
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
