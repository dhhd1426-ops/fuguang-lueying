package com.fuguang.lueying

import android.content.Context
import android.net.Uri

/**
 * 内置插画（以前图库里的「内置插画」，图库已去掉；留着给已做好的作品现拼，不联网、CC0）：
 * - Open Doodles：33 张手绘小场景（assets/illust/doodles，原稿来自 lunahq/react-open-doodles），颜色是 ink（线）和 accent（色块）。
 * - Open Peeps：手绘头像，按 head / face / facial-hair / accessories 四层拼（assets/illust/peeps，原稿来自 jenshor/open-peeps），
 *   位置照它的 PeepAvatar：64×64、留 8 的边，四层各自的偏移；颜色是 ink（线）、paper（底色）、accent。
 * 地址是 https://work.fuguang.local/illust/doodle/<名>.svg?ink=…&accent=… 或 /illust/peep/<头>~<脸>~<胡子>~<饰品>.svg?…，
 * 作品打开时由 MaterialStore.serve 现拼现给。
 */
object Illust {
    private const val BASE = "https://${MaterialStore.HOST}/illust/"
    private var app: Context? = null
    private val cache = HashMap<String, String>()

    fun init(ctx: Context) { app = ctx.applicationContext }

    private fun atom(path: String): String? = synchronized(cache) {
        cache.getOrPut(path) { runCatching { app!!.assets.open("illust/$path.svg").bufferedReader().use { it.readText() } }.getOrNull() ?: return null }
    }

    private fun color(u: Uri, k: String, def: String) = u.getQueryParameter(k)?.trim()?.removePrefix("#")?.takeIf { it.matches(Regex("[0-9a-fA-F]{3,8}")) }?.let { "#$it" } ?: def

    /** 现拼一张：地址不对返回 null。 */
    fun render(u: Uri): String? {
        val seg = u.pathSegments  // illust / doodle|peep / 名.svg
        if (seg.size < 3 || seg[0] != "illust") return null
        val name = seg[2].removeSuffix(".svg")
        val ink = color(u, "ink", "#222222"); val accent = color(u, "accent", "#e9b48e"); val paper = color(u, "paper", "#ffffff")
        return when (seg[1]) {
            "doodle" -> atom("doodles/$name")?.replace("{{ink}}", ink)?.replace("{{accent}}", accent)
            "peep" -> {
                val p = name.split('~'); if (p.size != 4) return null
                val s = 48f / 567f
                val pos = listOf("head" to (5f to 2f), "face" to (18.5f to 17.75f), "facial-hair" to (15.25f to 30.5f), "accessories" to (9f to 22.5f))
                val body = StringBuilder()
                pos.forEachIndexed { i, (dir, xy) ->
                    if (p[i] == "none" && i >= 2) return@forEachIndexed
                    val t = atom("peeps/$dir/${p[i]}") ?: return null
                    val vb = t.substringBefore('\n'); val inner = t.substringAfter('\n')
                    val (vw, vh) = vb.split(' ').let { it[2].toFloat() to it[3].toFloat() }
                    val x = 8 + xy.first + vw * 48 * (1f / 473 - 1f / 567) / 2; val y = 8 + xy.second
                    body.append("<svg x=\"%.2f\" y=\"%.2f\" width=\"%.2f\" height=\"%.2f\" viewBox=\"%s\" overflow=\"visible\">".format(java.util.Locale.US, x, y, vw * s, vh * s, vb)).append(inner).append("</svg>")
                }
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\">" +
                    body.toString().replace("{{ink}}", ink).replace("{{paper}}", paper).replace("{{accent}}", accent) + "</svg>"
            }
            else -> null
        }
    }

    fun isIllust(u: String) = u.startsWith(BASE)
}
