package com.fuguang.lueying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val spark = Ic("M12 3l1.8 7.2L21 12l-7.2 1.8L12 21l-1.8-7.2L3 12l7.2-1.8Z")
private val plugIc = Ic("M9 3v5M15 3v5M6.5 8h11v3a5.5 5.5 0 0 1-11 0ZM12 16.5V21")
private val closeIc = Ic("M6 6 18 18M6 18 18 6")

/** 卡片里的「模型」标签：点一个就让她用它（卡片和抽屉一起收掉）；测试、删除、获取、手动添加。 */
@Composable
fun ModelsPane(store: AssistantStore, p: Provider, onPicked: () -> Unit) {
    val c = store.config(p.id)
    val who = Memory.active.name
    val scope = rememberCoroutineScope()
    var status by remember(p.id) { mutableStateOf<Pair<Boolean, String>?>(null) }
    var pinging by remember(p.id) { mutableStateOf<String?>(null) }
    var adding by remember(p.id) { mutableStateOf(false) }
    var newModel by remember(p.id) { mutableStateOf("") }
    var fetching by remember(p.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            c.models.forEach { m ->
                val inUse = store.activeProvider == p.id && store.activeModel == m
                ModelRow(
                    m, inUse, who, pinging == m,
                    onPick = {
                        if (!c.configured) status = false to "先在「配置」里填好 API Key"
                        else { store.use(p.id, m); onPicked() }
                    },
                    onPing = {
                        pinging = m; status = null
                        scope.launch {
                            AssistantApi.ping(p, store.config(p.id), m)
                                .onSuccess { status = true to "$m 可以正常使用" }
                                .onFailure { status = false to "$m：${it.message}" }
                            pinging = null
                        }
                    },
                    onDelete = {
                        store.update(p.id, c.copy(models = c.models - m))
                        if (inUse) store.use(p.id, (c.models - m).firstOrNull() ?: "")
                    },
                )
            }
            if (c.models.isEmpty()) Txt("还没有模型。点「获取模型」从供应商拉取，或手动添加。", Page.muted, 12.5.sp, Modifier.fillMaxWidth().padding(vertical = 10.dp), lineHeight = 20.sp, align = TextAlign.Center)
            status?.let { (ok, msg) -> Txt(msg, if (ok) Page.accent else CardRose, 12.5.sp, Modifier.padding(start = 4.dp, top = 4.dp)) }
            if (c.models.isNotEmpty()) Txt("点一个模型，$who 就用它聊天。", Page.muted.copy(alpha = .5f), 12.sp, Modifier.fillMaxWidth().padding(top = 6.dp), align = TextAlign.Center)
            if (adding) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CardField(newModel, { newModel = it.trim() }, "模型名，例如 deepseek-flash", Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    CardButton(newModel.isNotBlank(), if (newModel.isNotBlank()) BtnTone.Primary else BtnTone.Quiet, "添加", onClick = {
                        if (newModel.isNotBlank()) {
                            if (newModel !in c.models) store.update(p.id, c.copy(models = c.models + newModel))
                            newModel = ""; adding = false
                        }
                    }, modifier = Modifier.width(72.dp)) { _, ink -> BtnLabel("添加", ink) }
                }
            }
        }
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton(fetching, BtnTone.Plain, "获取模型", onClick = {
                fetching = true; status = null
                scope.launch {
                    AssistantApi.models(p, store.config(p.id))
                        .onSuccess { list ->
                            val now = store.config(p.id)
                            val added = list.filter { it !in now.models }
                            store.update(p.id, now.copy(models = now.models + added))
                            status = true to if (added.isEmpty()) "没有新模型" else "新增 ${added.size} 个模型"
                        }
                        .onFailure { status = false to (it.message ?: "获取失败") }
                    fetching = false
                }
            }, modifier = Modifier.weight(1f), busy = fetching) { busy, ink ->
                if (busy) BtnLabel("获取中…", ink) { LoadRing(ink) } else BtnLabel("获取模型", ink)
            }
            CardButton(adding, BtnTone.Primary, "添加新模型", onClick = { adding = !adding }, modifier = Modifier.weight(1f)) { open, ink ->
                BtnLabel(if (open) "收起" else "添加新模型", ink)
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun ModelRow(m: String, inUse: Boolean, who: String, pinging: Boolean, onPick: () -> Unit, onPing: () -> Unit, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    PressSurface(
        onPick,
        Modifier.fillMaxWidth(),
        shape = shape, pressScale = .985f, label = "让 $who 用 $m", edge = if (inUse) CardRose.copy(alpha = .6f) else Color.Transparent,
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            SvgIcon(spark, Page.ink.copy(alpha = .8f), 18.dp, stroke = 1.2f)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(m, Page.ink, 14.5.sp, weight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (inUse) Txt("$who 在用", Page.ink, 11.sp, Modifier.background(CardRose.copy(alpha = .7f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 1.dp), maxLines = 1)
                    CardTag("聊天")
                    CardTag("文本 → 文本")
                }
            }
            PressSurface(onPing, Modifier.size(38.dp), shape = CircleShape, fill = Color.Transparent, pressScale = .9f, enabled = !pinging, label = "测试 $m") {
                if (pinging) LoadRing(Page.accent, 16.dp) else SvgIcon(plugIc, Page.ink, 18.dp)
            }
            PressSurface(onDelete, Modifier.size(38.dp), shape = CircleShape, fill = Color.Transparent, pressScale = .9f, label = "删除 $m") {
                SvgIcon(closeIc, Page.ink, 16.dp)
            }
        }
    }
}
