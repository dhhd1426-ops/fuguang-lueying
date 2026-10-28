package com.fuguang.lueying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

/**
 * 主题（「我的 → 主题」）：
 * - frost 透明磨砂（默认）：背景画面一直在，侧栏和悬浮卡片是磨砂玻璃，透出后面的画面。
 * - dark 暗黑优雅：照 Claude App 深色，深灰实底，不放背景画面。
 * - light 白色：照 Claude App 浅色，米白底黑字，不放背景画面。
 * - grad 渐变（第 327 版）：八套「神仙渐变」配色（见 Grad），上下两色竖着渐变铺满，侧栏和卡片是白色磨砂，
 *   点缀色（原来的杏色）换成每套的字色，正文是字色压深。配色位置 [pal] 是连续的，主题页滑到两套中间就是两套各一半。
 * 颜色都是普通 getter，读的是 [id] 这个 state：组合和绘制时读到就会跟着换。
 * 播放器、浏览器、作品全屏这些「看东西」的地方一直是深色，用 Page.INK / MUTED / LINE。
 */
object Skin {
    const val FROST = "frost"
    const val DARK = "dark"
    const val LIGHT = "light"
    const val GRAD = "grad"
    private const val KEY = "skin"
    private const val PAL = "grad-pal"

    class Option(val id: String, val name: String, val sub: String)
    val options = listOf(
        Option(FROST, "透明磨砂", "透出背景画面"),
        Option(DARK, "暗黑优雅", "深灰实底，安静"),
        Option(LIGHT, "白色", "米白底，黑字"),
        Option(GRAD, "渐变", "八套配色，滑着换"),
    )

    var id by mutableStateOf(FROST)
    private var prefs: Prefs? = null

    fun init(p: Prefs) {
        prefs = p; id = p.string(KEY)?.takeIf { s -> options.any { it.id == s } } ?: FROST; rail = p.string(RAIL) == "1"
        pal = (p.string(PAL)?.toIntOrNull() ?: 0).coerceIn(0, Grad.all.lastIndex).toFloat()
    }

    /** 渐变的配色位置：0..7，滑动时在两套中间连续变；停稳才存（[savePal]）。 */
    var pal by mutableFloatStateOf(0f)
    fun savePal(i: Int) { pal = i.toFloat(); prefs?.putString(PAL, i.toString()) }
    private val g: Grad.Now get() = Grad.at(pal)

    /** 侧栏顶上换助理的方式：false 连体卡片（默认，点名字从下面展开）；true 竖排头像（侧栏左边一排，一点就换）。 */
    var rail by mutableStateOf(false)
    private const val RAIL = "switch-rail"
    fun useRail(v: Boolean) { rail = v; prefs?.putString(RAIL, if (v) "1" else "0") }
    fun set(v: String) { id = v; prefs?.putString(KEY, v) }
    fun name(v: String = id) = options.firstOrNull { it.id == v }?.name.orEmpty() +
        (if (v == GRAD) " · " + Grad.all[Math.round(pal).coerceIn(0, Grad.all.lastIndex)].title else "")

    val frost get() = id == FROST
    val grad get() = id == GRAD
    /** 浅底：白色和渐变。色块、井、线都换成黑色薄层。 */
    val light get() = id == LIGHT || id == GRAD
    /** 侧栏和悬浮卡片用磨砂（透出背后的画面或渐变）。 */
    val glass get() = id == FROST || id == GRAD

    /** 点缀色：原来的杏色；渐变时换成那套配色的字色。 */
    val accent: Color get() = if (grad) g.k else Apricot
    private val Apricot = Color(0xFFE9B48E)
    /** 渐变时整页的底：上一色到下一色竖着渐变。别的主题是实底。 */
    val pageBrush: Brush get() = if (grad) Brush.verticalGradient(listOf(g.a, g.b)) else SolidColor(page)

    /** 不放背景画面时整页的底色。 */
    val page: Color get() = when (id) { DARK -> Color(0xFF141414); LIGHT -> Color(0xFFF4F3EE); GRAD -> g.mid; else -> Color(0xFF343C44) }
    /** 侧栏。 */
    val panel: Color get() = when (id) { DARK -> Color(0xFF131313); LIGHT -> Color(0xFFF4F3EE); GRAD -> Color.White.copy(alpha = .5f); else -> rgba(18, 21, 27, .42f) }
    /** 悬浮卡片。 */
    val card: Color get() = when (id) { DARK -> Color(0xFF1E1E1E); LIGHT -> Color(0xFFFAF9F5); GRAD -> Color.White.copy(alpha = .58f); else -> rgba(26, 29, 37, .5f) }
    /** 从下面升起的抽屉、长按菜单、圆钮卡片：磨砂时用原来的深色实底。 */
    val sheet: Color get() = when (id) { DARK -> Color(0xFF1E1E1E); LIGHT -> Color(0xFFFAF9F5); GRAD -> g.sheet; else -> rgba(20, 24, 31, .97f) }

    val ink: Color get() = when (id) { DARK -> Color(0xFFECECEC); LIGHT -> Color(0xFF1B1B19); GRAD -> g.ink; else -> Color(0xFFFAF5EE) }
    val muted: Color get() = when (id) { DARK -> Color(0xFF9A9A9A); LIGHT -> Color(0xFF77766F); GRAD -> g.muted; else -> rgba(250, 245, 238, .6f) }
    val line: Color get() = if (light) rgba(0, 0, 0, .1f) else rgba(255, 255, 255, .2f)
    /** 实心浅色药丸（新对话、启用）上的字。 */
    val onInk: Color get() = if (light) Color(0xFFF7F6F2) else Color(0xFF1B1A1A)

    /** 色块：深色主题是白色薄层，白色主题换成黑色薄层（淡一些）。 */
    fun veil(a: Float): Color = if (light) Color(0f, 0f, 0f, (a * .55f).coerceAtMost(1f)) else Color(1f, 1f, 1f, a)
    /** 往里凹的井（输入框、分段底）：白色主题淡很多。 */
    fun well(a: Float): Color = if (light) Color(0f, 0f, 0f, a * .18f) else Color(0f, 0f, 0f, a)
    /** 卡片里的玻璃块。 */
    fun tint(a: Float): Color = when (id) { DARK -> Color(0xFF262626).copy(alpha = (a + .5f).coerceAtMost(1f)); LIGHT, GRAD -> Color.White.copy(alpha = (a + .45f).coerceAtMost(1f)); else -> rgba(30, 34, 42, a) }
    /**
     * 弹层下面的遮罩（第 331 版）：深色主题压黑；白色压一层淡黑；渐变压一层带那套色调的深字色，不发脏发灰。
     * [a] 照原来深色时的不透明度给。
     */
    fun scrim(a: Float): Color = when (id) { LIGHT -> Color.Black.copy(alpha = a * .4f); GRAD -> g.ink.copy(alpha = a * .45f); else -> rgba(6, 9, 14, a) }
    /** 长按菜单、弹出菜单、小面板的底。 */
    val pop: Color get() = when (id) { DARK -> Color(0xFF2A2A2C); LIGHT -> Color.White; GRAD -> g.pop; else -> rgba(42, 44, 49, .98f) }
    /** 底下冒出来的提示条：深色主题是浅一点的灰底；浅色主题反过来，用深字色做底、浅字。 */
    val toast: Color get() = if (light) ink.copy(alpha = .96f) else rgba(47, 49, 54, .97f)
    val toastInk: Color get() = if (light) Color(0xFFFAF8F4) else Color(0xFFFAF5EE)
    /** 出错、删除这类红：深色主题是浅一点的粉红，浅色主题压深，保证看得清。 */
    val danger: Color get() = if (light) Color(0xFFC0504A) else Color(0xFFD79A94)
    /** 点缀色实底按钮上的字：杏色上是深棕；渐变上是那套的深字色。 */
    val onAccent: Color get() = if (grad) g.ink else Color(0xFF2A211B)
    /** 聊天里卡片（抖音、网盘、Telegram 长条）的底：深色主题深色玻璃，浅色主题白色玻璃。 */
    fun cardFill(a: Float): Color = if (light) Color.White.copy(alpha = (a * .7f).coerceAtMost(.9f)) else rgba(30, 33, 42, a)

    /** 聊天页压在背景画面上的那层暗：没有背景画面时不要。 */
    fun shade(a: Float): Color = if (frost) rgba(14, 17, 23, a) else Color.Transparent
}

/** 色块的简写，替代原来的 rgba(255, 255, 255, a)。 */
fun veil(a: Float): Color = Skin.veil(a)

/**
 * 八套「神仙渐变」配色（用户给的海报，2026-10-28）：上一色、下一色、字色。
 * [at] 按连续位置在 OKLab 里混两套，中间不发灰；同一个位置只算一次。
 */
object Grad {
    class Pal(val n: String, val title: String, val a: Long, val aName: String, val b: Long, val bName: String, val k: Long)
    val all = listOf(
        Pal("01", "这就是我想要的", 0xFFDDFFC2, "Lime Zest", 0xFFEEE1FF, "Lilac Gray", 0xFF7DBE5C),
        Pal("02", "一眼心动系列", 0xFFFFEAE1, "Blush Nude", 0xFFB6FAFF, "Sky Blue", 0xFFD2A98A),
        Pal("03", "美好从审美开始", 0xFFCFFFBD, "Spring Green", 0xFFB9DFFF, "Blue Gray", 0xFF7F86B8),
        Pal("04", "梦境的粉色泡泡", 0xFFDBDDFF, "Light Blue", 0xFFFFD0D0, "Blush Pink", 0xFFE58C88),
        Pal("05", "所有的美好给你", 0xFFACFFF1, "Aqua", 0xFFFFDCDC, "Rose Dust", 0xFF5BAE99),
        Pal("06", "这配色太上头", 0xFFFFFFD0, "Buff", 0xFFD8EFFF, "Blue Gray", 0xFF8DAFC8),
        Pal("07", "粉末天青一见倾心", 0xFFB8FBFB, "Pinkish Cyan", 0xFFFFC5CE, "Light Pink", 0xFFD89CA6),
        Pal("08", "黄紫迷人眼", 0xFFFFFFD0, "Cream", 0xFFD2CDFF, "Lavender", 0xFF9C98D8),
    )

    class Now(val a: Color, val b: Color, val k: Color, val ink: Color, val muted: Color, val mid: Color, val sheet: Color, val pop: Color)

    private class Lab(val l: Float, val a: Float, val b: Float)
    private val labs = all.map { Triple(lab(it.a), lab(it.b), lab(it.k)) }
    private var lastPos = Float.NaN
    private var last: Now? = null

    fun at(pos: Float): Now {
        last?.let { if (pos == lastPos) return it }
        val p = pos.coerceIn(0f, all.lastIndex.toFloat())
        val i = p.toInt().coerceAtMost(all.lastIndex); val j = (i + 1).coerceAtMost(all.lastIndex); val t = p - i
        val a = mix(labs[i].first, labs[j].first, t); val b = mix(labs[i].second, labs[j].second, t); val k = mix(labs[i].third, labs[j].third, t)
        val mid = mix(a, b, .5f)
        val n = Now(
            rgb(a), rgb(b), rgb(k),
            ink = rgb(Lab(.36f, k.a * .7f, k.b * .7f)),
            muted = rgb(Lab(.54f, k.a * .7f, k.b * .7f)),
            mid = rgb(mid),
            sheet = rgb(mix(mid, Lab(1f, 0f, 0f), .55f)),
            pop = rgb(mix(mid, Lab(1f, 0f, 0f), .8f)),
        )
        lastPos = pos; last = n
        return n
    }

    /** 主题页上一套的字色（压深一点，放在浅色卡片上看得清）。 */
    fun capInk(i: Int): Color = labs[i].third.let { rgb(Lab(.42f, it.a * .7f, it.b * .7f)) }

    private fun mix(x: Lab, y: Lab, t: Float) = Lab(x.l + (y.l - x.l) * t, x.a + (y.a - x.a) * t, x.b + (y.b - x.b) * t)
    private fun lin(c: Float) = if (c <= .04045f) c / 12.92f else Math.pow(((c + .055f) / 1.055f).toDouble(), 2.4).toFloat()
    private fun gam(c: Float) = if (c <= .0031308f) 12.92f * c else (1.055 * Math.pow(c.toDouble(), 1 / 2.4) - .055).toFloat()
    private fun lab(argb: Long): Lab {
        val r = lin(((argb shr 16) and 0xFF) / 255f); val g = lin(((argb shr 8) and 0xFF) / 255f); val b = lin((argb and 0xFF) / 255f)
        val l = Math.cbrt((.4122214708 * r + .5363325363 * g + .0514459929 * b)).toFloat()
        val m = Math.cbrt((.2119034982 * r + .6806995451 * g + .1073969566 * b)).toFloat()
        val s = Math.cbrt((.0883024619 * r + .2817188376 * g + .6299787005 * b)).toFloat()
        return Lab(.2104542553f * l + .793617785f * m - .0040720468f * s, 1.9779984951f * l - 2.428592205f * m + .4505937099f * s, .0259040371f * l + .7827717662f * m - .808675766f * s)
    }
    private fun rgb(c: Lab): Color {
        val l = (c.l + .3963377774f * c.a + .2158037573f * c.b).let { it * it * it }
        val m = (c.l - .1055613458f * c.a - .0638541728f * c.b).let { it * it * it }
        val s = (c.l - .0894841775f * c.a - 1.291485548f * c.b).let { it * it * it }
        fun ch(v: Float) = gam(v.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        return Color(ch(4.0767416621f * l - 3.3077115913f * m + .2309699292f * s), ch(-1.2684380046f * l + 2.6097574011f * m - .3413193965f * s), ch(-.0041960863f * l - .7034186147f * m + 1.707614701f * s))
    }
}
