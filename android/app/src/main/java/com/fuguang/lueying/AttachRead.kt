package com.fuguang.lueying

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/** 把你发来的文件读成她能看的：文字类直接读，Word / PPT / Excel 从包里抠字，PDF 画成几页图。 */
object AttachRead {
    private val TEXT = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "log", "ini", "conf", "yaml", "yml", "toml", "srt", "ass", "lrc",
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "cs", "go", "rs", "swift", "rb", "php", "sh", "bat", "sql", "css", "gradle", "properties",
    )

    /** 读不出字返回 null。 */
    fun text(f: File): String? = runCatching {
        val ext = f.extension.lowercase()
        when {
            ext in TEXT -> f.inputStream().use { head(it, 400_000) }.let { decode(it) }
            ext == "docx" -> office(f) { it == "word/document.xml" }
            ext == "pptx" -> office(f) { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
            ext == "xlsx" -> office(f) { it == "xl/sharedStrings.xml" || (it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml")) }
            ext == "epub" -> office(f) { it.endsWith(".xhtml") || it.endsWith(".html") }
            else -> f.inputStream().use { head(it, 200_000) }.takeIf { looksText(it) }?.let { decode(it) }
        }
    }.getOrNull()

    /** 最多读 n 个字节（InputStream.readNBytes 要 Android 13）。 */
    private fun head(i: java.io.InputStream, n: Int): ByteArray {
        val out = ByteArrayOutputStream(); val buf = ByteArray(16_384); var left = n
        while (left > 0) { val r = i.read(buf, 0, minOf(buf.size, left)); if (r <= 0) break; out.write(buf, 0, r); left -= r }
        return out.toByteArray()
    }

    private fun decode(b: ByteArray): String {
        val utf = String(b, Charsets.UTF_8)
        // 不是 UTF-8（乱码多）就按 GBK 读：国内的 txt 常见
        return if (utf.count { it == '�' } > utf.length / 50) String(b, charset("GBK")) else utf
    }

    private fun looksText(b: ByteArray): Boolean = b.isNotEmpty() && b.take(4000).count { it == 0.toByte() } == 0

    /** Office 文件是 zip：按顺序取出要的 xml，去掉标签，段落、单元格之间换行。 */
    private fun office(f: File, want: (String) -> Boolean): String = ZipFile(f).use { z ->
        val names = z.entries().asSequence().map { it.name }.filter(want).sortedWith(compareBy({ it.length }, { it })).toList()
        names.joinToString("\n\n") { n ->
            val xml = z.getInputStream(z.getEntry(n)).use { head(it, 3_000_000) }.toString(Charsets.UTF_8)
            xml.replace(Regex("</(w:p|a:p|p|div|h\\d|li|tr|si|row)>"), "\n")
                .replace(Regex("</(c|td|w:tc)>"), "\t")
                .replace(Regex("<[^>]+>"), "")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
                .lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
        }
    }

    /** PDF 的前几页画成图（给能看图、但不收 PDF 的供应商）。 */
    fun pdfPages(f: File, max: Int): List<ByteArray> = runCatching {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                (0 until minOf(max, r.pageCount)).map { i ->
                    r.openPage(i).use { pg ->
                        val k = 1400f / maxOf(pg.width, pg.height)
                        val bmp = Bitmap.createBitmap((pg.width * k).toInt().coerceAtLeast(1), (pg.height * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
                    }
                }
            }
        }
    }.getOrDefault(emptyList())
}
