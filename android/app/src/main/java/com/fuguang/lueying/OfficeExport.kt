package com.fuguang.lueying

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/*
 * 作品的排版与导出：只用 JDK，不依赖 Android，方便在电脑上单独测。
 * - 文档：助理写的 Markdown → 块 → 预览 HTML / .docx
 * - 幻灯片：每页一个标题 + 几条要点 → 预览 HTML / .pptx
 */

/* ---------------- 文字片段与块 ---------------- */

data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

sealed class Block {
    data class Heading(val level: Int, val runs: List<Run>) : Block()
    data class Para(val runs: List<Run>) : Block()
    data class Bullet(val runs: List<Run>, val level: Int) : Block()
    /** list：第几组编号列表（每组从 1 重新数）。 */
    data class Numbered(val runs: List<Run>, val list: Int) : Block()
    data class Quote(val runs: List<Run>) : Block()
    data class Table(val rows: List<List<List<Run>>>) : Block()
    object Rule : Block()
    /** 单独一行的 ![说明](地址)。 */
    data class Image(val alt: String, val url: String) : Block()
}

data class Slide(val title: String, val points: List<String>, val image: String = "")

object Md {
    /** **粗** *斜* `代码`，其余原样。 */
    fun inline(s: String): List<Run> {
        val out = ArrayList<Run>()
        val re = Regex("""\*\*(.+?)\*\*|__(.+?)__|\*(.+?)\*|`(.+?)`""")
        var i = 0
        for (m in re.findAll(s)) {
            if (m.range.first > i) out.add(Run(s.substring(i, m.range.first)))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> out.add(Run(g[1], bold = true))
                g[2].isNotEmpty() -> out.add(Run(g[2], bold = true))
                g[3].isNotEmpty() -> out.add(Run(g[3], italic = true))
                else -> out.add(Run(g[4], code = true))
            }
            i = m.range.last + 1
        }
        if (i < s.length) out.add(Run(s.substring(i)))
        return out.filter { it.text.isNotEmpty() }
    }

    fun parse(md: String): List<Block> {
        val out = ArrayList<Block>()
        val para = StringBuilder()
        var listNo = 0
        var inNumbered = false
        val table = ArrayList<List<List<Run>>>()
        fun flushPara() { if (para.isNotBlank()) out.add(Block.Para(inline(para.toString().trim()))); para.clear() }
        fun flushTable() { if (table.isNotEmpty()) out.add(Block.Table(table.toList())); table.clear() }
        md.replace("\r", "").lines().forEach { raw ->
            val line = raw.trimEnd()
            val t = line.trim()
            val isRow = t.startsWith("|") && t.endsWith("|") && t.length > 1
            if (!isRow) flushTable()
            val num = Regex("""^\d+[.、)]\s+(.*)$""").find(t)
            val pic = Regex("""^!\[([^\]]*)]\((\S+?)\)$""").find(t)
            if (num == null && t.isNotEmpty()) inNumbered = false
            when {
                t.isEmpty() -> flushPara()
                isRow -> {
                    flushPara()
                    val cells = t.trim('|').split("|").map { it.trim() }
                    if (cells.all { it.matches(Regex(":?-{2,}:?")) }) return@forEach
                    table.add(cells.map { inline(it) })
                }
                pic != null -> { flushPara(); out.add(Block.Image(pic.groupValues[1].trim(), pic.groupValues[2])) }
                t.startsWith("#") -> {
                    flushPara()
                    val lv = t.takeWhile { it == '#' }.length.coerceIn(1, 3)
                    out.add(Block.Heading(lv, inline(t.trimStart('#').trim())))
                }
                t == "---" || t == "***" -> { flushPara(); out.add(Block.Rule) }
                t.startsWith("> ") || t == ">" -> { flushPara(); out.add(Block.Quote(inline(t.removePrefix(">").trim()))) }
                t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") -> {
                    flushPara()
                    val indent = line.length - line.trimStart().length
                    out.add(Block.Bullet(inline(t.substring(2).trim()), if (indent >= 2) 1 else 0))
                }
                num != null -> {
                    flushPara()
                    if (!inNumbered) { listNo++; inNumbered = true }
                    out.add(Block.Numbered(inline(num.groupValues[1]), listNo))
                }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
            }
        }
        flushPara(); flushTable()
        return out
    }

    fun plain(runs: List<Run>) = runs.joinToString("") { it.text }
}

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** XML 里不允许的控制字符去掉。 */
private fun xml(s: String) = esc(s).replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")

/* ---------------- 预览 HTML ---------------- */

object Preview {
    private const val HEAD = """<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">"""
    private const val TOKENS = """:root{--bg:#121519;--card:#1d2227;--ink:#f1ebe4;--muted:#a39d96;--apricot:#e9b48e;
--serif:"Noto Serif SC","Songti SC","Source Han Serif SC",serif;--sans:system-ui,"Noto Sans SC",sans-serif;color-scheme:dark}
*{box-sizing:border-box}html,body{margin:0;background:var(--bg);color:var(--ink);font-family:var(--sans)}"""

    private fun runs(rs: List<Run>) = rs.joinToString("") { r ->
        var t = esc(r.text)
        if (r.code) t = "<code>$t</code>"
        if (r.italic) t = "<em>$t</em>"
        if (r.bold) t = "<strong>$t</strong>"
        t
    }

    fun doc(title: String, md: String): String {
        val blocks = Md.parse(md)
        val body = StringBuilder()
        var open: String? = null   // 当前开着的列表标签
        fun close() { open?.let { body.append("</$it>") }; open = null }
        blocks.forEach { b ->
            when (b) {
                is Block.Bullet -> { if (open != "ul") { close(); body.append("<ul>"); open = "ul" }; body.append("<li class=\"l${b.level}\">${runs(b.runs)}</li>") }
                is Block.Numbered -> { if (open != "ol") { close(); body.append("<ol>"); open = "ol" }; body.append("<li>${runs(b.runs)}</li>") }
                else -> {
                    close()
                    when (b) {
                        is Block.Heading -> body.append("<h${b.level + 1}>${runs(b.runs)}</h${b.level + 1}>")
                        is Block.Para -> body.append("<p>${runs(b.runs)}</p>")
                        is Block.Quote -> body.append("<blockquote>${runs(b.runs)}</blockquote>")
                        is Block.Rule -> body.append("<hr>")
                        is Block.Image -> body.append("<figure><img src=\"${esc(b.url)}\" alt=\"${esc(b.alt)}\">" + (if (b.alt.isNotBlank()) "<figcaption>${esc(b.alt)}</figcaption>" else "") + "</figure>")
                        is Block.Table -> {
                            body.append("<div class=\"tw\"><table>")
                            b.rows.forEachIndexed { i, row ->
                                val tag = if (i == 0) "th" else "td"
                                body.append("<tr>").append(row.joinToString("") { "<$tag>${runs(it)}</$tag>" }).append("</tr>")
                            }
                            body.append("</table></div>")
                        }
                        else -> {}
                    }
                }
            }
        }
        close()
        return """<!doctype html><html lang="zh"><head>$HEAD<title>${esc(title)}</title><style>$TOKENS
main{max-width:680px;margin:0 auto;padding:28px max(22px,env(safe-area-inset-left)) 64px}
h1{font-family:var(--serif);font-weight:600;font-size:28px;line-height:1.35;margin:0 0 6px;text-wrap:balance}
.mark{width:34px;height:2px;background:var(--apricot);margin:14px 0 26px}
h2{font-family:var(--serif);font-weight:600;font-size:21px;margin:34px 0 10px}
h3{font-family:var(--serif);font-weight:600;font-size:17.5px;margin:26px 0 8px}
h4{font-size:15.5px;margin:20px 0 6px;color:var(--apricot);font-weight:500}
p,li{font-size:16px;line-height:1.9;color:#e4ded7}p{margin:0 0 14px}
ul,ol{margin:0 0 14px;padding-left:1.3em}li{margin:4px 0}li.l1{margin-left:1.2em;list-style:circle}
li::marker{color:var(--apricot)}strong{color:var(--ink)}code{font-size:.9em;background:var(--card);padding:1px 5px;border-radius:5px}
blockquote{margin:16px 0;padding:12px 16px;background:var(--card);border-radius:12px;color:var(--muted);line-height:1.8}
hr{border:0;height:1px;background:#ffffff1a;margin:28px 0}
figure{margin:20px 0 22px}figure img{display:block;width:100%;border-radius:12px;background:var(--card)}figcaption{font-size:13px;color:var(--muted);margin-top:8px;text-align:center}
.tw{overflow-x:auto;margin:6px 0 18px}table{border-collapse:collapse;font-size:14.5px;min-width:100%}
th,td{padding:9px 12px;text-align:left;line-height:1.6}th{background:var(--card);font-weight:600}tr:nth-child(even) td{background:#ffffff06}
@media print{:root{--bg:#fff;--card:#f3efe9;--ink:#1b1b1b;--muted:#555}p,li{color:#222}main{max-width:none}}
</style></head><body><main><h1>${esc(title)}</h1><div class="mark"></div>$body</main></body></html>"""
    }

    fun slides(title: String, slides: List<Slide>): String {
        val n = slides.size
        val body = slides.mapIndexed { i, s ->
            val pic = if (s.image.isEmpty()) "" else """<img class="ph" src="${esc(s.image)}" alt="" data-no-zoom>"""
            if (i == 0) """<section class="s cover${if (pic.isEmpty()) "" else " pic"}">$pic<div class="in"><h1>${esc(s.title)}</h1><div class="mark"></div>${s.points.joinToString("") { "<p>${runs(Md.inline(it))}</p>" }}</div><span class="no">1 / $n</span></section>"""
            else """<section class="s${if (pic.isEmpty()) "" else " pic"}">$pic<div class="in"><h2>${esc(s.title)}</h2><div class="mark"></div><ul>${s.points.joinToString("") { p ->
                val sub = p.startsWith("  ") || p.trimStart().startsWith("- ")
                "<li${if (sub) " class=\"sub\"" else ""}>${runs(Md.inline(p.trim().removePrefix("- ")))}</li>"
            }}</ul></div><span class="no">${i + 1} / $n</span></section>"""
        }.joinToString("")
        return """<!doctype html><html lang="zh"><head>$HEAD<title>${esc(title)}</title><style>$TOKENS
.deck{display:flex;flex-direction:column;gap:18px;padding:20px max(14px,env(safe-area-inset-left)) 48px;align-items:center}
.s{position:relative;width:100%;max-width:960px;aspect-ratio:16/9;background:linear-gradient(160deg,#20262c,#171b20);border-radius:12px;container-type:inline-size;overflow:hidden}
.in{position:absolute;inset:7cqw 7cqw 6cqw}
.cover .in{display:flex;flex-direction:column;justify-content:center}
.ph{position:absolute;top:0;right:0;bottom:0;width:40%;height:100%;object-fit:cover;background:#ffffff08}
.s.pic:not(.cover) .in{right:46cqw}
.cover.pic .ph{width:100%;opacity:.62}
.cover.pic::after{content:"";position:absolute;inset:0;background:linear-gradient(90deg,#14181ccc 20%,#14181c40 75%)}
.cover.pic .in{z-index:1}
h1{font-family:var(--serif);font-weight:600;font-size:7cqw;line-height:1.25;margin:0}
h2{font-family:var(--serif);font-weight:600;font-size:5.2cqw;margin:0}
.mark{width:5cqw;height:.4cqw;background:var(--apricot);margin:2.2cqw 0 2.8cqw}
.cover p{font-size:3.2cqw;color:var(--muted);margin:.6cqw 0;line-height:1.6}
ul{margin:0;padding-left:2.6cqw}li{font-size:3.4cqw;line-height:1.55;margin:0 0 1.4cqw;color:#e4ded7}
li::marker{color:var(--apricot)}li.sub{margin-left:3cqw;font-size:2.9cqw;color:var(--muted);list-style:circle}
strong{color:var(--apricot);font-weight:600}
.no{position:absolute;right:3cqw;bottom:2.4cqw;font-size:2cqw;color:#ffffff55;font-variant-numeric:tabular-nums}
@media print{@page{size:landscape;margin:0}.deck{padding:0;gap:0}.s{border-radius:0;max-width:none;break-after:page}}
</style></head><body><div class="deck">$body</div></body></html>"""
    }

    /** 助理写的网页没写全，补上 viewport 和编码，好在手机上正常显示。 */
    fun page(html: String): String {
        val h = html.trim()
        if (h.contains("<html", true)) {
            if (h.contains("name=\"viewport\"", true) || h.contains("name='viewport'", true)) return h
            val m = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(h) ?: return HEAD + h
            return h.substring(0, m.range.last + 1) + HEAD + h.substring(m.range.last + 1)
        }
        return "<!doctype html><html lang=\"zh\"><head>$HEAD</head><body>$h</body></html>"
    }
}

/* ---------------- Office 文件 ---------------- */

private fun zip(files: List<Pair<String, String>>): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { z ->
        files.forEach { (path, body) ->
            z.putNextEntry(ZipEntry(path))
            z.write(body.toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
    }
    return bos.toByteArray()
}

private const val XH = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

object Docx {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun runs(rs: List<Run>, extra: String = "") = rs.joinToString("") { r ->
        val pr = buildString {
            if (r.code) append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/>")
            if (r.bold) append("<w:b/>")
            if (r.italic) append("<w:i/>")
            append(extra)
        }
        "<w:r>${if (pr.isNotEmpty()) "<w:rPr>$pr</w:rPr>" else ""}<w:t xml:space=\"preserve\">${xml(r.text)}</w:t></w:r>"
    }

    private fun p(style: String?, rs: List<Run>, numId: Int = 0, ilvl: Int = 0) =
        "<w:p><w:pPr>${if (style != null) "<w:pStyle w:val=\"$style\"/>" else ""}" +
            (if (numId > 0) "<w:numPr><w:ilvl w:val=\"$ilvl\"/><w:numId w:val=\"$numId\"/></w:numPr>" else "") +
            "</w:pPr>${runs(rs)}</w:p>"

    fun build(title: String, md: String): ByteArray {
        val blocks = Md.parse(md)
        val numbered = blocks.filterIsInstance<Block.Numbered>().map { it.list }.distinct()
        // numId 1 = 圆点；每组编号列表一个自己的 numId，从 1 重新数
        val numIdOf = numbered.mapIndexed { i, l -> l to (i + 2) }.toMap()
        val body = StringBuilder()
        // 标题为空（她直接发的文件）就不加大标题，正文是什么就是什么
        if (title.isNotBlank()) body.append(p("Title", listOf(Run(title))))
        blocks.forEach { b ->
            body.append(
                when (b) {
                    is Block.Heading -> p("Heading${b.level}", b.runs)
                    is Block.Para -> p(null, b.runs)
                    is Block.Bullet -> p("ListParagraph", b.runs, 1, b.level)
                    is Block.Numbered -> p("ListParagraph", b.runs, numIdOf[b.list] ?: 2)
                    is Block.Quote -> p("Quote", b.runs)
                    is Block.Image -> p(null, listOf(Run("（图：" + b.alt.ifBlank { "网页素材" } + "）", italic = true)))
                    is Block.Rule -> "<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"1\" w:color=\"D9CFC4\"/></w:pBdr></w:pPr></w:p>"
                    is Block.Table -> {
                        val cols = b.rows.maxOf { it.size }
                        val tb = StringBuilder("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"5000\" w:type=\"pct\"/></w:tblPr><w:tblGrid>")
                        repeat(cols) { tb.append("<w:gridCol w:w=\"${9000 / cols}\"/>") }
                        tb.append("</w:tblGrid>")
                        b.rows.forEachIndexed { ri, row ->
                            tb.append("<w:tr>")
                            for (c in 0 until cols) {
                                val cell = row.getOrElse(c) { emptyList() }
                                val shade = if (ri == 0) "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F3EDE6\"/>" else ""
                                tb.append("<w:tc><w:tcPr><w:tcW w:w=\"${9000 / cols}\" w:type=\"dxa\"/>$shade</w:tcPr><w:p>")
                                tb.append(runs(cell, if (ri == 0) "<w:b/>" else ""))
                                tb.append("</w:p></w:tc>")
                            }
                            tb.append("</w:tr>")
                        }
                        tb.append("</w:tbl>").append("<w:p/>").toString()
                    }
                },
            )
        }
        val document = "$XH<w:document xmlns:w=\"$W\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body>$body" +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>"

        val font = "<w:rFonts w:ascii=\"Georgia\" w:hAnsi=\"Georgia\" w:eastAsia=\"宋体\"/>"
        fun hs(id: String, name: String, sz: Int, before: Int, color: String = "1F1F1F") =
            "<w:style w:type=\"paragraph\" w:styleId=\"$id\"><w:name w:val=\"$name\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
                "<w:pPr><w:keepNext/><w:spacing w:before=\"$before\" w:after=\"120\"/>${if (id.startsWith("Heading")) "<w:outlineLvl w:val=\"${id.last() - '1'}\"/>" else ""}</w:pPr>" +
                "<w:rPr>$font<w:b/><w:color w:val=\"$color\"/><w:sz w:val=\"$sz\"/></w:rPr></w:style>"
        val styles = "$XH<w:styles xmlns:w=\"$W\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"微软雅黑\" w:cs=\"Calibri\"/><w:sz w:val=\"22\"/><w:lang w:val=\"en-US\" w:eastAsia=\"zh-CN\"/></w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"360\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/><w:rPr><w:color w:val=\"2B2B2B\"/></w:rPr></w:style>" +
            hs("Title", "Title", 44, 0) + hs("Heading1", "heading 1", 32, 360) + hs("Heading2", "heading 2", 28, 280) + hs("Heading3", "heading 3", 24, 200, "8A5A3C") +
            "<w:style w:type=\"paragraph\" w:styleId=\"Quote\"><w:name w:val=\"Quote\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/><w:pPr><w:ind w:left=\"420\"/><w:pBdr><w:left w:val=\"single\" w:sz=\"18\" w:space=\"10\" w:color=\"E9B48E\"/></w:pBdr></w:pPr><w:rPr><w:color w:val=\"666666\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/><w:pPr><w:spacing w:after=\"60\"/></w:pPr></w:style>" +
            "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/><w:tblPr><w:tblBorders>" +
            listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") { "<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"D9CFC4\"/>" } +
            "</w:tblBorders><w:tblCellMar><w:left w:w=\"108\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style></w:styles>"

        fun lvl(i: Int, fmt: String, text: String, ind: Int) =
            "<w:lvl w:ilvl=\"$i\"><w:start w:val=\"1\"/><w:numFmt w:val=\"$fmt\"/><w:lvlText w:val=\"$text\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"$ind\" w:hanging=\"360\"/></w:pPr></w:lvl>"
        val numbering = "$XH<w:numbering xmlns:w=\"$W\">" +
            "<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"hybridMultilevel\"/>${lvl(0, "bullet", "•", 720)}${lvl(1, "bullet", "◦", 1440)}</w:abstractNum>" +
            "<w:abstractNum w:abstractNumId=\"1\"><w:multiLevelType w:val=\"hybridMultilevel\"/>${lvl(0, "decimal", "%1.", 720)}</w:abstractNum>" +
            "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>" +
            (numIdOf.values.ifEmpty { listOf(2) }).joinToString("") { id -> "<w:num w:numId=\"$id\"><w:abstractNumId w:val=\"1\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"1\"/></w:lvlOverride></w:num>" } +
            "</w:numbering>"

        return zip(
            listOf(
                "[Content_Types].xml" to "$XH<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                    "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
                    "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>" +
                    "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/></Types>",
                "_rels/.rels" to "$XH<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
                    "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/></Relationships>",
                "docProps/core.xml" to core(title),
                "word/_rels/document.xml.rels" to "$XH<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
                    "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/></Relationships>",
                "word/document.xml" to document,
                "word/styles.xml" to styles,
                "word/numbering.xml" to numbering,
            ),
        )
    }
}

private fun core(title: String) = "$XH<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
    "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
    "<dc:title>${xml(title)}</dc:title><dc:creator>Vignette</dc:creator></cp:coreProperties>"

object Pptx {
    private const val NS = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
        "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
    private const val CX = 12192000L
    private const val CY = 6858000L
    private const val BG = "171B20"
    private const val INK = "F1EBE4"
    private const val MUTED = "A39D96"
    private const val APRICOT = "E9B48E"
    private const val GRP = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
        "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"

    private fun emu(inch: Double) = (inch * 914400).toLong()

    private fun run(r: Run, sz: Int, color: String, serif: Boolean): String {
        val c = if (r.bold && !serif) APRICOT else color
        val face = if (serif) "<a:latin typeface=\"Georgia\"/><a:ea typeface=\"宋体\"/>" else "<a:latin typeface=\"Calibri\"/><a:ea typeface=\"微软雅黑\"/>"
        return "<a:r><a:rPr lang=\"zh-CN\" altLang=\"en-US\" sz=\"$sz\"${if (r.bold || serif) " b=\"1\"" else ""}${if (r.italic) " i=\"1\"" else ""} dirty=\"0\">" +
            "<a:solidFill><a:srgbClr val=\"$c\"/></a:solidFill>$face</a:rPr><a:t>${xml(r.text)}</a:t></a:r>"
    }

    private fun box(id: Int, name: String, x: Long, y: Long, w: Long, h: Long, paras: String, anchor: String = "t") =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>" +
            "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>" +
            "<p:txBody><a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" anchor=\"$anchor\"><a:normAutofit/></a:bodyPr><a:lstStyle/>$paras</p:txBody></p:sp>"

    private fun bar(id: Int, x: Long, y: Long) =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"Mark\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>" +
            "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"${emu(.6)}\" cy=\"${emu(.035)}\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>" +
            "<a:solidFill><a:srgbClr val=\"$APRICOT\"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr></p:sp>"

    private fun slideXml(i: Int, n: Int, s: Slide): String {
        val left = emu(1.0)
        val width = CX - 2 * left
        val shapes = StringBuilder()
        if (i == 0) {
            val title = "<a:p><a:pPr><a:lnSpc><a:spcPct val=\"110000\"/></a:lnSpc></a:pPr>${run(Run(s.title), 4400, INK, true)}</a:p>"
            shapes.append(box(2, "Title", left, emu(2.0), width, emu(1.9), title, "b"))
            shapes.append(bar(3, left, emu(4.15)))
            val sub = s.points.joinToString("") { pt -> "<a:p><a:pPr><a:spcBef><a:spcPts val=\"600\"/></a:spcBef></a:pPr>${Md.inline(pt).joinToString("") { run(it, 2000, MUTED, false) }}</a:p>" }
            if (sub.isNotEmpty()) shapes.append(box(4, "Subtitle", left, emu(4.45), width, emu(2.0), sub))
        } else {
            val title = "<a:p>${run(Run(s.title), 3200, INK, true)}</a:p>"
            shapes.append(box(2, "Title", left, emu(.7), width, emu(1.0), title, "b"))
            shapes.append(bar(3, left, emu(1.95)))
            val body = s.points.joinToString("") { raw ->
                val sub = raw.startsWith("  ") || raw.trimStart().startsWith("- ")
                val text = raw.trim().removePrefix("- ")
                val mar = if (sub) 685800 else 342900
                "<a:p><a:pPr marL=\"$mar\" indent=\"-285750\"><a:lnSpc><a:spcPct val=\"120000\"/></a:lnSpc><a:spcBef><a:spcPts val=\"${if (sub) 400 else 1000}\"/></a:spcBef>" +
                    "<a:buClr><a:srgbClr val=\"$APRICOT\"/></a:buClr><a:buFont typeface=\"Arial\"/><a:buChar char=\"${if (sub) "◦" else "•"}\"/></a:pPr>" +
                    Md.inline(text).joinToString("") { run(it, if (sub) 1700 else 2000, if (sub) MUTED else INK, false) } + "</a:p>"
            }
            if (body.isNotEmpty()) shapes.append(box(4, "Body", left, emu(2.3), width, emu(4.4), body))
        }
        val no = "<a:p><a:pPr algn=\"r\"/>${run(Run("${i + 1} / $n"), 1100, "6E6A66", false)}</a:p>"
        shapes.append(box(5, "No", CX - emu(2.4), CY - emu(.6), emu(1.9), emu(.3), no))
        return "$XH<p:sld $NS><p:cSld><p:spTree>$GRP$shapes</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
    }

    private val theme = "$XH<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"Fuguang\"><a:themeElements>" +
        "<a:clrScheme name=\"Fuguang\"><a:dk1><a:srgbClr val=\"$BG\"/></a:dk1><a:lt1><a:srgbClr val=\"$INK\"/></a:lt1><a:dk2><a:srgbClr val=\"1D2227\"/></a:dk2><a:lt2><a:srgbClr val=\"$MUTED\"/></a:lt2>" +
        "<a:accent1><a:srgbClr val=\"$APRICOT\"/></a:accent1><a:accent2><a:srgbClr val=\"C9805A\"/></a:accent2><a:accent3><a:srgbClr val=\"8FA3B0\"/></a:accent3>" +
        "<a:accent4><a:srgbClr val=\"B7A58C\"/></a:accent4><a:accent5><a:srgbClr val=\"7E8C7A\"/></a:accent5><a:accent6><a:srgbClr val=\"A88BA0\"/></a:accent6>" +
        "<a:hlink><a:srgbClr val=\"$APRICOT\"/></a:hlink><a:folHlink><a:srgbClr val=\"C9805A\"/></a:folHlink></a:clrScheme>" +
        "<a:fontScheme name=\"Fuguang\"><a:majorFont><a:latin typeface=\"Georgia\"/><a:ea typeface=\"宋体\"/><a:cs typeface=\"\"/></a:majorFont>" +
        "<a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"微软雅黑\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>" +
        "<a:fmtScheme name=\"Fuguang\"><a:fillStyleLst>" + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>".repeat(3) + "</a:fillStyleLst>" +
        "<a:lnStyleLst>" + "<a:ln w=\"9525\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>".repeat(3) + "</a:lnStyleLst>" +
        "<a:effectStyleLst>" + "<a:effectStyle><a:effectLst/></a:effectStyle>".repeat(3) + "</a:effectStyleLst>" +
        "<a:bgFillStyleLst>" + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>".repeat(3) + "</a:bgFillStyleLst></a:fmtScheme>" +
        "</a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"

    fun build(title: String, slides: List<Slide>): ByteArray {
        val n = slides.size
        val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val files = ArrayList<Pair<String, String>>()
        files += "[Content_Types].xml" to "$XH<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>" +
            "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>" +
            "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>" +
            "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            (1..n).joinToString("") { "<Override PartName=\"/ppt/slides/slide$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>" } +
            "</Types>"
        files += "_rels/.rels" to "$XH<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"$rel/officeDocument\" Target=\"ppt/presentation.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/></Relationships>"
        files += "docProps/core.xml" to core(title)
        files += "ppt/presentation.xml" to "$XH<p:presentation $NS saveSubsetFonts=\"1\">" +
            "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>" +
            "<p:sldIdLst>" + (1..n).joinToString("") { "<p:sldId id=\"${255 + it}\" r:id=\"rId${it + 2}\"/>" } + "</p:sldIdLst>" +
            "<p:sldSz cx=\"$CX\" cy=\"$CY\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>"
        files += "ppt/_rels/presentation.xml.rels" to "$XH<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"$rel/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"$rel/theme\" Target=\"theme/theme1.xml\"/>" +
            (1..n).joinToString("") { "<Relationship Id=\"rId${it + 2}\" Type=\"$rel/slide\" Target=\"slides/slide$it.xml\"/>" } + "</Relationships>"
        files += "ppt/slideMasters/slideMaster1.xml" to "$XH<p:sldMaster $NS><p:cSld>" +
            "<p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"$BG\"/></a:solidFill><a:effectLst/></p:bgPr></p:bg><p:spTree>$GRP</p:spTree></p:cSld>" +
            "<p:clrMap bg1=\"dk1\" tx1=\"lt1\" bg2=\"dk2\" tx2=\"lt2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
            "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>" +
            "<p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz=\"3200\"/></a:lvl1pPr></p:titleStyle><p:bodyStyle><a:lvl1pPr><a:defRPr sz=\"2000\"/></a:lvl1pPr></p:bodyStyle>" +
            "<p:otherStyle><a:lvl1pPr><a:defRPr sz=\"1800\"/></a:lvl1pPr></p:otherStyle></p:txStyles></p:sldMaster>"
        files += "ppt/slideMasters/_rels/slideMaster1.xml.rels" to "$XH<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"$rel/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"$rel/theme\" Target=\"../theme/theme1.xml\"/></Relationships>"
        files += "ppt/slideLayouts/slideLayout1.xml" to "$XH<p:sldLayout $NS preserve=\"1\"><p:cSld name=\"Blank\"><p:spTree>$GRP</p:spTree></p:cSld>" +
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"
        files += "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to "$XH<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"$rel/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>"
        files += "ppt/theme/theme1.xml" to theme
        slides.forEachIndexed { i, s ->
            files += "ppt/slides/slide${i + 1}.xml" to slideXml(i, n, s)
            files += "ppt/slides/_rels/slide${i + 1}.xml.rels" to "$XH<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"$rel/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/></Relationships>"
        }
        return zip(files)
    }
}
