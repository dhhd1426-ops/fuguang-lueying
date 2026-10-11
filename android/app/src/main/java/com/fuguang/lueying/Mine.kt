package com.fuguang.lueying

import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

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
private val glassFill = rgba(30, 34, 42, .42f)

/** “我的”页内部导航：mine → providers → provider/<id>。由 App 持有，返回键逐级退回。 */
class MineNav {
    var screen by mutableStateOf("mine")
    fun back(): Boolean {
        screen = when {
            screen.startsWith("provider/") -> "providers"
            screen == "providers" || screen == "memory" -> "assistant"
            screen == "assistant" -> "mine"
            else -> return false
        }
        return true
    }
}

@Composable
fun MinePage(
    compact: Boolean,
    tiny: Boolean,
    width: Dp,
    blur: Dp,
    store: AssistantStore,
    nav: MineNav,
    onHome: () -> Unit,
    onChat: () -> Unit,
    onOpen: (ModalKind) -> Unit,
) {
    val side = if (compact) (if (tiny) 18.dp else 24.dp) else (width * .052f)
    Box(Modifier.fillMaxSize().background(rgba(14, 17, 23, .42f))) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(start = side, end = side, top = if (compact) 26.dp else 38.dp, bottom = if (compact) 22.dp else 30.dp),
        ) {
            val screen = nav.screen
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                    when {
                        screen == "assistant" -> AssistantScreen(store, compact, blur, nav)
                        screen == "providers" -> ProvidersScreen(store, compact, blur, nav)
                        screen == "memory" -> MemoryScreen(store, compact, blur, nav)
                        screen.startsWith("provider/") -> ProviderScreen(store, provider(screen.removePrefix("provider/")), compact, blur, nav)
                        else -> MineRoot(store, compact, blur, nav)
                    }
                }
            }
            if (screen == "mine") BottomNav(compact, active = "我的", onHome = onHome, onChat = onChat, onMine = {}, onOpen = onOpen)
        }
    }
}

/* ---------- 通用小部件 ---------- */

@Composable
private fun Title(text: String, compact: Boolean, onBack: (() -> Unit)?, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
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
) {
    val shape = RoundedCornerShape(14.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Page.ink, fontSize = 15.sp),
        cursorBrush = SolidColor(Page.accent),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().background(rgba(0, 0, 0, .18f), shape).border(1.dp, Page.line, shape).padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                if (value.isEmpty()) Txt(placeholder, Page.muted.copy(alpha = .45f), 15.sp, maxLines = 1)
                inner()
            }
        },
    )
}

@Composable
private fun Switch(on: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Pressable({ onChange(!on) }, label = label) { _ ->
        Box(
            Modifier.width(48.dp).height(28.dp)
                .background(if (on) rgba(233, 180, 142, .85f) else rgba(255, 255, 255, .12f), RoundedCornerShape(50))
                .padding(3.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) { Box(Modifier.size(22.dp).background(if (on) css("#2a211b") else Page.ink.copy(alpha = .7f), CircleShape)) }
    }
}

@Composable
private fun PillButton(text: String, icon: Ic?, primary: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Pressable(onClick, modifier, enabled = enabled, label = text) { pressed ->
        val fill = when {
            !enabled -> rgba(255, 255, 255, .05f)
            primary -> rgba(233, 180, 142, if (pressed) .7f else .9f)
            else -> rgba(255, 255, 255, if (pressed) .12f else .06f)
        }
        val ink = when { !enabled -> Page.muted.copy(alpha = .5f); primary -> css("#2a211b"); else -> Page.ink }
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
private fun Divider() { Box(Modifier.fillMaxWidth().height(1.dp).background(rgba(255, 255, 255, .08f))) }

/* ---------- 我的（根） ---------- */

@Composable
private fun ColumnScope.MineRoot(store: AssistantStore, compact: Boolean, blur: Dp, nav: MineNav) {
    Title("我的", compact, null)
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        Pressable({ nav.screen = "assistant" }, Modifier.fillMaxWidth(), label = "助理") { pressed ->
            Row(
                Modifier.fillMaxWidth()
                    .glass(RoundedCornerShape(20.dp), blur, fill = if (pressed) rgba(255, 255, 255, .08f) else glassFill)
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt("助理", Page.ink, 15.sp, Modifier.weight(1f))
                Txt(store.name, Page.muted, 13.sp, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                SvgIcon(MineIcons.next, Page.muted, 18.dp)
            }
        }
        ConnectorGroup(blur)
    }
}

/* ---------- 连接器（方案 2：宋体图标 + 一句说明，状态在右） ---------- */

@Composable
private fun ConnectorGroup(blur: Dp) {
    val scope = rememberCoroutineScope()
    // 进来、从别的 App 回来时都查一次：羽那边可能刚断开或刚装上
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        scope.launch { Yu.refresh() }
        onPauseOrDispose { }
    }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { scope.launch { Yu.refresh() } }
    val state = Yu.state
    Txt("连接器", Page.muted, 11.sp, Modifier.padding(start = 6.dp, top = 26.dp, bottom = 10.dp), spacing = ls(2.5f, 11f))
    Pressable(
        { if (state == "off") runCatching { launcher.launch(Yu.connectIntent()) }.onFailure { scope.launch { Yu.refresh() } } },
        Modifier.fillMaxWidth(),
        label = "羽",
    ) { pressed ->
        Row(
            Modifier.fillMaxWidth()
                .glass(RoundedCornerShape(20.dp), blur, fill = if (pressed && state == "off") rgba(255, 255, 255, .08f) else glassFill)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).background(rgba(233, 180, 142, .16f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) { Txt("羽", css("#e9b48e"), 18.sp, family = Serif) }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Txt("羽", Page.ink, 15.sp)
                Spacer(Modifier.height(3.dp))
                Txt(
                    when (state) { "on" -> "助理可以查阅 ${Yu.notes} 篇笔记"; "missing" -> "需要先安装羽"; else -> "让助理读取你的笔记" },
                    Page.muted, 12.5.sp, maxLines = 1,
                )
            }
            Spacer(Modifier.width(10.dp))
            when (state) {
                "on" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(css("#e9b48e"), CircleShape))
                    Spacer(Modifier.width(7.dp))
                    Txt("已连接", Page.muted, 13.sp)
                }
                "missing" -> Txt("未安装", Page.muted, 13.sp)
                else -> Txt(
                    "连接", css("#2a211b"), 13.sp,
                    Modifier.background(rgba(233, 180, 142, if (pressed) .75f else .92f), RoundedCornerShape(50)).padding(horizontal = 15.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/* ---------- 助理：名称、模型与供应商 ---------- */

@Composable
private fun ColumnScope.AssistantScreen(store: AssistantStore, compact: Boolean, blur: Dp, nav: MineNav) {
    Title("助理", compact, onBack = { nav.back() })
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), blur, fill = glassFill)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("名称", Page.ink, 15.sp, Modifier.width(78.dp))
                var draft by remember(store.name) { mutableStateOf(store.name) }
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it.take(16); if (it.isNotBlank()) store.rename(it.take(16).trim()) },
                    singleLine = true,
                    textStyle = TextStyle(color = Page.ink, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End),
                    cursorBrush = SolidColor(Page.accent),
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                )
            }
            Divider()
            Pressable({ nav.screen = "providers" }, Modifier.fillMaxWidth(), label = "模型与供应商") { pressed ->
                Row(
                    Modifier.fillMaxWidth().background(if (pressed) rgba(255, 255, 255, .04f) else Color.Transparent).padding(horizontal = 18.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt("模型与供应商", Page.ink, 15.sp, Modifier.weight(1f))
                    Txt("${provider(store.activeProvider).name} · ${store.activeModel}", Page.muted, 13.sp, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    SvgIcon(MineIcons.next, Page.muted, 18.dp)
                }
            }
            Divider()
            Pressable({ nav.screen = "memory" }, Modifier.fillMaxWidth(), label = "记忆") { pressed ->
                Row(
                    Modifier.fillMaxWidth().background(if (pressed) rgba(255, 255, 255, .04f) else Color.Transparent).padding(horizontal = 18.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt("记忆", Page.ink, 15.sp, Modifier.weight(1f))
                    Txt(if (Memory.enabled) "记得 ${Memory.items.size} 件事" else "全局记忆已关", Page.muted, 13.sp, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    SvgIcon(MineIcons.next, Page.muted, 18.dp)
                }
            }
        }
    }
}

/* ---------- 记忆（方案 4：相识摘要） ---------- */

private val warm = css("#e9b48e")

@Composable
private fun SectionLabel(text: String, trailing: @Composable () -> Unit = {}) {
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

@Composable
private fun ColumnScope.MemoryScreen(store: AssistantStore, compact: Boolean, blur: Dp, nav: MineNav) {
    Title("记忆", compact, onBack = { nav.back() })
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        // 相识摘要
        val days = if (Memory.firstAt <= 0) 0 else ((System.currentTimeMillis() - Memory.firstAt) / 86_400_000L).toInt() + 1
        Txt(if (days == 0) "${store.name} 还在认识你" else "${store.name} 认识你 $days 天了", Page.ink, 19.sp, Modifier.padding(start = 4.dp, top = 2.dp), family = Serif, lineHeight = 30.sp)
        Spacer(Modifier.height(4.dp))
        Txt(
            listOfNotNull("聊过 ${Memory.chats} 次", "记得 ${Memory.items.size} 件事", ago(Memory.lastAt).takeIf { it.isNotEmpty() }?.let { "上次是$it" }).joinToString(" · "),
            Page.muted, 12.5.sp, Modifier.padding(start = 4.dp),
        )

        // 它记得 + 全局记忆开关
        SectionLabel("它记得") {
            Txt(if (Memory.enabled) "全局记忆" else "全局记忆已关", Page.muted, 12.sp, Modifier.padding(end = 10.dp))
            Switch(Memory.enabled, "全局记忆") { Memory.enabled = it; Memory.save() }
        }
        var open by remember { mutableStateOf<String?>(null) }
        var all by remember { mutableStateOf(false) }
        Column(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), blur, fill = glassFill)
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)
                .alpha(if (Memory.enabled) 1f else .5f)
                .animateContentSize(),
        ) {
            if (!Memory.enabled) {
                Txt("关闭后，${store.name} 不会记住新的事，也不会用到已经记住的。", Page.muted, 13.sp, Modifier.padding(bottom = 6.dp), lineHeight = 21.sp)
            }
            if (Memory.items.isEmpty()) {
                Txt("还没有记住什么。聊得多了，值得记住的事会出现在这里。", Page.muted, 13.sp, Modifier.padding(bottom = 4.dp), lineHeight = 21.sp)
            }
            val shown = if (all) Memory.items.toList() else Memory.items.take(5)
            shown.forEachIndexed { k, m ->
                val isOpen = open == m.id
                Pressable({ open = if (isOpen) null else m.id }, Modifier.fillMaxWidth(), label = m.text) { _ ->
                    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                        // 时间线：竖线 + 节点，最新一条是杏色
                        Box(Modifier.width(16.dp).fillMaxHeight()) {
                            if (k < shown.lastIndex) Box(Modifier.padding(top = 14.dp).align(Alignment.TopCenter).width(1.dp).fillMaxHeight().background(rgba(255, 255, 255, .12f)))
                            Box(Modifier.padding(top = 8.dp).align(Alignment.TopCenter).size(6.dp).background(if (k == 0) warm else rgba(255, 255, 255, .35f), CircleShape))
                        }
                        Column(Modifier.weight(1f).padding(start = 8.dp, bottom = 14.dp)) {
                            Txt(m.text, Page.ink, 14.5.sp, lineHeight = 22.sp, maxLines = if (isOpen) Int.MAX_VALUE else 1)
                            Spacer(Modifier.height(2.dp))
                            Txt(ago(m.at), Page.muted, 12.sp)
                            if (isOpen) {
                                if (m.detail.isNotBlank()) {
                                    Spacer(Modifier.height(10.dp))
                                    Txt(m.detail, Page.ink.copy(alpha = .78f), 13.5.sp, lineHeight = 22.sp)
                                }
                                if (m.convTitle.isNotBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                    Txt("来自「${m.convTitle}」", Page.muted, 12.sp, maxLines = 1)
                                }
                                Spacer(Modifier.height(10.dp))
                                Pressable({ Memory.remove(m); open = null }, label = "忘掉这件事") { p ->
                                    Txt(
                                        "忘掉这件事", rose, 12.5.sp,
                                        Modifier.background(rgba(201, 138, 134, if (p) .2f else .12f), RoundedCornerShape(50)).padding(horizontal = 13.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (Memory.items.size > 5) {
                Pressable({ all = !all }, label = "全部") { p ->
                    Txt(if (all) "收起" else "全部 ${Memory.items.size} 件 ›", if (p) Page.ink else Page.muted, 13.sp, Modifier.padding(top = 2.dp, bottom = 4.dp))
                }
            }
        }

        // 你喜欢
        SectionLabel("你喜欢")
        ChipEditor(Memory.likes, "比如：回答简洁", onAdd = { Memory.addLike(it) }, onRemove = { Memory.likes.remove(it); Memory.save() })

        // 它的样子
        SectionLabel("它的样子")
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), blur, fill = glassFill).padding(horizontal = 16.dp, vertical = 16.dp)) {
            ToneSlider("温柔", "直率", Memory.gentle) { Memory.gentle = it }
            Spacer(Modifier.height(18.dp))
            ToneSlider("平静", "活泼", Memory.lively) { Memory.lively = it }
            Spacer(Modifier.height(16.dp))
            ChipEditor(Memory.amor, "比如：叫我「你」，不用「您」", onAdd = { Memory.addAmor(it) }, onRemove = { Memory.amor.remove(it); Memory.save() })
        }

        // 更多
        SectionLabel("更多")
        var sysOpen by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), blur, fill = glassFill).animateContentSize()) {
            Pressable({ sysOpen = !sysOpen }, Modifier.fillMaxWidth(), label = "系统提示词") { p ->
                Row(
                    Modifier.fillMaxWidth().background(if (p) rgba(255, 255, 255, .04f) else Color.Transparent).padding(horizontal = 18.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt("系统提示词", Page.ink, 15.sp, Modifier.weight(1f))
                    Txt(if (Memory.systemPrompt.isBlank()) "未设置" else "已设置", Page.muted, 13.sp)
                    Spacer(Modifier.width(6.dp))
                    SvgIcon(MineIcons.next, Page.muted, 18.dp, modifier = Modifier.graphicsLayer { rotationZ = if (sysOpen) 90f else 0f })
                }
            }
            if (sysOpen) {
                BasicTextField(
                    value = Memory.systemPrompt,
                    onValueChange = { Memory.systemPrompt = it; Memory.save() },
                    textStyle = TextStyle(color = Page.ink, fontSize = 14.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(Page.accent),
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth().heightIn(min = 96.dp).background(rgba(0, 0, 0, .2f), RoundedCornerShape(14.dp)).padding(14.dp)) {
                            if (Memory.systemPrompt.isEmpty()) Txt("写下你希望 ${store.name} 一直遵守的规则…", Page.muted.copy(alpha = .6f), 14.sp)
                            inner()
                        }
                    },
                )
            }
            Divider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp).alpha(.38f)) { Txt("待开放", Page.ink, 15.sp) }
        }
    }
}

/** 一排小标签 + 「+ 添加」：点标签出现 ×，点 × 删掉；点添加原地变成输入框。 */
@Composable
private fun ChipEditor(list: List<String>, hint: String, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    var picked by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        list.forEach { t ->
            val sel = picked == t
            Pressable({ picked = if (sel) null else t }, label = t) { _ ->
                Row(
                    Modifier.background(rgba(255, 255, 255, if (sel) .13f else .08f), RoundedCornerShape(50)).padding(start = 14.dp, end = if (sel) 6.dp else 14.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(t, Page.ink, 13.sp, maxLines = 1)
                    if (sel) Pressable({ onRemove(t); picked = null }, Modifier.padding(start = 4.dp), label = "删除") { _ ->
                        SvgIcon(Icons.close, Page.muted, 14.dp)
                    }
                }
            }
        }
        if (adding) {
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it.take(30) },
                singleLine = true,
                textStyle = TextStyle(color = Page.ink, fontSize = 13.sp),
                cursorBrush = SolidColor(warm),
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                    if (draft.isNotBlank()) onAdd(draft); draft = ""; adding = false
                }),
                modifier = Modifier.focusRequester(focus).onFocusChanged { if (!it.isFocused && adding && draft.isBlank()) adding = false }
                    .widthIn(min = 120.dp, max = 260.dp),
                decorationBox = { inner ->
                    Box(Modifier.background(rgba(233, 180, 142, .12f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                        if (draft.isEmpty()) Txt(hint, Page.muted, 13.sp, maxLines = 1)
                        inner()
                    }
                },
            )
        } else {
            Pressable({ adding = true; picked = null }, label = "添加") { p ->
                Txt("+ 添加", warm, 13.sp, Modifier.background(rgba(233, 180, 142, if (p) .24f else .14f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

/** 两端文字的滑杆：按住拖动，点一下也能跳过去；松手后保存。 */
@Composable
private fun ToneSlider(left: String, right: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Txt(left, if (value < .5f) Page.ink else Page.muted, 12.5.sp, Modifier.weight(1f))
            Txt(right, if (value >= .5f) Page.ink else Page.muted, 12.5.sp)
        }
        Spacer(Modifier.height(6.dp))
        var w by remember { mutableStateOf(1f) }
        Box(
            Modifier.fillMaxWidth().height(28.dp)
                .onSizeChanged { w = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(Unit) {
                    detectTapGestures { onChange((it.x / w).coerceIn(0f, 1f)); Memory.save() }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(onDragEnd = { Memory.save() }) { ch, _ ->
                        ch.consume(); onChange((ch.position.x / w).coerceIn(0f, 1f))
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(rgba(255, 255, 255, .1f), RoundedCornerShape(2.dp)))
            Box(Modifier.fillMaxWidth(value.coerceIn(.001f, 1f)).height(4.dp).background(warm, RoundedCornerShape(2.dp)))
            val d = LocalDensity.current
            Box(
                Modifier.graphicsLayer { translationX = value * w - with(d) { 9.dp.toPx() } }
                    .size(18.dp).background(Page.ink, CircleShape),
            )
        }
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
        PROVIDERS.filter { q.isBlank() || it.name.contains(q.trim(), ignoreCase = true) }.forEach { p ->
            val c = store.config(p.id)
            Pressable({ nav.screen = "provider/${p.id}" }, Modifier.fillMaxWidth(), label = p.name) { pressed ->
                Row(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), blur, fill = if (pressed) rgba(255, 255, 255, .08f) else glassFill)
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(44.dp), contentAlignment = Alignment.CenterStart) {
                        Txt(p.letter, Page.ink, 24.sp, family = Serif)
                    }
                    Column(Modifier.weight(1f)) {
                        Txt(c.name, Page.ink, 17.sp, weight = FontWeight.Medium, maxLines = 1)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            when {
                                c.enabled && c.configured -> Chip("启用", fill = Page.ink.copy(alpha = .92f), ink = css("#2a2f38"))
                                c.configured -> Chip("未启用")
                                else -> Chip("未配置")
                            }
                            Chip("${c.models.size} 个模型")
                            store.balances[p.id]?.let { Chip(it.substringBefore(" ·")) } ?: run { if (p.note.isNotEmpty()) Chip(p.note) }
                        }
                    }
                    SvgIcon(MineIcons.next, Page.ink, 20.dp)
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
    Title(saved.name, compact, onBack = { nav.back() })
    var tab by remember(p.id) { mutableStateOf("配置") }
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        listOf("配置", "模型").forEach { t ->
            Pressable({ tab = t }, Modifier.weight(1f), label = t) { _ ->
                Box(
                    Modifier.fillMaxWidth().background(if (tab == t) rgba(0, 0, 0, .28f) else Color.Transparent, RoundedCornerShape(14.dp)).padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) { Txt(t, if (tab == t) Page.ink else Page.muted, 15.sp, spacing = ls(2f, 15f)) }
            }
        }
    }
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        if (tab == "配置") ConfigTab(store, p, blur) else ModelsTab(store, p, blur)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ConfigTab(store: AssistantStore, p: Provider, blur: Dp) {
    val saved = store.config(p.id)
    var draft by remember(p.id, saved) { mutableStateOf(saved) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember(p.id) { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()

    // 打开余额开关、且已填密钥时，查一次余额
    LaunchedEffect(p.id, saved.key, saved.balance) {
        if (p.hasBalance && saved.balance && saved.configured) {
            AssistantApi.balance(p, saved).onSuccess { store.balances[p.id] = it }
        }
    }

    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp), blur, fill = glassFill).padding(18.dp)) {
        @Composable
        fun Label(t: String) = Txt(t, Page.ink, 14.sp, Modifier.padding(bottom = 10.dp), weight = FontWeight.Medium)

        Label("名称")
        Field(draft.name, { draft = draft.copy(name = it) }, p.name)
        Spacer(Modifier.height(18.dp))
        Label("API Key")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Field(draft.key, { draft = draft.copy(key = it.trim()) }, "sk-…", secret = !showKey, keyboard = KeyboardType.Password) }
            Pressable({ showKey = !showKey }, label = if (showKey) "隐藏密钥" else "显示密钥") { _ ->
                SvgIcon(if (showKey) MineIcons.eye else MineIcons.eyeOff, Page.ink, 22.dp, modifier = Modifier.padding(start = 14.dp, end = 4.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Label("API Base Url")
        Field(draft.base, { draft = draft.copy(base = it.trim()) }, p.base, keyboard = KeyboardType.Uri)
        Spacer(Modifier.height(18.dp))
        Label("API 路径")
        Field(draft.path, { draft = draft.copy(path = it.trim()) }, p.path, keyboard = KeyboardType.Uri)
        Spacer(Modifier.height(18.dp))
        Divider()
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("是否启用", Page.ink, 15.sp, Modifier.weight(1f))
            Switch(draft.enabled, "是否启用") { draft = draft.copy(enabled = it) }
        }
        if (p.hasBalance) {
            Divider()
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("获取账户余额", Page.ink, 15.sp, Modifier.weight(1f))
                Switch(draft.balance, "获取账户余额") { draft = draft.copy(balance = it) }
            }
            if (draft.balance) store.balances[p.id]?.let { Txt(it, Page.muted, 13.sp, Modifier.padding(bottom = 10.dp)) }
        }
        Spacer(Modifier.height(8.dp))
        PillButton(if (testing) "正在测试…" else "测试连接", MineIcons.plug, primary = false, enabled = !testing, modifier = Modifier.fillMaxWidth()) {
            testing = true; result = null
            scope.launch {
                AssistantApi.models(p, draft)
                    .onSuccess { result = true to "连接成功，可用 ${it.size} 个模型" }
                    .onFailure {
                        val k = draft.key.trim()
                        result = false to (
                            if (p.id == "deepseek" && k.isNotEmpty() && !k.startsWith("sk-")) "密钥应以 sk- 开头，好像少了这几个字符"
                            else it.message ?: "连接失败"
                        )
                    }
                if (p.hasBalance && draft.balance && result?.first == true) {
                    AssistantApi.balance(p, draft).onSuccess { store.balances[p.id] = it }
                }
                testing = false
            }
        }
        result?.let { (ok, msg) ->
            Txt(msg, if (ok) Page.accent else rose, 13.sp, Modifier.padding(top = 12.dp, start = 4.dp))
        }
        Spacer(Modifier.height(16.dp))
        Divider()
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            Pressable({ draft = ProviderConfig.default(p).copy(key = draft.key, models = draft.models); result = null }, label = "恢复默认地址") { _ ->
                SvgIcon(MineIcons.reset, Page.ink, 22.dp, modifier = Modifier.padding(12.dp))
            }
            Spacer(Modifier.width(10.dp))
            PillButton("保存", null, primary = true, enabled = draft != saved) { store.update(p.id, draft) }
        }
    }
}

@Composable
private fun ModelsTab(store: AssistantStore, p: Provider, blur: Dp) {
    val c = store.config(p.id)
    val scope = rememberCoroutineScope()
    var status by remember(p.id) { mutableStateOf<Pair<Boolean, String>?>(null) }
    var pinging by remember(p.id) { mutableStateOf<String?>(null) }
    var adding by remember(p.id) { mutableStateOf(false) }
    var newModel by remember(p.id) { mutableStateOf("") }
    var fetching by remember(p.id) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        c.models.forEach { m ->
            val inUse = store.activeProvider == p.id && store.activeModel == m
            Pressable({ store.use(p.id, m) }, Modifier.fillMaxWidth(), label = "让 ${store.name} 用 $m") { pressed ->
                Row(
                    Modifier.fillMaxWidth()
                        .glass(RoundedCornerShape(20.dp), blur, fill = if (pressed) rgba(255, 255, 255, .08f) else glassFill, border = if (inUse) rose.copy(alpha = .7f) else Page.glassBorder)
                        .padding(start = 18.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SvgIcon(Icons.spark, Page.ink.copy(alpha = .8f), 20.dp, stroke = 1.2f)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(m, Page.ink, 16.sp, weight = FontWeight.Medium, maxLines = 1)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (inUse) Chip("${store.name} 在用", fill = rose.copy(alpha = .75f), ink = Page.ink)
                            Chip("聊天")
                            Chip("文本 → 文本")
                        }
                    }
                    Pressable({
                        pinging = m; status = null
                        scope.launch {
                            AssistantApi.ping(p, store.config(p.id), m)
                                .onSuccess { status = true to "$m 可以正常使用" }
                                .onFailure { status = false to "$m：${it.message}" }
                            pinging = null
                        }
                    }, enabled = pinging == null, label = "测试 $m") { _ ->
                        SvgIcon(MineIcons.plug, if (pinging == m) Page.accent else Page.ink, 22.dp, modifier = Modifier.padding(10.dp))
                    }
                    Pressable({
                        store.update(p.id, c.copy(models = c.models - m))
                        if (inUse) store.use(p.id, (c.models - m).firstOrNull() ?: "")
                    }, label = "删除 $m") { _ ->
                        SvgIcon(Icons.close, Page.ink, 20.dp, modifier = Modifier.padding(10.dp))
                    }
                }
            }
        }
        if (c.models.isEmpty()) Txt("还没有模型。点“获取模型”从供应商拉取，或手动添加。", Page.muted, 13.sp, Modifier.padding(4.dp), lineHeight = 22.sp)
        status?.let { (ok, msg) -> Txt(msg, if (ok) Page.accent else rose, 13.sp, Modifier.padding(start = 4.dp)) }
        Txt("点一个模型，${store.name} 就用它聊天。", Page.muted, 13.sp, Modifier.fillMaxWidth().padding(top = 6.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
        if (adding) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field(newModel, { newModel = it.trim() }, "模型名，例如 deepseek-flash") }
                Spacer(Modifier.width(10.dp))
                PillButton("添加", null, primary = true, enabled = newModel.isNotBlank()) {
                    if (newModel !in c.models) store.update(p.id, c.copy(models = c.models + newModel))
                    newModel = ""; adding = false
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
            PillButton(if (fetching) "获取中…" else "获取模型", MineIcons.box, primary = false, enabled = !fetching) {
                fetching = true; status = null
                scope.launch {
                    AssistantApi.models(p, store.config(p.id))
                        .onSuccess { list ->
                            val now = store.config(p.id)
                            val added = list.filter { it !in now.models }
                            store.update(p.id, now.copy(models = now.models + added))
                            status = true to if (added.isEmpty()) "没有新模型" else "新增 ${added.size} 个模型"
                        }
                        .onFailure { status = false to (it.message ?: "获取失败") }
                    fetching = false
                }
            }
            PillButton("添加新模型", MineIcons.plus, primary = true) { adding = !adding }
        }
    }
}
