package com.fuguang.lueying

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 绯句：留给你的话。一封一张色块，先露三行；点开是一整封，可以前后翻。 */
@Composable
internal fun LettersPage(letters: List<Letter>) {
    Column(Modifier.widthIn(max = 600.dp).fillMaxSize()) {
        CardPageHead("绯句", "写给你")
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 10.dp)) {
            Txt("留给你的话", Page.muted.copy(alpha = .55f), 10.5.sp, Modifier.weight(1f), spacing = ls(2f, 10.5f))
            Txt("%02d / LETTERS".format(letters.size), Page.muted.copy(alpha = .55f), 10.5.sp, spacing = ls(2f, 10.5f))
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(letters) { i, l ->
                PressSurface(
                    { NoteCard.letter = i; NoteCard.screen = "letter" },
                    Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), pressScale = .985f,
                    label = "阅读${l.author.removePrefix("——")}的完整留言",
                ) {
                    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp)) {
                        Txt("%02d".format(i + 1), NoteEyebrow, 10.sp, spacing = ls(3f, 10f))
                        Spacer(Modifier.height(8.dp))
                        Txt(l.quote, Page.ink.copy(alpha = .86f), 15.sp, lineHeight = 27.sp, family = Serif, maxLines = 3)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth()) {
                            Txt("展开 ↗", Page.muted.copy(alpha = .45f), 11.sp, Modifier.weight(1f))
                            Txt(l.author, Page.muted, 12.sp, spacing = ls(2f, 12f))
                        }
                    }
                }
            }
            item { Txt("·", Page.muted.copy(alpha = .4f), 22.sp, Modifier.fillMaxWidth().padding(vertical = 14.dp), family = Serif, align = TextAlign.Center) }
        }
    }
}

@Composable
internal fun LetterPage(letters: List<Letter>) {
    val count = letters.size
    if (count == 0) return
    val index = NoteCard.letter.coerceIn(0, count - 1)
    Column(Modifier.widthIn(max = 600.dp).fillMaxSize()) {
        CardPageHead("绯句", "写给你")
        Crossfade(index, Modifier.weight(1f), animationSpec = tween(220), label = "letter") { i ->
            val l = letters[i]
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
                Txt("“", NoteWarm.copy(alpha = .5f), 64.sp, Modifier.height(48.dp), family = Serif, lineHeight = 76.sp)
                Txt(l.quote, Page.ink, 18.sp, Modifier.padding(top = 6.dp, bottom = 18.dp), spacing = ls(.6f, 18f), lineHeight = 36.sp, family = Serif)
                Txt(l.author, Page.muted, 12.5.sp, Modifier.fillMaxWidth().padding(bottom = 12.dp), spacing = ls(2f, 12.5f), align = TextAlign.End)
            }
        }
        CardBottomBar {
            BarText("← 上一封", index > 0) { NoteCard.letter = index - 1 }
            Spacer(Modifier.weight(1f))
            Txt("%02d / %02d".format(index + 1, count), Page.muted, 11.sp, spacing = ls(2f, 11f))
            Spacer(Modifier.weight(1f))
            BarText("下一封 →", index < count - 1) { NoteCard.letter = index + 1 }
        }
    }
}
