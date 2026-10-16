package com.fuguang.lueying

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val back = Ic("M14.5 6l-6 6 6 6")
private val warm = Color(0xFFE9B48E)

/** 卡片背面：那一天的一句话、气温曲线、六格细节。 */
@Composable
fun WeatherDayFace(d: WxDay, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PressSurface(onBack, Modifier.size(40.dp), shape = CircleShape, fill = Color.Transparent, pressScale = .9f, label = "翻回去") {
                SvgIcon(back, Page.ink, 20.dp, 1.6f, Modifier.align(Alignment.Center))
            }
            Spacer(Modifier.width(4.dp))
            val name = dayName(d.date)
            Txt(if (name == weekName(d.date)) name else "$name · ${weekName(d.date)}", Page.ink, 17.sp, spacing = ls(2.2f, 17f), family = Serif)
            Spacer(Modifier.width(8.dp))
            Txt("${d.date.monthValue}月${d.date.dayOfMonth}日", Page.muted.copy(alpha = .6f), 12.sp)
        }
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("${d.hi}°", Page.ink, 38.sp, weight = FontWeight.ExtraLight)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(wxText(d.code), Page.ink, 15.sp)
                Spacer(Modifier.height(4.dp))
                Txt("最低 ${d.lo}°", Page.muted, 12.sp)
            }
            WxIcon(wxKind(d.code), 36.dp)
        }
        Txt(daySay(d), Page.ink.copy(alpha = .86f), 13.sp, Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp), lineHeight = 22.sp)
        if (d.hours.size >= 2) TempCurve(d.hours)
        Spacer(Modifier.height(14.dp))
        val lv = windLevel(d.windKmh)
        Cells(
            listOf(
                Triple("降水概率", "${d.rain}%", if (d.rain >= 50) "带伞" else "不太会下"),
                Triple("风", "$lv 级", windDir(d.windDir)),
                Triple("湿度", "${d.hum}%", if (d.hum > 75) "有点闷" else if (d.hum < 40) "偏干" else "舒服"),
                Triple("紫外线", "%.0f".format(d.uv), uvWord(d.uv)),
                Triple("日出", d.sunrise, "${d.sunset} 落"),
                Triple("空气", d.aqi?.toString() ?: "—", d.aqi?.let { aqiWord(it) } ?: "暂无"),
            ),
        )
    }
}

@Composable
private fun Cells(items: List<Triple<String, String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { Cell(it) } }
        }
    }
}

@Composable
private fun RowScope.Cell(c: Triple<String, String, String>) {
    Column(Modifier.weight(1f).background(CardBlock, RoundedCornerShape(18.dp)).padding(horizontal = 10.dp, vertical = 9.dp)) {
        Txt(c.first, Page.muted, 10.5.sp, spacing = ls(.6f, 10.5f), maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Txt(c.second, Page.ink, 15.sp, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Txt(c.third, Page.muted.copy(alpha = .6f), 10.5.sp, maxLines = 1)
    }
}

/** 7–21 点的气温曲线：平滑线 + 往下淡出的杏色，最暖的那一点加圆点和亮字。 */
@Composable
private fun TempCurve(hours: List<WxHour>) {
    val tm = rememberTextMeasurer()
    val vs = hours.map { it.temp }
    val lo = vs.min() - 1
    val hi = vs.max() + 1
    val top = vs.indexOf(vs.max())
    Box(Modifier.fillMaxWidth().background(WxWell, RoundedCornerShape(22.dp)).padding(horizontal = 8.dp, vertical = 12.dp)) {
        Canvas(Modifier.fillMaxWidth().height(76.dp)) {
            val pad = 14.dp.toPx()
            val base = size.height - 20.dp.toPx()
            fun x(i: Int) = pad + i * (size.width - 2 * pad) / (vs.size - 1)
            fun y(v: Int) = 14.dp.toPx() + (1f - (v - lo).toFloat() / (hi - lo)) * (base - 22.dp.toPx())
            val line = Path().apply {
                moveTo(x(0), y(vs[0]))
                for (i in 1 until vs.size) { val m = (x(i - 1) + x(i)) / 2; cubicTo(m, y(vs[i - 1]), m, y(vs[i]), x(i), y(vs[i])) }
            }
            val fill = Path().apply { addPath(line); lineTo(x(vs.lastIndex), base); lineTo(x(0), base); close() }
            drawPath(fill, Brush.verticalGradient(listOf(warm.copy(alpha = .28f), Color.Transparent), endY = base))
            drawPath(line, warm, style = Stroke(1.6.dp.toPx()))
            drawCircle(warm, 3.dp.toPx(), Offset(x(top), y(vs[top])))
            vs.forEachIndexed { i, v ->
                val t = tm.measure("$v°", TextStyle(fontSize = 10.5.sp, color = if (i == top) Page.ink else Page.ink.copy(alpha = .6f)))
                drawText(t, topLeft = Offset(x(i) - t.size.width / 2f, y(v) - t.size.height - 4.dp.toPx()))
                val h = tm.measure("%02d时".format(hours[i].hour), TextStyle(fontSize = 10.sp, color = Page.ink.copy(alpha = .4f)))
                drawText(h, topLeft = Offset(x(i) - h.size.width / 2f, size.height - h.size.height))
            }
        }
    }
}

/** 换城市：输入停半秒去找，点一个就换，卡片里的天气跟着刷新。 */
@Composable
fun WeatherPlacePicker(onDone: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<WxPlace>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(q) {
        if (q.isBlank()) { found = emptyList(); return@LaunchedEffect }
        delay(450); busy = true; found = Weather.search(q); busy = false
    }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        BasicTextField(
            q, { q = it.take(20) }, singleLine = true,
            textStyle = TextStyle(color = Page.ink, fontSize = 15.sp), cursorBrush = SolidColor(Page.accent),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().background(WxWell, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    if (q.isEmpty()) Txt("输入城市或区县，比如 海淀", Page.muted.copy(alpha = .5f), 15.sp)
                    inner()
                }
            },
        )
        if (busy) Txt("正在找…", Page.muted, 12.sp, Modifier.padding(start = 6.dp, top = 8.dp))
        else if (q.isNotBlank() && found.isEmpty()) Txt("没找到「$q」，换个写法试试", Page.muted, 12.sp, Modifier.padding(start = 6.dp, top = 8.dp))
        found.forEach { pl ->
            PressSurface({ Weather.use(pl); onDone() }, Modifier.fillMaxWidth().padding(top = 6.dp), shape = RoundedCornerShape(14.dp), pressScale = .985f, label = pl.name) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(pl.name, Page.ink, 14.5.sp)
                    Spacer(Modifier.width(10.dp))
                    Txt(pl.area, Page.muted, 12.sp, maxLines = 1)
                }
            }
        }
    }
}
