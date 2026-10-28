package com.fuguang.lueying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Telegram 授权（悬浮卡片里，[TgAuthLayer]）。第一次用先替他开通 api_id（[TgApp]，验证码一，发在他的 Telegram 里，是一串字母数字），
 * 开通好马上用同一个号码登录 TDLib（验证码二，几位数字）→ 两步验证密码 → ✓。以后只走后半段。
 * 每步左右交叉滑过。按钮四段：按下、处理中转圈、错了抖一下写原因、好了画 ✓。登好调 [onDone]。
 */
@Composable
internal fun TgLogin(onDone: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var errCode by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("+86 ") }
    var phase by remember { mutableStateOf(if (Td.configured) "td" else "phone0") }
    var hash by remember { mutableStateOf("") }
    val dx = remember { Animatable(0f) }
    LaunchedEffect(phase) { if (phase == "td") Td.up() }
    LaunchedEffect(Td.state) {
        err = ""; busy = false
        if (Td.state == "ready" && phase == "td") { haptic.performHapticFeedback(HapticFeedbackType.LongPress); kotlinx.coroutines.delay(1100); onDone() }
    }
    fun go(f: suspend () -> Unit) {
        if (busy) return
        busy = true; err = ""; errCode = ""
        scope.launch {
            try { f(); busy = false } catch (e: Exception) {
                err = Td.say(e); errCode = (e as? TgFail)?.code ?: (e as? TgManual)?.code ?: (e as? TgErr)?.let { x -> "TG · ${x.code} ${x.msg.take(30)}" }.orEmpty(); busy = false
                dx.animateTo(0f, keyframes { durationMillis = 300; -6f at 75; 6f at 150; -3f at 225 })
            }
        }
    }
    val num = phone.filter { it.isDigit() || it == '+' }
    val step = if (phase == "td") Td.state else phase
    Column(Modifier.fillMaxWidth().graphicsLayer { translationX = dx.value * density }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AnimatedContent(step, transitionSpec = {
            (slideInHorizontally(tween(320, easing = Curves.standard)) { it / 6 } + fadeIn(tween(220, 60))) togetherWith fadeOut(tween(140, easing = Curves.accel))
        }, label = "tgStep") { st ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (st) {
                    "phone0", "phone" -> {
                        Txt(if (st == "phone0") "第一次用要授权一次：先替你在 Telegram 官网开通这台手机的通行证，再登录。会收到两次验证码，都在你的 Telegram 里。"
                            else "用你自己的 Telegram 账号登录，之后大视频、私有频道都能直接下。", Page.muted, 12.sp, lineHeight = 18.sp)
                        CardField(phone, { phone = it }, "+86 138 0000 0000", keyboard = KeyboardType.Phone)
                        Wide(if (busy) null else "发验证码", num.count { it.isDigit() } >= 7) {
                            go { if (st == "phone0") { hash = TgApp.send(num); phase = "code0" } else Td.phone(num) }
                        }
                    }
                    "code0" -> {
                        var c by remember { mutableStateOf("") }
                        Txt("第一个验证码在你的 Telegram 里（Telegram 官方发来的一串字母和数字），整串复制过来。", Page.muted, 12.sp, lineHeight = 18.sp)
                        CardField(c, { c = it.trim() }, "例如 aB3xYz9KlmN")
                        Wide(if (busy) null else "开通", c.length >= 4) {
                            go {
                                TgApp.login(num, hash, c)
                                phase = "making"
                                val k = try { TgApp.keys() } catch (e: TgManual) { phase = "manual"; throw e } catch (e: Exception) { phase = "code0"; throw e }
                                Td.keys(k.first, k.second)
                                phase = "td"
                                if (Td.up() == "phone") Td.phone(num)
                            }
                        }
                        Wide("换个号码", true, main = false) { phase = "phone0"; err = "" }
                    }
                    "manual" -> TgManualStep(onKeys = { i, h ->
                        Td.keys(i, h); err = ""; errCode = ""; phase = "td"
                        scope.launch { if (Td.up() == "phone") go { Td.phone(num) } }
                    })
                    "making" -> Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadRing(ClipWarm, 14.dp); Spacer(Modifier.width(8.dp)); Txt("正在开通…", Page.muted, 12.sp)
                    }
                    "code" -> {
                        var c by remember { mutableStateOf("") }
                        Txt("再输一次：这次的验证码发在${Td.codeVia}，${Td.codeLen} 位数字。", Page.muted, 12.sp, lineHeight = 18.sp)
                        CodeBoxes(c, Td.codeLen) { v -> c = v; if (v.length == Td.codeLen) go { Td.code(v) } }
                        Wide("换个号码", true, main = false) { go { Td.restart() } }
                    }
                    "password" -> {
                        var p by remember { mutableStateOf("") }
                        Txt("这个账号开了两步验证，输一下你设的密码" + (Td.pwdHint.takeIf { it.isNotBlank() }?.let { "（提示：$it）" } ?: "") + "。", Page.muted, 12.sp, lineHeight = 18.sp)
                        CardField(p, { p = it }, "两步验证密码", secret = true)
                        Wide(if (busy) null else "登录", p.isNotEmpty()) { go { Td.password(p) } }
                    }
                    "ready" -> Row(verticalAlignment = Alignment.CenterVertically) {
                        DrawnCheck(ClipWarm, 16.dp); Spacer(Modifier.width(6.dp))
                        Txt("已授权" + (Td.me.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), ClipWarm, 13.sp)
                    }
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadRing(ClipWarm, 14.dp); Spacer(Modifier.width(8.dp)); Txt("正在连 Telegram", Page.muted, 12.sp)
                    }
                }
            }
        }
        if (err.isNotEmpty()) Txt(err, ClipBad, 12.sp)
        // 原因码：真机截图时看得出卡在哪一步
        if (errCode.isNotEmpty()) Txt(errCode, Page.muted.copy(alpha = .45f), 10.5.sp)
    }
}

/** 整行按钮；text = null 时转圈（处理中）。 */
@Composable
internal fun Wide(text: String?, enabled: Boolean, main: Boolean = true, onClick: () -> Unit) {
    PressSurface(
        onClick, Modifier.fillMaxWidth().height(38.dp), RoundedCornerShape(11.dp), enabled = enabled && text != null,
        fill = if (main) ClipWarm else veil(.08f), pressedFill = if (main) ClipWarm.copy(alpha = .85f) else veil(.14f),
        label = text ?: "处理中",
    ) {
        Box(Modifier.alpha(if (enabled || text == null) 1f else .45f)) {
            if (text == null) LoadRing(if (main) Skin.onAccent else Page.ink, 14.dp)
            else Txt(text, if (main) Skin.onAccent else Page.ink, 13.5.sp)
        }
    }
}

/** 验证码格子：一个看不见的输入框管键盘，格子跟着填，填到的那格轻轻放大。 */
@Composable
private fun CodeBoxes(v: String, n: Int, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BasicTextField(
        v, { t -> onChange(t.filter { it.isDigit() }.take(n)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        decorationBox = { _ ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(n) { i ->
                    val on = i < v.length
                    val s by animateFloatAsState(if (on) 1.04f else 1f, tween(180, easing = Curves.standard), label = "code")
                    Box(Modifier.weight(1f).height(46.dp).graphicsLayer { scaleX = s; scaleY = s }
                        .background(if (on) ClipWarm.copy(alpha = .16f) else veil(.06f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        if (on) Txt(v[i].toString(), Page.ink, 20.sp)
                        else if (i == v.length) Box(Modifier.align(Alignment.BottomCenter).width(14.dp).height(2.dp).background(ClipWarm))
                    }
                }
            }
        },
    )
    Spacer(Modifier.size(0.dp))
}
