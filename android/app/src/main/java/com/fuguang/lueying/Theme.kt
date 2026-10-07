package com.fuguang.lueying

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/** CSS 写法的颜色：#rrggbb 或 #rrggbbaa（透明度在末尾）。 */
fun css(hex: String): Color {
    val h = hex.removePrefix("#")
    val r = h.substring(0, 2).toInt(16)
    val g = h.substring(2, 4).toInt(16)
    val b = h.substring(4, 6).toInt(16)
    val a = if (h.length >= 8) h.substring(6, 8).toInt(16) else 255
    return Color(r, g, b, a)
}

fun rgba(r: Int, g: Int, b: Int, a: Float) = Color(r, g, b, (a * 255).toInt())

val Serif: FontFamily = FontFamily.Serif
val Sans: FontFamily = FontFamily.SansSerif

/** 首页（不随主题变化，与网站一致）。 */
object Page {
    val ground = css("#343c44")
    val ink = css("#faf5ee")
    val muted = rgba(250, 245, 238, .6f)
    val line = rgba(255, 255, 255, .2f)
    val accent = css("#edc6a5")
    val eyebrow = css("#ebd0b9")
    val dot = css("#efc5a6")
    val glassFill = rgba(225, 229, 233, .065f)
    val glassBorder = rgba(255, 255, 255, .17f)
}

/** 弹层颜色：深色为默认，浅色对应网站 [data-theme=light]。 */
@Immutable
data class Palette(
    val light: Boolean,
    // 通用弹窗（显示设置 / 手记 / 场景）
    val modalBg: Color,
    val modalInk: Color,
    val modalMuted: Color,
    val modalLine: Color,
    val aboutBg: Color,
    val aboutText: Color,
    val noteBg: Color,
    val noteInk: Color,
    val saveBg: Color,
    val saveInk: Color,
    val sliderAccent: Color,
    val backdrop: Color,
    // 掠影
    val readerBg: Color,
    val readerInk: Color,
    val readerBorder: Color,
    val readerLine: Color,
    val searchBg: Color,
    val searchBorder: Color,
    val searchInk: Color,
    val placeholder: Color,
    val tabInk: Color,
    val tabSelBg: Color,
    val tabSelInk: Color,
    val itemSmall: Color,
    val itemStrong: Color,
    val itemActiveBg: Color,
    val itemActiveBar: Color,
    val itemPressBg: Color,
    val meta: Color,
    val text: Color,
    val strong: Color,
    val stamp: Color,
    val stampBorder: Color,
    val role: Color,
    val controls: Color,
    val meterBg: Color,
    val meterFill: Color,
    val notice: Color,
    val menuInk: Color,
    val fullInk: Color,
    val brandSmall: Color,
    val favorite: Color,
    val readerBackdrop: Color,
    // 留言
    val lettersBg: Brush,
    val lettersInk: Color,
    val lettersSub: Color,
    val lettersAction: Color,
    val lettersIntro: Color,
    val cardBg: Brush,
    val cardBgPressed: Brush,
    val cardBorder: Color,
    val cardBorderPressed: Color,
    val number: Color,
    val quote: Color,
    val author: Color,
    val open: Color,
    val end: Color,
    val lettersBackdrop: Color,
    // 悬浮卡片
    val detailBg: Brush,
    val detailBorder: Color,
    val detailInk: Color,
    val detailHead: Color,
    val detailMark: Color,
    val detailAuthor: Color,
    val detailLine: Color,
    val detailButton: Color,
    val detailCount: Color,
    val detailBackdrop: Color,
    // 主题开关
    val toggleBg: Color,
    val toggleInk: Color,
)

val DarkPalette = Palette(
    light = false,
    modalBg = rgba(30, 37, 45, .9f),
    modalInk = Page.ink,
    modalMuted = rgba(250, 245, 238, .6f),
    modalLine = rgba(255, 255, 255, .2f),
    aboutBg = rgba(5, 12, 21, .97f),
    aboutText = rgba(250, 245, 238, .45f),
    noteBg = css("#ffffff08"),
    noteInk = Page.ink,
    saveBg = css("#ebcfb4"),
    saveInk = css("#302d2c"),
    sliderAccent = css("#eac3a5"),
    backdrop = rgba(10, 15, 22, .28f),
    readerBg = rgba(8, 16, 25, .97f),
    readerInk = css("#efe9e2"),
    readerBorder = css("#ffffff1c"),
    readerLine = css("#ffffff12"),
    searchBg = css("#ffffff06"),
    searchBorder = css("#ffffff20"),
    searchInk = css("#efe9e2"),
    placeholder = css("#8d8a86"),
    tabInk = css("#aaa09a"),
    tabSelBg = css("#e5c7aa1c"),
    tabSelInk = css("#e5c7aa"),
    itemSmall = css("#7f858d"),
    itemStrong = css("#c8c4c0"),
    itemActiveBg = css("#ffffff0a"),
    itemActiveBar = css("#dfba98"),
    itemPressBg = css("#ffffff06"),
    meta = css("#ac927c"),
    text = css("#d5d0ca"),
    strong = css("#eee5dc"),
    stamp = css("#8c9299"),
    stampBorder = css("#ffffff12"),
    role = css("#bfa58c"),
    controls = css("#aaa09a"),
    meterBg = css("#ffffff0b"),
    meterFill = css("#dabb9e"),
    notice = css("#8d939a"),
    menuInk = css("#c6b29f"),
    fullInk = css("#9a948e"),
    brandSmall = css("#b8a392"),
    favorite = css("#e5c7aa"),
    readerBackdrop = css("#070e1855"),
    lettersBg = Brush.radialGradient(
        listOf(css("#2a3540"), css("#111d29")),
        center = androidx.compose.ui.geometry.Offset.Zero,
        radius = 1400f,
    ),
    lettersInk = css("#eee7de"),
    lettersSub = css("#b8bbc0"),
    lettersAction = css("#bdc0c6"),
    lettersIntro = css("#989da7"),
    cardBg = Brush.linearGradient(listOf(rgba(226, 207, 185, .10f), rgba(255, 255, 255, .035f))),
    cardBgPressed = Brush.linearGradient(listOf(rgba(226, 207, 185, .14f), rgba(255, 255, 255, .04f))),
    cardBorder = rgba(230, 213, 192, .12f),
    cardBorderPressed = css("#ead4b94d"),
    number = css("#d5b99b"),
    quote = css("#fff3e5"),
    author = css("#d6c9bb"),
    open = css("#b6b4b0"),
    end = css("#b99e80"),
    lettersBackdrop = rgba(10, 15, 22, .42f),
    detailBg = Brush.linearGradient(listOf(css("#26313c"), css("#101d2a"), css("#101d2a"))),
    detailBorder = css("#cbb29226"),
    detailInk = css("#eee7de"),
    detailHead = css("#dfc7a7"),
    detailMark = css("#dcc09a66"),
    detailAuthor = css("#d6c9bb"),
    detailLine = css("#ffffff0d"),
    detailButton = css("#bfb9b1"),
    detailCount = css("#a69d93"),
    detailBackdrop = css("#060d17a6"),
    toggleBg = css("#ffffff08"),
    toggleInk = css("#ebd1b1"),
)

val LightPalette = DarkPalette.copy(
    light = true,
    modalBg = css("#fafbfc"),
    modalInk = css("#303840"),
    modalMuted = css("#69727b"),
    modalLine = css("#3c465220"),
    aboutBg = css("#fafbfc"),
    aboutText = css("#626968"),
    noteBg = css("#ffffff91"),
    noteInk = css("#35414b"),
    saveBg = css("#e1e7ed"),
    saveInk = css("#35414b"),
    sliderAccent = css("#8b9ead"),
    backdrop = css("#a6afbb33"),
    readerBg = css("#f7f8fa"),
    readerInk = css("#33414d"),
    readerBorder = css("#66574320"),
    readerLine = css("#6b604c1a"),
    searchBg = css("#ffffff99"),
    searchBorder = css("#76655030"),
    searchInk = css("#37434d"),
    placeholder = css("#73716c"),
    tabInk = css("#68706f"),
    tabSelBg = css("#e5ebf1"),
    tabSelInk = css("#455e74"),
    itemSmall = css("#747770"),
    itemStrong = css("#424c53"),
    itemActiveBg = css("#e5ebf1"),
    itemActiveBar = css("#8b9ead"),
    itemPressBg = css("#e5ebf1"),
    meta = css("#758495"),
    text = css("#414b51"),
    strong = css("#29333b"),
    stamp = css("#777c77"),
    stampBorder = css("#dce0e5"),
    role = css("#758495"),
    controls = css("#68706f"),
    meterBg = css("#dce0e5"),
    meterFill = css("#8b9ead"),
    notice = css("#68706f"),
    menuInk = css("#68706f"),
    fullInk = css("#68706f"),
    brandSmall = css("#758495"),
    favorite = css("#455e74"),
    readerBackdrop = css("#a6afbb33"),
    lettersBg = Brush.linearGradient(listOf(css("#f2f4f6"), css("#f2f4f6"))),
    lettersInk = css("#303840"),
    lettersSub = css("#707982"),
    lettersAction = css("#707982"),
    lettersIntro = css("#707982"),
    cardBg = Brush.linearGradient(listOf(Color.White, Color.White)),
    cardBgPressed = Brush.linearGradient(listOf(css("#fafbfc"), css("#fafbfc"))),
    cardBorder = css("#e4e7eb"),
    cardBorderPressed = css("#c8d0d9"),
    number = css("#89939e"),
    quote = css("#35424a"),
    author = css("#697580"),
    open = css("#798490"),
    end = css("#89939e"),
    lettersBackdrop = css("#a6afbb33"),
    detailBg = Brush.linearGradient(listOf(Color.White, Color.White)),
    detailBorder = css("#dce0e5"),
    detailInk = css("#303840"),
    detailHead = css("#7b8793"),
    detailMark = css("#a6b3c166"),
    detailAuthor = css("#697580"),
    detailLine = css("#e6e9ed"),
    detailButton = css("#6f7c88"),
    detailCount = css("#6f7c88"),
    detailBackdrop = css("#24314055"),
    toggleBg = css("#e9edf1"),
    toggleInk = css("#657787"),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }
