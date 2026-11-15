package com.fuguang.lueying

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/*
 * 项目页（第 468 版，原型 https://claude.ai/artifact/Hppwum2KbaCx5JxB3gk2Qu）：
 * 列表 → 项目（上下文 / 自定义指令两张卡 + 这个项目的对话 + 新建会话）→ 上下文（资料网格 + 添加内容）/ 新建文档。
 * 宽度 ≥ 780dp：上下文在右边并排一栏（360dp，左栏至少留 420）；不够宽就推一页。动效照 dongxiaosheji：
 * 进 320ms decel、出 240ms accel，新页从右边来；成组依次出场（40ms）。
 */

internal object ProjIcons {
    val proj = Ic("M5 8h14M6 5h12M5.5 11h13l-1 8.5h-11z")
    val more = Ic("M12 5.5v.01M12 12v.01M12 18.5v.01")
    val upload = Ic("M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h4M14 3l5 5h-5zM17 21v-7M14 17l3-3 3 3")
    val camera = Ic("M4 8h3l2-3h6l2 3h3v11H4z", circles = listOf(floatArrayOf(12f, 13f, 3.5f)))
    val image = Ic("M7 4h10a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3V7a3 3 0 0 1 3-3ZM5 18l5-5 4 4 2-2 3 3", circles = listOf(floatArrayOf(9f, 9.5f, 1.6f)))
}

private val Card: Color get() = Skin.well(.16f)
private val CardPress: Color get() = veil(.1f)

/** 页面：list / proj / ctx（窄屏的上下文）/ doc（新建文档）。 */
private fun depth(s: String) = when (s) { "list" -> 0; "proj" -> 1; else -> 2 }

@Composable
fun ProjectsLayer(history: ConvStore) {
    val open = Projects.open
    val k by animateFloatAsState(if (open) 1f else 0f, tween(if (open) 320 else 240, easing = if (open) Curves.decel else Curves.accel), label = "proj")
    var sub by remember { mutableStateOf("") } // "" / "ctx" / "doc"
    var ctxSide by remember { mutableStateOf(true) }
    var adding by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<String?>(null) } // null 不显示；"" 新建；项目 id 重命名
    var insOpen by remember { mutableStateOf(false) }
    var projMenu by remember { mutableStateOf(false) }
    var fileMenu by remember { mutableStateOf<ProjFile?>(null) }
    LaunchedEffect(open) { if (!open) { sub = ""; Projects.showing = null } }
    val id = Projects.showing
    val page = when { id == null -> "list"; sub.isNotEmpty() -> sub; else -> "proj" }
    if (open) BackHandler { if (sub.isNotEmpty()) sub = "" else if (id != null) Projects.showing = null else Projects.open = false }
    if (k <= 0f) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 添加内容：系统相册 / 相机 / 选文件，跟聊天的加号一样不要权限
    var shot by remember { mutableStateOf<java.io.File?>(null) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { us ->
        val pid = Projects.showing ?: return@rememberLauncherForActivityResult
        scope.launch { us.forEach { Projects.add(ctx, pid, it, image = true) } }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = shot; shot = null
        val pid = Projects.showing
        if (f != null) scope.launch { if (ok && pid != null) Attach.fromCamera(f)?.let { Projects.addRef(pid, it) } else runCatching { f.delete() } }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { us ->
        val pid = Projects.showing ?: return@rememberLauncherForActivityResult
        scope.launch { us.take(9).forEach { Projects.add(ctx, pid, it, image = false) } }
    }

    Box(
        Modifier.fillMaxSize().graphicsLayer { translationX = (1f - k) * size.width * .25f; alpha = k }
            .background(Skin.sheet).pointerInput(Unit) { detectTapGestures { } },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val two = maxWidth >= 780.dp
            // 变宽时推开的上下文页换回右栏
            LaunchedEffect(two) { if (two && sub == "ctx") { sub = ""; ctxSide = true } }
            AnimatedContent(
                page,
                transitionSpec = {
                    val fwd = depth(targetState) >= depth(initialState)
                    (slideInHorizontally(tween(320, easing = Curves.standard)) { w -> if (fwd) w / 5 else -w / 5 } + fadeIn(tween(240)))
                        .togetherWith(slideOutHorizontally(tween(240, easing = Curves.accel)) { w -> if (fwd) -w / 8 else w / 5 } + fadeOut(tween(180)))
                },
                label = "projPage",
            ) { pg ->
                val p = Projects.get(id)
                when {
                    pg == "list" || p == null -> ProjList(history, onClose = { Projects.open = false }, onNew = { naming = "" })
                    pg == "doc" -> DocPage(onBack = { sub = "" }) { t, b -> Projects.addDoc(p.id, t, b); sub = "" }
                    pg == "ctx" -> Column(Modifier.fillMaxSize()) {
                        Head("上下文", onBack = { sub = "" })
                        CtxPane(p, Modifier.weight(1f), onAdd = { adding = true }, onFile = { fileMenu = it })
                    }
                    else -> Row(Modifier.fillMaxSize()) {
                        ProjDetail(
                            p, history, Modifier.weight(1f),
                            onBack = { Projects.showing = null },
                            onMore = { projMenu = true },
                            onCtx = { if (two) ctxSide = !ctxSide else sub = "ctx" },
                            onIns = { insOpen = true },
                        )
                        // 右栏：宽度从 0 长到 360，内容按 360 排好，只露出来（不跟着挤）
                        val w by animateFloatAsState(if (two && ctxSide) 1f else 0f, tween(320, easing = Curves.standard), label = "ctxW")
                        if (w > 0f) Row(Modifier.fillMaxHeight().width(361.dp * w).clipToBounds()) {
                            Box(Modifier.fillMaxHeight().width(1.dp).background(veil(.07f)))
                            Box(Modifier.fillMaxHeight().wrapContentWidth(Alignment.Start, unbounded = true).width(360.dp).graphicsLayer { alpha = w }) {
                                Column(Modifier.fillMaxSize()) {
                                    Txt("上下文", Page.ink, 15.5.sp, Modifier.padding(start = 22.dp, top = 22.dp, bottom = 12.dp), weight = FontWeight.Medium)
                                    CtxPane(p, Modifier.weight(1f), onAdd = { adding = true }, onFile = { fileMenu = it })
                                }
                            }
                        }
                    }
                }
            }
        }

        // 新建项目 / 重命名
        val namingId = naming
        Sheet(namingId != null, onDismiss = { naming = null }) {
            var v by remember(namingId) { mutableStateOf(Projects.get(namingId)?.name.orEmpty()) }
            Field(v, if (namingId.isNullOrEmpty()) "项目名" else "", single = true) { v = it.take(40) }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetButton("取消", false, Modifier.weight(1f)) { naming = null }
                SheetButton(if (namingId.isNullOrEmpty()) "创建" else "保存", true, Modifier.weight(1f), enabled = v.isNotBlank()) {
                    if (namingId.isNullOrEmpty()) { val nid = Projects.create(v); naming = null; Projects.showing = nid; ctxSide = true }
                    else { Projects.rename(namingId, v); naming = null }
                }
            }
        }
        // 自定义指令
        Sheet(insOpen, onDismiss = { insOpen = false }) {
            var v by remember(insOpen, id) { mutableStateOf(Projects.get(id)?.ins.orEmpty()) }
            Txt("自定义指令", Page.ink, 16.sp, Modifier.padding(bottom = 12.dp))
            Field(v, "在这个项目里，她该怎么做…", single = false) { v = it }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetButton("取消", false, Modifier.weight(1f)) { insOpen = false }
                SheetButton("保存", true, Modifier.weight(1f)) { id?.let { Projects.setIns(it, v) }; insOpen = false }
            }
        }
        // 添加内容
        Sheet(adding, onDismiss = { adding = false }) {
            AddOpt(ProjIcons.upload, "从设备上传") { adding = false; runCatching { files.launch(arrayOf("*/*")) } }
            AddOpt(ProjIcons.camera, "拍照") { adding = false; runCatching { val (f, u) = Attach.cameraTarget(ctx); shot = f; camera.launch(u) } }
            AddOpt(ProjIcons.image, "选择图片") { adding = false; runCatching { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
            AddOpt(SideIcons.pen, "新建文档") { adding = false; sub = "doc" }
        }
        // 项目 ⋮
        Sheet(projMenu, onDismiss = { projMenu = false }) {
            var sure by remember(projMenu) { mutableStateOf(false) }
            SheetButton("重命名", false, Modifier.fillMaxWidth()) { projMenu = false; naming = id }
            Spacer(Modifier.height(8.dp))
            // 删除点两下：第一下按钮变红
            SheetButton(if (sure) "删除这个项目和资料" else "删除", sure, Modifier.fillMaxWidth(), red = true) {
                if (!sure) sure = true else { id?.let { Projects.delete(it) }; projMenu = false }
            }
        }
        // 资料 ⋮：改名、删除
        val fm = fileMenu
        Sheet(fm != null, onDismiss = { fileMenu = null }) {
            var v by remember(fm) { mutableStateOf(fm?.name.orEmpty()) }
            Field(v, "", single = true) { v = it.take(40) }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetButton("删除", true, Modifier.weight(1f), red = true) { if (fm != null && id != null) Projects.deleteFile(id, fm.id); fileMenu = null }
                SheetButton("保存", true, Modifier.weight(1f), enabled = v.isNotBlank()) { if (fm != null && id != null) Projects.renameFile(id, fm.id, v); fileMenu = null }
            }
        }
    }
}

@Composable
private fun Head(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = { Spacer(Modifier.width(44.dp)) }) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        PressSurface(onBack, Modifier.size(44.dp), shape = CircleShape, fill = Color.Transparent, pressScale = .88f, label = "返回") {
            SvgIcon(SideIcons.back, Page.ink, 20.dp, stroke = 1.7f, modifier = Modifier.align(Alignment.Center))
        }
        Txt(title, Page.ink, 17.sp, Modifier.weight(1f), align = TextAlign.Center, maxLines = 1)
        trailing()
    }
}

/** 黑底胶囊（照侧栏「新对话」）。 */
@Composable
private fun Pill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Pressable(onClick, modifier, label = text) { p ->
        Row(
            Modifier.graphicsLayer { val s = if (p) .97f else 1f; scaleX = s; scaleY = s }
                .background(Page.ink.copy(alpha = if (p) .82f else 1f), RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            SvgIcon(SideIcons.plus, Skin.onInk, 15.dp, stroke = 2f)
            Spacer(Modifier.width(8.dp))
            Txt(text, Skin.onInk, 14.5.sp, weight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun Field(v: String, hint: String, single: Boolean, onChange: (String) -> Unit) {
    BasicTextField(
        v, onChange, singleLine = single,
        textStyle = TextStyle(color = Page.ink, fontSize = if (single) 17.sp else 14.5.sp, lineHeight = if (single) 24.sp else 23.sp, fontFamily = Sans),
        cursorBrush = SolidColor(Skin.accent),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().heightIn(min = if (single) 0.dp else 180.dp).background(Card, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (v.isEmpty() && hint.isNotEmpty()) Txt(hint, Page.muted.copy(alpha = .6f), if (single) 17.sp else 14.5.sp)
                inner()
            }
        },
    )
}

@Composable
private fun AddOpt(icon: Ic, text: String, onClick: () -> Unit) {
    PressSurface(onClick, Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(16.dp), fill = Card, pressedFill = CardPress, pressScale = .98f, label = text) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            SvgIcon(icon, Page.ink, 20.dp, stroke = 1.5f)
            Spacer(Modifier.width(14.dp))
            Txt(text, Page.ink, 15.sp)
        }
    }
}

/** 依次出场：第 i 个晚 40ms（最多 200ms），从下面 8dp 浮上来。 */
@Composable
private fun Modifier.rise(i: Int, key: Any?): Modifier {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { kotlinx.coroutines.delay(40L * i.coerceAtMost(5)); a.animateTo(1f, tween(320, easing = Curves.decel)) }
    return this.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 8.dp.toPx() }
}

private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when { m < 1 -> "刚刚"; m < 60 -> "$m 分钟前"; m < 60 * 24 -> "${m / 60} 小时前"; m < 60 * 24 * 30 -> "${m / 60 / 24} 天前"; else -> java.text.SimpleDateFormat("M 月 d 日", java.util.Locale.CHINA).format(java.util.Date(t)) }
}

/* ---------- 列表 ---------- */

@Composable
private fun ProjList(history: ConvStore, onClose: () -> Unit, onNew: () -> Unit) {
    var q by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Head("项目", onBack = onClose)
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = 18.dp)) {
                if (Projects.all.isNotEmpty()) BasicTextField(
                    q, { q = it }, singleLine = true,
                    textStyle = TextStyle(color = Page.ink, fontSize = 15.sp, fontFamily = Sans),
                    cursorBrush = SolidColor(Skin.accent),
                    decorationBox = { inner ->
                        Row(Modifier.fillMaxWidth().height(46.dp).background(Card, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            SvgIcon(SideIcons.search, Page.muted, 18.dp, stroke = 1.6f)
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.weight(1f)) { if (q.isEmpty()) Txt("搜索", Page.muted.copy(alpha = .6f), 15.sp); inner() }
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
            val list = Projects.all.filter { q.isBlank() || it.name.contains(q.trim(), true) }
            if (Projects.all.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                SvgIcon(ProjIcons.proj, Page.muted.copy(alpha = .45f), 64.dp, stroke = 1.1f)
            } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 110.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                list.forEachIndexed { i, p ->
                    key(p.id) {
                        val n = Projects.convs(p.id, history).size
                        PressSurface({ Projects.showing = p.id }, Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 10.dp).rise(i, p.id), shape = RoundedCornerShape(16.dp), fill = Color.Transparent, pressedFill = CardPress, pressScale = .98f, label = p.name) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(40.dp).background(Skin.accent.copy(alpha = .13f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { SvgIcon(ProjIcons.proj, Skin.accent, 20.dp, stroke = 1.5f) }
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Txt(p.name, Page.ink, 16.sp, maxLines = 1)
                                    Spacer(Modifier.height(3.dp))
                                    Txt((if (n > 0) "$n 个对话 · " else "") + ago(p.edited), Page.muted, 12.5.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
        Pill("新建项目", Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp), onClick = onNew)
    }
}

/* ---------- 项目 ---------- */

@Composable
private fun ProjDetail(p: Project, history: ConvStore, modifier: Modifier, onBack: () -> Unit, onMore: () -> Unit, onCtx: () -> Unit, onIns: () -> Unit) {
    Box(modifier.fillMaxHeight()) {
        Column(Modifier.fillMaxSize()) {
            Head("", onBack = onBack) {
                PressSurface(onMore, Modifier.size(44.dp), shape = CircleShape, fill = Color.Transparent, pressScale = .88f, label = "项目操作") {
                    SvgIcon(ProjIcons.more, Page.ink, 20.dp, stroke = 2.4f, modifier = Modifier.align(Alignment.Center))
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 110.dp)) {
                Txt(p.name, Page.ink, 30.sp, Modifier.padding(start = 4.dp, top = 2.dp, bottom = 18.dp), spacing = 1.5.sp, family = Serif, maxLines = 2)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PressSurface(onCtx, Modifier.weight(1f).height(132.dp).rise(0, p.id), shape = RoundedCornerShape(20.dp), fill = Card, pressedFill = CardPress, pressScale = .97f, label = "上下文") {
                        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
                            Txt("上下文", Page.ink, 14.5.sp, weight = FontWeight.Medium)
                            Spacer(Modifier.weight(1f))
                            if (p.files.isEmpty()) Txt("＋", Page.muted.copy(alpha = .6f), 18.sp)
                            else Row(verticalAlignment = Alignment.CenterVertically) {
                                p.files.take(4).forEachIndexed { i, f ->
                                    Box(Modifier.graphicsLayer { translationX = -8.dp.toPx() * i }.size(30.dp, 36.dp).clip(RoundedCornerShape(6.dp)).background(Skin.pop).border(1.dp, veil(.08f), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                                        if (f.image) Thumb(f.ref, Modifier.fillMaxSize(), px = 96) else Txt(f.tag, Page.muted, 8.sp, maxLines = 1)
                                    }
                                }
                                Txt("${p.files.size}", Page.muted, 12.5.sp, Modifier.graphicsLayer { translationX = -8.dp.toPx() * (p.files.take(4).size - 1) }.padding(start = 10.dp))
                            }
                        }
                    }
                    PressSurface(onIns, Modifier.weight(1f).height(132.dp).rise(1, p.id), shape = RoundedCornerShape(20.dp), fill = Card, pressedFill = CardPress, pressScale = .97f, label = "自定义指令") {
                        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
                            Txt("自定义指令", Page.ink, 14.5.sp, weight = FontWeight.Medium)
                            Spacer(Modifier.height(10.dp))
                            if (p.ins.isBlank()) { Spacer(Modifier.weight(1f)); Txt("＋", Page.muted.copy(alpha = .6f), 18.sp) }
                            else Txt(p.ins, Page.muted, 13.sp, lineHeight = 20.sp, maxLines = 3)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Projects.convs(p.id, history).forEachIndexed { i, c ->
                    key(c.id) {
                        PressSurface({ Chats.open(c); Projects.open = false; Projects.chatTick++ }, Modifier.fillMaxWidth().rise(2 + i, c.id), shape = RoundedCornerShape(14.dp), fill = Color.Transparent, pressedFill = CardPress, pressScale = .98f, label = c.title) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                SvgIcon(SideIcons.chat, Page.muted.copy(alpha = .7f), 18.dp, stroke = 1.4f)
                                Spacer(Modifier.width(12.dp))
                                Txt(c.title, Page.ink, 15.sp, Modifier.weight(1f), maxLines = 1)
                                Spacer(Modifier.width(10.dp))
                                Txt(ago(c.updated), Page.muted.copy(alpha = .75f), 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        Pill("新建会话", Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp)) {
            Chats.fresh(); Chats.current?.project = p.id
            Projects.open = false; Projects.chatTick++
        }
    }
}

/* ---------- 上下文 ---------- */

@Composable
private fun CtxPane(p: Project, modifier: Modifier, onAdd: () -> Unit, onFile: (ProjFile) -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            val frac = (Projects.used(p).toFloat() / Projects.LIMIT).coerceIn(0f, 1f)
            val shown by animateFloatAsState(frac, tween(460, easing = Curves.standard), label = "meter")
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(veil(.08f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(shown.coerceAtLeast(.001f)).background(if (frac >= 1f) Skin.danger else Skin.accent))
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp)) {
                Txt("${p.files.size} 个文件", Page.muted, 12.sp, Modifier.weight(1f))
                Txt("${(frac * 100).toInt()}%", Page.muted, 12.sp)
            }
            p.files.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { f -> key(f.id) { FileCard(f, Modifier.weight(1f)) { onFile(f) } } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Pill("添加内容", Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), onClick = onAdd)
    }
}

@Composable
private fun FileCard(f: ProjFile, modifier: Modifier, onMore: () -> Unit) {
    // 新加进来的：从 .94 放大、淡入
    val a = remember(f.id) { Animatable(0f) }
    LaunchedEffect(f.id) { a.animateTo(1f, tween(320, easing = Curves.decel)) }
    Box(
        modifier.aspectRatio(1f / 1.05f)
            .graphicsLayer { alpha = a.value; val s = .94f + .06f * a.value; scaleX = s; scaleY = s }
            .clip(RoundedCornerShape(16.dp)).background(Skin.pop).border(1.dp, veil(.08f), RoundedCornerShape(16.dp)),
    ) {
        if (f.image) {
            Thumb(f.ref, Modifier.fillMaxSize(), px = 480)
            Txt(f.name, Color.White, 12.5.sp, Modifier.align(Alignment.BottomStart).padding(12.dp), maxLines = 1, shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = .4f), blurRadius = 8f))
        } else Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
            Txt(f.tag, Page.muted, 10.5.sp, Modifier.border(1.dp, veil(.1f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp), maxLines = 1)
            Txt(f.text.take(200), Page.muted.copy(alpha = .7f), 9.5.sp, Modifier.weight(1f).padding(top = 8.dp, bottom = 6.dp), lineHeight = 14.sp)
            Txt(f.name, Page.ink, 12.5.sp, maxLines = 1)
        }
        PressSurface(onMore, Modifier.align(Alignment.TopEnd).padding(4.dp).size(32.dp), shape = CircleShape, fill = Color.Transparent, pressScale = .88f, label = "资料操作") {
            SvgIcon(ProjIcons.more, if (f.image) Color.White else Page.muted, 16.dp, stroke = 2.4f, modifier = Modifier.align(Alignment.Center))
        }
    }
}

/* ---------- 新建文档 ---------- */

@Composable
private fun DocPage(onBack: () -> Unit, onSave: (String, String) -> Unit) {
    var t by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().imePadding()) {
        Head("", onBack = onBack) {
            SheetButton("保存", true, Modifier.width(80.dp).padding(end = 8.dp), enabled = t.isNotBlank() || b.isNotBlank()) { onSave(t, b) }
        }
        Column(Modifier.weight(1f).widthIn(max = 720.dp).fillMaxWidth().align(Alignment.CenterHorizontally).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            BasicTextField(t, { t = it.take(40) }, singleLine = true, textStyle = TextStyle(color = Page.ink, fontSize = 22.sp, fontFamily = Serif), cursorBrush = SolidColor(Skin.accent), modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner -> Box { if (t.isEmpty()) Txt("标题", Page.muted.copy(alpha = .5f), 22.sp, family = Serif); inner() } })
            Spacer(Modifier.height(14.dp))
            BasicTextField(b, { b = it }, textStyle = TextStyle(color = Page.ink, fontSize = 15.sp, lineHeight = 26.sp, fontFamily = Sans), cursorBrush = SolidColor(Skin.accent), modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                decorationBox = { inner -> Box { if (b.isEmpty()) Txt("写点什么…", Page.muted.copy(alpha = .5f), 15.sp); inner() } })
        }
    }
}
