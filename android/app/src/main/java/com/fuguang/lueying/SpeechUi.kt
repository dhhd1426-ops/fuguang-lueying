package com.fuguang.lueying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* 「我的 → 语音」和「语音 API」两页，聊天里消息下面的朗读按钮，自动朗读的钩子。原型 67dnU3x6Adhf8QeCfheXar。 */

private val chev = Ic("M9.5 6l6 6-6 6")
private val eyeIc = Ic("M2 12s3.6-6.5 10-6.5S22 12 22 12s-3.6 6.5-10 6.5S2 12 2 12z", circles = listOf(floatArrayOf(12f, 12f, 3f)))
private val eyeOff = Ic("M3 3l18 18M10.6 5.6A10 10 0 0 1 12 5.5c6.4 0 10 6.5 10 6.5a17 17 0 0 1-3 3.6M6.6 6.6C3.9 8.3 2 12 2 12s3.6 6.5 10 6.5c1.6 0 3-.4 4.3-1M9.9 9.9a3 3 0 0 0 4.2 4.2")
private val refresh = Ic("M20 12a8 8 0 1 1-2.3-5.6M20 4v4h-4")
private val playIc = Ic("M8 5.5v13l11-6.5z")
private val stopIc = Ic("M7.5 7.5h9v9h-9z")
private val SPEEDS = listOf(.8f, 1f, 1.25f, 1.5f)

private fun sampleText() = Speech.sample.replace("{asst}", Memory.active.name).replace("{name}", Memory.userName.trim().ifEmpty { "你" })

/** 三根跳动的竖条：在读。[wait] 时慢一点、矮一点（还在合成）。 */
@Composable
internal fun SoundBars(color: Color, size: androidx.compose.ui.unit.Dp, wait: Boolean = false) {
    val t = rememberInfiniteTransition(label = "bars")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (wait) 1400 else 900), RepeatMode.Restart), label = "p")
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val gap = w * .22f
        for (i in 0..2) {
            val ph = (p + i / 3f) % 1f
            val k = .5f - .5f * kotlin.math.cos(ph * 2 * Math.PI).toFloat()
            val lh = h * (if (wait) .25f + .25f * k else .3f + .6f * k)
            val x = w / 2 + (i - 1) * gap
            drawLine(color, Offset(x, h / 2 - lh / 2), Offset(x, h / 2 + lh / 2), 2.2.dp.toPx(), StrokeCap.Round)
        }
    }
}

/** 消息下面的朗读按钮：喇叭 → 读的时候变成跳动竖条 + 计时，再点停。 */
@Composable
internal fun SpeakPill(key: String, text: String) {
    val on = Speech.playing == key
    val wait = Speech.loading == key
    val bg by animateColorAsState(if (on || wait) Skin.accent.copy(alpha = .14f) else Color.Transparent, tween(220, easing = Curves.standard), label = "speakBg")
    val ink by animateColorAsState(if (on || wait) Skin.workIcon else Page.muted, tween(220), label = "speakInk")
    var secs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(on) { while (on) { secs = (System.currentTimeMillis() - Speech.since) / 1000; delay(250) }; secs = 0 }
    var err by remember { mutableStateOf("") }
    Row(Modifier.offset(x = (-6).dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        PressSurface(
            { err = ""; Speech.toggle(key, text) { e -> if (e != null) err = e } },
            fill = bg, pressedFill = CardBlockPress, pressScale = .9f, label = if (on || wait) "停止朗读" else "朗读",
        ) {
            Row(Modifier.height(30.dp).padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Crossfade(on || wait, animationSpec = tween(180), label = "speakIc") { live ->
                    if (live) SoundBars(ink, 17.dp, wait) else SvgIcon(Speech.speaker, ink, 17.dp, stroke = 1.6f)
                }
                AnimatedVisibility(
                    on || wait,
                    enter = expandHorizontally(tween(320, easing = Curves.decel)) + fadeIn(tween(200)),
                    exit = shrinkHorizontally(tween(220, easing = Curves.accel)) + fadeOut(tween(140)),
                ) {
                    Txt(if (wait) "合成中" else "%d:%02d".format(secs / 60, secs % 60), ink, 12.sp, Modifier.padding(start = 6.dp), maxLines = 1)
                }
            }
        }
        AnimatedVisibility(err.isNotEmpty(), enter = fadeIn(tween(180)), exit = fadeOut(tween(140))) {
            Txt(err, Skin.danger, 12.sp, Modifier.padding(start = 6.dp), maxLines = 1)
        }
    }
}

/**
 * 自动朗读：边写边读。她写完一句就送去合成，不等整段写完；写完后把剩下的补上，
 * 按钮从 live 换成那条消息。你发下一条时停掉还在读的。
 */
@Composable
internal fun SpeechHooks(chat: ChatState) {
    LaunchedEffect(chat) {
        var was = false
        var fed = 0
        var said = ""
        var ch: kotlinx.coroutines.channels.SendChannel<String>? = null
        snapshotFlow { chat.busy to chat.partial }.collect { (busy, part) ->
            if (busy && !was) {
                Speech.stopMessages()
                fed = 0; said = ""
                // 语音对话里朗读没配好也要出声（Speech.synth 会改用手机自带的声音）；读不出来在语音页说一声
                ch = if (Talk.open || (Speech.auto && Speech.ready)) Speech.begin("live") { err -> if (err != null && Talk.open) Talk.say("朗读失败：$err") } else null
            }
            val out = ch
            if (busy && out != null) {
                val t = Speech.clean(part, live = true)
                val (parts, end) = Speech.cut(t, fed)
                if (end > fed) { parts.forEach { out.trySend(it) }; said = t.substring(0, end); fed = end }
            }
            if (!busy && was) {
                val m = chat.messages.lastOrNull()?.takeIf { it.role == "assistant" && it.text.isNotBlank() }
                if (out != null) {
                    if (m != null && !m.interrupted) {
                        val t = Speech.clean(m.text)
                        val rest = when { fed == 0 -> t; t.startsWith(said) -> t.substring(said.length); else -> "" }
                        Speech.cut(rest.trimEnd() + "\n", 0).first.forEach { out.trySend(it) }
                    }
                    out.close()
                    if (m != null) Speech.rekey("live", "msg:${m.at}")
                }
                ch = null
            }
            was = busy
        }
    }
}

/* ---------- 「我的 → 语音」 ---------- */

@Composable
internal fun SpeechPage(onBack: () -> Unit, onApi: () -> Unit) {
    val scope = rememberCoroutineScope()
    val c = Speech.conf
    val who = Memory.active.name
    var custom by remember { mutableStateOf("") }
    var vstat by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    // 离开这页，试听停下（消息朗读不管）
    DisposableEffect(Unit) { onDispose { if (!Speech.playing.startsWith("msg:") && !Speech.loading.startsWith("msg:")) Speech.stop() } }
    Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        CardPageHead("语音", "朗读 · TTS", onBack = onBack)
        Txt("接上一个语音合成（TTS）服务，$who 的回答就能用你挑的声音读出来。", Page.muted, 13.sp, Modifier.padding(horizontal = 8.dp), lineHeight = 21.sp)

        Label("语音 API", "Connection")
        Go(Speech.plug, Speech.prov.name + if (c.key.isBlank()) " · 未填 API Key" else "", c.model.ifEmpty { "还没选模型" }, onApi)

        Label("音色", "Voice")
        Block {
            if (!Speech.ready) {
                Txt("先在上面的「语音 API」里填好 API Key 和模型。", Page.muted, 12.5.sp, lineHeight = 20.sp)
                return@Block
            }
            Txt("点一个音色试听，同时设为 $who 的声音。再点一下停。", Page.muted, 12.5.sp, lineHeight = 20.sp)
            val list = Speech.voices()
            if (list.isEmpty()) Txt("这个模型没有内置的音色表。点「获取音色」从平台读，或者在下面直接填音色 ID。", Page.muted, 12.5.sp, Modifier.padding(top = 8.dp), lineHeight = 20.sp)
            list.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { v ->
                        VoiceChip(v, Modifier.weight(1f)) {
                            val k = "v:${v.id}"
                            if (Speech.playing == k || Speech.loading == k) Speech.stop()
                            else {
                                err = ""; c.voice = v.id; Speech.save()
                                Speech.play(k, sampleText(), v.id) { e -> if (e != null) err = e }
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Txt("音色 ID", Page.muted, 12.sp, Modifier.padding(start = 2.dp, top = 14.dp, bottom = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardField(custom, { custom = it }, c.voice.ifEmpty { "平台里的音色 ID，比如克隆音色" }, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Btn("使用", custom.isNotBlank()) {
                    val id = custom.trim()
                    c.voices = listOf(Speech.Voice(id, id, "自己填的")) + c.voices.filter { it.id != id }
                    c.voice = id; custom = ""; Speech.save()
                }
            }
            Row(Modifier.padding(top = 12.dp)) {
                CardButton(fetching, BtnTone.Plain, "获取音色", {
                    fetching = true; vstat = null
                    scope.launch { vstat = Speech.fetchVoices(); fetching = false }
                }, busy = fetching) { b, ink ->
                    BtnLabel(if (b) "获取中…" else "获取音色", ink) { if (b) LoadRing(ink, 15.dp) else SvgIcon(refresh, ink, 16.dp, stroke = 1.6f) }
                }
            }
            Status(vstat?.second.orEmpty(), vstat?.first != false)
            Status(err, false)
            if (err.isNotEmpty()) Trace()
        }

        Label("朗读", "Read Aloud")
        Toggle("自动朗读 $who 的回答", "写完就开始读；点消息下面的喇叭停", Speech.auto) { Speech.auto = it; Speech.save() }
        Toggle("消息下面显示朗读按钮", "点一下读这一条，再点停", Speech.btn) { Speech.btn = it; Speech.save() }
        Toggle("鲸鱼娘跟着声音动嘴", "按读出来的音量开合，停顿时闭上", Speech.lips) { Speech.lips = it; Speech.save() }
        Toggle(
            "鲸鱼娘冒泡的话也读出来",
            when {
                !Speech.whaleVoice -> "她头上冒的那句话（「别戳啦」这些）也用这个声音说"
                Speech.warming -> "正在把她的台词存到手机上：${Speech.warmHave} / ${Speech.warmAll}"
                Speech.warmAll > 0 && Speech.warmHave == Speech.warmAll -> "${Speech.warmAll} 句台词已经存在手机上，说的时候不用等"
                Speech.warmAll > 0 -> "存好了 ${Speech.warmHave} / ${Speech.warmAll} 句，缺的说到时现合成"
                else -> "她的台词会先合成好存在手机上，说的时候不用等"
            },
            Speech.whaleVoice,
        ) { Speech.whaleVoice = it; Speech.save(); if (it) Speech.warm() }
        Block {
            Txt("语速", Page.ink, 15.sp)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SPEEDS.forEach { s -> Chip(if (s == 1f) "1×" else "$s×", Speech.speed == s) { Speech.speed = s; Speech.save() } }
            }
            Txt("试听的句子", Page.muted, 12.sp, Modifier.padding(start = 2.dp, top = 14.dp, bottom = 6.dp))
            CardField(Speech.sample, { Speech.sample = it.take(80); Speech.save() }, "试听时读的一句话")
            Txt("{asst} 会换成助理的名字，{name} 换成你的名字。", Page.muted, 11.5.sp, Modifier.padding(start = 2.dp, top = 6.dp))
            val on = Speech.playing == "sample" || Speech.loading == "sample"
            Row(Modifier.padding(top = 12.dp)) {
                PressSurface(
                    { if (on) Speech.stop() else { err = ""; Speech.play("sample", sampleText()) { e -> if (e != null) err = e } } },
                    enabled = Speech.ready, fill = Page.ink.copy(alpha = if (Speech.ready) .9f else .3f), pressedFill = Page.ink.copy(alpha = .75f), label = if (on) "停止" else "试听",
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Crossfade(on, animationSpec = tween(180), label = "sampleIc") { p -> SvgIcon(if (p) stopIc else playIc, Skin.onInk, 15.dp, stroke = 1.8f) }
                        Spacer(Modifier.width(6.dp))
                        Txt(if (on) "停止" else "试听", Skin.onInk, 13.5.sp, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/* ---------- 「语音 → 语音 API」 ---------- */

@Composable
internal fun SpeechApiPage(onBack: () -> Unit) {
    val c = Speech.conf
    val p = Speech.prov
    val who = Memory.active.name
    var showKey by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf("") }
    var test by remember { mutableIntStateOf(0) }   // 0 没测 1 测试中 2 成功 3 失败
    var msg by remember { mutableStateOf("") }
    var shake by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) { onDispose { if (Speech.playing == "test" || Speech.loading == "test") Speech.stop() } }
    fun changed() { c.form = ""; Speech.save(); test = 0; msg = "" }
    Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        CardPageHead("语音 API", onBack = onBack)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Speech.PROVS.forEach { x -> Chip(x.name, Speech.pid == x.id) { Speech.pid = x.id; Speech.save(); test = 0; msg = ""; pick = false } }
        }
        Txt(p.note, Page.muted, 12.5.sp, Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), lineHeight = 20.sp)
        Block {
            Field("API 密钥")
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardField(c.key, { c.key = it.trim(); changed() }, "粘贴 API Key", Modifier.weight(1f), secret = !showKey)
                PressSurface({ showKey = !showKey }, Modifier.padding(start = 4.dp).size(38.dp), shape = CircleShape, fill = Color.Transparent, pressScale = .9f, label = if (showKey) "隐藏 Key" else "显示 Key") {
                    Crossfade(showKey, Modifier.align(Alignment.Center), tween(160), label = "eye") { s -> SvgIcon(if (s) eyeOff else eyeIc, Page.muted, 18.dp, stroke = 1.6f) }
                }
            }
            Field("API 地址")
            CardField(c.base, { c.base = it.trim(); changed() }, "https://…/v1")
            Field("接口路径")
            CardField(c.path, { c.path = it.trim(); changed() }, "/audio/speech")
            Txt(if (c.base.contains("volink")) "一般不用改。Volink 会自动走它自己的 /tts/speech。" else "一般不用改，OpenAI 兼容的语音接口都是 /audio/speech。", Page.muted, 11.5.sp, Modifier.padding(start = 2.dp, top = 6.dp))
            Field("模型")
            PressSurface({ pick = !pick }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), fill = CardWell, pressScale = .985f, label = "选择模型") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).background(Skin.accent.copy(alpha = .1f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) { SvgIcon(Speech.wave, Skin.accent, 16.dp, stroke = 1.6f) }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Txt(c.model.ifEmpty { "选择模型" }, Page.ink, 14.sp, maxLines = 1)
                        Txt(p.models.firstOrNull { it.first == c.model }?.second ?: if (c.model.isEmpty()) "从下面挑，或者直接输入模型 ID" else "自己填的模型", Page.muted, 11.5.sp, Modifier.padding(top = 2.dp), maxLines = 1)
                    }
                    val r by androidx.compose.animation.core.animateFloatAsState(if (pick) 90f else 0f, tween(250, easing = Curves.standard), label = "chev")
                    SvgIcon(chev, Page.muted.copy(alpha = .6f), 15.dp, stroke = 1.8f, modifier = Modifier.rotate(r))
                }
            }
            AnimatedVisibility(pick, enter = expandVertically(tween(320, easing = Curves.decel)) + fadeIn(tween(200)), exit = shrinkVertically(tween(220, easing = Curves.accel)) + fadeOut(tween(140))) {
                Column(Modifier.padding(top = 6.dp)) {
                    p.models.forEach { (id, d) ->
                        PressSurface({ c.model = id; changed(); pick = false }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), fill = Color.Transparent, pressScale = .985f, label = id) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Txt(id, Page.ink, 14.sp, maxLines = 1)
                                    Txt(d, Page.muted, 11.5.sp, Modifier.padding(top = 2.dp), maxLines = 1)
                                }
                                if (c.model == id) DrawnCheck(Skin.workIcon, 18.dp)
                            }
                        }
                    }
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        CardField(manual, { manual = it }, "直接输入模型 ID", Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Btn("使用", manual.isNotBlank()) { c.model = manual.trim(); manual = ""; changed(); pick = false }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                CardButton(test, when (test) { 2 -> BtnTone.Ok; 3 -> BtnTone.Bad; else -> BtnTone.Plain }, "测试连接", {
                    test = 1; msg = ""
                    Speech.play("test", "你好，我是$who，连接成功啦。") { e ->
                        if (e == null) { test = 2; msg = "合成成功，正在放出来。" + (Speech.trace.lastOrNull()?.let { "（$it）" } ?: "") }
                        else { test = 3; msg = e; shake++ }
                    }
                }, busy = test == 1, shake = shake) { s, ink ->
                    when (s) {
                        1 -> BtnLabel("合成中…", ink) { LoadRing(ink, 15.dp) }
                        2 -> BtnLabel("连接成功 · 再测一次", ink) { DrawnCheck(ink, 16.dp) }
                        3 -> BtnLabel("没连上 · 点我重试", ink)
                        else -> BtnLabel("测试连接", ink) { SvgIcon(Speech.plug, ink, 16.dp, stroke = 1.6f) }
                    }
                }
                Spacer(Modifier.weight(1f))
                Btn("完成", true, onBack)
            }
            Status(msg, test != 3)
            if (test == 3) Trace()
        }
        Txt("测试连接会用当前模型和音色合成一句话并放出来，能听到就是配好了。Key 只存在这台手机上。", Page.muted, 12.sp, Modifier.padding(horizontal = 8.dp, vertical = 12.dp), lineHeight = 20.sp)
    }
}

/* ---------- 小部件 ---------- */

@Composable
private fun Label(zh: String, en: String) {
    Row(Modifier.padding(start = 8.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Txt(zh, Page.muted, 11.sp, spacing = ls(2.75f, 11f))
        Txt(en, Skin.accent.copy(alpha = .85f), 11.5.sp, Modifier.padding(start = 6.dp), family = Serif)
    }
}

@Composable
private fun Field(text: String) = Txt(text, Page.muted, 12.sp, Modifier.padding(start = 2.dp, top = 12.dp, bottom = 6.dp))

@Composable
private fun Block(content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 6.dp).fillMaxWidth().background(CardBlock, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) { content() }
}

@Composable
private fun Toggle(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(top = 6.dp).fillMaxWidth().background(CardBlock, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(title, Page.ink, 15.sp)
            Txt(sub, Page.muted, 12.sp, Modifier.padding(top = 3.dp), maxLines = 2)
        }
        Spacer(Modifier.width(10.dp))
        CardSwitch(on, title, onChange)
    }
}

@Composable
private fun Go(icon: Ic, title: String, sub: String, onClick: () -> Unit) {
    PressSurface(onClick, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), pressScale = .985f, label = title) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(Skin.accent.copy(alpha = .1f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { SvgIcon(icon, Skin.accent, 19.dp, stroke = 1.6f) }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Txt(title, Page.ink, 15.sp, maxLines = 1)
                Txt(sub, Page.muted, 12.sp, Modifier.padding(top = 3.dp), maxLines = 1)
            }
            SvgIcon(chev, Page.muted.copy(alpha = .6f), 15.dp, stroke = 1.8f)
        }
    }
}

@Composable
private fun VoiceChip(v: Speech.Voice, modifier: Modifier, onClick: () -> Unit) {
    val k = "v:${v.id}"
    val on = Speech.conf.voice == v.id
    val live = Speech.playing == k || Speech.loading == k
    val edge by animateColorAsState(if (on) Skin.accent else Color.Transparent, tween(180, easing = Curves.standard), label = "vEdge")
    val fill by animateColorAsState(if (live) Skin.accent.copy(alpha = .14f) else CardWell, tween(220), label = "vFill")
    PressSurface(onClick, modifier.height(52.dp), shape = RoundedCornerShape(14.dp), fill = fill, pressedFill = CardBlockPress, pressScale = .97f, edge = edge, label = v.name) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Crossfade(live, animationSpec = tween(180), label = "vIc") { l ->
                if (l) SoundBars(Skin.workIcon, 18.dp, Speech.loading == k) else SvgIcon(Speech.speaker, Skin.workIcon, 18.dp, stroke = 1.6f)
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Txt(v.name, Page.ink, 14.5.sp, maxLines = 1)
                if (v.desc.isNotEmpty()) Txt(v.desc, Page.muted, 11.sp, maxLines = 1)
            }
            if (on) DrawnCheck(Skin.workIcon, 18.dp)
        }
    }
}

@Composable
private fun Btn(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    PressSurface(onClick, enabled = enabled, fill = Page.ink.copy(alpha = if (enabled) .9f else .3f), pressedFill = Page.ink.copy(alpha = .75f), label = text) {
        Txt(text, Skin.onInk, 13.5.sp, Modifier.padding(horizontal = 16.dp, vertical = 9.dp), maxLines = 1)
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    PressSurface(onClick, fill = if (on) Page.ink.copy(alpha = .9f) else CardWell, pressedFill = if (on) Page.ink.copy(alpha = .75f) else CardBlockPress, label = text) {
        Txt(text, if (on) Skin.onInk else Page.ink, 13.sp, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), maxLines = 1)
    }
}

@Composable
private fun Status(text: String, ok: Boolean) {
    AnimatedVisibility(text.isNotEmpty(), enter = expandVertically(tween(320, easing = Curves.decel)) + fadeIn(tween(180)), exit = shrinkVertically(tween(220, easing = Curves.accel)) + fadeOut(tween(140))) {
        Txt(text, if (ok) Page.muted else Skin.danger, 12.5.sp, Modifier.padding(top = 10.dp), lineHeight = 19.sp)
    }
}

/** 试了哪些写法（出错时截图给开发者）。 */
@Composable
private fun Trace() {
    val t = Speech.trace
    if (t.isEmpty()) return
    Column(Modifier.padding(top = 8.dp).fillMaxWidth().background(CardWell, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Txt("请求记录", Page.muted, 11.sp)
        t.forEach { Txt(it, Page.muted, 11.sp, Modifier.padding(top = 3.dp), lineHeight = 16.sp) }
    }
}
