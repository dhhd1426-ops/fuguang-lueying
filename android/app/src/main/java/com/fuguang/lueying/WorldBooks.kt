package com.fuguang.lueying

import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * 世界书（照 Kelivo 2026-11-19 192a6a8：core/models/world_book.dart、core/services/world_book_activation.dart、
 * message_builder_service.injectWorldBookPrompts）。放在全局设置里，每位助理勾自己用哪几本（AsstExt.worldBooks），
 * 也能只对当前对话勾（ConvWorldBooks）。
 */
enum class WbPosition(val wire: String, val label: String) {
    BEFORE_SYSTEM("BEFORE_SYSTEM_PROMPT", "系统提示前"),
    AFTER_SYSTEM("AFTER_SYSTEM_PROMPT", "系统提示后"),
    TOP("TOP_OF_CHAT", "对话顶部"),
    BOTTOM("BOTTOM_OF_CHAT", "对话底部"),
    DEPTH("AT_DEPTH", "指定深度");
    companion object { fun of(v: String?) = entries.firstOrNull { it.wire == v?.trim()?.uppercase() } ?: AFTER_SYSTEM }
}

data class WbEntry(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    val priority: Int = 0,
    val position: WbPosition = WbPosition.AFTER_SYSTEM,
    val content: String = "",
    val injectDepth: Int = 4,
    /** "user" / "assistant" */
    val role: String = "user",
    val keywords: List<String> = emptyList(),
    val useRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    val scanDepth: Int = 4,
    val constantActive: Boolean = false,
    val sticky: Int = 0,
    val cooldown: Int = 0,
    val delay: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("enabled", enabled).put("priority", priority)
        .put("position", position.wire).put("content", content).put("injectDepth", injectDepth).put("role", role.uppercase())
        .put("keywords", JSONArray(keywords)).put("useRegex", useRegex).put("caseSensitive", caseSensitive).put("scanDepth", scanDepth)
        .put("constantActive", constantActive).put("sticky", sticky).put("cooldown", cooldown).put("delay", delay)

    companion object {
        fun newId() = "${System.currentTimeMillis()}-${(Math.random() * 1e6).toInt()}"
        fun fromJson(o: JSONObject): WbEntry {
            val kw = o.optJSONArray("keywords")?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } } ?: emptyList()
            return WbEntry(
                o.optString("id").ifBlank { newId() }, o.optString("name"), o.optBoolean("enabled", true), o.optInt("priority"),
                WbPosition.of(o.optString("position")), o.optString("content"), o.optInt("injectDepth", 4),
                if (o.optString("role").equals("ASSISTANT", true)) "assistant" else "user", kw,
                o.optBoolean("useRegex"), o.optBoolean("caseSensitive"), o.optInt("scanDepth", 4), o.optBoolean("constantActive"),
                o.optInt("sticky").coerceIn(0, 10000), o.optInt("cooldown").coerceIn(0, 10000), o.optInt("delay").coerceIn(0, 10000),
            )
        }
    }
}

data class WorldBook(val id: String, val name: String = "", val description: String = "", val enabled: Boolean = true, val entries: List<WbEntry> = emptyList()) {
    val enabledCount get() = entries.count { it.enabled }
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("description", description).put("enabled", enabled)
        .put("entries", JSONArray(entries.map { it.toJson() }))
    companion object {
        fun fromJson(o: JSONObject) = WorldBook(
            o.optString("id").ifBlank { WbEntry.newId() }, o.optString("name"), o.optString("description"), o.optBoolean("enabled", true),
            o.optJSONArray("entries")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map(WbEntry::fromJson) } ?: emptyList(),
        )
    }
}

object WorldBooks {
    private const val KEY = "world-books"
    private const val CONV_KEY = "world-books-conv"
    private const val STATE_KEY = "world-books-state"
    private var prefs: Prefs? = null
    val books = mutableStateListOf<WorldBook>()
    /** 对话里「仅当前对话」勾的世界书：对话 id → 书 id 列表。 */
    private val conv = HashMap<String, List<String>>()
    /** 粘滞 / 冷却的计时：对话 id → {messageCount, historyHash, effects}。 */
    private val state = HashMap<String, JSONObject>()

    fun init(p: Prefs) {
        prefs = p
        runCatching { val a = JSONArray(p.string(KEY) ?: "[]"); books.clear(); for (i in 0 until a.length()) books += WorldBook.fromJson(a.getJSONObject(i)) }
        runCatching { val o = JSONObject(p.string(CONV_KEY) ?: "{}"); o.keys().forEach { k -> val a = o.getJSONArray(k); conv[k] = (0 until a.length()).map { a.getString(it) } } }
        runCatching { val o = JSONObject(p.string(STATE_KEY) ?: "{}"); o.keys().forEach { k -> state[k] = o.getJSONObject(k) } }
        migrate()
    }

    fun save() { prefs?.putString(KEY, JSONArray(books.map { it.toJson() }).toString()) }
    private fun saveConv() { prefs?.putString(CONV_KEY, JSONObject(conv.mapValues { JSONArray(it.value) }).toString()) }
    private fun saveState() { prefs?.putString(STATE_KEY, JSONObject(state.toMap()).toString()) }

    fun get(id: String) = books.firstOrNull { it.id == id }
    fun put(b: WorldBook) { val i = books.indexOfFirst { it.id == b.id }; if (i >= 0) books[i] = b else books += b; save() }
    fun remove(id: String) { books.removeAll { it.id == id }; save() }

    /** 这个对话有没有自己勾的（「仅当前对话」）；null = 跟着助理。 */
    fun convPick(convId: String): List<String>? = conv[convId]
    fun setConvPick(convId: String, ids: List<String>?) { if (ids == null) conv.remove(convId) else conv[convId] = ids; saveConv() }

    /** 这一轮实际用哪几本：对话自己勾了就用对话的，否则用助理的。 */
    fun active(p: Persona, convId: String?, allowConv: Boolean = true): List<WorldBook> {
        val ids = convId?.takeIf { allowConv }?.let { conv[it] } ?: AssistantExt.of(p).worldBooks
        return ids.mapNotNull { get(it) }.filter { it.enabled }
    }

    /** 原来每位助理的「条目」（Entries.kt）搬成一本世界书，勾在她名下；只搬一次。 */
    private fun migrate() {
        val done = prefs?.string("world-books-migrated") == "1"
        if (done) return
        Memory.all.forEach { p ->
            if (p.entries.isEmpty()) return@forEach
            val book = WorldBook(
                "entries-${p.id}", "${p.name}的条目", "从助理设置里的「条目」搬过来的", true,
                p.entries.map { e ->
                    val pos = when (e.pos) { "before" -> WbPosition.BEFORE_SYSTEM; "after" -> WbPosition.AFTER_SYSTEM; "top" -> WbPosition.TOP; else -> WbPosition.DEPTH }
                    WbEntry(
                        "${e.id}-wb", e.name, e.on, 0, pos,
                        if (e.voice == "self") "（你心里想：${e.body.trim()}）" else e.body.trim(),
                        e.depth, "user", e.keyList, false, false, 4, e.always,
                    )
                },
            )
            if (get(book.id) == null) books += book
            val x = AssistantExt.of(p)
            if (book.id !in x.worldBooks) x.worldBooks += book.id
        }
        save(); AssistantExt.save()
        prefs?.putString("world-books-migrated", "1")
    }

    // ───────── 触发（WorldBookActivation.evaluate） ─────────

    private fun hash(v: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(v.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun fingerprint(history: List<ChatMsg>, n: Int) = hash(history.take(n).joinToString("\u0000") { it.role + "\u0001" + it.text })

    fun matches(e: WbEntry, context: String): Boolean {
        if (!e.enabled) return false
        if (e.constantActive) return true
        for (raw in e.keywords) {
            val k = raw.trim()
            if (k.isEmpty()) continue
            if (e.useRegex) {
                val hit = runCatching { Regex(k, if (e.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)).containsMatchIn(context) }.getOrDefault(false)
                if (hit) return true
            } else if (if (e.caseSensitive) context.contains(k) else context.lowercase().contains(k.lowercase())) return true
        }
        return false
    }

    /** 这一轮触发了哪些条目（按优先级从高到低，同级按书和条目的顺序）；粘滞、冷却、延迟按消息条数算。 */
    fun evaluate(books: List<WorldBook>, history: List<ChatMsg>, convId: String?): List<WbEntry> {
        val count = history.size
        val prev = convId?.let { state[it] } ?: JSONObject()
        val prevCount = prev.optInt("messageCount", 0)
        val continues = prev.optString("historyHash").isNotEmpty() && prevCount in 0..count && prev.optString("historyHash") == fingerprint(history, prevCount)
        val oldEffects = if (continues) prev.optJSONObject("effects") ?: JSONObject() else JSONObject()
        val effects = JSONObject()
        val triggered = mutableListOf<Pair<WbEntry, Int>>()
        val ctxCache = HashMap<Int, String>()
        fun contextFor(depth: Int) = ctxCache.getOrPut(depth) {
            history.filter { it.role == "user" || it.role == "assistant" }.map { it.text.trim() }.filter { it.isNotEmpty() }.takeLast(depth).joinToString("\n")
        }
        var seq = 0
        for (book in books) {
            if (!book.enabled) continue
            for (e in book.entries) {
                val order = seq++
                if (!e.enabled || e.content.isBlank()) continue
                val key = JSONArray(listOf(book.id, e.id)).toString()
                val sig = if (e.sticky > 0 || e.cooldown > 0) hash(e.toJson().toString()) else ""
                val effect = oldEffects.optJSONObject(key)?.takeIf { it.optString("signature") == sig }
                var active = false
                val at = effect?.optInt("activatedAt", -1)?.takeIf { it >= 0 }
                if (at != null && at <= count) {
                    val elapsed = count - at
                    if (elapsed <= e.sticky) { active = true; effects.put(key, effect) }
                    else if (elapsed <= e.sticky + e.cooldown) { effects.put(key, effect); continue }
                }
                if (!active) {
                    if (count < e.delay) continue
                    active = matches(e, contextFor(e.scanDepth.coerceIn(1, 200)))
                    if (active && (e.sticky > 0 || e.cooldown > 0)) effects.put(key, JSONObject().put("signature", sig).put("activatedAt", count))
                }
                if (active) triggered += e to order
            }
        }
        if (convId != null) {
            state[convId] = JSONObject().put("messageCount", count).put("historyHash", if (effects.length() == 0) "" else fingerprint(history, count)).put("effects", effects)
            saveState()
        }
        return triggered.sortedWith(compareByDescending<Pair<WbEntry, Int>> { it.first.priority }.thenBy { it.second }).map { it.first }
    }

    // ───────── 放进去（injectWorldBookPrompts） ─────────

    class Injected(val system: String, val history: List<ChatMsg>)

    private fun join(items: List<WbEntry>) = items.map { it.content.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    /** 用户角色的条目包在 <system> 里（Kelivo wrapSystemTag），助手角色原样。同一处按角色合成一条。 */
    private fun merged(items: List<WbEntry>, at: Long): List<ChatMsg> =
        items.groupBy { it.role }.mapNotNull { (role, group) ->
            val text = join(group).ifEmpty { return@mapNotNull null }
            if (role == "assistant") ChatMsg("assistant", text, at = at) else ChatMsg("user", "<system>\n$text\n</system>", at = at)
        }

    /** allowConv：助理开着「独立对话指令注入」时，对话里自己勾的世界书才算数。 */
    fun inject(p: Persona, convId: String?, system: String, history: List<ChatMsg>, allowConv: Boolean = false): Injected {
        val books = active(p, convId, allowConv)
        if (books.isEmpty() || history.isEmpty()) return Injected(system, history)
        val hit = evaluate(books, history, convId)
        if (hit.isEmpty()) return Injected(system, history)
        val by = hit.groupBy { it.position }
        var sys = system
        join(by[WbPosition.BEFORE_SYSTEM].orEmpty()).takeIf { it.isNotEmpty() }?.let { sys = if (sys.isEmpty()) it else it + "\n" + sys }
        join(by[WbPosition.AFTER_SYSTEM].orEmpty()).takeIf { it.isNotEmpty() }?.let { sys = if (sys.isEmpty()) it else sys + "\n" + it }
        val out = history.toMutableList()
        val at = history.last().at
        by[WbPosition.TOP]?.let { top ->
            val i = out.indexOfFirst { it.role == "user" }.let { if (it < 0) out.size else it }
            out.addAll(i, merged(top, at))
        }
        by[WbPosition.BOTTOM]?.let { bottom -> out.addAll((out.size - 1).coerceAtLeast(0), merged(bottom, at)) }
        by[WbPosition.DEPTH]?.let { depthItems ->
            val original = out.size
            var inserted = 0
            depthItems.groupBy { it.injectDepth }.toSortedMap(compareByDescending { it }).forEach { (depth, items) ->
                val idx = (original - depth).coerceIn(0, original) + inserted
                val msgs = merged(items, at)
                out.addAll(idx, msgs); inserted += msgs.size
            }
        }
        return Injected(sys, out)
    }

    // ───────── 导入 / 导出（Kelivo 格式，兼容 SillyTavern 的 entries 对象） ─────────

    fun export(b: WorldBook): String = b.toJson().put("format", "kelivo-world-book").toString(2)

    /** 读 Kelivo 世界书 JSON，或 SillyTavern 的 { entries: { "0": {key, content, comment, constant, position, depth, ...} } }。 */
    fun import(raw: String, fallbackName: String): WorldBook? {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val entries = o.opt("entries")
        if (entries is JSONArray) return WorldBook.fromJson(o).copy(id = WbEntry.newId())
        if (entries is JSONObject) {
            val list = entries.keys().asSequence().mapNotNull { entries.optJSONObject(it) }.map { e ->
                val keys = e.optJSONArray("key")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                val pos = when (e.optInt("position", 1)) { 0 -> WbPosition.BEFORE_SYSTEM; 1 -> WbPosition.AFTER_SYSTEM; 4 -> WbPosition.DEPTH; else -> WbPosition.AFTER_SYSTEM }
                WbEntry(
                    WbEntry.newId(), e.optString("comment"), !e.optBoolean("disable"), e.optInt("order"), pos, e.optString("content"),
                    e.optInt("depth", 4), if (e.optInt("role") == 2) "assistant" else "user", keys.map { it.trim() }.filter { it.isNotEmpty() },
                    false, e.optBoolean("caseSensitive"), e.optInt("scanDepth", 4).let { if (it <= 0) 4 else it }, e.optBoolean("constant"),
                    e.optInt("sticky"), e.optInt("cooldown"), e.optInt("delay"),
                )
            }.toList()
            return WorldBook(WbEntry.newId(), o.optString("name").ifBlank { fallbackName }, o.optString("description"), true, list)
        }
        return null
    }
}
