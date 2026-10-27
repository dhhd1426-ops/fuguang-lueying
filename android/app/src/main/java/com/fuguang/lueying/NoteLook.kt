package com.fuguang.lueying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 背景的暗度、虚化（原来在右上角圆钮里，现在在手记卡片最下面「显示」一块）；
 * 「只看画面」请 App 马上进沉浸，轻点任意处回来。
 */
object ScreenLook {
    var dim by mutableIntStateOf(20)
    var blur by mutableIntStateOf(12)
    /** 手记里点了「只看画面」：App 看到就关卡片、进沉浸，再把它放回 false。 */
    var zenNow by mutableStateOf(false)
    private var prefs: Prefs? = null

    fun init(p: Prefs) { prefs = p; dim = p.dim; blur = p.blur }
    fun setDimTo(v: Int) { dim = v; prefs?.dim = v }
    fun setBlurTo(v: Int) { blur = v; prefs?.blur = v }
}

/** 手记首页最下面：显示。 */
@Composable
internal fun NoteDisplay(i: Int, t: Float) {
    Txt("显示", Page.muted, 11.sp, Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp).mineRise(i, t), spacing = ls(2.75f, 11f))
    Column(Modifier.fillMaxWidth().mineRise(i + 1, t).background(Skin.well(.22f), RoundedCornerShape(16.dp)).padding(vertical = 4.dp)) {
        LookSlider("暗度", ScreenLook.dim, 60) { ScreenLook.setDimTo(it) }
        LookSlider("虚化", ScreenLook.blur, 24) { ScreenLook.setBlurTo(it) }
    }
    Column(Modifier.padding(top = 6.dp)) {
        MineRow(i + 2, t, "只看画面", "轻点任意处回来", { ScreenLook.zenNow = true },
            glyph = { Txt("画", NoteWarm, 17.sp, family = Serif) }) {}
    }
}

@Composable
private fun LookSlider(label: String, v: Int, max: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Page.muted, 13.sp, Modifier.width(44.dp), maxLines = 1)
        RangeSlider(v, 0, max, NoteWarm, veil(.15f), onChange, Modifier.weight(1f))
        Txt("$v", NoteWarm, 12.sp, Modifier.width(30.dp).padding(start = 8.dp), maxLines = 1)
    }
}
