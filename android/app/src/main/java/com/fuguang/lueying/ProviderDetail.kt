package com.fuguang.lueying

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val plug = Ic("M9 3v5M15 3v5M6.5 8h11v3a5.5 5.5 0 0 1-11 0ZM12 16.5V21")
private val eye = Ic("M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Z", circles = listOf(floatArrayOf(12f, 12f, 3f)))
private val eyeOff = Ic("M3 3l18 18M10.6 5.1A10 10 0 0 1 12 5c6 0 10 7 10 7a17 17 0 0 1-3.2 3.9M6.6 6.6C3.9 8.3 2 12 2 12s4 7 10 7c1.6 0 3-.4 4.3-1.1M9.9 9.9a3 3 0 0 0 4.2 4.2")

/** 卡片里的某一家：标题 + 配置 / 模型 两个标签（色块滑过去）。 */
@Composable
fun ProviderDetailPane(state: ProviderCardState, store: AssistantStore, onPicked: () -> Unit) {
    val p = provider(state.shown)
    Column(Modifier.fillMaxSize()) {
        CardHeader(store.config(p.id).name, onBack = { state.back(store) })
        DetailTabs(state.tab) { state.setTab(store, it) }
        Crossfade(state.tab, Modifier.weight(1f), animationSpec = tween(180, easing = Curves.standard), label = "tab") { t ->
            if (t == 0) ConfigPane(state, store, p) else ModelsPane(store, p, onPicked)
        }
    }
}

@Composable
private fun DetailTabs(tab: Int, onTab: (Int) -> Unit) {
    val x by animateFloatAsState(tab.toFloat(), tween(320, easing = Curves.standard), label = "slab")
    BoxWithConstraints(
        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp).fillMaxWidth().height(42.dp)
            .background(CardWell, RoundedCornerShape(13.dp)).padding(3.dp),
    ) {
        val half = maxWidth / 2
        Box(Modifier.fillMaxHeight().width(half).graphicsLayer { translationX = x * half.toPx() }.background(veil(.08f), RoundedCornerShape(10.dp)))
        Row(Modifier.fillMaxSize()) {
            listOf("配置", "模型").forEachIndexed { i, t ->
                Pressable({ onTab(i) }, Modifier.weight(1f).fillMaxHeight(), label = t) { _ ->
                    Txt(t, if (tab == i) Page.ink else Page.muted, 14.sp, spacing = ls(4f, 14f))
                }
            }
        }
    }
}

private enum class Probe { Idle, Busy, Ok, Bad }
private enum class SaveLook { Clean, Dirty, Done }

@Composable
private fun ConfigPane(state: ProviderCardState, store: AssistantStore, p: Provider) {
    val d = state.draft ?: return
    val saved = store.config(p.id)
    val scope = rememberCoroutineScope()
    var showKey by remember { mutableStateOf(false) }
    var probe by remember(p.id) { mutableStateOf(Probe.Idle) }
    var shake by remember { mutableIntStateOf(0) }
    var result by remember(p.id) { mutableStateOf<Pair<Boolean, String>?>(null) }
    var done by remember { mutableStateOf(false) }
    val look = when { done -> SaveLook.Done; state.dirty() -> SaveLook.Dirty; else -> SaveLook.Clean }

    // 打开余额开关、且已填密钥时，查一次余额
    LaunchedEffect(p.id, saved.key, saved.balance) {
        if (p.hasBalance && saved.balance && saved.configured) AssistantApi.balance(p, saved).onSuccess { store.balances[p.id] = it }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Label("名称", first = true)
            CardField(d.name, { state.draft = d.copy(name = it) }, p.name)
            Label("API Key")
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardField(d.key, { state.draft = d.copy(key = it.trim()) }, "sk-…", Modifier.weight(1f), secret = !showKey, keyboard = KeyboardType.Password)
                PressSurface({ showKey = !showKey }, Modifier.padding(start = 6.dp).size(40.dp), shape = CircleShape, fill = Color.Transparent, pressScale = .9f, label = if (showKey) "隐藏密钥" else "显示密钥") {
                    SvgIcon(if (showKey) eye else eyeOff, Page.ink, 20.dp)
                }
            }
            Label("API Base Url")
            CardField(d.base, { state.draft = d.copy(base = it.trim()) }, p.base, keyboard = KeyboardType.Uri)
            Label("API 路径")
            CardField(d.path, { state.draft = d.copy(path = it.trim()) }, p.path, keyboard = KeyboardType.Uri)
            Spacer(Modifier.height(8.dp))
            ToggleRow("是否启用", d.enabled) { state.draft = d.copy(enabled = it) }
            if (p.hasBalance) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(veil(.06f)))
                ToggleRow("获取账户余额", d.balance) { state.draft = d.copy(balance = it) }
                if (d.balance && d.configured) store.balances[p.id]?.let { Txt(it, Page.muted, 12.sp, Modifier.padding(start = 4.dp, bottom = 8.dp)) }
            }
        }
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton(
                probe, when (probe) { Probe.Ok -> BtnTone.Ok; Probe.Bad -> BtnTone.Bad; else -> BtnTone.Plain }, "测试连接",
                onClick = {
                    probe = Probe.Busy; result = null
                    val cfg = state.draft ?: d
                    scope.launch {
                        AssistantApi.models(p, cfg)
                            .onSuccess { result = true to "连接成功，可用 ${it.size} 个模型" }
                            .onFailure {
                                val k = cfg.key.trim()
                                result = false to (
                                    if (k.isEmpty()) "先填上 API Key 再测试"
                                    else if (p.id == "deepseek" && !k.startsWith("sk-")) "密钥应以 sk- 开头，好像少了这几个字符"
                                    else it.message ?: "连接失败"
                                )
                            }
                        val ok = result?.first == true
                        if (ok && p.hasBalance && cfg.balance) AssistantApi.balance(p, cfg).onSuccess { store.balances[p.id] = it }
                        probe = if (ok) Probe.Ok else Probe.Bad
                        if (!ok) shake++
                        delay(1600)
                        probe = Probe.Idle
                    }
                },
                modifier = Modifier.weight(1f), busy = probe != Probe.Idle, shake = shake,
            ) { s, ink ->
                when (s) {
                    Probe.Idle -> BtnLabel("测试连接", ink) { SvgIcon(plug, ink, 17.dp) }
                    Probe.Busy -> BtnLabel("正在测试…", ink) { LoadRing(ink) }
                    Probe.Ok -> BtnLabel("连接成功", ink) { DrawnCheck(ink) }
                    Probe.Bad -> BtnLabel("连接失败", ink)
                }
            }
            CardButton(
                look, when (look) { SaveLook.Dirty -> BtnTone.Primary; SaveLook.Done -> BtnTone.Ok; SaveLook.Clean -> BtnTone.Quiet }, "保存",
                onClick = {
                    if (look == SaveLook.Dirty && state.commit(store)) {
                        done = true
                        scope.launch { delay(1500); done = false }
                    }
                },
                modifier = Modifier.weight(1f),
            ) { s, ink ->
                when (s) {
                    SaveLook.Dirty -> BtnLabel("保存", ink)
                    SaveLook.Done -> BtnLabel("已保存", ink) { DrawnCheck(ink) }
                    SaveLook.Clean -> BtnLabel("已保存", ink)
                }
            }
        }
        result?.let { (ok, msg) -> Txt(msg, if (ok) Page.accent else CardRose, 12.5.sp, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)) }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun Label(text: String, first: Boolean = false) =
    Txt(text, Page.muted, 12.5.sp, Modifier.padding(start = 4.dp, top = if (first) 2.dp else 12.dp, bottom = 6.dp))

@Composable
private fun ToggleRow(text: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(text, Page.ink, 14.sp, Modifier.weight(1f))
        CardSwitch(on, text, onChange)
    }
}
