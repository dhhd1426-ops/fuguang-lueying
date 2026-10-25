package com.fuguang.lueying

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** 「我的」卡片的开关：侧栏头像记下自己的中心（卡片从这里长出、缩回这里），点一下 open。 */
object MineCard : FloatCard()

private val person = Ic("M5.5 19.5c.9-3.4 3.4-5.3 6.5-5.3s5.6 1.9 6.5 5.3", circles = listOf(floatArrayOf(12f, 8.5f, 3.4f)))
private val chevron = Ic("M9.5 6l6 6-6 6")
private val tune = Ic("M6 4v5M6 14v6M12 4v2M12 11v9M18 4v9M18 18v2", circles = listOf(floatArrayOf(6f, 11.5f, 2.3f), floatArrayOf(12f, 8.5f, 2.3f), floatArrayOf(18f, 15.5f, 2.3f)))
private val warm = Color(0xFFE9B48E)
private val warmInk = Color(0xFF2A211B)

/** 页面深度：往深处走新页从右边进，往回走从左边进。 */
private fun depth(s: String) = when {
    s == "mine" -> 0
    s == "assistant" || s == "mcp" || s == "about" || s == "features" || s == "theme" -> 1
    s == "persona" || s.startsWith("mcp/") || s == "search" || s == "skills" || s == "gallery" -> 2
    s.startsWith("search/") || s.startsWith("skills/") || s.startsWith("gallery/") -> 3
    s == "providers" -> 3
    else -> 4
}

/** 「我的」：统一的悬浮卡片（CenterCard.kt）。助理、记忆、MCP、关于这些二级页也在卡片里一页页推进。 */
@Composable
fun MineCardLayer(store: AssistantStore, nav: MineNav) {
    CenterCardLayer(
        MineCard, nav.screen, ::depth,
        onBack = { if (nav.screen == "about" || nav.screen == "theme") { nav.screen = "mine"; true } else nav.screen != "mine" && nav.back() },
        onClosed = { nav.screen = "mine"; nav.persona = "" },
        above = { MemoryCardHost(); AvatarCropHost(); IconCropHost() },
    ) { screen, rise ->
        when (screen) {
            "mine" -> Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
                MineCardBody(rise) { nav.screen = it }
            }
            "about" -> AboutPage { nav.screen = "mine" }
            "theme" -> ThemePage { nav.screen = "mine" }
            "features" -> FeaturesPage({ nav.screen = "mine" }, { nav.screen = "search" }, { nav.screen = "gallery" }) { nav.screen = "skills" }
            "gallery" -> GalleryPage({ nav.screen = "features" }) { nav.screen = "gallery/$it" }
            "skills" -> SkillsPage({ nav.screen = "features" }) { nav.screen = "skills/$it" }
            "search" -> SearchPage({ nav.screen = "features" }) { nav.screen = "search/$it" }
            else -> if (screen.startsWith("skills/")) SkillDetailPage(screen.removePrefix("skills/")) { nav.screen = "skills" }
            else if (screen.startsWith("search/")) SearchSourcePage(screen.removePrefix("search/")) { nav.screen = "search" }
            else if (screen.startsWith("gallery/")) GallerySourcePage(screen.removePrefix("gallery/")) { nav.screen = "gallery" }
            else Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                CompositionLocalProvider(LocalTitleEnd provides 36.dp) { MineSub(store, screen, true, 0.dp, nav) }
            }
        }
    }
}

@Composable
private fun MineCardBody(t: Float, onGo: (String) -> Unit) {
    val me = Memory.userName.trim()
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 40.dp, bottom = 14.dp).mineRise(0, t), verticalAlignment = Alignment.CenterVertically) {
        AvatarFace(Avatars.ME, me.takeLast(1).ifEmpty { "你" }, Brush.linearGradient(listOf(warm, Color(0xFFB97F62))), 38.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt("我的", Page.ink, 19.sp, spacing = ls(2.6f, 19f), family = Serif)
            Spacer(Modifier.height(2.dp))
            Txt("${me.ifEmpty { "你" }} · 在这台手机上", Page.muted.copy(alpha = .62f), 11.5.sp, maxLines = 1)
        }
    }
    MineRow(1, t, "助理", "名字、提示词、记忆", { onGo("assistant") }, glyph = { SvgIcon(person, warm, 19.dp) }) {
        PersonaDot(Memory.active, 20.dp)
        Spacer(Modifier.width(6.dp))
        Txt(Memory.active.name, Page.muted, 13.5.sp, Modifier.widthIn(max = 96.dp), maxLines = 1)
        Spacer(Modifier.width(4.dp))
        SvgIcon(chevron, Page.muted.copy(alpha = .6f), 15.dp, stroke = 1.8f)
    }
    Spacer(Modifier.height(6.dp))
    MineRow(2, t, "功能", if (WebSearch.enabledCount > 0) "已启用 ${WebSearch.enabledCount} 项" else "都关着", { onGo("features") }, glyph = { SvgIcon(tune, warm, 19.dp, stroke = 1.6f) }) {
        SvgIcon(chevron, Page.muted.copy(alpha = .6f), 15.dp, stroke = 1.8f)
    }
    Txt("连接器", Page.muted, 11.sp, Modifier.padding(start = 8.dp, top = 14.dp, bottom = 8.dp).mineRise(2, t), spacing = ls(2.75f, 11f))
    MineYuRow(3, t)
    Spacer(Modifier.height(6.dp))
    MineTgRow(3, t)
    Spacer(Modifier.height(6.dp))
    val n = Mcp.servers.size
    val on = Mcp.enabled().size
    MineRow(
        4, t, "MCP 服务",
        if (n == 0) "地图、车票、搜索……接上就能用" else Mcp.servers.filter { it.enabled }.joinToString("、") { it.name }.ifEmpty { "都关着" },
        { onGo("mcp") },
        glyph = { Txt("M", warm, 16.sp, family = Serif) },
    ) {
        if (n > 0) {
            Box(Modifier.size(6.dp).background(if (on > 0) warm else Page.muted.copy(alpha = .4f), CircleShape))
            Spacer(Modifier.width(7.dp))
            Txt("$on 个", Page.muted, 13.sp)
            Spacer(Modifier.width(4.dp))
        }
        SvgIcon(chevron, Page.muted.copy(alpha = .6f), 15.dp, stroke = 1.8f)
    }
    Txt("主题", Page.muted, 11.sp, Modifier.padding(start = 8.dp, top = 14.dp, bottom = 8.dp).mineRise(5, t), spacing = ls(2.75f, 11f))
    MineRow(5, t, "主题", Skin.name(), { onGo("theme") }, glyph = { ThemeGlyph(warm) }) {
        SvgIcon(chevron, Page.muted.copy(alpha = .6f), 15.dp, stroke = 1.8f)
    }
    Txt("其他", Page.muted, 11.sp, Modifier.padding(start = 8.dp, top = 14.dp, bottom = 8.dp).mineRise(5, t), spacing = ls(2.75f, 11f))
    BackupRow(6, t)
    Spacer(Modifier.height(6.dp))
    MineRow(7, t, "关于 Vignette", "背景作品 · 版权", { onGo("about") }, glyph = { SvgIcon(Icons.spark, warm, 18.dp, stroke = 1.1f) }) {
        SvgIcon(chevron, Page.muted.copy(alpha = .6f), 15.dp, stroke = 1.8f)
    }
    Spacer(Modifier.height(8.dp))
}

/** 关于：原来主页「关于」弹窗里的话。 */
@Composable
private fun AboutPage(onBack: () -> Unit) {
    Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        CardPageHead("关于 Vignette", onBack = onBack)
        Txt("那些日子像掠过水面的影子，来不及看清，就已远去，只留下淡淡的怅惘。", Page.ink.copy(alpha = .86f), 13.5.sp, Modifier.padding(horizontal = 8.dp), lineHeight = 26.sp)
        Spacer(Modifier.height(16.dp))
        Txt("背景作品：WLOP-Sky2-海琴烟。作品版权归原作者所有。", Page.muted, 12.5.sp, Modifier.padding(horizontal = 8.dp), lineHeight = 24.sp)
    }
}
