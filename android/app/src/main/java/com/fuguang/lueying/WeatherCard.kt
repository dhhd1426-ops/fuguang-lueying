package com.fuguang.lueying

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 天气卡片的开关：主页小组件记下自己的中心，卡片从这里长出、缩回这里。 */
object WeatherCard {
    var open by mutableStateOf(false)
    var from by mutableStateOf(Offset.Unspecified)
}

internal val WxCardFill = Color(0xFF22262F)
internal val WxWell = Color.Black.copy(alpha = .22f)
internal val WxAqi = Color(0xFFD8B26A)
private val flipEase = CubicBezierEasing(.3f, .7f, .15f, 1f)
private val chev = Ic("M9.5 6l6 6-6 6")

/**
 * 天气卡片：从小组件弹出来停在中间，点空白缩回小组件。正面是现在、逐小时、七天；
 * 点某一天，整张卡片绕竖轴翻过去（640ms），背面是那一天；‹ 或返回键翻回来。
 */
@Composable
fun WeatherCardLayer() {
    val p = remember { Animatable(0f) }
    val flip = remember { Animatable(0f) }
    val rise = remember { Animatable(0f) }
    var back by remember { mutableStateOf(false) }
    var day by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(WeatherCard.open) {
        if (WeatherCard.open) {
            back = false; flip.snapTo(0f)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            launch { rise.snapTo(0f); rise.animateTo(1f, tween(600, easing = LinearEasing)) }
            p.animateTo(1f, spring(dampingRatio = .7f, stiffness = 360f))
        } else p.animateTo(0f, tween(200, easing = Curves.accel))
    }
    LaunchedEffect(back) {
        if (WeatherCard.open) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); flip.animateTo(if (back) 1f else 0f, tween(640, easing = flipEase)) }
    }
    BackHandler(enabled = WeatherCard.open) { if (back) back = false else WeatherCard.open = false }
    val data = Weather.data
    if ((!WeatherCard.open && p.value == 0f) || data == null) return
    val frontScroll = rememberScrollState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pv = p.value
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = pv.coerceIn(0f, 1f) }
                .background(rgba(6, 8, 12, .5f))
                .pointerInput(Unit) { detectTapGestures { WeatherCard.open = false } },
        )
        val w = min(316.dp, maxWidth - 56.dp)
        val h = min(maxHeight * .64f, 500.dp)
        val x = (maxWidth - w) / 2
        val y = (maxHeight * .96f - h) / 2
        val fv = flip.value
        Box(
            Modifier.offset(x, y).size(w, h)
                .graphicsLayer {
                    val s = .2f + .8f * pv
                    scaleX = s; scaleY = s
                    alpha = (pv * 1.8f).coerceIn(0f, 1f)
                    val from = WeatherCard.from
                    transformOrigin = if (from.isSpecified) TransformOrigin((from.x - x.toPx()) / size.width, (from.y - y.toPx()) / size.height) else TransformOrigin(.5f, 1.1f)
                    rotationY = 180f * fv
                    cameraDistance = 14f * density
                    shape = RoundedCornerShape(36.dp); clip = true
                    shadowElevation = 26.dp.toPx()
                }
                .background(WxCardFill)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            if (fv > .5f) {
                Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) {
                    WeatherDayFace(data.days[day.coerceIn(0, data.days.lastIndex)]) { back = false }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(frontScroll).padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = 12.dp)) {
                    WeatherFront(data, rise.value) { day = it; back = true }
                }
            }
        }
    }
}

@Composable
private fun WeatherFront(d: Forecast, t: Float, onDay: (Int) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).mineRise(0, t), verticalAlignment = Alignment.CenterVertically) {
        Txt(Weather.place.name, Page.ink, 17.sp, spacing = ls(2.6f, 17f), family = Serif, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        Txt(SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(d.at)) + " 更新", Page.muted.copy(alpha = .6f), 12.sp, Modifier.weight(1f), maxLines = 1)
        PressSurface({ picking = !picking }, shape = RoundedCornerShape(50), label = "换城市") {
            Txt(if (picking) "收起" else "换城市", Page.muted, 12.sp, Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
        }
    }
    if (picking) WeatherPlacePicker { picking = false }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 12.dp).mineRise(1, t), verticalAlignment = Alignment.CenterVertically) {
        Txt("${d.now.temp}°", Page.ink, 42.sp, weight = FontWeight.ExtraLight)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(wxText(d.now.code), Page.ink, 15.sp)
            Spacer(Modifier.height(4.dp))
            val today = d.days.firstOrNull()
            Txt("体感 ${d.now.feels}°" + (today?.let { " · ${it.hi}° / ${it.lo}°" } ?: ""), Page.muted, 12.sp, maxLines = 1)
            d.now.aqi?.let {
                Spacer(Modifier.height(6.dp))
                Txt("空气 $it · ${aqiWord(it)}", WxAqi, 11.5.sp, Modifier.background(WxAqi.copy(alpha = .16f), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp))
            }
        }
        WxIcon(wxKind(d.now.code, d.now.day), 38.dp)
    }
    // 逐小时：横着滑
    Row(
        Modifier.fillMaxWidth().mineRise(2, t).background(WxWell, RoundedCornerShape(22.dp))
            .horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 10.dp),
    ) {
        d.next.forEachIndexed { i, h ->
            Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(if (i == 0) "现在" else "%02d时".format(h.hour), if (i == 0) Page.accent else Page.muted, 11.sp)
                Spacer(Modifier.height(8.dp))
                WxIcon(wxKind(h.code, h.day), 20.dp)
                Spacer(Modifier.height(8.dp))
                Txt("${h.temp}°", Page.ink, 13.sp)
            }
        }
    }
    Txt("七天", Page.muted, 11.sp, Modifier.padding(start = 6.dp, top = 14.dp, bottom = 8.dp).mineRise(3, t), spacing = ls(2.75f, 11f))
    val lo = d.days.minOf { it.lo }
    val hi = d.days.maxOf { it.hi }.coerceAtLeast(lo + 1)
    Column(Modifier.fillMaxWidth().mineRise(4, t).background(CardBlock, RoundedCornerShape(22.dp))) {
        d.days.forEachIndexed { i, x ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .06f)))
            PressSurface({ onDay(i) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(0.dp), fill = Color.Transparent, pressScale = 1f, label = "${dayName(x.date)} ${wxText(x.code)}") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(dayName(x.date), Page.ink, 13.sp, Modifier.width(40.dp))
                    WxIcon(wxKind(x.code), 18.dp)
                    Txt("${x.lo}°", Page.muted, 13.sp, Modifier.width(34.dp), align = androidx.compose.ui.text.style.TextAlign.End)
                    Spacer(Modifier.width(10.dp))
                    TempBar((x.lo - lo).toFloat() / (hi - lo), (x.hi - lo).toFloat() / (hi - lo), Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    Txt("${x.hi}°", Page.ink, 13.sp, Modifier.width(30.dp))
                    SvgIcon(chev, Page.muted.copy(alpha = .5f), 14.dp, 1.8f)
                }
            }
        }
    }
}

/** 一周最低到最高的那根细轨上，这一天占的一段：冷蓝到杏色。 */
@Composable
private fun TempBar(a: Float, b: Float, modifier: Modifier) {
    BoxWithConstraints(modifier.height(4.dp).background(Color.White.copy(alpha = .08f), RoundedCornerShape(2.dp))) {
        Box(
            Modifier.offset(x = maxWidth * a).width(maxWidth * (b - a).coerceAtLeast(.04f)).height(4.dp)
                .background(Brush.horizontalGradient(listOf(Color(0xFF9FB6CF), Color(0xFFE9B48E))), RoundedCornerShape(2.dp)),
        )
    }
}
