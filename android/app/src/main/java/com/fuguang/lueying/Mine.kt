package com.fuguang.lueying

import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow

private object MineIcons {
    val back = Ic("M14.5 6l-6 6 6 6")
    val next = Ic("M9.5 6l6 6-6 6")
    val search = Ic("M20 20l-4.2-4.2", circles = listOf(floatArrayOf(11f, 11f, 6.5f)))
    val eye = Ic("M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Z", circles = listOf(floatArrayOf(12f, 12f, 3f)))
    val eyeOff = Ic("M3 3l18 18M10.6 5.1A10 10 0 0 1 12 5c6 0 10 7 10 7a17 17 0 0 1-3.2 3.9M6.6 6.6C3.9 8.3 2 12 2 12s4 7 10 7c1.6 0 3-.4 4.3-1.1M9.9 9.9a3 3 0 0 0 4.2 4.2")
    val plug = Ic("M9 3v5M15 3v5M6.5 8h11v3a5.5 5.5 0 0 1-11 0ZM12 16.5V21")
    val reset = Ic("M20 12a8 8 0 1 1-2.3-5.6M20 4.5v4h-4")
    val box = Ic("M12 3l8 4.5v9L12 21l-8-4.5v-9ZM4 7.5l8 4.5 8-4.5M12 12v9")
    val plus = Ic("M12 5v14M5 12h14")
}

private val rose = css("#c98a86")
private val glassFill get() = Skin.tint(.42f)

/** 「我的」卡片点进来的二级页：assistant / mcp → … 由 App 持有，返回键逐级退回，退到 mine 就回聊天。 */
class MineNav {
    var screen by mutableStateOf("mine")
    /** 正在看的那位助理，和她的哪个标签页。 */
    var persona by mutableStateOf("")
    var tab by mutableStateOf(0)
    /** MCP 页停在哪个标签：0 市场、1 已连接。 */
    var mcpTab by mutableStateOf(0)
    /** 从周月年那份信纸点进下面一份：返回时回到那份信纸。 */
    var letterFrom by mutableStateOf("")
    fun open(id: String, t: Int) { persona = id; tab = t; screen = "persona" }
    fun back(): Boolean {
        screen = when {
            screen.startsWith("provider/") -> "providers"
            screen.startsWith("search/") -> "search"
            screen.startsWith("gallery/") -> "gallery"
            screen == "search" || screen == "skills" || screen == "gallery" -> "features"
            screen.startsWith("skills/") -> "skills"
            screen.startsWith("letter/") -> letterFrom.ifEmpty { "memories" }.also { letterFrom = "" }
            screen == "memories" -> "persona"
            screen == "memmodel" -> if (persona.isNotEmpty() && Memory.get(persona) != null) "memories" else "mine"
            screen == "memprompt" -> "memmodel"
            screen == "memcatalog" -> "memories"
            screen == "digests" -> "persona"
            screen.startsWith("digest/") -> "digests"
            screen == "digestmodel" || screen == "digestprompt" -> "digests"
            screen == "features" -> "mine"
            screen == "theme" -> "mine"
            screen.startsWith("mcp/") -> "mcp"
            screen == "mcp" -> "mine"
            screen == "providers" -> if (persona.isNotEmpty() && Memory.get(persona) != null) "persona" else "assistant"
            screen == "persona" -> { persona = ""; "assistant" }
            screen == "assistant" -> "mine"
            else -> return false
        }
        return true
    }
}

/** 「我的」卡片（MineCard.kt）里的二级页。 */
@Composable
internal fun ColumnScope.MineSub(store: AssistantStore, screen: String, compact: Boolean, blur: Dp, nav: MineNav) {
    when {
        screen == "persona" -> Memory.get(nav.persona)?.let { PersonaScreen(store, it, compact, blur, nav) } ?: AssistantScreen(compact, blur, nav)
        screen == "providers" -> ProvidersScreen(store, compact, blur, nav)
        screen.startsWith("provider/") -> ProviderScreen(store, provider(screen.removePrefix("provider/")), compact, blur, nav)
        screen == "mcp" -> McpScreen(compact, blur, nav)
        screen.startsWith("mcp/") -> McpServerScreen(screen.removePrefix("mcp/"), compact, blur, nav)
        screen.startsWith("letter/") -> {
            val p = Memory.get(nav.persona)
            val m = p?.items?.firstOrNull { it.id == screen.removePrefix("letter/") }
            if (p != null && m != null) LetterScreen(p, m, onKid = { k -> nav.letterFrom = screen; nav.screen = "letter/${k.id}" }) { nav.back() } else LaunchedEffect(screen) { nav.back() }
        }
        screen == "memories" -> { val p = Memory.get(nav.persona); if (p != null) MemBookScreen(p, compact, nav) else LaunchedEffect(screen) { nav.back() } }
        screen == "memcatalog" -> { val p = Memory.get(nav.persona); if (p != null) MemCatalogScreen(p, compact, blur, nav) else LaunchedEffect(screen) { nav.back() } }
        screen == "digests" -> { val p = Memory.get(nav.persona); if (p != null) DigestsScreen(p, compact, nav) else LaunchedEffect(screen) { nav.back() } }
        screen.startsWith("digest/") -> {
            val c = Chats.current?.history?.get(screen.removePrefix("digest/"))
            if (c != null && c.gist.isNotBlank()) DigestScreen(c) { nav.back() } else LaunchedEffect(screen) { nav.back() }
        }
        screen == "digestmodel" -> DigestModelScreen(store, compact, blur, nav)
        screen == "digestprompt" -> DigestPromptScreen(compact, blur, nav)
        screen == "memmodel" -> MemModelScreen(store, compact, blur, nav)
        screen == "memprompt" -> MemPromptScreen(compact, blur, nav)
        else -> AssistantScreen(compact, blur, nav)
    }
}

/** 页头右边让出的位置：卡片右上角有一颗 ↗。 */
internal val LocalTitleEnd = androidx.compose.runtime.compositionLocalOf { 0.dp }

/* ---------- 通用小部件 ---------- */

@Composable
internal fun Title(text: String, compact: Boolean, onBack: (() -> Unit)?, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 22.dp, end = LocalTitleEnd.current), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Pressable(onBack, label = "返回") { pressed ->
                SvgIcon(MineIcons.back, if (pressed) Page.muted else Page.ink, 24.dp, modifier = Modifier.padding(end = 10.dp, top = 4.dp, bottom = 4.dp))
            }
        }
        val fs = if (compact) 24f else 28f
        Txt(text, Page.ink, fs.sp, Modifier.weight(1f), spacing = ls(4f, fs), family = Serif, maxLines = 1)
        trailing()
    }
}


@Composable
private fun Chip(text: String, fill: Color = Color.Transparent, ink: Color = Page.muted) {
    Txt(text, ink, 11.sp, Modifier.background(fill, RoundedCornerShape(50)).padding(horizontal = if (fill == Color.Transparent) 0.dp else 10.dp, vertical = 4.dp), maxLines = 1)
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    var focused by remember { mutableStateOf(false) }
    val ring by androidx.compose.animation.animateColorAsState(if (focused) Skin.accent.copy(alpha = .5f) else Skin.accent.copy(alpha = 0f), tween(200), label = "fieldRing")
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(fontFamily = Sans, color = Page.ink, fontSize = 15.sp),
        cursorBrush = SolidColor(Page.accent),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            // 第 381 版：去描边，凹下去的暗色块；正在输入时描一圈淡点缀色。trailing 放在框里右边（Key 的眼睛）
            Row(
                Modifier.fillMaxWidth().heightIn(min = 50.dp).background(wellFill, shape).border(1.5.dp, ring, shape)
                    .padding(start = 16.dp, end = if (trailing != null) 4.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                    if (value.isEmpty()) Txt(placeholder, Page.muted.copy(alpha = .45f), 15.sp, maxLines = 1)
                    inner()
                }
                trailing?.invoke()
            }
        },
    )
}

/**
 * 开关（第 375 版，原型 VDAUMyKCSFbcBhmTNZ4M4W 第 2 版）：滑块走 snappy 弹簧（k520，阻尼 0.745）；
 * 轨道、滑块颜色走 soft 弹簧（k180，阻尼 0.894，Oklab 插值不发灰）；按住时滑块拉长 6dp、轨道缩到 0.96，松手弹回。
 * pad 是开关四周额外的可点范围。
 * 第 379 版：给了 [said]（打开时的话 to 关掉时的话）的开关，切过去后轨道自己往左伸长，把这句写在轨道里（打开写在滑块左边，
 * 关掉写在滑块右边），停 1.4 秒再缩回去。伸长走 gentle 弹簧（k260，阻尼 .806），格子大小不变，不推挤旁边的字。
 */
@Composable
private fun Switch(on: Boolean, label: String, pad: PaddingValues = PaddingValues(0.dp), said: Pair<String, String>? = null, onChange: (Boolean) -> Unit) {
    var line by remember { mutableStateOf("") }
    var showing by remember { mutableStateOf(false) }
    var seen by remember { mutableStateOf(on) }
    LaunchedEffect(on) {
        if (said == null || on == seen) return@LaunchedEffect
        seen = on
        line = if (on) said.first else said.second
        showing = true
        kotlinx.coroutines.delay(1400)
        showing = false
    }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val lineW = remember(line) {
        if (line.isEmpty()) 0.dp
        else with(density) { measurer.measure(line, androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp, fontFamily = Sans, fontWeight = FontWeight.Medium)).size.width.toDp() }
    }
    val extra by androidx.compose.animation.core.animateDpAsState(
        if (showing) lineW + 16.dp else 0.dp, androidx.compose.animation.core.spring(dampingRatio = .806f, stiffness = 260f), label = "swExtra",
    )
    Pressable({ onChange(!on) }, label = label) { pr ->
        val snappy = androidx.compose.animation.core.spring<Float>(dampingRatio = .745f, stiffness = 520f)
        val soft = androidx.compose.animation.core.spring<Color>(dampingRatio = .894f, stiffness = 180f)
        val x by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, snappy, label = "swX")
        val press by androidx.compose.animation.core.animateFloatAsState(if (pr) 1f else 0f, snappy, label = "swPress")
        val track by androidx.compose.animation.animateColorAsState(if (on) Skin.accent.copy(alpha = .85f) else veil(.12f), soft, label = "swTrack")
        val knob by androidx.compose.animation.animateColorAsState(if (on) Skin.onAccent else Page.ink.copy(alpha = .7f), soft, label = "swKnob")
        val full = (lineW + 16.dp).value.coerceAtLeast(1f)
        val reveal = (extra.value / full).coerceIn(0f, 1f)
        Box(Modifier.padding(pad)) {
            // 格子还是 48×28，伸长的部分往左长出去
            Box(Modifier.width(48.dp).height(28.dp), contentAlignment = Alignment.CenterEnd) {
                Box(
                    Modifier.wrapContentWidth(Alignment.End, unbounded = true).width(48.dp + extra).height(28.dp)
                        .graphicsLayer { val k = 1f - .04f * press; scaleX = k; scaleY = k; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, .5f) }
                        // 伸长时先垫一层不透明的弹层色：关着时轨道是半透明的，不垫会透出底下的字
                        .background(Skin.pop.copy(alpha = (reveal * 3f).coerceAtMost(1f)), RoundedCornerShape(50))
                        .background(track, RoundedCornerShape(50))
                        .padding(3.dp),
                ) {
                    if (line.isNotEmpty() && reveal > 0f) Txt(
                        line, if (on) Skin.onAccent else Page.ink.copy(alpha = .85f), 12.5.sp,
                        Modifier.align(if (on) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 7.dp)
                            .graphicsLayer { alpha = reveal * reveal; translationX = (1f - reveal) * (if (on) -6f else 6f) * density.density },
                        maxLines = 1, weight = FontWeight.Medium,
                    )
                    val w = 22.dp + 6.dp * press
                    Box(Modifier.offset(x = (42.dp + extra - w) * x).width(w).height(22.dp).background(knob, RoundedCornerShape(50)))
                }
            }
        }
    }
}

@Composable
private fun PillButton(text: String, icon: Ic?, primary: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Pressable(onClick, modifier, enabled = enabled, label = text) { pressed ->
        val fill = when {
            !enabled -> veil(.05f)
            primary -> Skin.accent.copy(alpha = if (pressed) .7f else .9f)
            else -> veil(if (pressed) .12f else .06f)
        }
        val ink = when { !enabled -> Page.muted.copy(alpha = .5f); primary -> Skin.onAccent; else -> Page.ink }
        Row(
            Modifier.background(fill, RoundedCornerShape(50)).border(1.dp, if (primary && enabled) Color.Transparent else Page.line, RoundedCornerShape(50))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) { SvgIcon(icon, ink, 18.dp); Spacer(Modifier.width(8.dp)) }
            Txt(text, ink, 14.sp, maxLines = 1)
        }
    }
}

@Composable
private fun Divider() { Box(Modifier.fillMaxWidth().height(1.dp).background(veil(.08f))) }

/* ---------- 助理（方案 4：先选人，再进她的标签页） ---------- */

private val warm: Color get() = Skin.accent

/** 头像：宋体首字，杏色。 */
@Composable
internal fun Initial(name: String, size: Dp) {
    Box(Modifier.size(size).background(Skin.accent.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
        Txt(name.trim().take(1).ifEmpty { "?" }, warm, (size.value * .42f).sp, family = Serif)
    }
}

/** 助理列表：色块卡片、长按编辑 / 排序 / 勾选 / 删除，见 PersonaList.kt（第 381 版）。 */
@Composable
private fun ColumnScope.AssistantScreen(compact: Boolean, blur: Dp, nav: MineNav) = AssistantList(compact, blur, nav)

/** 分段标签：底下一块色块，跟着手指在标签之间滑。 */
@Composable
private fun SegTabs(tabs: List<String>, pager: androidx.compose.foundation.pager.PagerState, blur: Dp, onPick: (Int) -> Unit) {
    var w by remember { mutableStateOf(0f) }
    Box(
        Modifier.fillMaxWidth().padding(bottom = 16.dp)
            .background(wellFill, RoundedCornerShape(16.dp))
            .padding(4.dp)
            .onSizeChanged { w = it.width.toFloat() },
    ) {
        val d = LocalDensity.current
        val pos = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, tabs.lastIndex.toFloat())
        val tw = w / tabs.size
        Box(
            Modifier.graphicsLayer { translationX = pos * tw }
                .width(with(d) { tw.toDp() }).height(38.dp)
                .background(veil(.10f), RoundedCornerShape(12.dp)),
        )
        Row(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { i, t ->
                val near = (1f - kotlin.math.abs(pos - i)).coerceIn(0f, 1f)
                Pressable({ onPick(i) }, Modifier.weight(1f), label = t) { _ ->
                    Box(Modifier.fillMaxWidth().height(38.dp), contentAlignment = Alignment.Center) {
                        Txt(t, androidx.compose.ui.graphics.lerp(Page.muted, Page.ink, near), 14.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PersonaScreen(store: AssistantStore, p: Persona, compact: Boolean, blur: Dp, nav: MineNav) {
    Title(p.name.ifBlank { "未命名" }, compact, onBack = { nav.back() })
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = nav.tab) { 3 }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.currentPage) { nav.tab = pager.currentPage }
    // 停下来时没对齐（第 347 版真机截图：记忆页整页往右偏了几像素，卡片右边被裁掉）就补一下对齐
    LaunchedEffect(pager) {
        androidx.compose.runtime.snapshotFlow { pager.isScrollInProgress }.collect { moving ->
            if (!moving && kotlin.math.abs(pager.currentPageOffsetFraction) > .001f) pager.animateScrollToPage(pager.currentPage)
        }
    }
    SegTabs(listOf("基础", "系统提示词", "记忆"), pager, blur) { scope.launch { pager.animateScrollToPage(it) } }
    var undo by remember { mutableStateOf<Pair<Mem, Int>?>(null) }
    LaunchedEffect(undo) { if (undo != null) { kotlinx.coroutines.delay(4000); undo = null } }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        androidx.compose.foundation.pager.HorizontalPager(
            pager, Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Top,
            beyondViewportPageCount = 1,
            pageSpacing = 16.dp,
        ) { page ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                when (page) {
                    0 -> BasicTab(store, p, blur, nav)
                    1 -> PromptTab(p, blur)
                    else -> MemoryTab(p, blur, onDigests = { nav.screen = "digests" }) { nav.screen = "memories" }
                }
            }
        }
        // 删除后几秒内可以撤销
        androidx.compose.animation.AnimatedVisibility(
            undo != null,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
        ) {
            Row(
                Modifier.widthIn(max = 520.dp).fillMaxWidth().background(Skin.toast, RoundedCornerShape(16.dp))
                    .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt("忘掉了", Skin.toastInk, 13.5.sp, Modifier.weight(1f))
                Pressable({ undo?.let { (m, i) -> Memory.restore(p, m, i) }; undo = null }, label = "撤销") { pr ->
                    Txt("撤销", warm, 13.5.sp, Modifier.background(if (pr) Skin.toastInk.copy(alpha = .1f) else Color.Transparent, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp))
                }
            }
        }
        MemMenuLayer(p) { m, i -> undo = m to i }
    }
}

/* 基础（方案一）：名片（助理名称 / 用户名称，自动保存）、模型、回答的手感 */
@Composable
private fun ColumnScope.BasicTab(store: AssistantStore, p: Persona, blur: Dp, nav: MineNav) {
    Column(Modifier.spot("name")) { NameCard(p, blur) }
    Spacer(Modifier.height(10.dp))
    Pressable({ nav.screen = "providers" }, Modifier.fillMaxWidth().spot("model"), label = "模型与供应商") { pressed ->
        val f by androidx.compose.animation.animateColorAsState(if (pressed) blockPress else blockFill, tween(if (pressed) 80 else 220), label = "modelRow")
            Row(
                Modifier.fillMaxWidth().block(RoundedCornerShape(20.dp), blur, f).padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt("模型与供应商", Page.ink, 15.sp, Modifier.weight(1f))
                Txt("${provider(store.activeProvider).name} · ${store.activeModel}", Page.muted, 13.sp, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                SvgIcon(MineIcons.next, Page.muted, 18.dp)
            }
    }
    Txt("回 答 的 手 感", Page.muted, 11.sp, Modifier.padding(start = 4.dp, top = 24.dp, bottom = 10.dp), spacing = ls(1f, 11f))
    // 第 381 版：三段拆成三块色块，中间 3dp 缝代替分割线；头尾两块外角 20、里角 8
    val top = RoundedCornerShape(20.dp, 20.dp, 8.dp, 8.dp)
    val mid = RoundedCornerShape(8.dp)
    val bot = RoundedCornerShape(8.dp, 8.dp, 20.dp, 20.dp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // 温度
        Column(Modifier.fillMaxWidth().spot("temp").block(top, blur).padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Txt("温度", Page.ink, 15.sp, Modifier.weight(1f))
                Txt(String.format(java.util.Locale.US, "%.2f", p.temp), warm, 18.sp, family = Serif)
            }
            Spacer(Modifier.height(8.dp))
            FeelSlider(p.temp, 0f, 2f, .05f, onDone = { Memory.save() }) { p.temp = it }
            Ends("稳重、可预期", "随性、跳脱")
        }
        // Top P
        Column(Modifier.fillMaxWidth().spot("topp").block(mid, blur).padding(horizontal = 18.dp, vertical = 16.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("Top P", Page.ink, 15.sp, Modifier.weight(1f))
                ChoiceChip("用供应商默认", !p.topOn) { p.topOn = false; Memory.save() }
                Spacer(Modifier.width(8.dp))
                ChoiceChip("自己设", p.topOn) { p.topOn = true; Memory.save() }
            }
            if (p.topOn) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { FeelSlider(p.top, .1f, 1f, .05f, onDone = { Memory.save() }) { p.top = it } }
                    Spacer(Modifier.width(12.dp))
                    Txt(String.format(java.util.Locale.US, "%.2f", p.top), warm, 18.sp, Modifier.widthIn(min = 44.dp), family = Serif, align = androidx.compose.ui.text.style.TextAlign.End)
                }
                Ends("只挑最稳的词", "放开挑")
            }
        }
        // 上下文消息限制：10–200 条，最右边是「不限」
        Column(Modifier.fillMaxWidth().spot("limit").block(bot, blur).padding(horizontal = 18.dp, vertical = 16.dp)) {
            val v = if (p.limit <= 0) 210f else p.limit.toFloat()
            Row(verticalAlignment = Alignment.Bottom) {
                Txt("上下文消息限制", Page.ink, 15.sp, Modifier.weight(1f))
                Txt(if (p.limit <= 0) "不限" else "${p.limit} 条", warm, 18.sp, family = Serif)
            }
            Spacer(Modifier.height(8.dp))
            FeelSlider(v, 10f, 210f, 10f, ticks = 5, onDone = { Memory.save() }) { p.limit = if (it >= 210f) 0 else it.toInt() }
        }
    }
    Spacer(Modifier.height(22.dp))
    if (p.id == Memory.activeId) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).background(warm, CircleShape))
            Spacer(Modifier.width(8.dp))
            Txt("${p.name} 正在陪你聊天", Page.muted, 13.sp)
        }
    } else {
        PillButton("让 ${p.name} 陪你聊天", null, primary = true, modifier = Modifier.fillMaxWidth()) { Memory.use(p) }
    }
}

/** 名片：她和你并排（头像 + 名字，中间一道心电，见 NamePair.kt），宋体大字直接改；停手半秒自动保存，一道杏色细线从左划到右，右上角淡出「已保存」。 */
@Composable
private fun NameCard(p: Persona, blur: Dp) {
    var a by remember(p.id) { mutableStateOf(p.name) }
    var u by remember { mutableStateOf(Memory.userName) }
    val sweep = remember { androidx.compose.animation.core.Animatable(0f) }
    val tag = remember { androidx.compose.animation.core.Animatable(0f) }
    var first by remember(p.id) { mutableStateOf(true) }
    LaunchedEffect(a, u) {
        if (first) { first = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(600)
        if (a.isNotBlank()) p.name = a.trim().take(16)
        Memory.userName = u.trim().take(16)
        Memory.save()
        kotlinx.coroutines.coroutineScope {
            launch { sweep.snapTo(0f); sweep.animateTo(1f, tween(620, easing = androidx.compose.animation.core.CubicBezierEasing(.3f, .8f, .3f, 1f))); sweep.animateTo(2f, tween(280)) }
            launch { tag.snapTo(0f); tag.animateTo(1f, tween(240)); kotlinx.coroutines.delay(900); tag.animateTo(0f, tween(400)) }
        }
    }
    Box(Modifier.fillMaxWidth().block(RoundedCornerShape(20.dp), blur)) {
        NamePair(p, a, u, { a = it.take(16) }, { u = it.take(16) })
        // 保存：杏色细线从左划到右，然后淡掉
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp)
                .graphicsLayer {
                    val s = sweep.value
                    scaleX = s.coerceIn(0f, 1f); transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, .5f)
                    alpha = if (s <= 0f) 0f else if (s <= 1f) 1f else (2f - s).coerceIn(0f, 1f)
                }
                .background(warm),
        )
        Txt("已保存", warm, 11.sp, Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 14.dp).graphicsLayer { alpha = tag.value; translationY = (1f - tag.value) * 6f })
    }
}

@Composable
private fun NameField(label: String, value: String, hint: String, modifier: Modifier, onChange: (String) -> Unit) {
    Column(modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
        Txt(label, Page.muted, 11.5.sp, spacing = ls(1.5f, 11.5f))
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = Page.ink, fontSize = 22.sp, fontFamily = Serif, letterSpacing = 1.sp),
            cursorBrush = SolidColor(warm),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Txt(hint, Page.muted.copy(alpha = .5f), if (hint.length > 6) 14.sp else 22.sp, family = Serif, maxLines = 1)
                    inner()
                }
            },
        )
    }
}

@Composable
private fun Ends(left: String, right: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Txt(left, Page.muted, 11.5.sp, Modifier.weight(1f))
        Txt(right, Page.muted, 11.5.sp)
    }
}

@Composable
internal fun ChoiceChip(text: String, on: Boolean, onClick: () -> Unit) {
    Pressable(onClick, label = text) { pr ->
        Txt(
            text, if (on) warm else Page.muted, 12.sp,
            Modifier.background(if (on) Skin.accent.copy(alpha = .16f) else veil(if (pr) .1f else .06f), RoundedCornerShape(50)).padding(horizontal = 11.dp, vertical = 6.dp),
            maxLines = 1,
        )
    }
}

/** 滑杆：杏色走过的那段，白色圆钮带一圈淡杏色光晕；按住拖、点一下都行，按步长吸附。 */
@Composable
internal fun FeelSlider(value: Float, min: Float, max: Float, step: Float, ticks: Int = 0, onDone: () -> Unit, onChange: (Float) -> Unit) {
    var w by remember { mutableStateOf(1f) }
    fun at(x: Float): Float {
        val raw = min + (x / w).coerceIn(0f, 1f) * (max - min)
        return (Math.round((raw - min) / step) * step + min).coerceIn(min, max)
    }
    val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)
    var dragging by remember { mutableStateOf(false) }
    val glow by androidx.compose.animation.core.animateFloatAsState(if (dragging) 1f else 0f, tween(180), label = "glow")
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        Modifier.fillMaxWidth().height(30.dp)
            .onSizeChanged { w = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(min, max, step) { detectTapGestures { onChange(at(it.x)); onDone() } }
            .pointerInput(min, max, step) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false; onDone() },
                    onDragCancel = { dragging = false },
                ) { ch, _ ->
                    ch.consume()
                    val nv = at(ch.position.x)
                    if (nv != value) { onChange(nv); if (step >= 1f) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(veil(.1f), RoundedCornerShape(2.dp)))
        if (ticks > 1) Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            repeat(ticks) { Box(Modifier.width(1.dp).height(5.dp).background(veil(.16f))) }
        }
        Box(Modifier.fillMaxWidth(frac.coerceAtLeast(.001f)).height(3.dp).background(warm, RoundedCornerShape(2.dp)))
        val d = LocalDensity.current
        Box(
            Modifier.graphicsLayer { translationX = frac * w - with(d) { 9.dp.toPx() } }
                .size(18.dp)
                .drawBehind { drawCircle(warm.copy(alpha = .18f + .17f * glow), radius = size.minDimension / 2 + (4f + 4f * glow).dp.toPx()) }
                .background(Page.ink, CircleShape),
        )
    }
}

/* 提示词：她是谁，写在这里的就是她自己。改完点保存；没保存就离开，也替你存好。
 * 有没保存的改动时，下面一排左边露出 ↶ ↷（PromptUndo.kt）；一保存就收起、历史清掉。 */
@Composable
private fun ColumnScope.PromptTab(p: Persona, blur: Dp) {
    var field by remember(p.id) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(p.sys)) }
    val draft = field.text
    val undo = remember(p.id) { PromptUndo(p.sys) }
    fun put(s: String, cursor: Int = s.length) { field = androidx.compose.ui.text.input.TextFieldValue(s, androidx.compose.ui.text.TextRange(cursor)) }
    fun swap(s: String) { undo.replaced(field.text, s); put(s) }
    val latest = androidx.compose.runtime.rememberUpdatedState(draft)
    androidx.compose.runtime.DisposableEffect(p.id) {
        onDispose { if (latest.value != p.sys) { p.sys = latest.value; Memory.save() } }
    }
    BasicTextField(
        value = field,
        onValueChange = { undo.typed(field.text, it.text); field = it },
        textStyle = TextStyle(fontFamily = Sans, color = Page.ink, fontSize = 14.5.sp, lineHeight = 24.sp),
        cursorBrush = SolidColor(Page.accent),
        visualTransformation = rememberUndoFlash(undo, warm),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().spot("sys").heightIn(min = 280.dp).glass(RoundedCornerShape(20.dp), blur, fill = glassFill).padding(18.dp)) {
                if (draft.isEmpty()) Txt("用「你是…」写下 ${p.name} 本来的样子、怎么说话。留空就用默认的样子。", Page.muted.copy(alpha = .6f), 14.5.sp, lineHeight = 24.sp)
                inner()
            }
        },
    )
    Row(Modifier.fillMaxWidth().padding(start = 2.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            UndoPair(undo, draft != p.sys, warm, Skin.onAccent) { d -> undo.go(d, field.text)?.let { (s, c) -> put(s, c) } }
        }
        if (draft.trim() != Memory.DEFAULT_SYS) {
            Pressable({ swap(Memory.DEFAULT_SYS) }, Modifier.padding(end = 8.dp), label = "恢复默认") { pr ->
                Txt("恢复默认", Page.muted, 12.5.sp, Modifier.background(veil(if (pr) .1f else .05f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        SaveButton(dirty = draft != p.sys) { p.sys = draft; Memory.save(); undo.reset(draft) }
    }
    Txt("写在这里的，就是她自己。她的名字和你的称呼会先告诉她；文中可以写 {char}、{name}、{model}、{date}。", Page.muted, 12.5.sp, Modifier.padding(start = 6.dp, top = 8.dp), lineHeight = 20.sp)
    IdentityHint(draft) { swap(it) }
    Column(Modifier.spot("entries")) { EntriesSection(p, blur) }
}

/**
 * 保存：没改动时是暗的「已保存」，一改动亮成杏色「保存」。
 * 点下去先缩成一颗圆点，再展开成画出来的 ✓ 已保存，过一会儿安静下来。
 */
@Composable
private fun SaveButton(dirty: Boolean, onSave: () -> Unit) {
    var phase by remember { mutableStateOf(0) } // 0 平常，1 圆点，2 已保存 ✓
    val scope = rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val ease = androidx.compose.animation.core.CubicBezierEasing(.3f, .8f, .3f, 1f)
    val w by androidx.compose.animation.core.animateDpAsState(
        when { phase == 1 -> 34.dp; phase == 2 -> 96.dp; else -> 80.dp }, tween(300, easing = ease), label = "saveW",
    )
    val bg by androidx.compose.animation.animateColorAsState(
        when { phase == 1 -> warm.copy(alpha = .92f); phase == 2 -> warm.copy(alpha = .18f); dirty -> warm.copy(alpha = .92f); else -> Skin.accent.copy(alpha = .1f) },
        tween(300), label = "saveBg",
    )
    val tick by androidx.compose.animation.core.animateFloatAsState(if (phase == 2) 1f else 0f, tween(if (phase == 2) 380 else 0, delayMillis = if (phase == 2) 120 else 0), label = "tick")
    Box(
        Modifier.width(w).height(34.dp).background(bg, RoundedCornerShape(50))
            .pointerInput(dirty, phase) {
                detectTapGestures {
                    if (!dirty || phase != 0) return@detectTapGestures
                    onSave()
                    scope.launch {
                        phase = 1
                        kotlinx.coroutines.delay(300)
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        phase = 2
                        kotlinx.coroutines.delay(1600)
                        phase = 0
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (phase) {
            1 -> {}
            2 -> Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Canvas(Modifier.size(14.dp)) {
                    // 勾：先画短的一笔，再画长的一笔
                    val u = size.width / 14f
                    val a = androidx.compose.ui.geometry.Offset(3f * u, 7.5f * u)
                    val m = androidx.compose.ui.geometry.Offset(5.6f * u, 10.1f * u)
                    val e = androidx.compose.ui.geometry.Offset(11f * u, 4.6f * u)
                    val k = .32f
                    val st = 1.8f * u
                    if (tick > 0f) drawLine(warm, a, a + (m - a) * (tick / k).coerceAtMost(1f), st, androidx.compose.ui.graphics.StrokeCap.Round)
                    if (tick > k) drawLine(warm, m, m + (e - m) * ((tick - k) / (1f - k)), st, androidx.compose.ui.graphics.StrokeCap.Round)
                }
                Spacer(Modifier.width(6.dp))
                Txt("已保存", warm, 13.sp, maxLines = 1)
            }
            else -> Txt(if (dirty) "保存" else "已保存", if (dirty) Skin.onAccent else Page.muted, 13.sp, maxLines = 1)
        }
    }
}

@Composable
internal fun SectionLabel(text: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp, top = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(text, Page.muted, 11.sp, Modifier.weight(1f), spacing = ls(2.5f, 11f))
        trailing()
    }
}

private fun ago(t: Long): String {
    if (t <= 0) return ""
    val now = java.util.Calendar.getInstance()
    val c = java.util.Calendar.getInstance().apply { timeInMillis = t }
    val sameDay = now.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.DAY_OF_YEAR) == c.get(java.util.Calendar.DAY_OF_YEAR)
    if (sameDay) {
        val h = c.get(java.util.Calendar.HOUR_OF_DAY)
        return "今天" + when (h) { in 5..11 -> "早上"; in 12..13 -> "中午"; in 14..17 -> "下午"; in 18..23 -> "晚上"; else -> "凌晨" }
    }
    val days = ((System.currentTimeMillis() - t) / 86_400_000L).toInt()
    return if (days <= 1) "昨天" else java.text.SimpleDateFormat("M 月 d 日", java.util.Locale.CHINA).format(java.util.Date(t))
}

/* 记忆：相识摘要 + 全局记忆（关着只剩一行） */
/* ---------- 记忆：相识摘要里放「跨聊天回忆」，下面一个框装下她记得的全部（不再分本） ---------- */

/** 每条记忆底下那行小字的开头：这是哪一类。 */
internal fun kindZh(k: String) = MemTree.kindZh(k)

/* ---------- 记忆（第 345 版，原型 EzmAr4Lo7ucsmJG6FEV4as 第 9 版）：标签页里一个「记忆」入口 + 跨聊天回忆；点进去是折角卡片的记忆本 ---------- */

private val menuIc = Ic("M4 7h16M4 12h16M4 17h16")
private val modelIc = Ic("M7 5h10a3 3 0 0 1 3 3v8a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3ZM8.5 10h7M8.5 14h4.5")
private val listIc = Ic("M5 7h14M5 12h14M5 17h8")
private val infoIc = Ic("M12 11v5.5M12 7.6v.4", circles = listOf(floatArrayOf(12f, 12f, 9f)))

@Composable
private fun ColumnScope.MemoryTab(p: Persona, blur: Dp, onDigests: () -> Unit, onBook: () -> Unit) {
    val days = if (p.firstAt <= 0) 0 else ((System.currentTimeMillis() - p.firstAt) / 86_400_000L).toInt() + 1
    Txt(if (days == 0) "${p.name} 还在认识你" else "${p.name} 认识你 $days 天了", Page.ink, 19.sp, Modifier.padding(start = 4.dp, top = 2.dp, bottom = 16.dp), family = Serif, lineHeight = 30.sp)
    // 记忆入口：折角纸图标 + 记忆 + 一行小字；右边开关停用整个记忆（第 375 版）
    SwitchCard(
        p.noteOn, "记忆", "memory", blur, onBook, { pr -> MemIcon(pr) },
        listOfNotNull("聊过 ${p.chats} 次", "记得 ${p.items.size} 件".takeIf { p.items.isNotEmpty() }, ago(p.lastAt).takeIf { it.isNotEmpty() }?.let { "上次是$it" }).joinToString(" · "),
        "已停用 · 她不记也不翻记忆",
        "已打开" to "已停用",
    ) { p.noteOn = it; Memory.save() }
    Spacer(Modifier.height(12.dp))
    // 聊天摘要（第 363 版，原型 9wcvNyRWC8mbSBUexNC7FF 第 2 版）：每段聊天聊了什么，她翻以前的聊天时先看这些；右边开关停用（第 375 版）
    val convs = Chats.current?.history?.of(p.id).orEmpty().filter { c -> c.msgs.any { it.role == "user" } }
    SwitchCard(
        p.gistOn, "聊天摘要", "digests", blur, onDigests, { pr -> DigestIcon(pr) },
        if (convs.isEmpty()) "还没有聊天 · 她翻以前的聊天时看" else "${convs.size} 段聊天 · ${convs.count { it.gist.isNotBlank() }} 段有摘要 · 她翻以前的聊天时看",
        "已停用 · 不写摘要，翻聊天时也不看",
        "已打开" to "已停用",
    ) { p.gistOn = it; Memory.save() }
    Spacer(Modifier.height(12.dp))
    // 跨聊天回忆（不管记录，只管她能不能翻以前的聊天）；第 379 版起切过去后轨道伸长写「已打开 / 已关」
    Row(
        Modifier.fillMaxWidth().spot("cross").glass(RoundedCornerShape(20.dp), blur, fill = glassFill).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt("跨聊天回忆", Page.ink, 15.sp, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Txt("她能翻以前的聊天", Page.muted, 12.5.sp, maxLines = 1)
        }
        Switch(p.memOn, "跨聊天回忆", said = "已打开" to "已关") { p.memOn = it; Memory.save() }
    }
}

/**
 * 记忆页带开关的入口卡（第 375 版，原型 VDAUMyKCSFbcBhmTNZ4M4W 第 2 版）：左边点进详情，一道细竖线，右边开关。
 * 关掉时图标和字淡到 50%、小字换成停用说明——这几处由同一个值（soft 弹簧）驱动，小字旧的下移淡出、新的上移淡入。
 */
@Composable
private fun SwitchCard(
    on: Boolean, title: String, spotKey: String, blur: Dp, onOpen: () -> Unit, icon: @Composable (Boolean) -> Unit, sub: String, offSub: String,
    said: Pair<String, String>, onChange: (Boolean) -> Unit,
) {
    val t by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, androidx.compose.animation.core.spring(dampingRatio = .894f, stiffness = 180f), label = "cardOn")
    Row(
        Modifier.fillMaxWidth().spot(spotKey).glass(RoundedCornerShape(20.dp), blur, fill = glassFill).clip(RoundedCornerShape(20.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pressable(onOpen, Modifier.weight(1f), label = title) { pr ->
            Row(
                Modifier.fillMaxWidth().background(veil(if (pr) .06f else 0f)).padding(start = 18.dp, end = 10.dp, top = 16.dp, bottom = 16.dp)
                    .graphicsLayer { alpha = .5f + .5f * t.coerceIn(0f, 1f) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon(pr)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Txt(title, Page.ink, 16.sp, maxLines = 1)
                    Spacer(Modifier.height(3.dp))
                    Box {
                        val u = t.coerceIn(0f, 1f)
                        Txt(sub, Page.muted, 12.5.sp, Modifier.graphicsLayer { alpha = u; translationY = (1f - u) * 6.dp.toPx() }, maxLines = 2, lineHeight = 19.sp)
                        Txt(offSub, Page.muted, 12.5.sp, Modifier.graphicsLayer { alpha = 1f - u; translationY = u * -6.dp.toPx() }, maxLines = 2, lineHeight = 19.sp)
                    }
                }
            }
        }
        Box(Modifier.width(1.dp).height(32.dp).background(veil(.08f)))
        // 第 379 版：切过去后轨道往左伸长写「已打开 / 已停用」，详细的那句由下面小字交叉过渡带出来
        Switch(on, title, pad = PaddingValues(start = 14.dp, end = 16.dp, top = 20.dp, bottom = 20.dp), said = said, onChange = onChange)
    }
}

/**
 * 入口上的小图标（第 348 版，原型 XtuZuenNnBsQ6jr6b5krDt 第 5 版）：一张折角纸，放在点缀色的浅底小方块里。
 * 颜色全从主题取：方块和折角是点缀色（Skin.accent）淡几层，第一行是点缀色，下面两行是次要字色，外框是正文字色淡到 45%（深色主题 40%）、线宽 1.1。
 * 按下卡片纸微微翘起（往右上 0.6/1.6、转 3°，260ms 减速）。按 32 格画，放大到 40dp。
 */
@Composable
private fun MemIcon(pressed: Boolean) {
    val light = Skin.light
    val ac = Skin.accent
    val ink = Page.ink
    val muted = Page.muted
    val lift by androidx.compose.animation.core.animateFloatAsState(if (pressed) 1f else 0f, tween(260, easing = Curves.decel), label = "memLift")
    val page = remember { androidx.compose.ui.graphics.vector.PathParser().parsePathString("M9.5 4.5h10l6 6v15a2.5 2.5 0 0 1-2.5 2.5H9.5A2.5 2.5 0 0 1 7 25.5V7a2.5 2.5 0 0 1 2.5-2.5Z").toPath() }
    val fold = remember { androidx.compose.ui.graphics.vector.PathParser().parsePathString("M19.5 4.5v4a2 2 0 0 0 2 2h4Z").toPath() }
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(15.dp))
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(ac.copy(alpha = if (light) .22f else .2f), ac.copy(alpha = if (light) .34f else .1f)))),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            // 顶边一道高光
            drawLine(Color.White.copy(alpha = if (light) .7f else .08f), androidx.compose.ui.geometry.Offset(15.dp.toPx(), .5.dp.toPx()), androidx.compose.ui.geometry.Offset(size.width - 15.dp.toPx(), .5.dp.toPx()), 1.dp.toPx())
            val u = 40.dp.toPx() / 32f
            val ox = (size.width - 40.dp.toPx()) / 2f; val oy = (size.height - 40.dp.toPx()) / 2f
            val thin = androidx.compose.ui.graphics.drawscope.Stroke(1.1.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round)
            val edge = ink.copy(alpha = ink.alpha * (if (light) .45f else .4f))
            translate(ox, oy) {
                // 很淡的投影
                translate(0f, 1.dp.toPx()) { scale(u, u, androidx.compose.ui.geometry.Offset.Zero) { drawPath(page, Color.Black.copy(alpha = if (light) .06f else .22f)) } }
                withTransform({
                    translate(.6f * u * lift, -1.6f * u * lift)
                    rotate(3f * lift, androidx.compose.ui.geometry.Offset(16f * u, 28f * u))
                }) {
                    scale(u, u, androidx.compose.ui.geometry.Offset.Zero) {
                        drawPath(page, if (light) Color.White else Color.White.copy(alpha = .12f))
                        drawPath(fold, ac.copy(alpha = if (light) .4f else .5f))
                    }
                    // 线在放大之后画，线宽按 dp 算
                    val pg = android.graphics.Path(); page.asAndroidPath().let { pg.set(it) }
                    val m = android.graphics.Matrix().apply { setScale(u, u) }
                    pg.transform(m)
                    drawPath(pg.asComposePath(), edge, style = thin)
                    val fd = android.graphics.Path(fold.asAndroidPath()).apply { transform(m) }
                    drawPath(fd.asComposePath(), edge, style = thin)
                    fun ln(x1: Float, x2: Float, y: Float, c: Color, w: Float) =
                        drawLine(c, androidx.compose.ui.geometry.Offset(x1 * u, y * u), androidx.compose.ui.geometry.Offset(x2 * u, y * u), w.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    ln(11f, 20f, 15f, ac, 2f)
                    val lc = muted.copy(alpha = muted.alpha * (if (light) .7f else .5f))
                    ln(11f, 21.5f, 19.2f, lc, 1.5f)
                    ln(11f, 17f, 23.2f, lc, 1.5f)
                }
            }
        }
    }
}

/** 记忆本：★ 人和规矩在上，下面按天、周、月、年；卡片宽屏四列、手机两列。右上 ☰ 悬浮菜单。 */
@Composable
private fun ColumnScope.MemBookScreen(p: Persona, compact: Boolean, nav: MineNav) {
    var menu by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf<Pair<Mem, Int>?>(null) }
    var dropping by remember { mutableStateOf<Pact?>(null) }
    LaunchedEffect(undo) { if (undo != null) { kotlinx.coroutines.delay(4000); undo = null } }
    Title("记忆", compact, onBack = { nav.back() }) {
        Pressable({ menu = !menu }, label = "菜单") { pr ->
            SvgIcon(menuIc, if (pr) Page.muted else Page.ink, 24.dp, modifier = Modifier.padding(6.dp))
        }
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val cols = when { maxWidth >= 640.dp -> 4; maxWidth >= 460.dp -> 3; else -> 2 }
            val secs = MemTree.sections(p)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                val pacts = Pacts.of(p.id)
                if (secs.isEmpty() && pacts.isEmpty()) Txt(
                    "我们聊过的、你说起你自己的、你喜欢我怎么陪你的，我都会记在这儿。", Page.ink.copy(alpha = .7f), 14.sp,
                    Modifier.fillMaxWidth().padding(top = 40.dp, start = 12.dp, end = 12.dp), lineHeight = 24.sp, align = androidx.compose.ui.text.style.TextAlign.Center,
                )
                var idx = 0
                // 约定（Pacts.kt）：到点她会主动找你；放在最上面，长按取消
                if (pacts.isNotEmpty()) {
                    Row(Modifier.padding(start = 2.dp, top = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
                        Txt("约定", Page.ink, 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
                        Spacer(Modifier.width(10.dp))
                        Txt("到点她会主动找你", Page.muted, 12.sp, maxLines = 1)
                    }
                    Box(Modifier.padding(bottom = 16.dp)) { KeepCard() }
                    pacts.chunked(cols).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            row.forEach { k -> PactNote(k, idx++, Modifier.weight(1f)) { dropping = k } }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                secs.forEach { s ->
                    Row(Modifier.padding(start = 2.dp, top = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
                        Txt(s.name, Page.ink, 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
                        Spacer(Modifier.width(10.dp))
                        Txt(s.sub, Page.muted, 12.sp, maxLines = 1)
                    }
                    s.items.chunked(cols).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            row.forEach { m ->
                                val i = idx++
                                NoteCard(m, MemTree.kids(p, m).size, i, Modifier.weight(1f)) { nav.letterFrom = ""; nav.screen = "letter/${m.id}" }
                            }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        // 删除后几秒内可以撤销
        androidx.compose.animation.AnimatedVisibility(
            undo != null,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
        ) {
            Row(
                Modifier.widthIn(max = 520.dp).fillMaxWidth().background(Skin.toast, RoundedCornerShape(16.dp))
                    .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt("忘掉了", Skin.toastInk, 13.5.sp, Modifier.weight(1f))
                Pressable({ undo?.let { (m, i) -> Memory.restore(p, m, i) }; undo = null }, label = "撤销") { pr ->
                    Txt("撤销", warm, 13.5.sp, Modifier.background(if (pr) Skin.toastInk.copy(alpha = .1f) else Color.Transparent, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp))
                }
            }
        }
        MemMenuLayer(p) { m, i -> undo = m to i }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val shownDrop = remember { mutableStateOf<Pact?>(null) }
        if (dropping != null) shownDrop.value = dropping
        Sheet(dropping != null, { dropping = null }) {
            val k = shownDrop.value
            Txt("取消这条约定？", Page.ink, 16.sp)
            Spacer(Modifier.height(6.dp))
            Txt(k?.let { Pacts.rule(it) + " · " + it.title }.orEmpty() + "，取消后到点她不会再来找你。", Page.muted, 13.sp, align = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 20.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetButton("留着", false, Modifier.weight(1f)) { dropping = null }
                SheetButton("取消约定", true, Modifier.weight(1f), red = true) { k?.let { Pacts.drop(ctx, it.id) }; dropping = null }
            }
        }
        FloatMenu(menu, { menu = false }, listOf(
            Triple(modelIc, "记忆模型") { menu = false; nav.screen = "memmodel" },
            Triple(listIc, "她带着的目录") { menu = false; nav.screen = "memcatalog" },
        ))
    }
}

/** ☰ 点开的悬浮菜单：从右上角长出来（220ms 减速），点空白处收回（140ms 加速）。样式照 ⋮ 对话菜单。 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.FloatMenu(open: Boolean, onDismiss: () -> Unit, items: List<Triple<Ic, String, () -> Unit>>) {
    val t by androidx.compose.animation.core.animateFloatAsState(
        if (open) 1f else 0f, tween(if (open) 220 else 140, easing = if (open) Curves.decel else Curves.accel), label = "floatMenu",
    )
    androidx.activity.compose.BackHandler(open) { onDismiss() }
    if (t <= 0f) return
    if (open) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } })
    Column(
        Modifier.align(Alignment.TopEnd)
            .graphicsLayer {
                alpha = t
                val s = .9f + .1f * t; scaleX = s; scaleY = s
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
            }
            .widthIn(min = 190.dp, max = 260.dp)
            .shadow(18.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black.copy(alpha = .35f), spotColor = Color.Black.copy(alpha = .35f))
            .background(Skin.pop, RoundedCornerShape(16.dp))
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(vertical = 6.dp),
    ) {
        items.forEach { (ic, label, go) -> MenuItem(ic, label, red = false, filled = false) { if (open) go() } }
    }
}

/** 她带着的目录：每次聊天她身上带的就是这些（MemTree.catalog），用来看记忆跑得对不对。 */
@Composable
private fun ColumnScope.MemCatalogScreen(p: Persona, compact: Boolean, blur: Dp, nav: MineNav) {
    Title("她带着的目录", compact, onBack = { nav.back() })
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Txt("每次聊天她身上只带这些。你问到和某条直接有关的事，她才去翻那条的正文；不相关就不翻，也不主动提起。", Page.muted, 13.sp, Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp), lineHeight = 21.sp)
        androidx.compose.foundation.text.selection.SelectionContainer {
            Txt(
                MemTree.catalog(p).ifBlank { "（还没有记下什么）" }, Page.ink, 13.5.sp,
                Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), blur, fill = glassFill).padding(18.dp), lineHeight = 23.sp,
            )
        }
    }
}

/** 记忆模型：处理模型、启用思考；下面「记忆系统提示词」点进去改。 */
@Composable
private fun ColumnScope.MemModelScreen(store: AssistantStore, compact: Boolean, blur: Dp, nav: MineNav) {
    var info by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(false) }
    Title("记忆模型", compact, onBack = { nav.back() })
    Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 2.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("记忆模型", Page.muted, 14.sp, Modifier.weight(1f))
                Pressable({ info = true }, label = "说明") { pr -> SvgIcon(infoIc, if (pr) Page.ink else Page.muted, 22.dp, modifier = Modifier.padding(4.dp)) }
            }
            Column(Modifier.fillMaxWidth().spot("memmodel").glass(RoundedCornerShape(20.dp), blur, fill = glassFill)) {
                Pressable({ pick = true }, Modifier.fillMaxWidth(), label = "处理模型") { pr ->
                    Row(Modifier.fillMaxWidth().background(veil(if (pr) .06f else 0f)).padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Txt("处理模型", Page.ink, 16.sp, maxLines = 1)
                            Spacer(Modifier.height(4.dp))
                            Txt(MemModel.label(store), Page.muted, 13.sp, maxLines = 1)
                        }
                        SvgIcon(MineIcons.next, Page.muted, 18.dp)
                    }
                }
                Box(Modifier.padding(horizontal = 20.dp)) { Divider() }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Txt("启用思考", Page.ink, 16.sp, maxLines = 1)
                        Spacer(Modifier.height(4.dp))
                        Txt("在模型支持时允许记忆模型使用推理", Page.muted, 13.sp, lineHeight = 19.sp)
                    }
                    Switch(MemModel.think, "启用思考") { MemModel.think = it; MemModel.save() }
                }
            }
            Txt("记忆系统提示词", Page.muted, 14.sp, Modifier.padding(start = 6.dp, top = 26.dp, bottom = 10.dp))
            Pressable({ nav.screen = "memprompt" }, Modifier.fillMaxWidth().spot("memprompt"), label = "记忆系统提示词") { pr ->
                Row(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), blur, fill = glassFill).background(veil(if (pr) .06f else 0f)).padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Txt("记下和整理", Page.ink, 16.sp, maxLines = 1)
                            Spacer(Modifier.width(8.dp))
                            Chip(if (MemModel.custom) "改过" else "默认", if (MemModel.custom) Skin.accent.copy(alpha = .9f) else veil(.08f), if (MemModel.custom) Skin.onAccent else Page.muted)
                        }
                        Spacer(Modifier.height(5.dp))
                        Txt(MemModel.text.replace("\n", " "), Page.muted, 13.sp, lineHeight = 20.sp, maxLines = 2)
                    }
                    SvgIcon(MineIcons.next, Page.muted, 18.dp)
                }
            }
        }
        // 选模型：在视觉中心弹出
        Sheet(pick, onDismiss = { pick = false }) {
            Txt("处理模型", Page.ink, 16.sp)
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                ModelOption("跟着默认", "能看图的帮手，没有就用聊天的模型", MemModel.pid.isEmpty()) { MemModel.use("", ""); pick = false }
                store.order.filter { store.config(it).configured }.forEach { id ->
                    val cfg = store.config(id)
                    cfg.models.ifEmpty { ProviderConfig.DEFAULT_MODELS[id].orEmpty() }.forEach { m ->
                        ModelOption("${cfg.name} / $m", "", MemModel.pid == id && MemModel.model == m) { MemModel.use(id, m); pick = false }
                    }
                }
            }
        }
        Sheet(info, onDismiss = { info = false }) {
            Txt("记忆模型是做什么的", Page.ink, 16.sp)
            Spacer(Modifier.height(8.dp))
            Txt(
                "你们聊完，它回头看一遍，决定记不记、写成一条（标题 + 她自己的一段话）；每天你第一次来时，它把之前没理过的理一遍，过完的周、月、年收成一份。这些都在后台做，不占聊天那一路。开思考会理得更细，也更慢、更费。",
                Page.muted, 13.5.sp, lineHeight = 22.sp, align = androidx.compose.ui.text.style.TextAlign.Center,
            )
            SheetButton("知道了", false, Modifier.fillMaxWidth().padding(top = 14.dp)) { info = false }
        }
    }
}

@Composable
private fun ModelOption(name: String, sub: String, on: Boolean, onClick: () -> Unit) {
    Pressable(onClick, Modifier.fillMaxWidth(), label = name) { pr ->
        Row(
            Modifier.fillMaxWidth().background(veil(if (pr) .08f else if (on) .05f else 0f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Txt(name, Page.ink, 14.5.sp, maxLines = 1)
                if (sub.isNotEmpty()) Txt(sub, Page.muted, 12.sp, maxLines = 1)
            }
            if (on) Txt("✓", Skin.accent, 15.sp)
        }
    }
}

/** 记忆系统提示词：记忆模型记下、整理时读的写法。右上保存；改了没保存就返回，先问一句。 */
@Composable
private fun ColumnScope.MemPromptScreen(compact: Boolean, blur: Dp, nav: MineNav) {
    var draft by remember { mutableStateOf(MemModel.text) }
    var ask by remember { mutableStateOf(false) }
    val dirty = draft.trim() != MemModel.text
    androidx.activity.compose.BackHandler(dirty && !ask) { ask = true }
    Title("记忆系统提示词", compact, onBack = { if (dirty) ask = true else nav.back() }) {
        SaveButton(dirty) { val t = draft.trim(); MemModel.prompt = if (t == MemModel.DEFAULT) "" else t; MemModel.save(); draft = t }
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.fillMaxSize()) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = TextStyle(fontFamily = Sans, color = Page.ink, fontSize = 14.5.sp, lineHeight = 25.sp),
                cursorBrush = SolidColor(Page.accent),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize().glass(RoundedCornerShape(20.dp), blur, fill = glassFill).padding(18.dp)) { inner() }
                },
            )
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("${draft.length} 字 · 记下、每天整理时都按这段写", Page.muted, 12.5.sp, Modifier.weight(1f), maxLines = 1)
                if (draft.trim() != MemModel.DEFAULT) {
                    Pressable({ draft = MemModel.DEFAULT }, label = "恢复默认") { pr ->
                        Txt("恢复默认", Skin.danger, 12.5.sp, Modifier.background(veil(if (pr) .1f else .05f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
        }
        Sheet(ask, onDismiss = { ask = false }) {
            Txt("改的还没保存", Page.ink, 16.sp)
            Spacer(Modifier.height(8.dp))
            Txt("现在返回，这次改的就不要了。", Page.muted, 13.5.sp, lineHeight = 22.sp, align = androidx.compose.ui.text.style.TextAlign.Center)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetButton("继续改", false, Modifier.weight(1f)) { ask = false }
                SheetButton("不保存", true, Modifier.weight(1f), red = true) { ask = false; draft = MemModel.text; nav.back() }
            }
        }
    }
}


/* ---------- 聊天摘要的 ☰（第 365 版，原型 N1rGviN3HUFX2VdB5yj8qx 第 3 版）：下拉菜单里自动写摘要开关、摘要模型、摘要提示词；后两个各推一页 ---------- */

private val autoIc = Ic("M12 4v3M12 17v3M4 12h3M17 12h3M6.3 6.3l2.1 2.1M15.6 15.6l2.1 2.1M6.3 17.7l2.1-2.1M15.6 8.4l2.1-2.1")
private val penIc = Ic("M14.5 5.5l4 4M5 19l1-4.5L15.5 5a1.4 1.4 0 0 1 2 0l1.5 1.5a1.4 1.4 0 0 1 0 2L9.5 18z")

/** 从右上角长出来（220ms 减速），点空白处收回（140ms 加速）；样式照记忆页的 ☰。 */
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.DigestMenu(open: Boolean, onDismiss: () -> Unit, nav: MineNav) {
    val t by androidx.compose.animation.core.animateFloatAsState(
        if (open) 1f else 0f, tween(if (open) 220 else 140, easing = if (open) Curves.decel else Curves.accel), label = "digestMenu",
    )
    androidx.activity.compose.BackHandler(open) { onDismiss() }
    if (t <= 0f) return
    if (open) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } })
    Column(
        Modifier.align(Alignment.TopEnd)
            .graphicsLayer {
                alpha = t
                val s = .9f + .1f * t; scaleX = s; scaleY = s
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
            }
            .width(250.dp)
            .shadow(18.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black.copy(alpha = .35f), spotColor = Color.Black.copy(alpha = .35f))
            .background(Skin.pop, RoundedCornerShape(16.dp))
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(vertical = 6.dp),
    ) {
        @Composable
        fun row(ic: Ic, label: String, sub: String?, onClick: () -> Unit, end: @Composable () -> Unit) {
            Pressable({ if (open) onClick() }, Modifier.fillMaxWidth(), label = label) { pr ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).background(veil(if (pr) .08f else 0f)).padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SvgIcon(ic, Page.ink.copy(alpha = .8f), 18.dp, stroke = 1.6f)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(label, Page.ink, 14.5.sp, maxLines = 1)
                        if (sub != null) Txt(sub, Page.muted, 12.sp, maxLines = 1)
                    }
                    Spacer(Modifier.width(8.dp))
                    end()
                }
            }
        }
        row(autoIc, "自动写摘要", null, { GistModel.auto = !GistModel.auto; GistModel.save() }) {
            Switch(GistModel.auto, "自动写摘要") { GistModel.auto = it; GistModel.save() }
        }
        row(modelIc, "摘要模型", GistModel.label(AttachLook.store), { onDismiss(); nav.screen = "digestmodel" }) { SvgIcon(MineIcons.next, Page.muted, 14.dp) }
        row(penIc, "摘要提示词", null, { onDismiss(); nav.screen = "digestprompt" }) {
            Chip(if (GistModel.custom) "改过" else "默认", if (GistModel.custom) Skin.accent.copy(alpha = .9f) else veil(.08f), if (GistModel.custom) Skin.onAccent else Page.muted)
            Spacer(Modifier.width(6.dp))
            SvgIcon(MineIcons.next, Page.muted, 14.dp)
        }
        Box(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) { Divider() }
        Txt(
            if (GistModel.auto) "开着：聊完停一会儿、聊满 6 轮、切走时，后台用摘要模型写。" else "关着：不自动写。这一页的「补写」「重试」照样能用。",
            Page.muted, 12.sp, Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 8.dp), lineHeight = 19.sp,
        )
    }
}

/** 摘要模型：跟着记忆模型，或者单独挑一个。 */
@Composable
private fun ColumnScope.DigestModelScreen(store: AssistantStore, compact: Boolean, blur: Dp, nav: MineNav) {
    Title("摘要模型", compact, onBack = { nav.back() })
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Txt("写每段聊天摘要用哪个模型。可以挑个便宜、快的专门写摘要。", Page.muted, 13.sp, Modifier.padding(start = 6.dp, end = 6.dp, bottom = 14.dp), lineHeight = 21.sp)
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), blur, fill = glassFill).padding(8.dp)) {
            ModelOption("跟着记忆模型", "现在是 " + MemModel.label(store).removePrefix("跟着默认 · "), GistModel.pid.isEmpty()) { GistModel.use("", "") }
            store.order.filter { store.config(it).configured }.forEach { id ->
                val cfg = store.config(id)
                cfg.models.ifEmpty { ProviderConfig.DEFAULT_MODELS[id].orEmpty() }.forEach { m ->
                    ModelOption("${cfg.name} / $m", "", GistModel.pid == id && GistModel.model == m) { GistModel.use(id, m) }
                }
            }
        }
    }
}

/** 摘要提示词：写摘要时读的写法。照记忆系统提示词那页。 */
@Composable
private fun ColumnScope.DigestPromptScreen(compact: Boolean, blur: Dp, nav: MineNav) {
    var draft by remember { mutableStateOf(GistModel.text) }
    var ask by remember { mutableStateOf(false) }
    val dirty = draft.trim() != GistModel.text
    androidx.activity.compose.BackHandler(dirty && !ask) { ask = true }
    Title("摘要提示词", compact, onBack = { if (dirty) ask = true else nav.back() }) {
        SaveButton(dirty) { val t = draft.trim(); GistModel.prompt = if (t == GistModel.DEFAULT) "" else t; GistModel.save(); draft = t }
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.fillMaxSize()) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = TextStyle(fontFamily = Sans, color = Page.ink, fontSize = 14.5.sp, lineHeight = 25.sp),
                cursorBrush = SolidColor(Page.accent),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize().glass(RoundedCornerShape(20.dp), blur, fill = glassFill).padding(18.dp)) { inner() }
                },
            )
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("${draft.length} 字 · 写每段摘要时都按这段写", Page.muted, 12.5.sp, Modifier.weight(1f), maxLines = 1)
                if (draft.trim() != GistModel.DEFAULT) {
                    Pressable({ draft = GistModel.DEFAULT }, label = "恢复默认") { pr ->
                        Txt("恢复默认", Skin.danger, 12.5.sp, Modifier.background(veil(if (pr) .1f else .05f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
        }
        Sheet(ask, onDismiss = { ask = false }) {
            Txt("改的还没保存", Page.ink, 16.sp)
            Spacer(Modifier.height(8.dp))
            Txt("现在返回，这次改的就不要了。", Page.muted, 13.5.sp, lineHeight = 22.sp, align = androidx.compose.ui.text.style.TextAlign.Center)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetButton("继续改", false, Modifier.weight(1f)) { ask = false }
                SheetButton("不保存", true, Modifier.weight(1f), red = true) { ask = false; draft = GistModel.text; nav.back() }
            }
        }
    }
}

/** 往左滑：底下露出「删除」，滑过一半松手就删；没过一半就弹回。 */
@Composable
private fun SwipeToDelete(key: String, onDelete: () -> Unit, content: @Composable () -> Unit) {
    val dx = remember(key) { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    var w by remember { mutableStateOf(1f) }
    val d = LocalDensity.current
    Box(
        Modifier.fillMaxWidth().onSizeChanged { w = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(key) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (dx.value < -w * .45f) { dx.animateTo(-w, tween(180)); onDelete() }
                            else dx.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = .8f, stiffness = 500f))
                        }
                    },
                    onDragCancel = { scope.launch { dx.animateTo(0f) } },
                ) { ch, amount -> ch.consume(); scope.launch { dx.snapTo((dx.value + amount).coerceIn(-w, 0f)) } }
            },
    ) {
        // 底下的红色「删除」，只露出手指拉开的那一截
        if (dx.value < 0f) {
            val revealed = with(d) { (-dx.value).toDp() }
            Box(
                Modifier.align(Alignment.CenterEnd).width(revealed).fillMaxHeight()
                    .padding(bottom = 8.dp)
                    .background(css("#8f4f4b").copy(alpha = (-dx.value / (w * .45f)).coerceIn(.35f, 1f)), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.CenterEnd,
            ) { if (revealed > 56.dp) Txt("删除", css("#f6e3e1"), 13.sp, Modifier.padding(end = 16.dp), maxLines = 1) }
        }
        Box(Modifier.graphicsLayer { translationX = dx.value }) { content() }
    }
}

/* ---------- 模型供应商列表 ---------- */

@Composable
private fun ColumnScope.ProvidersScreen(store: AssistantStore, compact: Boolean, blur: Dp, nav: MineNav) {
    Title("模型供应商", compact, onBack = { nav.back() })
    var q by remember { mutableStateOf("") }
    Box {
        Field(q, { q = it }, "搜索供应商", modifier = Modifier.padding(bottom = 16.dp))
    }
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        store.ordered().filter { q.isBlank() || it.name.contains(q.trim(), ignoreCase = true) }.forEach { p ->
            val c = store.config(p.id)
            // 第 381 版：色块卡片；首字放进小方块；状态、模型数变小色块标签；正在用的那家整块透一层点缀色
            val live = store.activeProvider == p.id
            Pressable({ nav.screen = "provider/${p.id}" }, Modifier.fillMaxWidth(), label = p.name) { pressed ->
                val base = if (live) lerp(blockFill, Skin.accent.copy(alpha = blockFill.alpha), .14f) else blockFill
                val f by androidx.compose.animation.animateColorAsState(if (pressed) blockPress else base, tween(if (pressed) 80 else 220), label = "prov")
                Row(
                    Modifier.fillMaxWidth().block(RoundedCornerShape(20.dp), blur, f).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(44.dp).background(if (live) Skin.accent.copy(alpha = .2f) else veil(.07f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Txt(p.letter, if (live) Skin.accent else Page.ink, 20.sp, family = Serif) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(c.name, Page.ink, 16.5.sp, weight = FontWeight.Medium, maxLines = 1)
                        Spacer(Modifier.height(7.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            when {
                                c.enabled && c.configured -> Chip("启用", fill = Page.ink.copy(alpha = .92f), ink = Skin.onInk)
                                c.configured -> Chip("未启用", fill = veil(.07f))
                                else -> Chip("未配置", fill = veil(.07f))
                            }
                            Chip("${c.models.size} 个模型", fill = veil(.07f))
                            store.balances[p.id]?.let { Chip(it.substringBefore(" ·"), fill = veil(.07f)) } ?: run { if (p.note.isNotEmpty()) Chip(p.note, fill = veil(.07f)) }
                        }
                    }
                    SvgIcon(MineIcons.next, Page.muted, 18.dp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/* ---------- 某个供应商：配置 / 模型 ---------- */

@Composable
private fun ColumnScope.ProviderScreen(store: AssistantStore, p: Provider, compact: Boolean, blur: Dp, nav: MineNav) {
    val saved = store.config(p.id)
    // 第 381 版：去掉「配置 / 模型」两个标签，模型连上后直接长在 Key 下面；余额放在标题右边
    Title(saved.name, compact, onBack = { nav.back() }) { BalanceTag(store.balances[p.id]) }
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        ConfigTab(store, p, blur)
        Spacer(Modifier.height(12.dp))
    }
}

/** 标题右边的余额：没有色块底，小字「余额」+ 点缀色宋体数字；点一下换成「赠送 / 充值」两行，再点换回来。 */
@Composable
private fun BalanceTag(bal: String?) {
    var last by remember { mutableStateOf("") }
    if (bal != null) last = bal
    var alt by remember { mutableStateOf(false) }
    androidx.compose.animation.AnimatedVisibility(
        bal != null,
        enter = androidx.compose.animation.fadeIn(tween(220, 200)) + androidx.compose.animation.slideInHorizontally(tween(380, 200, Curves.decel)) { it / 4 } +
            androidx.compose.animation.scaleIn(spring(dampingRatio = .7f, stiffness = 380f), .9f, androidx.compose.ui.graphics.TransformOrigin(1f, .5f)),
        exit = androidx.compose.animation.fadeOut(tween(140)),
    ) {
        val parts = last.split(" · ")
        val main = parts.first().removePrefix("余额").trim()
        val rest = parts.drop(1)
        Pressable({ if (rest.isNotEmpty()) alt = !alt }, Modifier.padding(start = 8.dp), label = "账户余额 $last") { _ ->
            androidx.compose.animation.AnimatedContent(
                alt && rest.isNotEmpty(),
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(tween(200)) + androidx.compose.animation.slideInVertically(tween(260, easing = Curves.standard)) { it / 3 }) togetherWith
                        (androidx.compose.animation.fadeOut(tween(140)) + androidx.compose.animation.slideOutVertically(tween(200, easing = Curves.accel)) { -it / 3 })
                },
                contentAlignment = Alignment.CenterEnd, label = "bal",
            ) { a ->
                if (a) Column(Modifier.height(40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.End) {
                    rest.take(2).forEach { Txt(it, Skin.accent, 10.5.sp, maxLines = 1) }
                } else Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt("余额", Page.muted, 11.sp)
                    Spacer(Modifier.width(5.dp))
                    Txt(main, Skin.accent, 19.sp, family = Serif, maxLines = 1)
                }
            }
        }
    }
}

/**
 * 供应商配置（第 381 版，原型 Bvvrc1FEaNqjXgG43Zr65Q 第 5 版）：
 * 顺序 Base Url → Key → 路径；去掉名称、「是否启用」「获取账户余额」两个开关，改动停手 0.6 秒自动存。
 * Key 框里右边是眼睛，框外一块方形测试按钮：插头 → 转圈 → 成功变点缀色实心勾 / 失败淡红「!」并晃一下，1.6 秒后变回插头。
 * 连上就自动启用这家、顺手查余额（标题右边），可用模型以一块块凹下去的长条直接长在 Key 下面；
 * 「已连接，N 个模型」那行是折叠开关，选好一个模型列表自己折成只剩正在用的那条。
 */
@Composable
private fun ConfigTab(store: AssistantStore, p: Provider, blur: Dp) {
    val saved = store.config(p.id)
    var draft by remember(p.id) { mutableStateOf(saved) }
    var showKey by remember { mutableStateOf(false) }
    var state by remember(p.id) { mutableStateOf(0) } // 0 插头，1 转圈，2 成功，3 失败
    var fail by remember(p.id) { mutableStateOf<String?>(null) }
    var fresh by remember(p.id) { mutableStateOf(false) }
    var open by remember(p.id) { mutableStateOf(false) }
    val resetMorph = remember(p.id) { Morph() }
    val shake = remember { androidx.compose.animation.core.Animatable(0f) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 停手 0.6 秒自动存（没有保存按钮了）
    LaunchedEffect(draft) {
        if (draft != store.config(p.id)) { kotlinx.coroutines.delay(600); store.update(p.id, draft) }
    }
    // 已经配好的，进来查一次余额
    LaunchedEffect(p.id) {
        if (p.hasBalance && saved.configured) AssistantApi.balance(p, saved).onSuccess { store.balances[p.id] = it }
    }

    fun test() {
        if (state == 1) return
        state = 1; fail = null
        scope.launch {
            val k = draft.key.trim()
            AssistantApi.models(p, draft).fold(
                { list ->
                    draft = draft.copy(enabled = true, balance = p.hasBalance, models = list.ifEmpty { draft.models })
                    store.update(p.id, draft)
                    fresh = true; open = true; state = 2
                    if (p.hasBalance) AssistantApi.balance(p, draft).onSuccess { store.balances[p.id] = it }
                    Unit
                },
                { e ->
                    val msg = e.message.orEmpty()
                    fail = when {
                        k.isEmpty() -> "先填 Key"
                        p.id == "deepseek" && !k.startsWith("sk-") -> "Key 应该以 sk- 开头"
                        msg.contains("401") || msg.contains("auth", ignoreCase = true) -> "Key 不对，检查一下再点测试"
                        else -> "连不上：${msg.ifBlank { "没有回应" }}"
                    }
                    state = 3
                    val d = density.density
                    launch {
                        shake.animateTo(0f, androidx.compose.animation.core.keyframes {
                            durationMillis = 360
                            -5f * d at 72
                            4f * d at 144
                            -3f * d at 216
                            2f * d at 288
                        })
                    }
                    Unit
                },
            )
            kotlinx.coroutines.delay(1600)
            state = 0
        }
    }

    Column(Modifier.fillMaxWidth().block(RoundedCornerShape(22.dp), blur).padding(18.dp)) {
        @Composable
        fun Label(t: String) = Txt(t, Page.muted, 13.5.sp, Modifier.padding(bottom = 8.dp))

        Label("API Base Url")
        Field(draft.base, { draft = draft.copy(base = it.trim()) }, p.base, keyboard = KeyboardType.Uri)
        Spacer(Modifier.height(16.dp))
        Label("API Key")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Field(draft.key, { draft = draft.copy(key = it.trim()) }, "sk-…", secret = !showKey, keyboard = KeyboardType.Password, trailing = {
                    Pressable({ showKey = !showKey }, label = if (showKey) "隐藏密钥" else "显示密钥") { pr ->
                        SvgIcon(if (showKey) MineIcons.eye else MineIcons.eyeOff, if (pr) Page.ink else Page.muted, 20.dp, modifier = Modifier.padding(10.dp))
                    }
                })
            }
            Spacer(Modifier.width(8.dp))
            TestButton(state, Modifier.graphicsLayer { translationX = shake.value }) { test() }
        }
        // 失败的原因；成功时这里是模型列表
        androidx.compose.animation.AnimatedVisibility(
            fail != null && state != 2,
            enter = androidx.compose.animation.expandVertically(tween(240, easing = Curves.standard)) + androidx.compose.animation.fadeIn(tween(200)),
            exit = androidx.compose.animation.shrinkVertically(tween(200, easing = Curves.accel)) + androidx.compose.animation.fadeOut(tween(140)),
        ) {
            var last by remember { mutableStateOf("") }
            fail?.let { last = it }
            Txt(last, Skin.danger, 12.5.sp, Modifier.padding(top = 10.dp, start = 2.dp))
        }
        val models = draft.models
        val showList = (fresh || (saved.enabled && saved.configured)) && models.isNotEmpty() && fail == null
        androidx.compose.animation.AnimatedVisibility(
            showList,
            enter = androidx.compose.animation.expandVertically(tween(380, easing = Curves.standard)) + androidx.compose.animation.fadeIn(tween(240, 120)),
            exit = androidx.compose.animation.shrinkVertically(tween(260, easing = Curves.accel)) + androidx.compose.animation.fadeOut(tween(160)),
        ) {
            ModelFold(store, p, models, fresh, open, onOpen = { open = it })
        }
        Spacer(Modifier.height(16.dp))
        Label("API 路径")
        Field(draft.path, { draft = draft.copy(path = it.trim()) }, p.path, keyboard = KeyboardType.Uri)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
            // ↻ 恢复默认地址：图标原地长成「已恢复默认」，本来就是默认的写「已经是默认地址」
            MorphPill(resetMorph, "恢复默认地址", MineIcons.reset, onClick = {
                val next = draft.copy(base = p.base, path = p.path)
                val changed = next != draft
                draft = next
                scope.launch { if (changed) resetMorph.flash("已恢复默认") else resetMorph.flash("已经是默认地址", info = true) }
            }, quiet = true)
        }
    }
}

/** Key 框外的测试按钮：48dp 方块，插头 / 转圈 / 勾 / ! 原地换（缩放 + 淡入淡出，弹簧阻尼 .5）。 */
@Composable
private fun TestButton(state: Int, modifier: Modifier, onClick: () -> Unit) {
    val bg by androidx.compose.animation.animateColorAsState(
        when (state) { 2 -> Skin.accent.copy(alpha = .9f); 3 -> Skin.danger.copy(alpha = .2f); else -> Skin.accent.copy(alpha = .16f) }, tween(260), label = "testBg",
    )
    val ink by androidx.compose.animation.animateColorAsState(
        when (state) { 2 -> Skin.onAccent; 3 -> Skin.danger; else -> Skin.accent }, tween(260), label = "testInk",
    )
    val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
    val turn by spin.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(tween(800, easing = androidx.compose.animation.core.LinearEasing)), label = "turn")
    Pressable(onClick, modifier, label = when (state) { 1 -> "正在连接"; else -> "测试连接" }) { pr ->
        val k by androidx.compose.animation.core.animateFloatAsState(if (pr) .92f else 1f, if (pr) tween(70) else spring(dampingRatio = .5f, stiffness = 600f), label = "testPress")
        Box(Modifier.size(48.dp).graphicsLayer { scaleX = k; scaleY = k }.background(bg, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            @Composable
            fun Slot(on: Boolean, content: @Composable (Modifier) -> Unit) {
                val v by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = .5f, stiffness = 500f), label = "slot")
                content(Modifier.graphicsLayer { alpha = v.coerceIn(0f, 1f); val s = .5f + .5f * v; scaleX = s; scaleY = s })
            }
            Slot(state == 0) { m -> SvgIcon(MineIcons.plug, ink, 20.dp, stroke = 1.7f, modifier = m) }
            Slot(state == 1) { m ->
                androidx.compose.foundation.Canvas(m.size(22.dp).graphicsLayer { rotationZ = turn }) {
                    drawArc(ink, -90f, 270f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
            }
            Slot(state == 2) { m -> SvgIcon(Ic("M5 12.5l4.5 4.5L19 7.5"), ink, 20.dp, stroke = 2.2f, modifier = m) }
            Slot(state == 3) { m -> SvgIcon(Ic("M12 6v8M12 18h.01"), ink, 20.dp, stroke = 2.2f, modifier = m) }
        }
    }
}

/** 可用模型（方案 B 列表）：凹下去的长条拼在一起，左边名字、右边圆勾；折起来只剩正在用的那条。 */
@Composable
private fun ModelFold(store: AssistantStore, p: Provider, models: List<String>, fresh: Boolean, open: Boolean, onOpen: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val using = if (store.activeProvider == p.id) store.activeModel.takeIf { it in models } else null
    Column(Modifier.padding(top = 10.dp)) {
        Pressable({ onOpen(!open) }, label = if (open) "收起模型" else "展开模型") { pr ->
            val r by androidx.compose.animation.core.animateFloatAsState(if (open) 0f else -90f, spring(dampingRatio = .6f, stiffness = 420f), label = "chev")
            Row(Modifier.background(if (pr) veil(.06f) else Color.Transparent, RoundedCornerShape(10.dp)).padding(horizontal = 2.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("${if (fresh) "已连接" else "已配置"}，${models.size} 个模型", Skin.accent, 12.5.sp, maxLines = 1)
                if (!open) Txt(if (using != null) " · 正在用 $using" else " · 点开选一个", Page.muted, 12.5.sp, Modifier.weight(1f, fill = false), maxLines = 1)
                Spacer(Modifier.width(4.dp))
                SvgIcon(Ic("M6 9.5l6 6 6-6"), Skin.accent, 14.dp, stroke = 2f, modifier = Modifier.graphicsLayer { rotationZ = r })
            }
        }
        Spacer(Modifier.height(8.dp))
        val shown = models.filter { open || it == using }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            models.forEach { m ->
                androidx.compose.runtime.key(m) {
                    val inUse = m == using
                    androidx.compose.animation.AnimatedVisibility(
                        open || inUse,
                        enter = androidx.compose.animation.expandVertically(tween(320, easing = Curves.standard)) + androidx.compose.animation.fadeIn(tween(220, 80)),
                        exit = androidx.compose.animation.shrinkVertically(tween(320, easing = Curves.standard)) + androidx.compose.animation.fadeOut(tween(180)),
                    ) {
                        val i = shown.indexOf(m)
                        val big = 14.dp; val small = 6.dp
                        val shape = when {
                            shown.size <= 1 -> RoundedCornerShape(big)
                            i == 0 -> RoundedCornerShape(big, big, small, small)
                            i == shown.lastIndex -> RoundedCornerShape(small, small, big, big)
                            else -> RoundedCornerShape(small)
                        }
                        val s by androidx.compose.animation.core.animateFloatAsState(if (inUse) 1f else 0f, spring(dampingRatio = .6f, stiffness = 500f), label = "pick")
                        Pressable({
                            if (!open) onOpen(true)
                            else { store.use(p.id, m); scope.launch { kotlinx.coroutines.delay(260); onOpen(false) } }
                        }, Modifier.fillMaxWidth(), label = "让 ${Memory.active.name} 用 $m") { pr ->
                            val f by androidx.compose.animation.animateColorAsState(
                                when { pr -> Skin.well(.34f); inUse -> Skin.accent.copy(alpha = .14f); else -> wellFill }, tween(if (pr) 80 else 220), label = "mrow",
                            )
                            Row(Modifier.fillMaxWidth().height(50.dp).background(f, shape).padding(start = 14.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Txt(m, Page.ink, 14.sp, Modifier.weight(1f), family = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 1)
                                CheckDot(s, size = 22.dp)
                            }
                        }
                    }
                }
            }
        }
        if (open) Txt("点一个模型，${Memory.active.name} 就用它聊天", Page.muted, 12.sp, Modifier.fillMaxWidth().padding(top = 10.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/* ---------- MCP 服务：左边市场，右边已连接 ---------- */

private val mcpTints = listOf("#e9b48e", "#e8a1a1", "#b7c9a3", "#d9c58c", "#c9b9d9", "#a9c6d8", "#d8c3a5", "#e6b0a0")
private fun mcpTint(id: String) = css(mcpTints[kotlin.math.abs(id.hashCode()) % mcpTints.size])

@Composable
private fun McpIcon(id: String, letter: String, size: Dp) {
    Box(Modifier.size(size).background(mcpTint(id), RoundedCornerShape(size * .32f)), contentAlignment = Alignment.Center) {
        Txt(letter, Skin.onAccent, (size.value * .43f).sp, family = Serif, weight = FontWeight.Medium)
    }
}

@Composable
private fun ColumnScope.McpScreen(compact: Boolean, blur: Dp, nav: MineNav) {
    Title("MCP 服务", compact, onBack = { nav.back() })
    LaunchedEffect(Unit) { Mcp.refreshCatalog() }
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = nav.mcpTab) { 2 }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.currentPage) { nav.mcpTab = pager.currentPage }
    SegTabs(listOf("市场", "已连接"), pager, blur) { scope.launch { pager.animateScrollToPage(it) } }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        androidx.compose.foundation.pager.HorizontalPager(
            pager, Modifier.fillMaxSize(), verticalAlignment = Alignment.Top, beyondViewportPageCount = 1, pageSpacing = 16.dp,
        ) { page ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                if (page == 0) McpMarketTab(blur, nav) else McpConnectedTab(blur, nav)
            }
        }
    }
}

@Composable
private fun ColumnScope.McpMarketTab(blur: Dp, nav: MineNav) {
    var q by remember { mutableStateOf("") }
    Field(q, { q = it }, "搜服务，或粘贴 MCP 地址", modifier = Modifier.padding(bottom = 12.dp))
    val query = q.trim()
    val isUrl = query.startsWith("http://") || query.startsWith("https://")
    if (isUrl) {
        Pressable({ nav.screen = "mcp/new?" + java.net.URLEncoder.encode(query, "UTF-8") }, Modifier.fillMaxWidth().padding(bottom = 10.dp), label = "连接这个地址") { pressed ->
            Row(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), blur, fill = if (pressed) veil(.08f) else glassFill).padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Txt("连接这个地址", Page.ink, 15.sp)
                    Spacer(Modifier.height(3.dp))
                    Txt(query, Page.muted, 12.5.sp, maxLines = 1)
                }
                SvgIcon(MineIcons.next, Page.muted, 18.dp)
            }
        }
    }
    val items = Mcp.catalog.filter { query.isEmpty() || isUrl || (it.name + it.desc + it.category).contains(query, ignoreCase = true) }
    val cats = items.map { it.category }.distinct()
    cats.forEach { cat ->
        SectionLabel(cat)
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), blur, fill = glassFill)) {
            items.filter { it.category == cat }.forEachIndexed { i, item ->
                if (i > 0) Divider()
                val have = Mcp.byCatalog(item.id)
                Pressable({ nav.screen = if (have != null) "mcp/${have.id}" else "mcp/new:${item.id}" }, Modifier.fillMaxWidth(), label = item.name) { pressed ->
                    Row(
                        Modifier.fillMaxWidth().background(if (pressed) veil(.05f) else Color.Transparent).padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        McpIcon(item.id, item.name.take(1), 40.dp)
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(item.name, Page.ink, 15.sp, weight = FontWeight.Medium, maxLines = 1)
                            Spacer(Modifier.height(2.dp))
                            Txt(item.desc, Page.muted, 12.sp, maxLines = 2, lineHeight = 17.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        if (have != null) Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).background(if (have.enabled) warm else Page.muted.copy(alpha = .4f), CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Txt(if (have.enabled) "已连接" else "已关", Page.muted, 12.sp)
                        } else Chip(
                            when (item.auth) { "key" -> "填 key"; "paste" -> "贴地址"; "bearer" -> "填 token"; else -> "连接" },
                            fill = Skin.accent.copy(alpha = .92f), ink = Skin.onAccent,
                        )
                    }
                }
            }
        }
    }
    if (items.isEmpty() && !isUrl) Txt("没有找到。有地址的话直接粘贴进来。", Page.muted, 13.sp, Modifier.padding(top = 30.dp).align(Alignment.CenterHorizontally))
    Spacer(Modifier.height(20.dp))
    Txt("都是服务商自己托管的，地址和 key 只存在这台手机上。魔搭 MCP 广场里还有几千个，复制地址粘贴进来就能用。", Page.muted.copy(alpha = .7f), 12.sp, Modifier.padding(horizontal = 6.dp), lineHeight = 18.sp)
}

@Composable
private fun ColumnScope.McpConnectedTab(blur: Dp, nav: MineNav) {
    val list = Mcp.servers
    if (list.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt("还没有连接任何服务", Page.ink, 15.sp, family = Serif)
            Spacer(Modifier.height(8.dp))
            Txt("往左翻，市场里挑一个", Page.muted, 13.sp)
        }
        return
    }
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), blur, fill = glassFill)) {
        list.forEachIndexed { i, s ->
            if (i > 0) Divider()
            Pressable({ nav.screen = "mcp/${s.id}" }, Modifier.fillMaxWidth(), label = s.name) { pressed ->
                Row(
                    Modifier.fillMaxWidth().background(if (pressed) veil(.05f) else Color.Transparent).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    McpIcon(s.catalog.ifEmpty { s.id }, s.initial, 40.dp)
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(s.name, Page.ink, 15.sp, weight = FontWeight.Medium, maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        Txt(
                            if (s.tools.isEmpty()) "还没拿到工具列表" else if (s.off.isEmpty()) "${s.tools.size} 个工具" else "${s.tools.size} 个工具 · 开了 ${s.onTools.size} 个",
                            Page.muted, 12.sp, maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(s.enabled, "启用") { s.enabled = it; Mcp.save() }
                }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    Txt("开着的服务，她聊天时随手能用；关掉就从她手边拿走，设置还留着。", Page.muted.copy(alpha = .7f), 12.sp, Modifier.padding(horizontal = 6.dp), lineHeight = 18.sp)
}

/** 某个服务：新建（new:精选id / new?地址）或已有（服务 id）。 */
@Composable
private fun ColumnScope.McpServerScreen(key: String, compact: Boolean, blur: Dp, nav: MineNav) {
    val scope = rememberCoroutineScope()
    val existing = Mcp.get(key)
    val item = when {
        key.startsWith("new:") -> Mcp.catalog.firstOrNull { it.id == key.removePrefix("new:") }
        existing != null && existing.catalog.isNotEmpty() -> Mcp.catalog.firstOrNull { it.id == existing.catalog }
        else -> null
    }
    val pasted = if (key.startsWith("new?")) java.net.URLDecoder.decode(key.removePrefix("new?"), "UTF-8") else ""
    val auth = item?.auth ?: "bearer"
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    var name by remember(key) { mutableStateOf(existing?.name ?: item?.name ?: "") }
    // key 型：用户只填 key，地址由模板拼；其他：填地址
    var keyText by remember(key) { mutableStateOf(existing?.let { e -> item?.url?.let { t -> extractKey(t, e.url) } } ?: "") }
    var url by remember(key) { mutableStateOf(existing?.url ?: pasted.ifEmpty { if (auth == "bearer" || auth == "none") item?.url.orEmpty() else "" }) }
    var token by remember(key) { mutableStateOf(existing?.token ?: "") }
    var tools by remember(key) { mutableStateOf(existing?.tools ?: emptyList()) }
    var off by remember(key) { mutableStateOf(existing?.off ?: emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var fail by remember { mutableStateOf("") }
    var okMsg by remember { mutableStateOf("") }
    val mcpMorph = remember { Morph() }

    val finalUrl = if (auth == "key" && item != null) item.url.replace("{key}", keyText.trim()) else url.trim()
    val canConnect = finalUrl.startsWith("http") && (auth != "key" || keyText.isNotBlank()) && !busy

    Title(name.ifBlank { item?.name ?: "新服务" }, compact, onBack = { nav.back() })
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            McpIcon(item?.id ?: existing?.id ?: "new", (name.ifBlank { item?.name ?: "M" }).take(1), 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (item != null) Txt(item.desc, Page.muted, 13.sp, lineHeight = 19.sp)
                else Txt("自己粘贴地址的服务。连上后会拿到它的名字和工具。", Page.muted, 13.sp, lineHeight = 19.sp)
            }
        }

        SectionLabel(when (auth) { "key" -> "KEY"; "paste" -> "地址"; else -> "地址与 TOKEN" })
        when (auth) {
            "key" -> Field(keyText, { keyText = it }, "粘贴 key", secret = true)
            "paste" -> Field(url, { url = it }, "粘贴你的 MCP 地址")
            else -> {
                Field(url, { url = it }, "https://…")
                Spacer(Modifier.height(8.dp))
                Field(token, { token = it }, if (auth == "bearer") "token（有的话）" else "token，没有就留空", secret = true)
            }
        }
        if (item != null && item.help.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Pressable({ InBrowser.open(item.helpUrl) }, label = "怎么拿") { pressed ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Txt(item.help, if (pressed) warm.copy(alpha = .7f) else warm, 12.5.sp, Modifier.weight(1f), lineHeight = 18.sp)
                    Spacer(Modifier.width(6.dp))
                    SvgIcon(MineIcons.next, warm, 16.dp)
                }
            }
        }
        if (existing != null) {
            SectionLabel("名字")
            Field(name, { name = it }, "给她看的名字")
        }

        Spacer(Modifier.height(18.dp))
        // 连接（第 379 版，原型 V8FnfRLAXjmBryEUQTd4cT 第 2 版）：按钮自己变形——转圈 + 「正在连接」+ 毫秒数 → 「已连上 · N 个工具」或「连不上 · 点我重试」
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            MorphPill(
                mcpMorph, if (existing == null) "连接" else "重新连接", null,
                onClick = {
                    fail = ""; okMsg = ""
                    scope.launch {
                        var made: McpServer? = null
                        mcpMorph.run("正在连接", clock = true) {
                            runCatching { Mcp.probe(finalUrl, token.trim()) }.fold(
                                { (serverName, list) ->
                                    tools = list
                                    if (name.isBlank()) name = item?.name ?: serverName.ifBlank { "MCP 服务" }
                                    // 精选单说了默认只开哪几个，就照它的；别的全开
                                    if (existing == null && item != null && item.defaultTools.isNotEmpty()) off = list.map { it.name }.filter { it !in item.defaultTools }.toSet()
                                    val s = existing ?: McpServer(Mcp.newId(), name, finalUrl, token.trim(), list, off, true, item?.id.orEmpty())
                                    s.name = name; s.url = finalUrl; s.token = token.trim(); s.tools = list; s.off = off; s.enabled = true
                                    if (existing == null) Mcp.add(s) else Mcp.save()
                                    okMsg = "连上了，${list.size} 个工具"
                                    made = s
                                    Morph.Ok("已连上", "${list.size} 个工具")
                                },
                                { fail = it.message ?: "连不上"; Morph.Bad("连不上") },
                            )
                        }
                        // 新建的连上了：按钮报完再进它自己的页面
                        if (existing == null) made?.let { nav.screen = "mcp/${it.id}" }
                    }
                },
                primary = true, enabled = canConnect,
            )
        }
        if (fail.isNotEmpty()) { Spacer(Modifier.height(10.dp)); Txt(fail, rose, 12.5.sp, Modifier.padding(horizontal = 6.dp), lineHeight = 18.sp) }
        if (okMsg.isNotEmpty()) { Spacer(Modifier.height(10.dp)); Txt(okMsg, warm, 12.5.sp, Modifier.padding(horizontal = 6.dp)) }

        if (tools.isNotEmpty()) {
            SectionLabel("她会多这些工具") {
                val allOn = off.isEmpty()
                Pressable({ off = if (allOn) tools.map { it.name }.toSet() else emptySet(); existing?.let { it.off = off; Mcp.save() } }, label = "全开或全关") { _ ->
                    Txt(if (allOn) "全关" else "全开", warm, 12.sp)
                }
            }
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), blur, fill = glassFill)) {
                tools.forEachIndexed { i, t ->
                    if (i > 0) Divider()
                    val on = t.name !in off
                    Pressable({ off = if (on) off + t.name else off - t.name; existing?.let { it.off = off; Mcp.save() } }, Modifier.fillMaxWidth(), label = t.name) { pressed ->
                        Row(Modifier.fillMaxWidth().background(if (pressed) veil(.05f) else Color.Transparent).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(20.dp).background(if (on) Skin.accent.copy(alpha = .9f) else veil(.1f), RoundedCornerShape(6.dp)),
                                contentAlignment = Alignment.Center,
                            ) { if (on) SvgIcon(Ic("M6 12.5l4 4 8-9"), Skin.onAccent, 14.dp, stroke = 2.2f) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Txt(t.name, if (on) Page.ink else Page.muted, 13.sp, family = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 1)
                                if (t.description.isNotEmpty()) { Spacer(Modifier.height(2.dp)); Txt(firstSentence(t.description, 60), Page.muted, 12.sp, maxLines = 2, lineHeight = 17.sp) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Txt("开太多工具会让她每次都读一长串说明，常用的开几个就够。", Page.muted.copy(alpha = .7f), 12.sp, Modifier.padding(horizontal = 6.dp), lineHeight = 18.sp)
        }

        if (existing != null) {
            Spacer(Modifier.height(26.dp))
            Pressable({ Mcp.remove(existing.id); nav.back() }, Modifier.fillMaxWidth(), label = "删除") { pressed ->
                Txt("删除这个服务", if (pressed) rose.copy(alpha = .7f) else rose, 14.sp, Modifier.fillMaxWidth().padding(vertical = 12.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** 从模板 "https://x/mcp?key={key}" 和实际地址里把 key 抠出来；对不上返回 null。 */
private fun extractKey(template: String, actual: String): String? {
    val i = template.indexOf("{key}")
    if (i < 0) return null
    val head = template.substring(0, i); val tail = template.substring(i + 5)
    if (!actual.startsWith(head) || !actual.endsWith(tail) || actual.length < head.length + tail.length) return null
    return actual.substring(head.length, actual.length - tail.length)
}
