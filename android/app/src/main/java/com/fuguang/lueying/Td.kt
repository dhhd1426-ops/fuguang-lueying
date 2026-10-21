package com.fuguang.lueying

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Telegram 官方 TDLib（Telegram X 用的同一套）。只在用到时才起来；登录信息存在 files/tdlib，只在这台手机上。
 * 状态 [state]：off 没起 · wait 起着 · phone 要手机号 · code 要验证码 · password 要两步验证密码 · ready 登好了。
 * 下载照 TDLib 默认的连接数走（普通账号 2 路）：不硬调 session_count，免得账号被盯上。
 */
object Td {
    /** 在 my.telegram.org 申请的（每个 App 要用自己的）：第一次登录时 [TgApp] 替他开通，存在 prefs `tg-api`（「id:hash」）。 */
    private var apiId by mutableStateOf(0)
    private var apiHash = ""
    val configured get() = apiId != 0
    private var prefs: Prefs? = null

    fun keys(id: Int, hash: String) { apiId = id; apiHash = hash; prefs?.putString("tg-api", "$id:$hash") }

    var state by mutableStateOf("off")
    /** 验证码发到哪（「Telegram 里」/「短信」）、几位。 */
    var codeVia by mutableStateOf("")
    var codeLen by mutableStateOf(5)
    var pwdHint by mutableStateOf("")
    var me by mutableStateOf("")

    private lateinit var app: Context
    private var client: Client? = null
    internal val files = ConcurrentHashMap<Int, (TdApi.File) -> Unit>()

    fun init(ctx: Context, p: Prefs) {
        app = ctx.applicationContext; prefs = p
        p.string("tg-api")?.split(':', limit = 2)?.takeIf { it.size == 2 }?.let { (i, h) -> apiId = i.toIntOrNull() ?: 0; apiHash = h }
    }

    /** 起 TDLib（已经起了就不动）；等它说清楚要不要登录，最多 10 秒。 */
    suspend fun up(): String {
        if (!configured) return "off"
        if (client == null) {
            state = "wait"
            runCatching { Client.execute(TdApi.SetLogVerbosityLevel(1)) }
            client = Client.create({ on(it) }, null, null)
        }
        withTimeoutOrNull(10_000) { while (state == "wait") delay(60) }
        return state
    }

    private fun on(u: TdApi.Object) {
        when (u) {
            is TdApi.UpdateAuthorizationState -> auth(u.authorizationState)
            is TdApi.UpdateFile -> files[u.file.id]?.invoke(u.file)
        }
    }

    private fun auth(s: TdApi.AuthorizationState) {
        when (s) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> send(TdApi.SetTdlibParameters().apply {
                databaseDirectory = File(app.filesDir, "tdlib").absolutePath
                filesDirectory = File(app.filesDir, "tdlib/files").absolutePath
                databaseEncryptionKey = ByteArray(0)
                useFileDatabase = true; useChatInfoDatabase = true; useMessageDatabase = false; useSecretChats = false
                apiId = this@Td.apiId; apiHash = this@Td.apiHash
                systemLanguageCode = "zh-hans"; deviceModel = Build.MODEL ?: "Android"; systemVersion = Build.VERSION.RELEASE ?: ""
                applicationVersion = "浮光掠影"
            })
            is TdApi.AuthorizationStateWaitPhoneNumber -> state = "phone"
            is TdApi.AuthorizationStateWaitCode -> {
                val t = s.codeInfo.type
                codeVia = if (t is TdApi.AuthenticationCodeTypeTelegramMessage) "你的 Telegram 里（不是短信）" else "短信里"
                codeLen = when (t) { is TdApi.AuthenticationCodeTypeTelegramMessage -> t.length; is TdApi.AuthenticationCodeTypeSms -> t.length; else -> 5 }.coerceIn(4, 8)
                state = "code"
            }
            is TdApi.AuthorizationStateWaitPassword -> { pwdHint = s.passwordHint; state = "password" }
            is TdApi.AuthorizationStateReady -> {
                state = "ready"
                send(TdApi.GetMe()) { r -> if (r is TdApi.User) me = (r.firstName + " " + r.lastName).trim() }
            }
            is TdApi.AuthorizationStateClosed -> { client = null; state = "off"; me = "" }
            else -> {}
        }
    }

    private fun send(f: TdApi.Function<*>, then: (TdApi.Object) -> Unit = {}) { client?.send(f) { then(it) } }

    /** 发一个请求等结果；TDLib 回错误时抛 [TgErr]。 */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T : TdApi.Object> call(f: TdApi.Function<T>): T {
        val c = client ?: throw TgErr(0, "TDLib 没起来")
        return suspendCancellableCoroutine { k ->
            c.send(f) { r -> if (k.isActive) { if (r is TdApi.Error) k.resumeWithException(TgErr(r.code, r.message)) else k.resume(r as T) } }
        }
    }

    // ———— 登录 ————

    suspend fun phone(n: String) = call(TdApi.SetAuthenticationPhoneNumber(n.filter { it.isDigit() || it == '+' }, TdApi.PhoneNumberAuthenticationSettings()))
    suspend fun code(c: String) = call(TdApi.CheckAuthenticationCode(c))
    suspend fun password(p: String) = call(TdApi.CheckAuthenticationPassword(p))
    suspend fun logout() { runCatching { call(TdApi.LogOut()) } }

    /** 回到填手机号（换个号码）：TDLib 没有退一步，只能退出登录重来。 */
    suspend fun restart() { logout(); withTimeoutOrNull(8_000) { while (state != "phone") { if (state == "off") up(); delay(100) } } }

    /** 登录时 Telegram 回的错误，换成人话。 */
    fun say(e: Throwable): String {
        if (e is TgFail) return e.why
        if (e is TgManual) return if ("too many" in e.code.lowercase()) "Telegram 说开通试太多次了，隔几个小时（最长一天）再点，期间别反复试" else "Telegram 不让 App 自动开通，下面点一下就好"
        if (e is PanError) return e.why
        val m = (e as? TgErr)?.msg ?: return "连不上 Telegram，看看网络"
        return when {
            "PHONE_NUMBER_INVALID" in m -> "号码不对，记得带国家区号"
            "PHONE_NUMBER_BANNED" in m -> "这个号码被 Telegram 封了"
            "PHONE_CODE_INVALID" in m -> "验证码不对"
            "PHONE_CODE_EXPIRED" in m -> "验证码过期了，换个号码重新发"
            "PASSWORD_HASH_INVALID" in m -> "密码不对"
            "FLOOD" in m || "Too Many Requests" in m -> "试太多次了，过一会儿再来"
            "API_ID" in m -> "App 的 Telegram 配置有问题（$m）"
            else -> m.take(40)
        }
    }
}

class TgErr(val code: Int, val msg: String) : Exception(msg)
