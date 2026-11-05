package com.fuguang.lueying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject

/*
 * Amor（第 396 版，原型 https://claude.ai/artifact/NhrBDwXdLU1o3pqd7bwJVy 第 2 版）：
 * 「我的 › 功能」里一行，开关直接在这一行右边；点左边进去是 α、β、γ 三个分身，点一个是它的提示词（默认空）+ 右上角模型（默认跟着聊天模型）。
 * 这一版只有设置。分身真的上场干活（团队后台跑、进度卡、零调度、审查）按《Amor 多分身协作机制设计方案》另做，
 * 原型 https://claude.ai/artifact/DQqyv4AAxQkVig2FrNCJs1 第 3 版。界面上不写说明字（用户说的）。
 */

/** 一个分身的设置：模型空 = 跟着聊天模型；提示词默认空。 */
class AmorRole(pid: String = "", model: String = "", prompt: String = "") {
    var pid by mutableStateOf(pid)
    var model by mutableStateOf(model)
    var prompt by mutableStateOf(prompt)
}

object Amor {
    private const val KEY = "amor"
    private var prefs: Prefs? = null
    private var loaded = false

    val NAMES = listOf("α", "β", "γ")

    var on by mutableStateOf(true)
    val roles = mutableStateMapOf<String, AmorRole>().apply { NAMES.forEach { put(it, AmorRole()) } }

    fun role(name: String): AmorRole = roles.getOrPut(name) { AmorRole() }

    fun init(p: Prefs) {
        prefs = p
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(p.string(KEY) ?: return@runCatching)
            on = o.optBoolean("on", true)
            val r = o.optJSONObject("r") ?: JSONObject()
            NAMES.forEach { n -> r.optJSONObject(n)?.let { x -> roles[n] = AmorRole(x.optString("pid"), x.optString("model"), x.optString("prompt")) } }
        }
    }

    fun save() {
        val r = JSONObject()
        NAMES.forEach { n -> val x = role(n); r.put(n, JSONObject().put("pid", x.pid).put("model", x.model).put("prompt", x.prompt)) }
        prefs?.putString(KEY, JSONObject().put("on", on).put("r", r).toString())
    }

    /** 自己挑了模型、而且那家配好了。 */
    fun own(store: AssistantStore, r: AmorRole) = r.pid.isNotEmpty() && r.model.isNotBlank() && store.config(r.pid).configured

    /** 列表里那行小字：用哪个模型（写了提示词再加一句）。 */
    fun sub(store: AssistantStore, r: AmorRole): String =
        (if (own(store, r)) r.model else "跟着聊天模型") + (if (r.prompt.isNotBlank()) " · 写了提示词" else "")
}

private val chevron = Ic("M9.5 6l6 6-6 6")
private val back = Ic("M14.5 6l-6 6 6 6")

/** 一个希腊字母当图标（和功能页其它行一样的 38 方块）。 */
@Composable
private fun Greek(letter: String, size: Int = 19) {
    Txt(letter, Skin.accent, size.sp, family = Serif, maxLines = 1)
}

/** 功能页里那一行：点左边进去，右边一道细竖线 + 开关；关掉左边淡下去，小字换「已停用」。 */
@Composable
internal fun AmorRow(onOpen: () -> Unit) {
    val t by animateFloatAsState(if (Amor.on) 1f else 0f, tween(300, easing = Curves.standard), label = "amorOn")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBlock), verticalAlignment = Alignment.CenterVertically) {
        PressSurface(onOpen, Modifier.weight(1f), shape = RoundedCornerShape(16.dp), fill = Color.Transparent, pressedFill = veil(.05f), pressScale = .985f, label = "Amor") {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 10.dp).graphicsLayer { alpha = .5f + .5f * t },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(38.dp).background(Skin.accent.copy(alpha = .1f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { Greek("α") }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Txt("Amor", Page.ink, 15.sp, maxLines = 1)
                    Spacer(Modifier.height(3.dp))
                    SwapText(if (Amor.on) "分身 α β γ" else "已停用", Page.muted, 12.sp)
                }
            }
        }
        Box(Modifier.width(1.dp).height(30.dp).background(veil(.08f)))
        Box(Modifier.padding(start = 12.dp, end = 14.dp)) {
            CardSwitch(Amor.on, "Amor") { Amor.on = it; Amor.save() }
        }
    }
}

/** 点进去：α、β、γ 三行拼成一组；关着时淡下去、点不了。 */
@Composable
internal fun AmorPage(store: AssistantStore, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val t by animateFloatAsState(if (Amor.on) 1f else .4f, tween(300, easing = Curves.standard), label = "amorList")
    Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        CardPageHead("Amor", onBack = onBack)
        Column(Modifier.graphicsLayer { alpha = t }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Amor.NAMES.forEachIndexed { i, n ->
                val shape = when (i) { 0 -> RoundedCornerShape(16.dp, 16.dp, 6.dp, 6.dp); Amor.NAMES.lastIndex -> RoundedCornerShape(6.dp, 6.dp, 16.dp, 16.dp); else -> RoundedCornerShape(6.dp) }
                PressSurface({ onOpen(n) }, Modifier.fillMaxWidth(), shape = shape, pressScale = .985f, enabled = Amor.on, label = n) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).background(Skin.accent.copy(alpha = .1f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { Greek(n) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(n, Page.ink, 15.sp, family = Serif, maxLines = 1)
                            Spacer(Modifier.height(3.dp))
                            SwapText(Amor.sub(store, Amor.role(n)), Page.muted, 12.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        SvgIcon(chevron, Page.muted.copy(alpha = .6f), 15.dp, stroke = 1.8f)
                    }
                }
            }
        }
    }
}

/** 一个分身：标题 + 右上角模型胶囊（默认跟着聊天模型），下面空的提示词框，停手 0.6 秒自动存。 */
@Composable
internal fun AmorRolePage(store: AssistantStore, name: String, onBack: () -> Unit) {
    val r = Amor.role(name)
    var draft by remember(name) { mutableStateOf(r.prompt) }
    var pick by remember { mutableStateOf(false) }
    fun commit() { if (draft != r.prompt) { r.prompt = draft; Amor.save() } }
    LaunchedEffect(draft) { delay(600); commit() }
    DisposableEffect(name) { onDispose { commit() } }
    val own = Amor.own(store, r)
    Box(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(end = 40.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                PressSurface({ commit(); onBack() }, Modifier.size(40.dp), shape = CircleShape, fill = Color.Transparent, pressScale = .9f, label = "返回") {
                    SvgIcon(back, Page.ink, 20.dp, stroke = 1.6f, modifier = Modifier.align(Alignment.Center))
                }
                Spacer(Modifier.width(6.dp))
                Txt(name, Page.ink, 19.sp, Modifier.weight(1f), family = Serif, maxLines = 1)
                ModelChip(if (own) r.pid else store.activeProvider, if (own) r.model else "跟着聊天模型", pick) { pick = true }
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = TextStyle(fontFamily = Sans, color = Page.ink, fontSize = 14.5.sp, lineHeight = 25.sp),
                cursorBrush = SolidColor(Page.accent),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(bottom = 12.dp),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize().background(Skin.well(.24f), RoundedCornerShape(20.dp)).padding(18.dp)) { inner() }
                },
            )
        }
        ModelSheet(
            pick, store, "跟着聊天模型", store.activeProvider, store.config(store.activeProvider).name + " / " + store.activeModel,
            own, r.pid, r.model, { id, m -> r.pid = id; r.model = m; Amor.save() },
        ) { pick = false }
    }
}
