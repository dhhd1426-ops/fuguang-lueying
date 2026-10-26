package com.fuguang.lueying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

/** 每位助理一个颜色（按她在列表里的位置）。 */
private val Tints = listOf(Color(0xFFE9B48E), Color(0xFFB9C7A8), Color(0xFFA9B6CC), Color(0xFFD3A9B4), Color(0xFFC9BC97))

internal fun tintOf(p: Persona): Color = Tints[(Memory.all.indexOf(p).coerceAtLeast(0)) % Tints.size]

@Composable
internal fun PersonaDot(p: Persona, size: Dp, shape: androidx.compose.ui.graphics.Shape = androidx.compose.foundation.shape.CircleShape) {
    AvatarFace(p.id, p.name.trim().take(1), androidx.compose.ui.graphics.SolidColor(tintOf(p)), size, shape = shape)
}

/** 「117 段对话 · 21 条记忆」。 */
internal fun personaMeta(history: ConvStore, p: Persona): String {
    val n = history.of(p.id).size
    return "${if (n == 0) "还没有对话" else "$n 段对话"} · ${if (p.items.isEmpty()) "还没有记忆" else "${p.items.size} 条记忆"}"
}

/** 名字签：头像 + 名字 + 小箭头。侧栏顶上那颗和展开后卡片顶上那颗是同一个样子，展开时一点不跳。 */
@Composable
private fun NameChip(now: Persona, turn: Float) {
    Row(Modifier.padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        AnimatedContent(now, transitionSpec = { (fadeIn(tween(220, 60)) + scaleIn(tween(260, 60, Curves.standard), .8f)) togetherWith (fadeOut(tween(140)) + scaleOut(tween(140), .8f)) }, label = "dot") { PersonaDot(it, 28.dp) }
        Spacer(Modifier.width(10.dp))
        AnimatedContent(now.name, Modifier.widthIn(max = 160.dp), transitionSpec = {
            (fadeIn(tween(240, 60)) + slideInVertically(tween(280, 60, Curves.standard)) { it / 3 }) togetherWith (fadeOut(tween(140)) + slideOutVertically(tween(160)) { -it / 3 })
        }, label = "name") { Txt(it, Page.ink, 19.sp, spacing = ls(2.4f, 19f), family = Serif, maxLines = 1) }
        Spacer(Modifier.width(6.dp))
        SvgIcon(Ic("M6 9.5l6 6 6-6"), SideFaint, 16.dp, stroke = 1.8f, modifier = Modifier.graphicsLayer { rotationZ = turn })
    }
}

/**
 * 侧栏顶上（连体卡片，第 305 版，照 DroidDeck 的设置面板）：点名字，名字那颗变成一个「签」，
 * 卡片从签里往下、往右长出来，和签是同一整块色块（不描边）；行随后浮出。点一位：✓ 落定 260ms 后整块收回签里。
 */
@Composable
internal fun AssistantTitle(history: ConvStore, onSearch: () -> Unit, onManage: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) }
    // 点空白收起时，那一下也会落在名字上：刚收起的 250ms 内不再打开
    var shutAt by remember { mutableStateOf(0L) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(picked) { if (picked != null) { delay(260); open = false; picked = null } }
    androidx.activity.compose.BackHandler(enabled = open) { open = false }
    val now = Memory.active
    val turn by animateFloatAsState(if (open) 180f else 0f, spring(dampingRatio = .8f, stiffness = 420f), label = "chev")
    // 0 = 只有签；1 = 整块展开。开：弹簧；关：加速收回（离开更快）
    val grow = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) { shown = true; grow.animateTo(1f, spring(dampingRatio = .86f, stiffness = 360f)) }
        else if (shown) { grow.animateTo(0f, tween(200, easing = Curves.accel)); shown = false }
    }
    var chip by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.onSizeChanged { chip = it }) {
            Pressable({ if (System.currentTimeMillis() - shutAt > 250) open = !open }, label = "切换助理，现在是 ${now.name}") { p ->
                Box(Modifier.graphicsLayer { alpha = if (shown) 0f else 1f }.background(if (p) veil(.05f) else Color.Transparent, RoundedCornerShape(14.dp))) { NameChip(now, turn) }
            }
            if (shown) Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, 0),
                onDismissRequest = { if (open) { open = false; shutAt = System.currentTimeMillis() } },
                properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
            ) {
                PickPanel(history, now, turn, grow.value, chip,
                    onToggle = { open = false },
                    onPick = { p -> if (p.id != Memory.activeId) { Memory.use(p); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }; picked = p.id },
                    onManage = { open = false; onManage() })
            }
        }
        Spacer(Modifier.weight(1f))
        Pressable(onSearch, label = "搜索对话") { p ->
            SvgIcon(SideIcons.search, if (p) Page.ink else SideInk, 21.dp, stroke = 1.5f, modifier = Modifier.padding(8.dp))
        }
    }
}

/** 签 + 卡片这一整块：按 k 从签的大小长到整块的大小，色块和里面的东西都只画在这块里面。 */
@Composable
private fun PickPanel(
    history: ConvStore, now: Persona, turn: Float, k: Float, chip: androidx.compose.ui.unit.IntSize,
    onToggle: () -> Unit, onPick: (Persona) -> Unit, onManage: () -> Unit,
) {
    var full by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val fill = Skin.sheet
    val r = with(LocalDensity.current) { 16.dp.toPx() }
    Column(
        Modifier.width(256.dp)
            .onSizeChanged { full = it }
            .drawWithContent {
                val w = chip.width + (full.width - chip.width) * k
                val h = chip.height + (full.height - chip.height) * k
                val path = androidx.compose.ui.graphics.Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, CornerRadius(r))) }
                drawRoundRect(fill, size = Size(w, h), cornerRadius = CornerRadius(r))
                clipPath(path) { this@drawWithContent.drawContent() }
            },
    ) {
        Pressable(onToggle, label = "收起") { _ -> Box(Modifier.fillMaxWidth()) { NameChip(now, turn) } }
        Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp)) {
            Memory.all.forEachIndexed { i, p ->
                val on = p.id == Memory.activeId
                // 行跟在色块后面一行行浮出
                val a = ((k - .35f - i * .08f) / .4f).coerceIn(0f, 1f)
                Pressable({ onPick(p) }, Modifier.fillMaxWidth().graphicsLayer { alpha = a; translationY = (1f - a) * -6.dp.toPx() }, label = "换成 ${p.name}") { pr ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (on) SideWarm.copy(alpha = .12f) else if (pr) veil(.06f) else Color.Transparent, RoundedCornerShape(14.dp))
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PersonaDot(p, 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(p.name, Page.ink, 15.sp, maxLines = 1)
                            Txt(personaMeta(history, p), SideFaint, 11.5.sp, Modifier.padding(top = 2.dp), maxLines = 1)
                        }
                        if (on) SvgIcon(Ic("M5 12.5l4.5 4.5L19 7.5"), SideWarm, 16.dp, stroke = 1.8f)
                    }
                }
            }
            val a = ((k - .5f) / .4f).coerceIn(0f, 1f)
            Box(Modifier.graphicsLayer { alpha = a }.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(veil(.06f)))
            Pressable(onManage, Modifier.fillMaxWidth().graphicsLayer { alpha = a }, label = "管理助理") { pr ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SvgIcon(SideIcons.plus, if (pr) Page.ink else Page.muted, 16.dp, stroke = 1.7f)
                    Spacer(Modifier.width(10.dp))
                    Txt("管理助理", if (pr) Page.ink else Page.muted, 13.5.sp)
                }
            }
        }
    }
}
