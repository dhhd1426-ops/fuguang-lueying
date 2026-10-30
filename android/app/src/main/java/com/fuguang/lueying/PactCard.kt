package com.fuguang.lueying

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 约定的两张卡（第 352 版，原型 2zfSHtSMKQBreEMyNQDznu 第 5 版）：
 * - 约定小卡：回复里「每天 08:00 · 早报 / 天气、日程 · 下次：明天早上 08:00」，取消了的写「取消了 · 早报」。
 * - 让她能按时找你：约定小卡下面，这台手机还有没允许的才出现。同一张卡自己认这台手机：
 *   允许定时提醒（安卓 12 起的「闹钟和提醒」）、后台耗电不受限制（系统弹窗）、让它自己在后台醒来（小米是自启动，三星是永不休眠，别的牌子没有这步）。
 *   前两步读系统状态，第三步读不到，点过就算。都好了整张卡不再出现。
 */

private val clockIc = Ic("M12 20a7 7 0 1 0 0-14 7 7 0 0 0 0 14ZM12 9.5V13l2.2 1.6M9 3h6")

/** 让手机别在后台把她关掉的那几步。 */
object Keep {
    /** 回到 App 时加一：卡片重新读一遍系统状态。 */
    var ver by androidx.compose.runtime.mutableIntStateOf(0)
    private const val VENDOR = "keep-vendor"

    class Step(val key: String, val title: String, val note: String, val go: (Context) -> Unit)

    private val brand get() = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
    private val mi get() = listOf("xiaomi", "redmi", "poco").any { it in brand }
    private val samsung get() = "samsung" in brand

    private fun open(ctx: Context, vararg intents: Intent) {
        for (i in intents) if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
    private fun details(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))

    /** 这台手机还差哪几步（已经允许的、手机上没有的都不在里面）。 */
    fun needs(ctx: Context): List<Step> {
        val out = ArrayList<Step>()
        if (Build.VERSION.SDK_INT >= 31 && ctx.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == false)
            out += Step("exact", "允许定时提醒", "系统「闹钟和提醒」") { c -> open(c, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + c.packageName)), details(c)) }
        if (ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == false)
            out += Step("battery", "后台耗电不受限制", "系统弹窗点「允许」") { c ->
                @android.annotation.SuppressLint("BatteryLife")
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.packageName))
                open(c, i, details(c))
            }
        val done = Prefs(ctx).string(VENDOR) == "1"
        if (!done && mi) out += Step("vendor", "允许它自己在后台醒来", "安全中心 · 自启动") { c ->
            Prefs(c).putString(VENDOR, "1")
            open(c, Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")), details(c))
        }
        if (!done && samsung) out += Step("vendor", "别让它进休眠", "电池 · 永不休眠的应用") { c ->
            Prefs(c).putString(VENDOR, "1")
            open(c, Intent("com.samsung.android.sm.ACTION_BATTERY"), details(c))
        }
        return out
    }
}

/** 回复里的约定卡；下面跟着「让她能按时找你」（还差的时候）。 */
@Composable
internal fun PactCard(ref: String) {
    val id = ref.removePrefix(Pacts.PREFIX).substringBefore('|')
    val name = ref.substringAfter('|', "约定")
    val p = Pacts.all.firstOrNull { it.id == id }
    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.widthIn(max = 320.dp).background(veil(.07f), RoundedCornerShape(14.dp)).padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SvgIcon(clockIc, if (p != null) Skin.accent else Page.muted, 20.dp, stroke = 1.6f)
            Spacer(Modifier.width(10.dp))
            Column {
                Txt(if (p != null) Pacts.rule(p) + " · " + p.title else "取消了 · $name", if (p != null) Page.ink else Page.muted, 14.sp, maxLines = 1)
                if (p != null) Txt(Pacts.nextWord(p), Page.muted, 11.5.sp, maxLines = 1)
            }
        }
        if (p != null) KeepCard()
    }
}

@Composable
private fun KeepCard() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val steps = remember(Keep.ver, tick) { Keep.needs(ctx) }
    if (steps.isEmpty()) return
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, tween(320, easing = Curves.decel)) }
    Column(
        Modifier.widthIn(max = 340.dp).fillMaxWidth()
            .graphicsLayer { alpha = rise.value; translationY = (1f - rise.value) * 8.dp.toPx() }
            .background(Skin.cardFill(.85f), RoundedCornerShape(18.dp)).border(1.dp, Page.line, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt("让她能按时找你", Page.ink, 14.sp)
        steps.forEach { s ->
            Pressable({ s.go(ctx); tick++ }, Modifier.fillMaxWidth(), label = s.title) { pr ->
                val k by animateFloatAsState(if (pr) .98f else 1f, tween(if (pr) 70 else 180), label = "keepK")
                Row(
                    Modifier.fillMaxWidth().graphicsLayer { scaleX = k; scaleY = k }
                        .background(veil(if (pr) .1f else .06f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(18.dp).border(1.5.dp, Page.muted, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Txt(s.title, Page.ink, 13.5.sp, Modifier.weight(1f), maxLines = 1)
                    Txt(s.note, Page.muted, 12.sp, maxLines = 1)
                }
            }
        }
        Txt("已经允许过的不会再问；不开也能用：没叫醒的话，你下次打开时她补一句。", Page.muted, 12.sp, lineHeight = 19.sp)
    }
}

/** 她按约定主动来找你的那句话上面：一行小字「早报 · 按约定」。 */
@Composable
internal fun PactTag(name: String) {
    Row(Modifier.padding(top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SvgIcon(clockIc, Skin.accent, 14.dp, stroke = 1.6f)
        Spacer(Modifier.width(6.dp))
        Txt("$name · 按约定", Page.muted, 12.sp, maxLines = 1)
    }
}

/**
 * 记忆本最上面「约定」那一组的一张（样子照 NoteCard）：纸上是到点要做的事，右下一个「下次」小标；
 * 下面名字和「答应你的 · 每天 08:00」。轻点没有反应，长按问一句「取消这条约定？」。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun PactNote(p: Pact, index: Int, modifier: Modifier, onDrop: () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val k by animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 60 else 220), label = "pactPress")
    val enter = remember(p.id) { Animatable(0f) }
    LaunchedEffect(p.id) { kotlinx.coroutines.delay(40L * index.coerceAtMost(12)); enter.animateTo(1f, tween(420, easing = Curves.decel)) }
    val shape = remember { NoteShape(30.dp, 18.dp) }
    Column(
        modifier
            .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 12.dp.toPx(); val s = 1f - .03f * k; scaleX = s; scaleY = s }
            .combinedClickable(src, null, onClickLabel = null, onLongClickLabel = "取消约定", onClick = { }, onLongClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onDrop() }),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.fillMaxWidth().aspectRatio(1f).background(Skin.note, shape)
                .drawBehind {
                    val c = 30.dp.toPx()
                    val fold = androidx.compose.ui.graphics.Path().apply { moveTo(size.width - c, 0f); lineTo(size.width - c, c - 6.dp.toPx()); quadraticTo(size.width - c, c, size.width - c + 6.dp.toPx(), c); lineTo(size.width, c); close() }
                    drawPath(fold, Skin.noteFold)
                }
                .padding(start = 14.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        ) {
            Txt(p.what, Page.ink, 13.sp, Modifier.weight(1f).padding(end = 12.dp), lineHeight = 21.sp, maxLines = 6)
            Txt(
                Pacts.nextWord(p).removePrefix("下次：").let { "下次 $it" }, Page.ink, 11.sp,
                Modifier.background(Skin.accent.copy(alpha = .22f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp), maxLines = 1,
            )
        }
        Txt(p.title, Page.ink, 14.sp, Modifier.fillMaxWidth().padding(top = 10.dp), weight = androidx.compose.ui.text.font.FontWeight.SemiBold, align = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
        Txt("答应你的 · " + Pacts.rule(p), Page.muted, 11.5.sp, Modifier.fillMaxWidth().padding(top = 3.dp), align = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
    }
}
