package com.fuguang.lueying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

private const val ITEM = 56   // 每位占的高度（dp）
private const val TOP = 6

/**
 * 侧栏左边一竖排头像（「我的 → 主题 → 换助理的方式」选「竖排头像」时，第 305 版）：
 * 点一位就换，选中的变圆角方块、左边一道杏色小条弹过去；按住一位，从头像旁边长出扁卡片看她（不用换过去）；最下面「＋」去管理助理。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AssistantRail(history: ConvStore, onManage: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val idx = Memory.all.indexOfFirst { it.id == Memory.activeId }.coerceAtLeast(0)
    val barY by animateDpAsState((TOP + idx * ITEM + 10).dp, spring(dampingRatio = .72f, stiffness = 420f), label = "bar")
    var peek by remember { mutableStateOf<Persona?>(null) }
    Box(Modifier.width(64.dp).fillMaxHeight()) {
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = TOP.dp, bottom = 110.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Memory.all.forEach { p ->
                val on = p.id == Memory.activeId
                val src = remember { MutableInteractionSource() }
                val pressed by src.collectIsPressedAsState()
                val corner by animateDpAsState(if (on) 15.dp else 22.dp, spring(dampingRatio = .8f, stiffness = 420f), label = "corner")
                val dim by animateFloatAsState(if (on) 1f else .62f, label = "dim")
                val sc by animateFloatAsState(if (pressed) .9f else 1f, if (pressed) tween(300) else spring(.45f, 600f), label = "press")
                Box(Modifier.size(64.dp, ITEM.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.graphicsLayer { scaleX = sc; scaleY = sc; alpha = dim }
                            .combinedClickable(src, null,
                                onClick = { if (!on) { Memory.use(p); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } },
                                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); peek = p },
                                onClickLabel = "换成 ${p.name}",
                            ),
                    ) { PersonaDot(p, 44.dp, RoundedCornerShape(corner)) }
                    if (peek?.id == p.id) PeekCard(history, p, onDismiss = { peek = null }, onUse = { if (p.id != Memory.activeId) Memory.use(p); peek = null }, onManage = { peek = null; onManage() })
                }
            }
            Pressable(onManage, Modifier.padding(top = 4.dp), label = "管理助理") { pr ->
                Box(Modifier.size(44.dp).border(1.5.dp, veil(if (pr) .3f else .16f), CircleShape), contentAlignment = Alignment.Center) {
                    SvgIcon(SideIcons.plus, if (pr) Page.ink else Page.muted, 18.dp, stroke = 1.7f)
                }
            }
        }
        // 左边那道杏色小条：跟着选中的那位弹过去
        Box(Modifier.offset(y = barY).width(4.dp).height(36.dp).background(SideWarm, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)))
        // 和右边对话列表之间一道很淡的竖线
        Box(Modifier.align(Alignment.TopEnd).width(1.dp).fillMaxHeight().padding(bottom = 96.dp).background(veil(.04f)))
    }
}

/** 按住头像时，从头像右边长出的扁卡片：她是谁、聊过多少、最近那段对话；「她的设置」「换成她」。 */
@Composable
private fun PeekCard(history: ConvStore, p: Persona, onDismiss: () -> Unit, onUse: () -> Unit, onManage: () -> Unit) {
    val k = remember { Animatable(0f) }
    LaunchedEffect(Unit) { k.animateTo(1f, spring(dampingRatio = .8f, stiffness = 420f)) }
    Popup(alignment = Alignment.CenterStart, offset = IntOffset(with(androidx.compose.ui.platform.LocalDensity.current) { 58.dp.roundToPx() }, 0),
        onDismissRequest = onDismiss, properties = PopupProperties(focusable = true, dismissOnClickOutside = true)) {
        Column(
            Modifier.width(236.dp)
                .graphicsLayer { scaleX = .85f + .15f * k.value; scaleY = .85f + .15f * k.value; alpha = k.value.coerceIn(0f, 1f); transformOrigin = TransformOrigin(0f, .5f) }
                .background(Skin.sheet, RoundedCornerShape(18.dp)).padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PersonaDot(p, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Txt(p.name, Page.ink, 16.sp, maxLines = 1)
                    Txt(personaMeta(history, p), SideFaint, 11.5.sp, Modifier.padding(top = 2.dp), maxLines = 1)
                }
            }
            val last = history.of(p.id).maxByOrNull { it.updated }
            Txt(last?.let { "最近：" + it.title } ?: "还没聊过。", Page.muted, 12.5.sp,
                Modifier.padding(top = 12.dp).fillMaxWidth().background(veil(.05f), RoundedCornerShape(12.dp)).padding(horizontal = 11.dp, vertical = 9.dp), maxLines = 2, lineHeight = 19.sp)
            Row(Modifier.padding(top = 12.dp)) {
                PressSurface(onManage, Modifier.weight(1f).height(38.dp), shape = RoundedCornerShape(12.dp), fill = veil(.06f), pressedFill = veil(.12f), label = "她的设置") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Txt("她的设置", Page.ink, 13.5.sp) }
                }
                Spacer(Modifier.width(8.dp))
                val on = p.id == Memory.activeId
                PressSurface(onUse, Modifier.weight(1f).height(38.dp), shape = RoundedCornerShape(12.dp), fill = SideWarm, pressedFill = SideWarm.copy(alpha = .85f), label = if (on) "就是她" else "换成她") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Txt(if (on) "就是她" else "换成她", Color(0xFF2A211B), 13.5.sp) }
                }
            }
        }
    }
}

/** 竖排时右边顶上：现在这一位的名字 + 一行小字，右边搜索。 */
@Composable
internal fun RailTitle(history: ConvStore, onSearch: () -> Unit) {
    val now = Memory.active
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            AnimatedContent(now.name, transitionSpec = {
                (fadeIn(tween(240, 60)) + slideInVertically(tween(280, 60, Curves.standard)) { it / 3 }) togetherWith (fadeOut(tween(140)) + slideOutVertically(tween(160)) { -it / 3 })
            }, label = "railName") { Txt(it, Page.ink, 19.sp, spacing = ls(2.4f, 19f), family = Serif, maxLines = 1) }
            Txt(personaMeta(history, now), SideFaint, 11.5.sp, Modifier.padding(top = 2.dp), maxLines = 1)
        }
        Pressable(onSearch, label = "搜索对话") { p ->
            SvgIcon(SideIcons.search, if (p) Page.ink else SideInk, 21.dp, stroke = 1.5f, modifier = Modifier.padding(8.dp))
        }
    }
}
