package com.fuguang.lueying

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * 桌面图标（「我的 → 主题 → 图标」）。
 * 预设四个：清单里四个 activity-alias（.IconBlue 默认开着），换哪个就只开哪个，DONT_KILL_APP。
 * 自定义：Android 不让 App 把自己的图标换成任意图片，只能在桌面加一个快捷方式（requestPinShortcut），
 * 所以应用列表里还是当时开着的那个预设。图存在 files/icon/custom.png（432×432，可见的是中间 288）。
 * prefs：icon = blue / whale / lime / gold / custom。
 */
object AppIcon {
    class Opt(val id: String, val name: String, val alias: String, val fg: Int)
    val opts = listOf(
        Opt("blue", "原版", "IconBlue", R.mipmap.ic_launcher_foreground),
        Opt("whale", "鲸", "IconWhale", R.mipmap.ic_launcher_whale_foreground),
        Opt("lime", "青柠牡丹", "IconLime", R.mipmap.ic_launcher_lime_foreground),
        Opt("gold", "鎏金牡丹", "IconGold", R.mipmap.ic_launcher_gold_foreground),
    )
    const val CUSTOM = "custom"
    private const val KEY = "icon"
    private const val SHORTCUT = "vignette-custom"

    var id by mutableStateOf("blue")
        private set
    /** 自定义图每换一次加一：格子里那张跟着刷新。 */
    var customVer by mutableIntStateOf(0)
        private set
    private var prefs: Prefs? = null
    private lateinit var dir: File

    fun init(ctx: Context, p: Prefs) {
        prefs = p; dir = File(ctx.filesDir, "icon").apply { mkdirs() }
        id = p.string(KEY)?.takeIf { s -> s == CUSTOM || opts.any { it.id == s } } ?: "blue"
    }

    fun name(v: String = id) = if (v == CUSTOM) "自定义" else opts.firstOrNull { it.id == v }?.name.orEmpty()
    fun customFile() = File(dir, "custom.png")
    fun hasCustom() = customFile().exists()

    /** 换成一个预设：先开新的入口再关别的，免得有一瞬间桌面上一个都没有。 */
    fun use(ctx: Context, v: String) {
        val o = opts.firstOrNull { it.id == v } ?: return
        val pm = ctx.packageManager
        runCatching {
            pm.setComponentEnabledSetting(comp(ctx, o), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            opts.filter { it !== o }.forEach { pm.setComponentEnabledSetting(comp(ctx, it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP) }
        }
        id = v; prefs?.putString(KEY, v)
    }

    /** 桌面上有没有我们加的那个快捷方式（换回预设时提醒一句）。 */
    fun pinned(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(ShortcutManager::class.java).pinnedShortcuts.any { it.id == SHORTCUT }
    }.getOrDefault(false)

    /** 存好自定义图，并请系统把它放到桌面；已经放过的直接换图。返回 false = 这台手机的桌面不支持。 */
    fun pin(ctx: Context, bmp: Bitmap? = null): Boolean {
        if (bmp != null) { runCatching { customFile().outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }; customVer++ }
        val img = bmp ?: BitmapFactory.decodeFile(customFile().path) ?: return false
        val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return false
        val info = ShortcutInfo.Builder(ctx, SHORTCUT)
            .setShortLabel(ctx.getString(R.string.app_name))
            .setIcon(Icon.createWithAdaptiveBitmap(img))
            .setIntent(Intent(Intent.ACTION_MAIN).setClassName(ctx, "com.fuguang.lueying.MainActivity").addCategory(Intent.CATEGORY_LAUNCHER))
            .build()
        return runCatching {
            if (pinned(ctx)) sm.updateShortcuts(listOf(info))
            else { if (!sm.isRequestPinShortcutSupported) return false; sm.requestPinShortcut(info, null) }
            id = CUSTOM; prefs?.putString(KEY, CUSTOM)
            true
        }.getOrDefault(false)
    }

    /** 回桌面看看。 */
    fun goHome(ctx: Context) = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun comp(ctx: Context, o: Opt) = ComponentName(ctx.packageName, "com.fuguang.lueying.${o.alias}")
}
