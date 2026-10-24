package com.fuguang.lueying

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp

/**
 * 聊天页右上角圆钮：点开从钮里长出一张小卡片——上面「手记」（浮光 / 掠影 / 绯句都在里面），
 * 下面暗度、虚化（「只看画面」去掉了：停手一阵自动沉浸，见 Zen.kt）；点空白或选完缩回钮里。卡片画在 App 最上层。
 */
object CornerMenu {
    var open by mutableStateOf(false)
    /** 圆钮在屏幕上的位置：卡片从它的右下角长出来。 */
    var anchor by mutableStateOf(Rect.Zero)
}

private val MenuApricot = Color(0xFFE9B48E)

@Composable
fun CornerMenuButton(compact: Boolean) {
    val round = if (compact) 35.dp else 42.dp
    val k by animateFloatAsState(if (CornerMenu.open) 1f else 0f, tween(320, easing = Curves.standard), label = "corner")
    Pressable(
        { CornerMenu.open = !CornerMenu.open },
        Modifier.onGloballyPositioned { CornerMenu.anchor = it.boundsInRoot() },
        label = if (CornerMenu.open) "收起菜单" else "打开菜单",
    ) { pressed ->
        Box(
            Modifier.size(round).clip(CircleShape)
                .background(lerp(veil(if (pressed) .12f else .04f), MenuApricot.copy(alpha = .16f), k))
                .border(1.dp, Page.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            SvgIcon(Icons.settings, Page.ink, modifier = Modifier.graphicsLayer { alpha = 1f - k; rotationZ = 90f * k; val s = 1f - .4f * k; scaleX = s; scaleY = s })
            SvgIcon(Icons.close, MenuApricot, 18.dp, stroke = 1.5f, modifier = Modifier.graphicsLayer { alpha = k; rotationZ = -90f * (1f - k); val s = .6f + .4f * k; scaleX = s; scaleY = s })
        }
    }
}

/** 卡片本体。「我的」不在这里：从聊天侧栏底部的头像进。 */
@Composable
fun CornerMenuLayer(dim: Int, blur: Int, onDim: (Int) -> Unit, onBlur: (Int) -> Unit, onNote: () -> Unit) {
    val open = CornerMenu.open
    BackHandler(enabled = open) { CornerMenu.open = false }
    val t by animateFloatAsState(
        if (open) 1f else 0f,
        if (open) spring(dampingRatio = .74f, stiffness = 360f) else tween(240, easing = Curves.standard), label = "cornerCard",
    )
    if (t <= .001f && !open) return
    val close = { CornerMenu.open = false }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cardW = min(300.dp, maxWidth - 32.dp)
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = t.coerceIn(0f, 1f) }.background(rgba(6, 8, 12, .28f)).pointerInput(Unit) { detectTapGestures { close() } })
        val a = CornerMenu.anchor
        Column(
            Modifier
                .offset { IntOffset((a.right - cardW.toPx()).toInt().coerceAtLeast(16.dp.roundToPx()), (a.bottom + 10.dp.toPx()).toInt()) }
                .width(cardW)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(1f, 0f)
                    val s = .18f + .82f * t
                    scaleX = s; scaleY = s; alpha = (t * 1.6f).coerceIn(0f, 1f)
                }
                .background(Skin.sheet, RoundedCornerShape(24.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(8.dp),
        ) {
            var n = 0
            // 卡片里的项依次浮出
            fun Modifier.rise(): Modifier { val k = n++; return this.graphicsLayer { val v = ((t - .2f - k * .05f) / .4f).coerceIn(0f, 1f); alpha = v; translationY = (1f - v) * (-4).dp.toPx() } }
            MenuRow("手记", "浮光 · 掠影 · 绯句", false, Modifier.rise()) { close(); onNote() }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(1.dp).background(veil(.06f)))
            Spacer(Modifier.height(10.dp))
            Txt("显示", Skin.ink.copy(alpha = .34f), 11.sp, Modifier.rise().padding(start = 14.dp, bottom = 6.dp), spacing = ls(3f, 11f))
            MenuSlider("暗度", dim, 0, 60, Modifier.rise(), onDim)
            MenuSlider("虚化", blur, 0, 24, Modifier.rise(), onBlur)
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun MenuRow(title: String, note: String, on: Boolean, modifier: Modifier, small: Boolean = false, onClick: () -> Unit) {
    Pressable(onClick, modifier.fillMaxWidth(), label = title) { pressed ->
        Row(
            Modifier.fillMaxWidth()
                .background(
                    when { on -> MenuApricot.copy(alpha = .14f); pressed -> veil(.06f); else -> Color.Transparent },
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fs = if (small) 14f else 16f
            Txt(title, if (on) Page.ink else Page.muted, fs.sp, spacing = ls(if (small) 2f else 3f, fs), family = Serif, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Txt(note, if (on) MenuApricot.copy(alpha = .8f) else Skin.ink.copy(alpha = .34f), 11.5.sp, maxLines = 1)
        }
    }
}

@Composable
private fun MenuSlider(label: String, v: Int, min: Int, max: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    Row(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Page.muted, 13.sp, Modifier.width(44.dp), maxLines = 1)
        RangeSlider(v, min, max, MenuApricot, veil(.15f), onChange, Modifier.weight(1f))
    }
}
