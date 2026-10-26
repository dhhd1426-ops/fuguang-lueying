package com.fuguang.lueying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * 主题（「我的 → 主题」）：
 * - frost 透明磨砂（默认）：背景画面一直在，侧栏和悬浮卡片是磨砂玻璃，透出后面的画面。
 * - dark 暗黑优雅：照 Claude App 深色，深灰实底，不放背景画面。
 * - light 白色：照 Claude App 浅色，米白底黑字，不放背景画面。
 * 颜色都是普通 getter，读的是 [id] 这个 state：组合和绘制时读到就会跟着换。
 * 播放器、浏览器、作品全屏这些「看东西」的地方一直是深色，用 Page.INK / MUTED / LINE。
 */
object Skin {
    const val FROST = "frost"
    const val DARK = "dark"
    const val LIGHT = "light"
    private const val KEY = "skin"

    class Option(val id: String, val name: String, val sub: String)
    val options = listOf(
        Option(FROST, "透明磨砂", "透出背景画面"),
        Option(DARK, "暗黑优雅", "深灰实底，安静"),
        Option(LIGHT, "白色", "米白底，黑字"),
    )

    var id by mutableStateOf(FROST)
    private var prefs: Prefs? = null

    fun init(p: Prefs) { prefs = p; id = p.string(KEY)?.takeIf { s -> options.any { it.id == s } } ?: FROST; rail = p.string(RAIL) == "1" }

    /** 侧栏顶上换助理的方式：false 连体卡片（默认，点名字从下面展开）；true 竖排头像（侧栏左边一排，一点就换）。 */
    var rail by mutableStateOf(false)
    private const val RAIL = "switch-rail"
    fun setRail(v: Boolean) { rail = v; prefs?.putString(RAIL, if (v) "1" else "0") }
    fun set(v: String) { id = v; prefs?.putString(KEY, v) }
    fun name(v: String = id) = options.firstOrNull { it.id == v }?.name.orEmpty()

    val frost get() = id == FROST
    val light get() = id == LIGHT

    /** 不放背景画面时整页的底色。 */
    val page: Color get() = when (id) { DARK -> Color(0xFF141414); LIGHT -> Color(0xFFF4F3EE); else -> Color(0xFF343C44) }
    /** 侧栏。 */
    val panel: Color get() = when (id) { DARK -> Color(0xFF131313); LIGHT -> Color(0xFFF4F3EE); else -> rgba(18, 21, 27, .42f) }
    /** 悬浮卡片。 */
    val card: Color get() = when (id) { DARK -> Color(0xFF1E1E1E); LIGHT -> Color(0xFFFAF9F5); else -> rgba(26, 29, 37, .5f) }
    /** 从下面升起的抽屉、长按菜单、圆钮卡片：磨砂时用原来的深色实底。 */
    val sheet: Color get() = when (id) { DARK -> Color(0xFF1E1E1E); LIGHT -> Color(0xFFFAF9F5); else -> rgba(20, 24, 31, .97f) }

    val ink: Color get() = when (id) { DARK -> Color(0xFFECECEC); LIGHT -> Color(0xFF1B1B19); else -> Color(0xFFFAF5EE) }
    val muted: Color get() = when (id) { DARK -> Color(0xFF9A9A9A); LIGHT -> Color(0xFF77766F); else -> rgba(250, 245, 238, .6f) }
    val line: Color get() = if (light) rgba(0, 0, 0, .1f) else rgba(255, 255, 255, .2f)
    /** 实心浅色药丸（新对话、启用）上的字。 */
    val onInk: Color get() = if (light) Color(0xFFF7F6F2) else Color(0xFF1B1A1A)

    /** 色块：深色主题是白色薄层，白色主题换成黑色薄层（淡一些）。 */
    fun veil(a: Float): Color = if (light) Color(0f, 0f, 0f, (a * .55f).coerceAtMost(1f)) else Color(1f, 1f, 1f, a)
    /** 往里凹的井（输入框、分段底）：白色主题淡很多。 */
    fun well(a: Float): Color = if (light) Color(0f, 0f, 0f, a * .18f) else Color(0f, 0f, 0f, a)
    /** 卡片里的玻璃块。 */
    fun tint(a: Float): Color = when (id) { DARK -> Color(0xFF262626).copy(alpha = (a + .5f).coerceAtMost(1f)); LIGHT -> Color.White.copy(alpha = (a + .45f).coerceAtMost(1f)); else -> rgba(30, 34, 42, a) }
    /** 聊天页压在背景画面上的那层暗：没有背景画面时不要。 */
    fun shade(a: Float): Color = if (frost) rgba(14, 17, 23, a) else Color.Transparent
}

/** 色块的简写，替代原来的 rgba(255, 255, 255, a)。 */
fun veil(a: Float): Color = Skin.veil(a)
