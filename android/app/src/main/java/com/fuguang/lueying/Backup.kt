package com.fuguang.lueying

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 备份：存进「下载 / 浮光掠影」，不弹选文件夹。
 * - 备份数据（.fgly）：设置文件（对话、助理、记忆、手记、供应商、MCP 都在里面）+ App 自己的文件（作品、图片）。
 * - 打包（.zip）：上面那个 .fgly + 浮光掠影本身的安装包，换手机先装 App 再恢复。
 */
object Backup {
    const val FOLDER = "浮光掠影"
    private const val LAST = "backup-last"

    /** 上次备份：时间（毫秒）和大小（字节）；没备份过是 null。 */
    var last by mutableStateOf<Pair<Long, Long>?>(null)
        private set
    private var prefs: Prefs? = null

    fun init(p: Prefs) {
        prefs = p
        last = p.string(LAST)?.split("|")?.let { runCatching { it[0].toLong() to it[1].toLong() }.getOrNull() }
    }

    class Result(val uri: Uri, val name: String, val bytes: Long)
    class NoRoom(val short: Long) : Exception()

    private fun dataFiles(ctx: Context): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        val sp = File(ctx.applicationInfo.dataDir, "shared_prefs/fuguang.xml")
        if (sp.exists()) out += "shared_prefs/fuguang.xml" to sp
        val root = ctx.filesDir
        root.walkTopDown().filter { it.isFile }.forEach { out += "files/" + it.relativeTo(root).path to it }
        return out
    }

    private fun apk(ctx: Context) = File(ctx.applicationInfo.sourceDir)

    /** 两种备份大概多大（字节）：小卡片上显示。 */
    suspend fun sizes(ctx: Context): Pair<Long, Long> = withContext(Dispatchers.IO) {
        val d = dataFiles(ctx).sumOf { it.second.length() }
        d to d + apk(ctx).length()
    }

    /** 做一份备份。step 报告做到哪一步（给进度圈和步骤字用）。 */
    suspend fun run(ctx: Context, pack: Boolean, step: suspend (Int) -> Unit): Result = withContext(Dispatchers.IO) {
        // 先让还在排队的设置写进文件
        ctx.getSharedPreferences("fuguang", Context.MODE_PRIVATE).edit().commit()
        val files = dataFiles(ctx)
        val need = files.sumOf { it.second.length() } + (if (pack) apk(ctx).length() else 0L)
        val free = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        if (free < need + 4_000_000) throw NoRoom(need + 4_000_000 - free)

        val stamp = SimpleDateFormat("MMdd-HHmm", Locale.CHINA).format(Date())
        val name = if (pack) "浮光掠影-打包-$stamp.zip" else "浮光掠影-$stamp.fgly"
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, if (pack) "application/zip" else "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("下载文件夹打不开")
        try {
            resolver.openOutputStream(uri)!!.use { os ->
                if (!pack) writeData(files, os, step)
                else ZipOutputStream(os).use { z ->
                    z.putNextEntry(ZipEntry("浮光掠影-$stamp.fgly"))
                    writeData(files, NoClose(z), step)
                    z.closeEntry()
                    step(2)
                    z.putNextEntry(ZipEntry("浮光掠影-安装包.apk"))
                    apk(ctx).inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
            step(if (pack) 3 else 2)
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
        val bytes = resolver.query(uri, arrayOf(MediaStore.Downloads.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else need } ?: need
        val now = System.currentTimeMillis()
        withContext(Dispatchers.Main) {
            last = now to bytes
            prefs?.putString(LAST, "$now|$bytes")
        }
        Result(uri, name, bytes)
    }

    /** 数据部分：设置文件一步，作品和图片一步。 */
    private suspend fun writeData(files: List<Pair<String, File>>, os: OutputStream, step: suspend (Int) -> Unit) {
        ZipOutputStream(os).use { z ->
            z.putNextEntry(ZipEntry("fgly.txt"))
            z.write("浮光掠影备份 1\n".toByteArray())
            z.closeEntry()
            val (sp, rest) = files.partition { it.first.startsWith("shared_prefs/") }
            sp.forEach { (n, f) -> z.putNextEntry(ZipEntry(n)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
            step(0)
            rest.forEach { (n, f) -> z.putNextEntry(ZipEntry(n)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
            step(1)
        }
    }

    /** 交给系统分享：发到网盘、发给自己。 */
    fun share(ctx: Context, r: Result) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (r.name.endsWith(".zip")) "application/zip" else "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, r.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "发给…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun mb(bytes: Long): String = if (bytes < 1_000_000) "%.1f MB".format(Locale.US, bytes / 1_000_000.0).let { if (bytes < 100_000) "0.1 MB" else it } else if (bytes < 10_000_000) "%.1f MB".format(Locale.US, bytes / 1_000_000.0) else "${bytes / 1_000_000} MB"

    /** 「刚刚备份 · 3.8 MB」「10月17日备份 · 3.8 MB」。 */
    fun lastLine(): String {
        val (t, b) = last ?: return "还没有备份过"
        val gap = System.currentTimeMillis() - t
        val w = when {
            gap < 60_000 -> "刚刚"
            gap < 86_400_000 -> ago(t)
            else -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(t))
        }
        return "${w}备份 · ${mb(b)}"
    }

    /** 包在外层 zip 里写内层 zip：内层关闭时别把外层也关了。 */
    private class NoClose(private val o: OutputStream) : OutputStream() {
        override fun write(b: Int) = o.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = o.write(b, off, len)
        override fun flush() = o.flush()
        override fun close() { flush() }
    }
}
