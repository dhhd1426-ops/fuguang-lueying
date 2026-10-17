package com.fuguang.lueying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 每位助理一个颜色（按她在列表里的位置）。 */
private val Tints = listOf(Color(0xFFE9B48E), Color(0xFFB9C7A8), Color(0xFFA9B6CC), Color(0xFFD3A9B4), Color(0xFFC9BC97))

internal fun tintOf(p: Persona): Color = Tints[(Memory.all.indexOf(p).coerceAtLeast(0)) % Tints.size]

@Composable
internal fun PersonaDot(p: Persona, size: Dp) {
    Box(Modifier.size(size).background(tintOf(p), CircleShape), contentAlignment = Alignment.Center) {
        Txt(p.name.trim().take(1).ifEmpty { "?" }, Color(0xFF2A211B), (size.value * .5f).sp, family = Serif, maxLines = 1)
    }
}

/**
 * 侧栏顶上：现在这一位的名字（每位助理一个分区，下面只列她的对话）。
 * 点名字，下面长出一张小卡片列出所有助理；点一位：✓ 先落到她身上，卡片自己收回，列表换成她的。
 * 最下面「管理助理」去「我的 → 助理」。
 */
@Composable
internal fun AssistantTitle(history: ConvStore, onSearch: () -> Unit, onManage: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    // ✓ 落定后再收：先让人看到换成了谁
    LaunchedEffect(picked) { if (picked != null) { delay(260); open = false; picked = null } }
    androidx.activity.compose.BackHandler(enabled = open) { open = false }
    val now = Memory.active
    val turn by animateFloatAsState(if (open) 180f else 0f, spring(dampingRatio = .8f, stiffness = 420f), label = "chev")
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pressable({ open = !open }, Modifier.weight(1f), label = "切换助理，现在是 ${now.name}") { p ->
                Row(
                    Modifier.background(if (open || p) rgba(255, 255, 255, if (p) .05f else .07f) else Color.Transparent, RoundedCornerShape(14.dp))
                        .padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedContent(now, transitionSpec = { (fadeIn(tween(220, 60)) + scaleIn(tween(260, 60, Curves.standard), .8f)) togetherWith (fadeOut(tween(140)) + scaleOut(tween(140), .8f)) }, label = "dot") { PersonaDot(it, 28.dp) }
                    Spacer(Modifier.width(10.dp))
                    AnimatedContent(now.name, Modifier.weight(1f, fill = false), transitionSpec = {
                        (fadeIn(tween(240, 60)) + slideInVertically(tween(280, 60, Curves.standard)) { it / 3 }) togetherWith (fadeOut(tween(140)) + slideOutVertically(tween(160)) { -it / 3 })
                    }, label = "name") { Txt(it, Page.ink, 19.sp, spacing = ls(2.4f, 19f), family = Serif, maxLines = 1) }
                    Spacer(Modifier.width(6.dp))
                    SvgIcon(Ic("M6 9.5l6 6 6-6"), SideFaint, 16.dp, stroke = 1.8f, modifier = Modifier.graphicsLayer { rotationZ = turn })
                }
            }
            Pressable(onSearch, label = "搜索对话") { p ->
                SvgIcon(SideIcons.search, if (p) Page.ink else SideInk, 21.dp, stroke = 1.5f, modifier = Modifier.padding(8.dp))
            }
        }
        AnimatedVisibility(
            open,
            enter = expandVertically(spring(dampingRatio = .8f, stiffness = 420f), expandFrom = Alignment.Top, initialHeight = { 0 }) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(220, easing = Curves.standard), shrinkTowards = Alignment.Top) + fadeOut(tween(160)),
        ) {
            Column(
                Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp).fillMaxWidth()
                    .background(Color(0xFF22262F), RoundedCornerShape(18.dp)).padding(6.dp),
            ) {
                Memory.all.forEach { p ->
                    val on = p.id == Memory.activeId || (Memory.activeId.isEmpty() && p == now)
                    Pressable({ if (!on) { Memory.use(p); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }; picked = p.id }, Modifier.fillMaxWidth(), label = "换成 ${p.name}") { pr ->
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (on) SideWarm.copy(alpha = .12f) else if (pr) rgba(255, 255, 255, .06f) else Color.Transparent, RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PersonaDot(p, 32.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Txt(p.name, Page.ink, 15.sp, maxLines = 1)
                                val n = history.of(p.id).size
                                Txt("${if (n == 0) "还没有对话" else "$n 段对话"} · ${if (p.items.isEmpty()) "还没有记忆" else "${p.items.size} 条记忆"}", SideFaint, 11.5.sp, Modifier.padding(top = 2.dp), maxLines = 1)
                            }
                            if (on) SvgIcon(Ic("M5 12.5l4.5 4.5L19 7.5"), SideWarm, 18.dp, stroke = 1.8f)
                        }
                    }
                }
                Box(Modifier.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(rgba(255, 255, 255, .06f)))
                Pressable({ open = false; onManage() }, Modifier.fillMaxWidth(), label = "管理助理") { pr ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        SvgIcon(SideIcons.plus, if (pr) Page.ink else Page.muted, 16.dp, stroke = 1.7f)
                        Spacer(Modifier.width(10.dp))
                        Txt("管理助理", if (pr) Page.ink else Page.muted, 13.5.sp)
                    }
                }
            }
        }
    }
}
