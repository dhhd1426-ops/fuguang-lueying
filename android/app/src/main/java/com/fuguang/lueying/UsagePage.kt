package com.fuguang.lueying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val gauge = Ic("M5 17a8 8 0 1 1 14 0m-7-4 3-3", circles = listOf(floatArrayOf(12f, 13f, 1.1f)))
private val refreshIcon = Ic("M20 11a8 8 0 1 0-2 6m2-11v5h-5")
private val arrow = Ic("M9.5 6l6 6-6 6")
private val usageWarning = Color(0xFFE07A6E)

private fun money(currency: String, value: Double): String {
    val formatted = NumberFormat.getNumberInstance().apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(value)
    return (if (currency == "CNY") "¥" else "US\$") + formatted
}

private fun count(n: Long) = NumberFormat.getIntegerInstance().format(n)
private fun percent(fraction: Float) = "已用 ${(fraction * 100).roundToInt()}%"
private fun clock(at: Long) = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.SIMPLIFIED_CHINESE)
    .format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

private fun resetLabel(at: Long?, now: Long): String {
    if (at == null) return "重置时间未提供"
    return if (at - now < 60_000) "约 ${((at - now).coerceAtLeast(0) + 999) / 1000} 秒后重置"
    else "${clock(at)} 重置"
}

private fun periodTitle(period: String?) = when (period) {
    "daily" -> "每日密钥额度"
    "weekly" -> "每周密钥额度"
    "monthly" -> "每月密钥额度"
    else -> "密钥额度"
}

@Composable
private fun UsageMeter(fraction: Float, modifier: Modifier = Modifier) {
    val tint = if (fraction >= .85f) usageWarning else Color(0xFF7FB88A)
    Box(modifier.fillMaxWidth().height(6.dp).background(tint.copy(alpha = .19f), RoundedCornerShape(50))
        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }) {
        if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(tint, RoundedCornerShape(50)))
    }
}

/** 没有上限时，横条只表达已记录的输入和输出构成。 */
@Composable
private fun TokenMix(totals: ModelUsage.Totals, modifier: Modifier = Modifier) {
    val sum = totals.input.toDouble() + totals.output
    val inputShare = if (sum > 0) (totals.input / sum).toFloat() else 0f
    val outputTint = Skin.accent
    val inputTint = Color(0xFF7FB88A)
    val description = if (sum > 0) "本机 Token 构成，输入 ${count(totals.input)}，输出 ${count(totals.output)}" else "暂无已记录的 Token"
    Box(modifier.fillMaxWidth().height(6.dp)
        .background(if (sum > 0) outputTint else Page.muted.copy(alpha = .18f), RoundedCornerShape(50))
        .semantics { contentDescription = description }) {
        if (inputShare > 0f) Box(Modifier.fillMaxWidth(inputShare).fillMaxHeight().background(inputTint, RoundedCornerShape(50)))
    }
}

@Composable
private fun rememberUsage(p: Provider, config: ProviderConfig, model: String, now: Long): ModelUsage.Snapshot {
    val route = remember(p.id, config.base, config.key) { ModelUsage.accountKey(p, config) }
    val key = remember(route, config.path, model) { ModelUsage.modelKey(route, config, model) }
    val revision by ModelUsage.revision.collectAsState()
    return remember(route, key, now, revision) { ModelUsage.snapshot(route, key, now) }
}

@Composable
internal fun MineUsageRow(store: AssistantStore, t: Float, onOpen: () -> Unit) {
    val p = provider(store.activeProvider)
    val config = store.config(p.id)
    val model = store.activeModel
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(5000); value = System.currentTimeMillis() }
    }
    val snapshot = rememberUsage(p, config, model, now)
    LaunchedEffect(p.id, config.base, config.key) {
        if (ModelUsage.canRefresh(config) && (snapshot.account == null || System.currentTimeMillis() - snapshot.account.at > 30_000)) {
            ModelUsage.refresh(p, config)
        }
    }
    val tokenRate = snapshot.entryTokenRate
    val totals = snapshot.totals
    val detail = when {
        !config.configured -> "尚未配置模型"
        tokenRate != null -> "${tokenRate.name} · ${percent(tokenRate.fraction)}"
        totals.input + totals.output > 0 -> "近 7 天 · ${count(totals.input + totals.output)} Token"
        else -> "暂无 Token 记录"
    }
    Box(Modifier.spot("usage")) {
        MineRow(3, t, "用量", "", onOpen, glyph = { SvgIcon(gauge, Skin.accent, 19.dp) }, subSlot = {
            val meterPadding = Modifier.padding(top = 3.dp, bottom = 5.dp)
            if (tokenRate != null) UsageMeter(tokenRate.fraction, meterPadding)
            else TokenMix(totals, meterPadding)
            Txt(detail, Page.muted, 12.sp, maxLines = 1)
        }) {
            if (config.configured) ProviderLogo(p.id, p.letter, 24.dp)
            Spacer(Modifier.width(4.dp))
            SvgIcon(arrow, Page.muted, 15.dp)
        }
    }
}

@Composable
internal fun ColumnScope.UsagePage(store: AssistantStore, compact: Boolean, onBack: () -> Unit) {
    val p = provider(store.activeProvider)
    val config = store.config(p.id)
    val model = store.activeModel
    var refresh by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(1000); value = System.currentTimeMillis() }
    }
    val snapshot = rememberUsage(p, config, model, now)
    LaunchedEffect(p.id, config.base, config.key, refresh) {
        failed = false
        refreshing = ModelUsage.canRefresh(config)
        try {
            if (refreshing) {
                val result = ModelUsage.refresh(p, config)
                (result.exceptionOrNull() as? CancellationException)?.let { throw it }
                failed = result.isFailure
            }
        } finally { refreshing = false }
    }
    Title("用量", compact, onBack) {
        if (ModelUsage.canRefresh(config)) Pressable(
            { if (!refreshing) refresh++ }, Modifier.size(40.dp), label = "刷新账户额度",
        ) { _ -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SvgIcon(refreshIcon, Skin.accent, 20.dp) } }
    }
    Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        UsageCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderLogo(p.id, p.letter, 38.dp, dim = !config.configured)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Txt(config.name.ifBlank { p.name }, Page.muted, 12.sp)
                    Txt(model.ifBlank { "未选择模型" }, Page.ink, 15.sp, maxLines = 2)
                }
            }
        }
        UsageHeading("Token 用量")
        val totals = snapshot.totals
        val tokenRates = snapshot.tokenRates
        if (tokenRates.isEmpty()) {
            UsageCard {
                UsageTitle("本机 Token 构成", "最近 7 天")
                TokenMix(totals, Modifier.padding(vertical = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Txt("输入 ${count(totals.input)}", Color(0xFF7FB88A), 13.sp)
                    Txt("输出 ${count(totals.output)}", Skin.accent, 13.sp)
                }
                Txt(
                    if (totals.input + totals.output > 0) "按已记录的 Token 构成显示 · 供应商上限未提供"
                    else "收到模型返回的 Token 数据后，横条会自动更新。",
                    Page.muted, 12.sp, Modifier.padding(top = 5.dp),
                )
            }
        } else {
            tokenRates.forEach { rate ->
                UsageCard {
                    UsageTitle(rate.name, percent(rate.fraction))
                    UsageMeter(rate.fraction, Modifier.padding(vertical = 8.dp))
                    Txt("剩余 ${count(rate.remaining)} / ${count(rate.limit)} · ${resetLabel(rate.reset, now)}", Page.muted, 12.sp)
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        if (!config.configured) {
            Spacer(Modifier.height(12.dp))
            Txt("配置模型后会自动记录用量。", Page.muted, 13.sp)
            return@Column
        }
        if (refreshing || failed) {
            Spacer(Modifier.height(10.dp))
            Txt(if (refreshing) "正在刷新账户额度…" else "账户额度更新失败", Page.muted, 12.sp)
        }
        val account = snapshot.account
        if (account != null) {
            UsageHeading("账户额度")
            UsageCard {
                UsageTitle(
                    if (account.limit != null) periodTitle(account.period) else "账户余额",
                    account.fraction?.let { percent(it) }.orEmpty(),
                )
                account.fraction?.let { UsageMeter(it, Modifier.padding(vertical = 8.dp)) }
                Txt(
                    if (account.limit != null) "剩余 ${money(account.currency, account.remaining)} / ${money(account.currency, account.limit)}" else money(account.currency, account.remaining),
                    Page.ink, if (account.limit == null) 24.sp else 14.sp, Modifier.padding(top = 4.dp),
                )
                Txt("该渠道共享 · ${clock(account.at)} 获取", Page.muted, 12.sp, Modifier.padding(top = 5.dp))
                ModelUsage.tokenEstimate(config, model, account)?.let { (input, output) ->
                    Spacer(Modifier.height(12.dp))
                    Txt("仅输入约 ${count(input)} · 仅输出约 ${count(output)} Token", Page.ink, 13.sp)
                    Txt("按高峰、未缓存单价分别估算", Page.muted, 12.sp, Modifier.padding(top = 4.dp))
                }
            }
        }
        val requestRates = snapshot.rates.filterNot { it.isToken }
        if (requestRates.isNotEmpty()) {
            UsageHeading("API 请求速率")
            requestRates.forEach { rate ->
                UsageCard {
                    UsageTitle(rate.name, percent(rate.fraction))
                    UsageMeter(rate.fraction, Modifier.padding(vertical = 8.dp))
                    Txt("剩余 ${count(rate.remaining)} / ${count(rate.limit)} · ${resetLabel(rate.reset, now)}", Page.muted, 12.sp)
                }
                Spacer(Modifier.height(6.dp))
            }
            Txt("根据最近一次模型请求的响应头显示。", Page.muted, 12.sp, Modifier.padding(start = 2.dp))
        }
        UsageHeading("本机用量 · 最近 7 天")
        UsageCard {
            UsageTitle("模型请求", "${count(totals.requests)} 次")
            Spacer(Modifier.height(8.dp))
            Txt("输入 ${count(totals.input)} · 输出 ${count(totals.output)} Token", Page.ink, 13.sp)
            if (totals.incomplete != 0L) {
                Txt("${count(totals.incomplete)} 次请求的 Token 数据不全。", Page.muted, 12.sp, Modifier.padding(top = 5.dp))
            }
            if (account == null && snapshot.rates.isEmpty() && !refreshing) {
                Txt("暂无可显示的供应商余额或额度。", Page.muted, 12.sp, Modifier.padding(top = 5.dp))
            }
        }
    }
}

@Composable
private fun UsageHeading(name: String) {
    Txt(name, Page.muted, 13.sp, Modifier.padding(start = 4.dp, top = 17.dp, bottom = 7.dp))
}

@Composable
private fun UsageTitle(title: String, trailing: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Txt(title, Page.ink, 15.sp, Modifier.weight(1f))
        if (trailing.isNotBlank()) Txt(trailing, Page.muted, 13.sp, Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun UsageCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(veil(.06f), RoundedCornerShape(16.dp)).padding(14.dp), content = content)
}
