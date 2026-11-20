package com.fuguang.lueying

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 世界书页面（照 Kelivo features/world_book/pages/world_book_page.dart、widgets/world_book_entry_widgets.dart、
 * home/widgets/world_book_sheet.dart）：全局设置 › 世界书 → 一本本书 → 一条条条目；聊天里点「世界书」勾这位助理（或只这个对话）用哪几本。
 */
@Composable
internal fun WorldBookPage(screen: String, nav: MineNav) {
    val sheet = remember { KSheetState() }
    Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            val id = screen.removePrefix("worldbook").removePrefix("/")
            if (id.isEmpty()) BookList(nav, sheet) else WorldBooks.get(id)?.let { BookDetail(it, nav, sheet) } ?: LaunchedEffect(id) { nav.screen = "worldbook" }
        }
        KSheetHost(sheet)
    }
}

@Composable
private fun ColumnScope.BookList(nav: MineNav, sheet: KSheetState) {
    val ctx = LocalContext.current
    var toast by remember { mutableStateOf("") }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u == null) return@rememberLauncherForActivityResult
        val raw = runCatching { ctx.contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        toast = when {
            raw.isNullOrBlank() -> "文件内容为空"
            else -> WorldBooks.import(raw, u.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "世界书")?.let { WorldBooks.put(it); "导入完成" } ?: "JSON 解析失败"
        }
    }
    LaunchedEffect(toast) { if (toast.isNotEmpty()) { kotlinx.coroutines.delay(1800); toast = "" } }
    Title("世界书", false, onBack = { nav.screen = "mine" }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KChip("导入") { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }
            KChip("添加世界书") { sheet.open { BookSheet(null, sheet) } }
        }
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        if (WorldBooks.books.isEmpty()) KCard { Txt("暂无世界书", K.sub, 13.5.sp, Modifier.fillMaxWidth().padding(26.dp), align = TextAlign.Center) }
        WorldBooks.books.forEach { b ->
            KCard {
                KRow(b.name.ifBlank { "未命名世界书" }, KI.worldBook, listOfNotNull(b.description.takeIf { it.isNotBlank() }, "已启用 ${b.enabledCount}/${b.entries.size}").joinToString(" · "),
                    tag = if (b.enabled) null else "已停用", onClick = { nav.screen = "worldbook/${b.id}" })
            }
        }
        if (toast.isNotEmpty()) Txt(toast, K.acc, 13.sp, Modifier.fillMaxWidth().padding(8.dp), align = TextAlign.Center)
    }
}

@Composable
private fun BookSheet(book: WorldBook?, sheet: KSheetState, onDeleted: () -> Unit = {}) {
    var name by remember { mutableStateOf(book?.name.orEmpty()) }
    var desc by remember { mutableStateOf(book?.description.orEmpty()) }
    var on by remember { mutableStateOf(book?.enabled ?: true) }
    var asking by remember { mutableStateOf(false) }
    KSheetTitle(if (book == null) "添加世界书" else "配置")
    KLabel("名称"); KField(name, { name = it })
    KLabel("简介"); KField(desc, { desc = it }, lines = 2)
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt("启用", K.ink, 15.sp, Modifier.weight(1f)); KSwitch(on) { on = it }
    }
    if (asking && book != null) Txt("确定删除「${book.name.ifBlank { "未命名世界书" }}」？此操作无法撤销。", K.danger, 13.sp, Modifier.padding(bottom = 10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (book != null) KButton(if (asking) "删除" else "删除世界书", Modifier.weight(1f), color = K.danger) {
            if (!asking) asking = true else { WorldBooks.remove(book.id); AssistantExt.save(); sheet.close(); onDeleted() }
        }
        KButton("保存", Modifier.weight(1f), primary = true) {
            WorldBooks.put((book ?: WorldBook(WbEntry.newId())).copy(name = name.trim(), description = desc.trim(), enabled = on))
            sheet.close()
        }
    }
}

@Composable
private fun ColumnScope.BookDetail(b: WorldBook, nav: MineNav, sheet: KSheetState) {
    val ctx = LocalContext.current
    var multi by remember { mutableStateOf(false) }
    val sel = remember { mutableStateListOf<String>() }
    var toast by remember { mutableStateOf("") }
    LaunchedEffect(toast) { if (toast.isNotEmpty()) { kotlinx.coroutines.delay(1800); toast = "" } }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u: Uri? ->
        if (u == null) return@rememberLauncherForActivityResult
        toast = runCatching { ctx.contentResolver.openOutputStream(u)?.use { it.write(WorldBooks.export(b).toByteArray()) }; "已导出为 ${b.name.ifBlank { "world_book" }}.json" }
            .getOrElse { "导出失败：${it.message}" }
    }
    Title(b.name.ifBlank { "未命名世界书" }, false, onBack = { nav.screen = "worldbook" }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KChip(if (multi) "完成" else "多选条目") { multi = !multi; sel.clear() }
            KChip("分享/导出") { exporter.launch("${b.name.ifBlank { "world_book" }}.json") }
            KChip("配置") { sheet.open { BookSheet(b, sheet) { nav.screen = "worldbook" } } }
        }
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        KCard {
            if (b.entries.isEmpty()) Txt("暂无条目", K.sub, 13.5.sp, Modifier.fillMaxWidth().padding(26.dp), align = TextAlign.Center)
            b.entries.forEachIndexed { i, e ->
                val sub = if (e.keywords.isNotEmpty()) e.keywords.joinToString("、") else e.content.take(40)
                val pos = e.position.label + if (e.position == WbPosition.DEPTH) " · ${e.injectDepth}" else ""
                KRow(e.name.ifBlank { "未命名条目" }, sub = sub, tag = if (e.constantActive) "常驻" else null, value = pos, divider = i > 0,
                    onClick = { if (multi) { if (e.id in sel) sel.remove(e.id) else sel.add(e.id) } else sheet.open { EntrySheet(b, e, sheet) } }) {
                    if (multi) KCheck(e.id in sel)
                    else KSwitch(e.enabled) { on -> WorldBooks.put(b.copy(entries = b.entries.map { if (it.id == e.id) it.copy(enabled = on) else it })) }
                }
            }
            Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 16.dp)) {
                KButton("添加条目", Modifier.weight(1f), primary = true) { sheet.open { EntrySheet(b, null, sheet) } }
            }
        }
        if (toast.isNotEmpty()) Txt(toast, K.acc, 13.sp, Modifier.fillMaxWidth().padding(8.dp), align = TextAlign.Center)
    }
    AnimatedVisibility(multi, enter = slideInVertically(tween(320, easing = K.decel)) { it }, exit = slideOutVertically(tween(220, easing = K.accel)) { it }) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp)).background(K.card).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt("已选择 ${sel.size} 项", K.sub, 13.sp, Modifier.weight(1f))
            KChip(if (sel.size == b.entries.size && sel.isNotEmpty()) "取消全选" else "全选") { if (sel.size == b.entries.size) sel.clear() else { sel.clear(); sel.addAll(b.entries.map { it.id }) } }
            for (move in listOf(false, true)) KChip(if (move) "移动到…" else "复制到…") {
                val others = WorldBooks.books.filter { it.id != b.id }
                if (sel.isEmpty()) return@KChip
                if (others.isEmpty()) { toast = "请先创建另一本世界书，再移动条目。"; return@KChip }
                sheet.open {
                    KSheetTitle(if (move) "移动到…" else "复制到…")
                    others.forEach { o ->
                        KOption(o.name.ifBlank { "未命名世界书" }, false) {
                            val picked = b.entries.filter { it.id in sel }
                            WorldBooks.put(o.copy(entries = o.entries + picked.map { it.copy(id = WbEntry.newId()) }))
                            if (move) WorldBooks.put(b.copy(entries = b.entries.filter { it.id !in sel }))
                            toast = (if (move) "已移动 " else "已复制 ") + "${picked.size} 个条目"
                            sel.clear(); multi = false; sheet.close()
                        }
                    }
                }
            }
            KChip("删除") { WorldBooks.put(b.copy(entries = b.entries.filter { it.id !in sel })); sel.clear() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntrySheet(b: WorldBook, e0: WbEntry?, sheet: KSheetState) {
    var e by remember { mutableStateOf(e0 ?: WbEntry(WbEntry.newId())) }
    var kw by remember { mutableStateOf("") }
    fun num(s: String) = s.filter { it.isDigit() }.take(5).toIntOrNull() ?: 0
    KSheetTitle(if (e0 == null) "添加条目" else "编辑条目")
    KLabel("条目名称"); KField(e.name, { e = e.copy(name = it) })
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Txt("启用条目", K.ink, 15.sp, Modifier.weight(1f)); KSwitch(e.enabled) { e = e.copy(enabled = it) } }
    KLabel("内容"); KField(e.content, { e = e.copy(content = it) }, lines = 5)
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Txt("常驻激活", K.ink, 15.sp); Txt("无需匹配也会注入", K.sub, 12.sp) }
        KSwitch(e.constantActive) { e = e.copy(constantActive = it) }
    }
    Column(Modifier.then(if (e.constantActive) Modifier.background(androidx.compose.ui.graphics.Color.Transparent) else Modifier)) {
        KLabel("关键词")
        if (e.keywords.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            e.keywords.forEachIndexed { i, k -> KChip("$k  ×") { e = e.copy(keywords = e.keywords.filterIndexed { j, _ -> j != i }) } }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            KField(kw, { kw = it }, Modifier.weight(1f), hint = "添加关键词")
            Spacer(Modifier.width(8.dp))
            KButton("+", Modifier.width(52.dp), primary = true) { if (kw.isNotBlank()) { e = e.copy(keywords = e.keywords + kw.trim()); kw = "" } }
        }
        Txt("输入关键词后点 + 添加。", K.sub, 12.sp, Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Txt("使用正则", K.ink, 15.sp, Modifier.weight(1f)); KSwitch(e.useRegex) { e = e.copy(useRegex = it) } }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Txt("区分大小写", K.ink, 15.sp, Modifier.weight(1f)); KSwitch(e.caseSensitive) { e = e.copy(caseSensitive = it) } }
        KLabel("扫描深度"); KField(e.scanDepth.toString(), { e = e.copy(scanDepth = num(it).coerceAtLeast(1)) }, number = true)
    }
    KLabel("注入位置")
    KSeg(WbPosition.entries.take(3).map { it to it.label }, { e.position == it }) { e = e.copy(position = it) }
    Spacer(Modifier.height(6.dp))
    KSeg(WbPosition.entries.drop(3).map { it to it.label }, { e.position == it }) { e = e.copy(position = it) }
    if (e.position == WbPosition.DEPTH) { KLabel("注入深度"); KField(e.injectDepth.toString(), { e = e.copy(injectDepth = num(it)) }, number = true) }
    KLabel("注入角色")
    KSeg(listOf("user" to "用户", "assistant" to "助手"), { e.role == it }) { e = e.copy(role = it) }
    KLabel("优先级"); KField(e.priority.toString(), { e = e.copy(priority = num(it)) }, number = true)
    KLabel("粘滞（消息数）"); KField(e.sticky.toString(), { e = e.copy(sticky = num(it)) }, number = true)
    Txt("触发后在后续 N 条消息中保持激活，重复命中不延长。0 表示关闭。", K.sub, 12.sp, Modifier.padding(top = 4.dp))
    KLabel("冷却（消息数）"); KField(e.cooldown.toString(), { e = e.copy(cooldown = num(it)) }, number = true)
    Txt("触发后（或粘滞结束后）N 条消息内不再触发。0 表示关闭。", K.sub, 12.sp, Modifier.padding(top = 4.dp))
    KLabel("延迟（消息数）"); KField(e.delay.toString(), { e = e.copy(delay = num(it)) }, number = true)
    Txt("对话至少有 N 条消息时才允许触发。按单条消息计数，不是对话轮数。0 表示关闭。", K.sub, 12.sp, Modifier.padding(top = 4.dp))
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (e0 != null) KButton("删除", Modifier.weight(1f), color = K.danger) { WorldBooks.put(b.copy(entries = b.entries.filter { it.id != e0.id })); sheet.close() }
        else KButton("取消", Modifier.weight(1f)) { sheet.close() }
        KButton("保存", Modifier.weight(1f), primary = true) {
            val cur = WorldBooks.get(b.id) ?: b
            WorldBooks.put(cur.copy(entries = if (e0 == null) cur.entries + e else cur.entries.map { if (it.id == e.id) e else it }))
            sheet.close()
        }
    }
}

// ───────── 聊天里的弹层：选世界书、快捷短语 ─────────

object ChatSheets {
    val sheet = KSheetState()

    /** 聊天里点「世界书」（Kelivo world_book_sheet）：勾这位助理用哪几本；助理开着「独立对话指令注入」时可以只对这个对话勾。 */
    fun worldBooks(convId: String?) {
        sheet.open {
            val p = Memory.active
            val x = AssistantExt.of(p)
            var convOnly by remember { mutableStateOf(convId != null && WorldBooks.convPick(convId) != null) }
            val picked = remember { mutableStateListOf<String>().apply { addAll(convId?.let { WorldBooks.convPick(it) }?.takeIf { convOnly } ?: x.worldBooks) } }
            fun commit() {
                if (convOnly && convId != null) WorldBooks.setConvPick(convId, picked.toList())
                else { if (convId != null) WorldBooks.setConvPick(convId, null); x.worldBooks.clear(); x.worldBooks.addAll(picked); AssistantExt.save() }
            }
            KSheetTitle("世界书")
            if (x.perConvInject && convId != null) Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("仅当前对话", K.ink, 15.sp, Modifier.weight(1f)); KSwitch(convOnly) { convOnly = it; commit() }
            }
            if (WorldBooks.books.isEmpty()) Txt("暂无世界书", K.sub, 13.5.sp, Modifier.fillMaxWidth().padding(20.dp), align = TextAlign.Center)
            WorldBooks.books.forEach { b ->
                Pressable({ if (b.id in picked) picked.remove(b.id) else picked.add(b.id); commit() }, Modifier.fillMaxWidth(), label = b.name) { pr ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (pr) K.card2 else androidx.compose.ui.graphics.Color.Transparent).padding(horizontal = 4.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        KCheck(b.id in picked)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) { Txt(b.name.ifBlank { "未命名世界书" }, K.ink, 15.sp); if (!b.enabled) { Spacer(Modifier.width(6.dp)); KTag("已停用") } }
                            Txt("已启用 ${b.enabledCount}/${b.entries.size}", K.sub, 12.sp)
                        }
                    }
                }
            }
        }
    }

    /** 快捷短语（Kelivo 输入框里的短语）：点一条放进输入框。 */
    fun phrases(onPick: (String) -> Unit) {
        sheet.open {
            val x = AssistantExt.of(Memory.active)
            KSheetTitle("快捷短语")
            x.phrases.forEach { ph -> KOption(ph.title, false, ph.content.take(80)) { onPick(ph.content); sheet.close() } }
        }
    }
}

@Composable
fun ChatSheetsLayer() { Box(Modifier.fillMaxSize()) { KSheetHost(ChatSheets.sheet) } }

/** 助理设置 › 聊天背景：设了就铺在聊天页后面（盖住主题的背景画面）。 */
@Composable
fun AssistantBackdrop() {
    val uri = AssistantExt.of(Memory.active).background ?: return
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val u = Uri.parse(uri)
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
                var s = 1; while (o.outWidth / s > 2400 || o.outHeight / s > 2400) s *= 2
                ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }?.asImageBitmap()
            }.getOrNull()
        }
    }
    img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
}
