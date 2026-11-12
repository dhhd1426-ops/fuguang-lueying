package com.fuguang.lueying

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Drafts stay outside the works shelf until a rendered revision is selected. */
internal object ArtworkJob {
    private const val WRITE = "write_draft"
    private const val READ = "read_draft"
    private const val RENDER = "render_preview"
    private const val PRESENT = "present_artwork"
    private const val MAX_SOURCE = 100_000
    private const val MAX_DRAFTS = 4

    data class Model(val provider: Provider, val config: ProviderConfig, val name: String, val effort: String?)

    private lateinit var context: Context
    private lateinit var root: File

    fun init(ctx: Context) {
        context = ctx.applicationContext
        root = File(context.filesDir, "artwork-drafts").apply { mkdirs() }
    }

    private fun directory(key: String) =
        key.takeIf { it.length <= 32 && it.matches(Regex("[a-z0-9]+")) }?.let { File(root, it) }

    fun latestPreview(key: String): File? = directory(key)?.let { dir ->
        (MAX_DRAFTS downTo 1).map { File(dir, "$it.png") }.firstOrNull { it.isFile }
    }

    private fun spec(name: String, description: String, vararg fields: Pair<String, String>) =
        ToolSpec(name, description, JSONObject().put("type", "object")
            .put("properties", JSONObject().apply {
                fields.forEach { (field, type) -> put(field, JSONObject().put("type", type)) }
            }).put("required", JSONArray(fields.map { it.first })))

    private val tools = listOf(
        spec(WRITE, "保存完整静态 SVG 原稿。首稿 base=0，修改时 base=所依据的版本。" +
            "SVG 必须有 xmlns 和 viewBox，素材内嵌。width/height 是导出像素尺寸，每边 64–1600。",
            "base" to "integer", "svg" to "string", "width" to "integer", "height" to "integer"),
        spec(READ, "读指定稿件的 SVG 原稿。", "version" to "integer"),
        spec(RENDER, "渲染指定稿件。结果包含实际画面或错误。看完图再决定怎么改。", "version" to "integer"),
        spec(PRESENT, "交付已经渲染的版本。已看过几版时选效果最好的一版。", "version" to "integer"),
    )

    suspend fun run(
        key: String, title: String, editId: String?, persona: String, quick: Boolean, author: Model, reviewer: Model?,
        request: ChatMsg?, brief: String, onStage: (String, File?) -> Unit, onEffort: (String?) -> Unit,
        onMade: (String) -> Unit,
    ): String? {
        val dir = checkNotNull(directory(key))
        val revisions = withContext(Dispatchers.IO) {
            check(dir.isDirectory || dir.mkdirs()) { "无法保存草稿" }
            (1..MAX_DRAFTS).filter { File(dir, "$it.json").isFile }.toMutableList()
        }
        val vision = Attach.vision(author.provider.id, author.name)
        val reviewed = mutableSetOf<Int>()
        var presented = false
        var failedRenders = 0
        val reviewLimit = if (quick) 1 else 3

        fun preview(version: Int) = File(dir, "$version.png")
        suspend fun draft(version: Int): JSONObject? = withContext(Dispatchers.IO) {
            if (version !in revisions) null else JSONObject(File(dir, "$version.json").readText())
        }

        suspend fun use(name: String, args: JSONObject): ToolOut {
            if (presented) return ToolOut("作品已交付。")
            val version = args.optInt("version", -1)
            return when (name) {
                WRITE -> {
                    val base = args.optInt("base", -1)
                    val svg = args.optString("svg").trim()
                    val width = args.optInt("width")
                    val height = args.optInt("height")
                    if (revisions.size >= MAX_DRAFTS) return ToolOut("草稿预算已用完，选择一张已渲染稿交付。")
                    if (base !in revisions && !(revisions.isEmpty() && base == 0)) return ToolOut("请指定已有的 base 版本。")
                    if (svg.length !in 1..MAX_SOURCE || width !in 64..1600 || height !in 64..1600)
                        return ToolOut("请提交完整 SVG（最多 100000 字符），宽高各为 64–1600 像素。")
                    val problem = withContext(Dispatchers.IO) { ArtworkPreview.validate(svg) }
                    if (problem != null) return ToolOut(problem)
                    val next = (revisions.lastOrNull() ?: 0) + 1
                    val data = JSONObject().put("svg", svg).put("width", width).put("height", height)
                    withContext(Dispatchers.IO) { AtomicFile(File(dir, "$next.json")).write(data.toString().toByteArray()) }
                    revisions += next
                    onStage("已保存第 ${next} 稿", null)
                    ToolOut("已保存第 ${next} 稿。调用 $RENDER，version=$next。")
                }
                READ -> draft(version)?.let { ToolOut("第 ${version} 稿：\n" + it.getString("svg")) }
                    ?: ToolOut("没有这个版本。")
                RENDER -> {
                    val data = draft(version) ?: return ToolOut("没有这个版本。")
                    if (version in reviewed) return ToolOut("第 ${version} 稿的预览已经回传，可直接根据上次结果修改或交付。")
                    if (reviewed.size >= reviewLimit || failedRenders >= 2) return ToolOut("渲染预算已用完，请选择已检查的稿件。")
                    onStage("正在渲染第 ${version} 稿", null)
                    val file = preview(version)
                    val bytes = try {
                        if (file.isFile) withContext(Dispatchers.IO) { file.readBytes() } else {
                            val png = ArtworkPreview.render(context, data.getString("svg"), data.getInt("width"), data.getInt("height"))
                            withContext(Dispatchers.IO) { AtomicFile(file).write(png) }
                            png
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        failedRenders++
                        return ToolOut("第 ${version} 稿渲染失败：${e.message ?: "未能生成 PNG"}。请修复后写入新版本。")
                    }
                    onStage(if (quick) "第 ${version} 稿已渲染" else "正在检查第 ${version} 稿", file)
                    if (!quick && !vision && reviewer == null) return ToolOut("第 ${version} 稿已渲染。没有可用的看图模型；这份预览会保留给用户。")
                    if (!quick && !vision && reviewer != null) {
                        val note = try {
                            AttachLook.describe(reviewer.provider, reviewer.config, reviewer.name, bytes, "image/png",
                                "按原始要求检查这张静态插画。只提出 1–3 条具体的画面问题、位置、修改方向和应保留的部分；没有明显问题就说明可交付。\n" + brief.take(3000))
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { return ToolOut("渲染成功，但看图请求失败。可重试或保留预览。") }
                        reviewed += version
                        return ToolOut("第 ${version} 稿视觉检查（${reviewer.name}）：\n$note")
                    }
                    reviewed += version
                    val image = if (quick) emptyList() else listOf(ToolImage("image/png", Base64.encodeToString(bytes, Base64.NO_WRAP)))
                    ToolOut("第 ${version} 稿渲染成功。" +
                        if (quick) "可直接交付。" else "这条工具结果的图片就是该版本，请看图后再决定。", image)
                }
                PRESENT -> {
                    val data = draft(version) ?: return ToolOut("没有这个版本。")
                    val file = preview(version)
                    if (version !in reviewed || !file.isFile) return ToolOut("请先完成这个版本要求的渲染和检查。")
                    onStage("正在交付第 ${version} 稿", file)
                    val html = ArtworkPreview.page(data.getString("svg"))
                    val result = Works.saveArtwork(title, editId, html, file, onMade)
                    presented = result
                    ToolOut(if (result) "已交付第 ${version} 稿。" else "保存成品失败，预览已保留。")
                }
                else -> ToolOut("没有这个工具。")
            }
        }

        val prior = revisions.joinToString("、").ifEmpty { "无" }
        val input = request?.copy(text = "${request.text}\n\n$brief\n\n可继续的已保存稿件：$prior")
            ?: ChatMsg("user", "$brief\n\n可继续的已保存稿件：$prior")
        val instructions = "你在 Vignette 里绘制静态艺术作品。先决定构图和绘法，用 $WRITE 保存 SVG，" +
            "用 $RENDER 检查实际画面。必要时修改源码、再渲染。选出最好的一版后必须调用 $PRESENT。" +
            "用户的原话和参考图优先。不要把源码贴进正文。全部画面要在 viewBox 内，不使用脚本、远程资源或 HTML。" +
            if (quick) "本次快速模式，完成一次渲染即可交付。" else "本次精修模式。对照用户要求看图，修正实质问题，最多两轮视觉返工。"
        ChatApi.stream(author.provider, author.config, author.name, instructions, listOf(input), author.effort,
            onThink = {}, tools = Tools(tools, ::use, onUse = {}, onEffort = onEffort, finished = { presented }), onText = {})
        return when {
            presented -> null
            !quick && !vision && reviewer == null -> "预览已保留，缺少可用的看图模型"
            else -> "这轮未选定交付稿，可继续修改"
        }
    }

    private fun AtomicFile.write(bytes: ByteArray) {
        val stream = startWrite()
        try {
            stream.write(bytes)
            finishWrite(stream)
        } catch (e: Exception) {
            failWrite(stream)
            throw e
        }
    }
}
