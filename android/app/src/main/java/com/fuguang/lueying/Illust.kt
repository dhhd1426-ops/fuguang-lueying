package com.fuguang.lueying

import android.content.Context
import android.net.Uri

/**
 * 内置插画（图库里的「内置插画」，不联网、不要 Key、CC0）：
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

    /** 每张小场景的意思：英文名 + 中文词，找图时按这些对。 */
    private val TAGS = mapOf(
        "ballet" to "ballet dance 芭蕾 跳舞 优雅", "bikini" to "beach summer swim 海边 夏天 泳装 度假", "chilling" to "relax chill sofa 放松 休息 沙发 周末",
        "clumsy" to "clumsy fall mistake 摔倒 出错 尴尬 失误", "coffee" to "coffee cafe drink 咖啡 咖啡馆 喝 下午", "dancing" to "dance party music 跳舞 派对 音乐 开心",
        "dog-jump" to "dog pet play 狗 宠物 玩耍 跳", "doggie" to "dog pet walk 狗 宠物 遛狗", "float" to "float dream calm 漂浮 梦 放空 安静",
        "groovy" to "groovy music fun 音乐 律动 开心", "ice-cream" to "ice cream dessert summer 冰淇淋 甜品 夏天 甜", "jumping" to "jump happy celebrate 跳 开心 庆祝 成功",
        "laying" to "lay rest phone lazy 躺 休息 手机 懒", "levitate" to "levitate magic meditate 悬浮 魔法 冥想", "loving" to "love heart care 爱 喜欢 关心 心",
        "meditating" to "meditate yoga calm mindful 冥想 瑜伽 平静 正念", "moshing" to "rock concert music 摇滚 演唱会 音乐", "petting" to "pet cat dog care 宠物 撸猫 狗 陪伴",
        "plant" to "plant garden grow green 植物 盆栽 园艺 成长 绿", "reading" to "read book study 读书 看书 阅读 学习", "reading-side" to "read book study 读书 看书 阅读 学习 安静",
        "roller-skating" to "roller skate sport fun 轮滑 运动 滑", "rolling" to "roll play fun 打滚 玩", "running" to "run sport fitness 跑步 运动 健身 锻炼",
        "selfie" to "selfie photo phone social 自拍 拍照 手机 社交", "sitting" to "sit think wait 坐 思考 等待", "sitting-reading" to "read book sit study 读书 看书 阅读 学习 坐",
        "sleek" to "cool style fashion 酷 时尚 帅", "sprinting" to "sprint run fast hurry 冲刺 跑 快 赶时间", "strolling" to "walk stroll travel city 散步 走路 逛街 旅行",
        "swinging" to "swing play childhood 秋千 玩 童年", "unboxing" to "unbox package gift shopping 拆箱 快递 礼物 购物", "zombieing" to "zombie tired halloween 僵尸 累 万圣节 熬夜",
    )

    /** 头像的脸：情绪词。猜不出情绪时只从和善的里挑。 */
    private val FACES = mapOf(
        "smile" to "smile happy 微笑 开心", "smile-big" to "smile happy joy 大笑 开心", "smile-lol" to "laugh lol 大笑 哈哈", "smile-teeth-gap" to "smile 微笑 可爱",
        "cute" to "cute 可爱", "calm" to "calm 平静 安静", "cheeky" to "cheeky playful 调皮 俏皮", "loving-grin-1" to "love 爱 喜欢", "loving-grin-2" to "love 爱 喜欢",
        "explaining" to "explain talk teach 讲 讲解 老师 说话", "driven" to "driven focus work 专注 努力 工作", "eating-happy" to "eat food 吃 美食", "awe" to "awe wow surprise 惊讶 哇",
        "eyes-closed" to "sleep rest 睡 闭眼 休息", "serious" to "serious 严肃 认真", "solemn" to "solemn 庄重", "suspicious" to "suspicious 怀疑", "tired" to "tired 累 疲惫",
        "concerned" to "worry concerned 担心", "concerned-fear" to "fear 害怕", "fear" to "fear scared 害怕", "blank" to "blank 无语 发呆", "old" to "old elder 老人 长辈",
        "hectic" to "busy hectic 忙 慌", "contempt" to "contempt 不屑", "angry-with-fang" to "angry 生气", "very-angry" to "angry 生气 愤怒", "rage" to "rage 暴怒",
        "monster" to "monster 怪兽", "cyclops" to "cyclops 独眼",
    )
    private val KIND = listOf("smile", "smile-big", "cute", "calm", "cheeky", "loving-grin-1", "explaining", "driven", "eating-happy", "awe", "smile-teeth-gap", "eyes-closed", "serious")
    private val PEOPLE = Regex("人|头像|面孔|大家|团队|用户|成员|朋友|people|person|avatar|face|team|portrait|user|member|friend", RegexOption.IGNORE_CASE)

    private fun list(dir: String) = runCatching { app!!.assets.list("illust/$dir")!!.map { it.removeSuffix(".svg") } }.getOrDefault(emptyList())

    /** 找插画：小场景按词对；问到人、头像、团队，或者小场景不够数，再拼几个头像。 */
    fun search(q: String, n: Int): List<Pic> {
        val words = q.lowercase().split(Regex("[\\s,，、]+")).filter { it.isNotBlank() }
        fun score(tags: String) = words.count { w -> tags.contains(w) || tags.split(' ').any { t -> t.isNotEmpty() && w.contains(t) } }
        val scenes = TAGS.entries.map { it.key to score(it.value) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
            .filter { it in list("doodles") }.take(n)
        val out = scenes.map { doodle(it) }.toMutableList()
        if (PEOPLE.containsMatchIn(q) || FACES.values.any { score(it) > 0 }) {
            val moodFaces = FACES.entries.filter { score(it.value) > 0 }.map { it.key }
            val rnd = java.util.Random(q.hashCode().toLong())
            val heads = list("peeps/head"); val hair = list("peeps/facial-hair"); val acc = list("peeps/accessories")
            repeat((n - out.size).coerceAtLeast(4).coerceAtMost(n)) {
                val f = (moodFaces.ifEmpty { KIND }).let { it[rnd.nextInt(it.size)] }
                val h = heads[rnd.nextInt(heads.size)]
                val fh = if (rnd.nextInt(4) == 0) hair[rnd.nextInt(hair.size)] else "none"
                val a = if (rnd.nextInt(3) == 0) acc[rnd.nextInt(acc.size)] else "none"
                out += peep(h, f, fh, a)
            }
        }
        return out.take(n)
    }

    private fun doodle(name: String) = Pic(BASE + "doodle/$name.svg?ink=222222&accent=e9b48e", "插画", 1024, 768, "Open Doodles", "内置插画", "CC0", "https://www.opendoodles.com/", "")
    private fun peep(h: String, f: String, fh: String, a: String) = Pic(BASE + "peep/$h~$f~$fh~$a.svg?ink=222222&paper=ffffff&accent=e9b48e", "插画", 64, 64, "Open Peeps", "内置插画", "CC0", "https://www.openpeeps.com/", "")

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
