package com.fuguang.lueying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date

private val aqiC = Color(0xFFD8B26A)
private val hair = Color.White.copy(alpha = .12f)

/**
 * 主页左下的时间天气：没有底板，字直接落在画面暗处，背后一团很淡的暗影托着。
 * 左边现在（时间、日期、气温、地点和空气），右边接下来四天；点一下长成天气卡片。沉浸时跟主页一起隐去。
 */
@Composable
fun WeatherWidget(now: Date, compact: Boolean, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { while (true) { Weather.refresh(); delay(10 * 60_000) } }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) .985f else 1f, if (pressed) tween(80) else spring(dampingRatio = .5f, stiffness = 600f), label = "wxPress")
    val d = Weather.data
    Row(
        modifier
            .widthIn(max = if (compact) 460.dp else 560.dp)
            .fillMaxWidth()
            .onGloballyPositioned { WeatherCard.from = it.boundsInRoot().center }
            .graphicsLayer { scaleX = s; scaleY = s }
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(Color.Black.copy(alpha = .28f), Color.Transparent), center = Offset(size.width * .35f, size.height * .6f), radius = size.width * .75f))
            }
            .background(Color.White.copy(alpha = if (pressed) .05f else 0f), RoundedCornerShape(20.dp))
            .clickable(source, null, onClickLabel = "看天气详情", role = Role.Button) {
                if (d == null) scope.launch { Weather.refresh(force = true) } else WeatherCard.open = true
            }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            val c = Calendar.getInstance().apply { time = now }
            Txt(
                "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)), Page.ink,
                (if (compact) 46 else 56).sp, weight = FontWeight.ExtraLight, maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Txt("${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日 ${weekName(java.time.LocalDate.now())}", Page.ink.copy(alpha = .86f), 11.5.sp, spacing = ls(1.4f, 11.5f))
            Spacer(Modifier.height(12.dp))
            if (d != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WxIcon(wxKind(d.now.code, d.now.day), 20.dp)
                    Spacer(Modifier.width(8.dp))
                    Txt("${d.now.temp}°", Page.ink, 21.sp, weight = FontWeight.Light)
                    Spacer(Modifier.width(8.dp))
                    Txt(wxText(d.now.code), Page.ink.copy(alpha = .86f), 13.sp)
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Txt(Weather.place.name, Page.muted, 11.5.sp, maxLines = 1)
                    d.now.aqi?.let { Txt("空气 $it ${aqiWord(it)}", aqiC, 11.5.sp, maxLines = 1) }
                }
            } else {
                Txt(if (Weather.failed) "天气暂时取不到 · 点一下再试" else "正在看天气…", Page.muted, 12.sp)
            }
        }
        if (d != null) {
            Spacer(Modifier.width(if (compact) 16.dp else 26.dp))
            Column(Modifier.width(if (compact) 150.dp else 190.dp)) {
                d.days.drop(1).take(4).forEachIndexed { i, x ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(hair))
                    Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt(weekName(x.date), Page.ink.copy(alpha = .86f), 12.sp, Modifier.width(36.dp))
                        WxIcon(wxKind(x.code), 18.dp)
                        Spacer(Modifier.weight(1f))
                        Txt("${x.hi}°", Page.ink.copy(alpha = .9f), 12.sp)
                        Spacer(Modifier.width(6.dp))
                        Txt("${x.lo}°", Page.muted, 12.sp)
                    }
                }
            }
        }
    }
}
