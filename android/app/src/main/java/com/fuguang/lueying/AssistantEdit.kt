package com.fuguang.lueying

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 助理设置（照 Kelivo features/assistant/pages/assistant_settings_edit_*.dart）：九个标签，文字照 Kelivo 中文原文。
 * 原来的「记忆」页并进第三个标签；原来的「条目」搬去全局设置 › 世界书（WorldBooks.kt）。
 */
private val TABS = listOf("基础设置", "提示词", "记忆", "本地工具", "技能", "MCP", "快捷短语", "自定义请求", "正则替换")

@Composable
internal fun ColumnScope.AssistantEditScreen(store: AssistantStore, p: Persona, compact: Boolean, blur: Dp, nav: MineNav) {
    Title(p.name.ifBlank { "未命名" }, compact, onBack = { nav.back() })
    val x = AssistantExt.of(p)
    val pager = rememberPagerState(initialPage = nav.tab.coerceIn(0, TABS.lastIndex)) { TABS.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.currentPage) { nav.tab = pager.currentPage }
    val sheet = remember { KSheetState() }
    KTabs(TABS, pager.currentPage + pager.currentPageOffsetFraction) { scope.launch { pager.animateScrollToPage(it) } }
    Spacer(Modifier.height(10.dp))
    Box(Modifier.weight(1f).fillMaxWidth()) {
        HorizontalPager(pager, Modifier.fillMaxSize(), verticalAlignment = Alignment.Top, beyondViewportPageCount = 1, pageSpacing = 16.dp) { page ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                when (page) {
                    0 -> BasicPage(store, p, x, blur, sheet)
                    1 -> PromptPage(p, x, blur, sheet)
                    2 -> MemoryPage(p, x, nav, sheet)
                    3 -> ToolsPage(x)
                    4 -> SkillsTabPage(p, x, nav)
                    5 -> McpTabPage(x, nav)
                    6 -> PhrasesPage(x, sheet)
                    7 -> CustomRequestPage(x, sheet)
                    else -> RegexPage(x, sheet)
                }
            }
        }
        KSheetHost(sheet)
    }
}

private fun saveX() = AssistantExt.save()
private fun fmt(v: Float) = String.format(java.util.Locale.US, "%.2f", v)

// ───────── 基础设置 ─────────

@Composable
private fun BasicPage(store: AssistantStore, p: Persona, x: AsstExt, blur: Dp, sheet: KSheetState) {
    NameCard(p, blur)
    Spacer(Modifier.height(14.dp))
    KCard {
        KRow("Temperature", KI.therm, value = if (p.tempOn) fmt(p.temp) else "已关闭（使用服务商默认）", onClick = {
            sheet.open { ParamSheet("Temperature", p.tempOn, p.temp, 0f, 2f, .1f, "控制输出的随机性，范围 0–2", { p.tempOn = it; if (it && p.temp !in 0f..2f) p.temp = .6f; Memory.save(); sheet.close() }) { p.temp = it; Memory.save() } }
        })
        KRow("Top P", KI.wand, value = if (p.topOn) fmt(p.top) else "已关闭（使用服务商默认）", divider = true, onClick = {
            sheet.open { ParamSheet("Top P", p.topOn, p.top, 0f, 1f, .05f, "请不要修改此值，除非你知道自己在做什么", { p.topOn = it; Memory.save(); sheet.close() }) { p.top = it; Memory.save() } }
        })
        KRow("上下文消息数量", KI.msgs, value = if (p.limit <= 0) "已关闭（无限制）" else "${p.limit}", divider = true, onClick = {
            sheet.open {
                ParamSheet("上下文消息数量", p.limit > 0, (if (p.limit > 0) p.limit else 64).toFloat(), 1f, 512f, 1f,
                    "多少历史消息会被当作上下文发送给模型，超过数量会忽略，只保留最近 N 条", { p.limit = if (it) 64 else 0; Memory.save(); sheet.close() }, int = true, offText = "已关闭（无限制）") { p.limit = it.toInt(); Memory.save() }
            }
        })
        KRow("思考", KI.brain, value = x.think?.let { Effort.label(it) } ?: "跟随模型默认", divider = true, onClick = {
            sheet.open {
                KSheetTitle("思考")
                Txt("实际档位会按各模型的能力收敛", K.sub, 12.sp, Modifier.padding(bottom = 6.dp))
                val opts = listOf<String?>(null, Effort.OFF, Effort.AUTO, "minimal", "low", "medium", "high", "xhigh", "max")
                opts.forEach { v ->
                    KOption(v?.let { Effort.label(it) } ?: "跟随模型默认", x.think == v,
                        when (v) { null -> "使用模型或助手未设置时的默认档位"; Effort.AUTO -> "使用模型或供应商的默认设置"; Effort.OFF -> "关闭推理，直接回答"; else -> null }) {
                        x.think = v; saveX(); sheet.close()
                    }
                }
            }
        })
        KRow("最大 Token 数", KI.hash, value = x.maxTokens?.toString() ?: "无限制", divider = true, onClick = {
            sheet.open {
                var t by remember { mutableStateOf(x.maxTokens?.toString().orEmpty()) }
                KSheetTitle("最大 Token 数")
                KField(t, { s -> t = s.filter { it.isDigit() }.take(7) }, hint = "留空表示无限制", number = true)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KButton("取消", Modifier.weight(1f)) { sheet.close() }
                    KButton("保存", Modifier.weight(1f), primary = true) { x.maxTokens = t.toIntOrNull()?.takeIf { it > 0 }; saveX(); sheet.close() }
                }
            }
        })
        KRow("流式输出", KI.zap, divider = true) { KSwitch(x.stream, "流式输出") { x.stream = it; saveX() } }
    }
    KCard {
        val label = x.chatModel?.let { s -> s.split("|", limit = 2).let { (pid, m) -> "${provider(pid).name} · $m" } } ?: "使用全局默认"
        KRow("聊天模型", KI.chat, "为该助手设置默认聊天模型（未设置时使用全局默认）", label, onClick = {
            sheet.open {
                KSheetTitle("聊天模型") { if (x.chatModel != null) KChip("重置为默认") { x.chatModel = null; saveX(); sheet.close() } }
                KOption("使用全局默认", x.chatModel == null) { x.chatModel = null; saveX(); sheet.close() }
                PROVIDERS.filter { store.config(it.id).configured }.forEach { pv ->
                    store.config(pv.id).models.forEach { m ->
                        val v = "${pv.id}|$m"
                        KOption("${pv.name} · $m", x.chatModel == v) { x.chatModel = v; saveX(); if (Memory.active.id == p.id) store.use(pv.id, m); sheet.close() }
                    }
                }
            }
        })
    }
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            x.background = u.toString(); saveX()
        }
    }
    KCard {
        KRow("聊天背景", KI.image, "设置助手聊天页面的背景图片", if (x.background != null) "已设置" else null, onClick = {
            sheet.open {
                KSheetTitle("聊天背景")
                KOption("选择背景图片", false) { sheet.close(); pick.launch(arrayOf("image/*")) }
                if (x.background != null) KOption("清除", false) { x.background = null; saveX(); sheet.close() }
            }
        })
    }
}

/** Temperature / Top P / 上下文条数的弹层：右上角开关，开着才有滑杆。 */
@Composable
private fun ParamSheet(
    title: String, on: Boolean, value: Float, min: Float, max: Float, step: Float, desc: String,
    onToggle: (Boolean) -> Unit, int: Boolean = false, offText: String = "已关闭（使用服务商默认）", onValue: (Float) -> Unit,
) {
    var v by remember { mutableStateOf(value) }
    KSheetTitle(title) { KSwitch(on, title, onToggle) }
    if (on) {
        Txt(if (int) "${v.toInt()}" else fmt(v), K.acc, 13.sp, Modifier.padding(bottom = 2.dp))
        KSlider(v, min, max, step) { v = it; onValue(it) }
        Txt(desc, K.sub, 12.sp, Modifier.padding(top = 6.dp))
    } else Txt(offText, K.sub, 13.sp, Modifier.padding(vertical = 8.dp))
}

// ───────── 提示词 ─────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromptPage(p: Persona, x: AsstExt, blur: Dp, sheet: KSheetState) {
    var ver by remember { mutableIntStateOf(0) }
    KSection("系统提示词")
    key(ver) { Column { PromptTab(p, blur) } }
    KSection("可用变量：")
    KCard {
        FlowRow(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("日期" to "cur_date", "时间" to "cur_time", "日期和时间" to "cur_datetime", "模型ID" to "model_id", "模型名称" to "model_name", "语言环境" to "locale",
                "时区" to "timezone", "系统版本" to "system_version", "设备信息" to "device_info", "电池电量" to "battery_level", "用户昵称" to "nickname", "助手名称" to "assistant_name",
            ).forEach { (l, k) ->
                KChip("$l {$k}") {
                    val add = { p.sys = p.sys.trimEnd() + (if (p.sys.isBlank()) "" else " ") + "{$k}"; Memory.save(); ver++ }
                    if (k.startsWith("cur_")) sheet.open {
                        KSheetTitle("系统提示词中含时间变量")
                        Txt("你的系统提示词里用了 {$k}。系统提示词每次请求都会重新渲染，含时间变量会让每一轮请求的开头都不同，Prompt 缓存无法命中。建议移除这些变量，改用「追加当前时间」——它把时间放在请求末尾，不影响前缀。", K.ink, 13.5.sp, lineHeight = 21.sp)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            KButton("仍然开启", Modifier.weight(1f)) { add(); sheet.close() }
                            KButton("改用追加时间", Modifier.weight(1f), primary = true) { x.appendTime = true; saveX(); sheet.close() }
                        }
                    } else add()
                }
            }
        }
    }
    KSection("聊天内容模板")
    KCard {
        Column(Modifier.padding(14.dp)) {
            KField(x.template, { x.template = it; saveX() }, hint = "{{ message }}", mono = true)
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.well).padding(12.dp)) {
                Txt("预览", K.sub, 12.sp)
                Spacer(Modifier.height(6.dp))
                Txt(AssistantExt.applyTemplate(x.template, "user", "你好啊", System.currentTimeMillis()), K.ink, 13.5.sp,
                    Modifier.clip(RoundedCornerShape(14.dp)).background(K.acc.copy(alpha = .25f)).padding(horizontal = 12.dp, vertical = 8.dp))
                Spacer(Modifier.height(6.dp))
                Txt("你好，有什么我可以帮你的吗？", K.ink, 13.5.sp, Modifier.clip(RoundedCornerShape(14.dp)).background(K.card2).padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
    KSection("预设对话信息")
    KCard {
        if (x.presets.isEmpty()) Txt("暂无预设消息", K.sub, 13.5.sp, Modifier.fillMaxWidth().padding(26.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
        x.presets.forEachIndexed { i, m ->
            KRow(m.text, sub = if (m.role == "user") "用户" else "助手", divider = i > 0, onClick = { sheet.open { PresetSheet(x, sheet, i, m.role) } })
        }
        Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KButton("添加预设用户信息", Modifier.weight(1f)) { sheet.open { PresetSheet(x, sheet, -1, "user") } }
            KButton("添加预设助手信息", Modifier.weight(1f)) { sheet.open { PresetSheet(x, sheet, -1, "assistant") } }
        }
    }
    KCard {
        KRow("独立对话指令注入", sub = "每个对话单独选择指令注入和世界书，默认不选中。") { KSwitch(x.perConvInject) { x.perConvInject = it; saveX() } }
        KRow("追加当前时间", sub = "在每条用户消息末尾追加发送时刻。时间在请求末尾，不影响 Prompt 缓存。", divider = true) { KSwitch(x.appendTime) { x.appendTime = it; saveX() } }
        if (x.appendTime) KRow("使用 ISO 8601 格式", sub = "包含时区偏移，例如 2026-08-08T14:30:05+08:00", divider = true) { KSwitch(x.iso8601) { x.iso8601 = it; saveX() } }
    }
}

@Composable
private fun PresetSheet(x: AsstExt, sheet: KSheetState, index: Int, role: String) {
    var t by remember { mutableStateOf(if (index >= 0) x.presets[index].text else "") }
    KSheetTitle(if (index >= 0) "编辑预设消息" else if (role == "user") "添加预设用户信息" else "添加预设助手信息")
    KField(t, { t = it }, hint = if (role == "user") "输入用户消息…" else "输入助手消息…", lines = 4)
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (index >= 0) KButton("删除", Modifier.weight(1f), color = K.danger) { x.presets.removeAt(index); saveX(); sheet.close() }
        else KButton("取消", Modifier.weight(1f)) { sheet.close() }
        KButton("保存", Modifier.weight(1f), primary = true) {
            if (t.isNotBlank()) { if (index >= 0) x.presets[index] = PresetMsg(role, t.trim()) else x.presets += PresetMsg(role, t.trim()); saveX() }
            sheet.close()
        }
    }
}

// ───────── 记忆（原来的记忆页并进来） ─────────

@Composable
private fun MemoryPage(p: Persona, x: AsstExt, nav: MineNav, sheet: KSheetState) {
    val days = if (p.firstAt <= 0) 0 else ((System.currentTimeMillis() - p.firstAt) / 86_400_000L).toInt() + 1
    KCard {
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((i, s) in listOf(Triple(days, "天", "相识"), Triple(p.chats, "次", "聊过"), Triple(p.items.size, "件", "记得")).withIndex()) {
                if (i > 0) Box(Modifier.width(1.dp).height(30.dp).background(K.line))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Txt("${s.first}", K.ink, 32.sp, maxLines = 1)
                        Spacer(Modifier.width(3.dp))
                        Txt(s.second, K.sub, 13.sp, Modifier.padding(bottom = 6.dp), maxLines = 1)
                    }
                    Txt(s.third, K.sub, 12.sp, maxLines = 1)
                }
            }
        }
    }
    KCard { KRow("记忆", KI.book, "记忆模式、模型与提示词", onClick = { nav.screen = "memmodel" }) }
    KCard {
        KRow("使用长期记忆", KI.book, "把已保存的记忆注入对话，并允许此助手写入新记忆") { KSwitch(p.noteOn) { p.noteOn = it; Memory.save() } }
        KRow("自动整理记忆", KI.sparkle, "对话后自动运行记忆管线", enabled = p.noteOn, divider = true) { KSwitch(x.autoOrganize) { x.autoOrganize = it; saveX() } }
        KRow("允许回忆过去对话", KI.recall, "启用跨会话的对话搜索", divider = true) { KSwitch(p.memOn) { p.memOn = it; Memory.save() } }
        KRow("前情", KI.fold, "聊长了把前面按话题整理成几段，原话都留着", divider = true, onClick = { nav.screen = "fold" }) { KSwitch(p.foldOn) { p.foldOn = it; Memory.save() } }
    }
    KCard {
        KRow("整理频率", sub = "每累积 N 轮助手回复触发一次自动整理", value = "每 ${x.organizeEvery} 轮", enabled = p.noteOn && x.autoOrganize, onClick = {
            sheet.open {
                KSheetTitle("整理频率")
                listOf(1, 3, 5, 10, 20).forEach { n -> KOption("每 $n 轮", x.organizeEvery == n) { x.organizeEvery = n; saveX(); sheet.close() } }
            }
        })
    }
    KSection("本助手可见的记忆")
    KCard {
        if (!p.noteOn) Txt("该助手未启用长期记忆", K.sub, 13.5.sp, Modifier.fillMaxWidth().padding(26.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
        else if (p.items.isEmpty()) Txt("还没有记忆", K.sub, 13.5.sp, Modifier.fillMaxWidth().padding(26.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
        else p.items.take(6).forEachIndexed { i, m ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                KTag(kindZh(m.kind))
                Spacer(Modifier.width(10.dp))
                Txt(m.text.take(60), K.ink, 14.sp, Modifier.weight(1f), maxLines = 2)
            }
            if (i < minOf(5, p.items.lastIndex)) Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(1.dp).background(K.line))
        }
        Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KButton("全部 ${p.items.size} 件", Modifier.weight(1f)) { nav.screen = "memories" }
        }
    }
}

// ───────── 本地工具 ─────────

@Composable
private fun ToolsPage(x: AsstExt) {
    KCard {
        LocalTools.GROUPS.forEachIndexed { i, g ->
            KRow(g.title, KI.group(g.icon), g.desc, divider = i > 0, tag = if (g.isNew) "新" else null) {
                KSwitch(LocalTools.on(x, g.key)) { on -> if (on) x.toolsOff.remove(g.key) else if (g.key !in x.toolsOff) x.toolsOff += g.key; saveX() }
            }
        }
    }
}

// ───────── 技能 ─────────

@Composable
private fun SkillsTabPage(p: Persona, x: AsstExt, nav: MineNav) {
    KCard { KRow("使用全部技能", sub = "此助手可以使用所有已启用的技能。") { KSwitch(x.allSkills) { x.allSkills = it; saveX() } } }
    if (!x.allSkills) {
        val list = Skills.of(p.id).filter { it.on }
        KCard {
            if (list.isEmpty()) Txt("还没有技能", K.sub, 13.5.sp, Modifier.fillMaxWidth().padding(26.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
            list.forEachIndexed { i, s ->
                KRow(s.title, KI.sparkle, s.shown.take(60), divider = i > 0) {
                    KSwitch(s.id in x.skills) { on -> if (on) x.skills += s.id else x.skills.remove(s.id); saveX() }
                }
            }
        }
    }
    KCard { Row(Modifier.padding(14.dp)) { KButton("管理技能", Modifier.weight(1f)) { nav.screen = "skills" } } }
}

// ───────── MCP ─────────

@Composable
private fun McpTabPage(x: AsstExt, nav: MineNav) {
    KSection("MCP服务器 · 为该助手启用的服务")
    KCard {
        val list = Mcp.servers.filter { it.enabled }
        if (list.isEmpty()) Txt("暂无已启动的 MCP 服务器", K.sub, 13.5.sp, Modifier.fillMaxWidth().padding(26.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
        list.forEachIndexed { i, s ->
            val on = x.mcpIds?.contains(s.id) ?: true
            KRow(s.name, KI.server, "工具: ${s.onTools.size}/${s.tools.size}", divider = i > 0) {
                KSwitch(on) { v ->
                    val cur = (x.mcpIds ?: list.map { it.id }).toMutableList()
                    if (v) { if (s.id !in cur) cur += s.id } else cur.remove(s.id)
                    x.mcpIds = if (cur.containsAll(list.map { it.id })) null else cur
                    saveX()
                }
            }
        }
    }
    KCard { Row(Modifier.padding(14.dp)) { KButton("管理 MCP 服务", Modifier.weight(1f)) { nav.screen = "mcp" } } }
}

// ───────── 快捷短语 ─────────

@Composable
private fun PhrasesPage(x: AsstExt, sheet: KSheetState) {
    KCard {
        if (x.phrases.isEmpty()) Txt("管理该助手的快捷短语。点击下方按钮添加短语。", K.sub, 13.5.sp, Modifier.fillMaxWidth().padding(26.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
        x.phrases.forEachIndexed { i, ph ->
            KRow(ph.title, sub = ph.content.take(80), divider = i > 0, onClick = { sheet.open { PhraseSheet(x, sheet, i) } }) {
                Pressable({ x.phrases.removeAt(i); saveX() }, label = "删除") { pr -> SvgIcon(KI.trash, if (pr) K.danger else K.sub, 20.dp, stroke = 1.8f) }
            }
        }
        Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 16.dp)) { KButton("添加快捷短语", Modifier.weight(1f), primary = true) { sheet.open { PhraseSheet(x, sheet, -1) } } }
    }
}

@Composable
private fun PhraseSheet(x: AsstExt, sheet: KSheetState, index: Int) {
    var t by remember { mutableStateOf(if (index >= 0) x.phrases[index].title else "") }
    var c by remember { mutableStateOf(if (index >= 0) x.phrases[index].content else "") }
    KSheetTitle(if (index >= 0) "编辑快捷短语" else "添加快捷短语")
    KLabel("标题"); KField(t, { t = it })
    KLabel("内容"); KField(c, { c = it }, lines = 4)
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        KButton("取消", Modifier.weight(1f)) { sheet.close() }
        KButton("保存", Modifier.weight(1f), primary = true) {
            if (t.isNotBlank() && c.isNotBlank()) { val v = PhraseItem(t.trim(), c); if (index >= 0) x.phrases[index] = v else x.phrases += v; saveX() }
            sheet.close()
        }
    }
}

// ───────── 自定义请求 ─────────

@Composable
private fun CustomRequestPage(x: AsstExt, sheet: KSheetState) {
    for (headers in listOf(true, false)) {
        val list = if (headers) x.headers else x.bodies
        KSection(if (headers) "自定义 Header" else "自定义 Body")
        KCard {
            if (list.isEmpty()) Txt(if (headers) "未添加 Header" else "未添加 Body 项", K.sub, 13.5.sp, Modifier.fillMaxWidth().padding(26.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
            list.forEachIndexed { i, kv ->
                KRow(kv.key, sub = kv.value.take(120), divider = i > 0) {
                    Pressable({ list.removeAt(i); saveX() }, label = "删除") { pr -> SvgIcon(KI.trash, if (pr) K.danger else K.sub, 20.dp, stroke = 1.8f) }
                }
            }
            Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 16.dp)) {
                KButton(if (headers) "添加 Header" else "添加 Body", Modifier.weight(1f)) {
                    sheet.open {
                        var k by remember { mutableStateOf("") }
                        var v by remember { mutableStateOf("") }
                        KSheetTitle(if (headers) "添加 Header" else "添加 Body")
                        KLabel(if (headers) "Header 名称" else "Body Key"); KField(k, { k = it }, mono = true)
                        KLabel(if (headers) "Header 值" else "Body 值 (JSON)"); KField(v, { v = it }, lines = if (headers) 1 else 4, mono = true)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            KButton("取消", Modifier.weight(1f)) { sheet.close() }
                            KButton("保存", Modifier.weight(1f), primary = true) { if (k.isNotBlank()) { list += KvItem(k.trim(), v); saveX() }; sheet.close() }
                        }
                    }
                }
            }
        }
    }
}

// ───────── 正则替换 ─────────

@Composable
private fun RegexPage(x: AsstExt, sheet: KSheetState) {
    KSection("为用户/助手消息配置正则规则，可修改或仅调整显示效果。")
    KCard {
        if (x.regex.isEmpty()) Txt("添加正则规则", K.sub, 13.5.sp, Modifier.fillMaxWidth().padding(26.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
        x.regex.forEachIndexed { i, r ->
            val scope = listOfNotNull("用户".takeIf { r.user }, "助手".takeIf { r.assistant }).joinToString("、")
            val mode = when { r.visualOnly -> " · 仅视觉"; r.replaceOnly -> " · 仅替换"; else -> "" }
            KRow(r.name.ifBlank { "未命名规则" }, sub = "/${r.pattern}/ → ${r.replacement.ifEmpty { "∅" }} · $scope$mode", divider = i > 0, onClick = { sheet.open { RegexSheet(x, sheet, i) } }) {
                KSwitch(r.enabled) { on -> x.regex[i] = RegexRule(r.id, r.name, r.pattern, r.replacement, r.user, r.assistant, r.visualOnly, r.replaceOnly, on); saveX() }
            }
        }
        Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 16.dp)) { KButton("添加正则规则", Modifier.weight(1f), primary = true) { sheet.open { RegexSheet(x, sheet, -1) } } }
    }
}

@Composable
private fun RegexSheet(x: AsstExt, sheet: KSheetState, index: Int) {
    val r0 = x.regex.getOrNull(index)
    var name by remember { mutableStateOf(r0?.name.orEmpty()) }
    var pat by remember { mutableStateOf(r0?.pattern.orEmpty()) }
    var rep by remember { mutableStateOf(r0?.replacement.orEmpty()) }
    var user by remember { mutableStateOf(r0?.user ?: true) }
    var asst by remember { mutableStateOf(r0?.assistant ?: true) }
    var visual by remember { mutableStateOf(r0?.visualOnly ?: false) }
    var replace by remember { mutableStateOf(r0?.replaceOnly ?: false) }
    var err by remember { mutableStateOf("") }
    KSheetTitle(if (index >= 0) "编辑正则规则" else "添加正则规则")
    KLabel("规则名称"); KField(name, { name = it })
    KLabel("正则表达式"); KField(pat, { pat = it }, mono = true)
    KLabel("替换字符串"); KField(rep, { rep = it }, mono = true)
    KLabel("影响范围")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KRowCheck("用户", user, Modifier.weight(1f)) { user = !user }
        KRowCheck("助手", asst, Modifier.weight(1f)) { asst = !asst }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KRowCheck("仅视觉", visual, Modifier.weight(1f)) { visual = !visual; if (visual) replace = false }
        KRowCheck("仅替换", replace, Modifier.weight(1f)) { replace = !replace; if (replace) visual = false }
    }
    if (err.isNotEmpty()) Txt(err, K.danger, 12.5.sp, Modifier.padding(top = 8.dp))
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (index >= 0) KButton("删除", Modifier.weight(1f), color = K.danger) { x.regex.removeAt(index); saveX(); sheet.close() }
        else KButton("取消", Modifier.weight(1f)) { sheet.close() }
        KButton(if (index >= 0) "保存" else "添加", Modifier.weight(1f), primary = true) {
            if (name.isBlank() || pat.isBlank() || (!user && !asst)) {
                err = "请填写名称、正则表达式，并至少选择一个范围。"
            } else if (runCatching { Regex(pat) }.isFailure) {
                err = "正则表达式无效"
            } else {
                val v = RegexRule(r0?.id ?: WbEntry.newId(), name.trim(), pat, rep, user, asst, visual, replace, r0?.enabled ?: true)
                if (index >= 0) x.regex[index] = v else x.regex.add(v)
                saveX(); sheet.close()
            }
        }
    }
}

@Composable
private fun KRowCheck(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Pressable(onClick, modifier, label = label) { _ ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.well).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            KCheck(on); Spacer(Modifier.width(10.dp)); Txt(label, K.ink, 14.sp)
        }
    }
}
