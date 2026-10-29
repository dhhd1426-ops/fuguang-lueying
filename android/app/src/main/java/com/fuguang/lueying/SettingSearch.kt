package com.fuguang.lueying

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 「我的」里的设置搜索（第 342 版，原型 Syj4hgPuGzmzsLzQxNVw4D 方案 1）。
 * 标题右边只有一个放大镜，没有底；点开往左铺满这一行，头像和「我的」淡出，字直接写在页面上（不画框、不画线），右边「取消」。
 * 下面换成结果：每条写着在哪；点了推进那一页，滚到那一项，亮一下。
 * 设置都登记在 [SettingIndex] 里；那一项在页面上用 Modifier.spot(key) 标出来。以后加新设置，两处都补上。
 */
internal class SettingSpot(val key: String, val title: String, val where: String, val words: String, val screen: String, val tab: Int = -1)

internal object SettingIndex {
    val all = listOf(
        SettingSpot("name", "名字和头像", "我的 › 助理 › 基础", "名字 头像 照片 昵称 称呼 用户名", "persona", 0),
        SettingSpot("model", "模型与供应商", "我的 › 助理 › 基础", "模型 供应商 api key 接口 账号 deepseek claude gemini", "persona", 0),
        SettingSpot("temp", "温度", "我的 › 助理 › 基础 › 回答的手感", "温度 随机 创意 temperature 手感", "persona", 0),
        SettingSpot("topp", "Top P", "我的 › 助理 › 基础 › 回答的手感", "top p 采样 手感", "persona", 0),
        SettingSpot("limit", "上下文消息限制", "我的 › 助理 › 基础 › 回答的手感", "上下文 消息 限制 条数 记几条", "persona", 0),
        SettingSpot("sys", "系统提示词", "我的 › 助理", "系统提示词 提示词 人设 性格 设定 她自己", "persona", 1),
        SettingSpot("entries", "设定条目", "我的 › 助理 › 系统提示词", "设定条目 条目 世界书 关键词", "persona", 1),
        SettingSpot("cross", "跨聊天回忆", "我的 › 助理 › 记忆", "跨聊天回忆 回忆 以前 历史 翻聊天", "persona", 2),
        SettingSpot("memory", "记忆", "我的 › 助理 › 记忆", "记忆 记住 忘掉 删除记忆 她记得", "persona", 2),
        SettingSpot("web", "联网搜索", "我的 › 功能", "联网 搜索 网页 上网 exa", "features"),
        SettingSpot("skills", "技能", "我的 › 功能", "技能 skill 本事 工具", "features"),
        SettingSpot("yu", "羽", "我的 › 连接器", "羽 笔记 读取 连接器", "mine"),
        SettingSpot("tg", "Telegram", "我的 › 连接器", "telegram 电报 频道 下载 登录", "mine"),
        SettingSpot("mcp", "MCP 服务", "我的 › 连接器", "mcp 服务 高德 地图 车票 连接", "mcp"),
        SettingSpot("skin", "主题", "我的 › 主题", "主题 颜色 皮肤 深色 暗色 夜间 暗黑 渐变 白色 配色", "theme"),
        SettingSpot("rail", "换助理的方式", "我的 › 主题", "换助理 侧栏 切换 连体卡片 竖排头像", "theme"),
        SettingSpot("card", "卡片样式", "我的 › 主题", "卡片样式 整页 悬浮 卡片", "theme"),
        SettingSpot("icon", "图标", "我的 › 主题", "图标 桌面 app 图标 换图标", "theme"),
        SettingSpot("backup", "备份", "我的 › 其他", "备份 导出 恢复 还原 迁移 换手机", "mine"),
        SettingSpot("about", "关于 Vignette", "我的 › 其他", "关于 版本 背景 作品 版权", "about"),
    )

    /** 名字开头对上的最前，名字里有的其次，别的叫法对上的最后。 */
    fun find(q: String): List<SettingSpot> {
        val s = q.trim().lowercase()
        if (s.isEmpty()) return emptyList()
        return all.mapNotNull { x ->
            val t = x.title.lowercase()
            val k = when { t.startsWith(s) -> 3; s in t -> 2; s in x.words -> 1; else -> 0 }
            if (k > 0) x to k else null
        }.sortedByDescending { it.second }.map { it.first }
    }

    val common = listOf("温度", "模型", "主题", "备份", "跨聊天回忆", "联网搜索")
}

/** 刚从搜索点过来的那一项：2.5 秒内出现在页面上就滚过去亮一下。 */
internal object SpotFlash {
    var key by mutableStateOf("")
    var at by mutableLongStateOf(0L)
    fun set(k: String) { key = k; at = System.currentTimeMillis() }
}

/** 把一项设置标出来：搜索点到它时，滚到能看见的地方，盖一层主题色亮一下（进 200ms，停 500ms，退 900ms）。 */
internal fun Modifier.spot(key: String): Modifier = composed {
    val req = remember { BringIntoViewRequester() }
    val a = remember { Animatable(0f) }
    LaunchedEffect(SpotFlash.key) {
        if (SpotFlash.key == key && System.currentTimeMillis() - SpotFlash.at < 2500) {
            delay(420)
            runCatching { req.bringIntoView() }
            a.animateTo(1f, tween(200, easing = Curves.decel))
            SpotFlash.key = ""
            delay(500)
            a.animateTo(0f, tween(900, easing = Curves.standard))
        }
    }
    this.bringIntoViewRequester(req).drawWithContent {
        drawContent()
        if (a.value > 0f) drawRoundRect(Skin.accent.copy(alpha = .2f * a.value), cornerRadius = CornerRadius(16.dp.toPx()))
    }
}

private val lens = Ic("M16 16l4 4", circles = listOf(floatArrayOf(11f, 11f, 6.5f)))

/**
 * 「我的」顶上那一行：平时是头像 + 我的 + 右边一个放大镜；点开放大镜往左铺满这一行变成输入（380ms 减速），取消原路收回（260ms 加速）。
 * [head] 是原来那一行的内容（放大镜让出 44dp）。
 */
@Composable
internal fun SearchHead(open: Boolean, query: String, onQuery: (String) -> Unit, onOpen: () -> Unit, onCancel: () -> Unit, head: @Composable () -> Unit) {
    val s by animateFloatAsState(if (open) 1f else 0f, if (open) tween(380, easing = Curves.decel) else tween(260, easing = Curves.accel), label = "sh")
    val focus = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    LaunchedEffect(open) { if (open) { delay(200); runCatching { focus.requestFocus() } } else fm.clearFocus() }
    BackHandler(open) { onCancel() }
    BoxWithConstraints(Modifier.fillMaxWidth().height(52.dp)) {
        Box(Modifier.fillMaxWidth().padding(end = 44.dp).graphicsLayer { alpha = 1f - s; val k = 1f - .04f * s; scaleX = k; scaleY = k }, contentAlignment = Alignment.CenterStart) { head() }
        Row(
            Modifier.align(Alignment.CenterEnd).width(lerp(44.dp, maxWidth, s)).height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pressable({ if (!open) onOpen() else runCatching { focus.requestFocus() } }, label = "搜索设置") { pr ->
                val p by animateFloatAsState(if (pr) 1f else 0f, if (pr) tween(60) else tween(220), label = "lensP")
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(40.dp).graphicsLayer { alpha = p }.background(veil(.1f), CircleShape))
                    SvgIcon(lens, Page.ink.copy(alpha = .86f), 20.dp, stroke = 1.7f)
                }
            }
            if (s > 0.01f) {
                Box(Modifier.weight(1f).graphicsLayer { alpha = ((s - .4f) / .6f).coerceIn(0f, 1f) }, contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Txt("搜索设置", Page.muted.copy(alpha = .7f), 15.sp, maxLines = 1)
                    BasicTextField(
                        query, { onQuery(it.replace("\n", "").take(30)) }, singleLine = true,
                        textStyle = TextStyle(fontFamily = Sans, color = Page.ink, fontSize = 15.sp),
                        cursorBrush = SolidColor(Skin.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                Pressable(onCancel, Modifier.graphicsLayer { alpha = ((s - .4f) / .6f).coerceIn(0f, 1f) }, label = "取消") { pr ->
                    Txt("取消", if (pr) Page.ink else Page.muted, 14.sp, Modifier.padding(horizontal = 12.dp, vertical = 10.dp), maxLines = 1)
                }
            }
        }
    }
}

/** 搜索结果：空着时给几个常找的；有字就一条条列出来，对上的字用主题色。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchResults(query: String, onPick: (String) -> Unit, onGo: (SettingSpot) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        if (query.isBlank()) {
            Txt("常找的", Page.muted, 12.sp, Modifier.padding(start = 8.dp, bottom = 10.dp))
            FlowRow(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingIndex.common.forEach { w ->
                    Pressable({ onPick(w) }, label = w) { pr ->
                        Txt(w, Page.ink, 13.sp, Modifier.background(veil(if (pr) .12f else .06f), RoundedCornerShape(50)).padding(horizontal = 13.dp, vertical = 7.dp), maxLines = 1)
                    }
                }
            }
            return@Column
        }
        val hits = SettingIndex.find(query)
        if (hits.isEmpty()) {
            Txt("没找到「${query.trim()}」。换个说法试试，比如「颜色」「Key」「导出」。", Page.muted, 13.sp, Modifier.padding(horizontal = 8.dp, vertical = 6.dp), lineHeight = 22.sp)
            return@Column
        }
        val q = query.trim().lowercase()
        hits.forEachIndexed { i, x ->
            val t = remember(x.key) { Animatable(0f) }
            LaunchedEffect(x.key) { delay(30L * i); t.animateTo(1f, tween(320, easing = Curves.decel)) }
            Pressable({ onGo(x) }, Modifier.fillMaxWidth().padding(bottom = 8.dp).graphicsLayer { alpha = t.value; translationY = (1f - t.value) * 6.dp.toPx() }, label = x.title) { pr ->
                Column(Modifier.fillMaxWidth().background(Skin.tint(if (pr) .55f else .42f), RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 13.dp)) {
                    val at = x.title.lowercase().indexOf(q)
                    val title = buildAnnotatedString {
                        if (at < 0) append(x.title) else {
                            append(x.title.substring(0, at))
                            withStyle(SpanStyle(color = Skin.accent)) { append(x.title.substring(at, at + q.length)) }
                            append(x.title.substring(at + q.length))
                        }
                    }
                    androidx.compose.foundation.text.BasicText(title, style = TextStyle(fontFamily = Sans, color = Page.ink, fontSize = 15.5.sp), maxLines = 1)
                    Spacer(Modifier.height(3.dp))
                    Txt(x.where, Page.muted, 12.sp, maxLines = 1)
                }
            }
        }
    }
}
