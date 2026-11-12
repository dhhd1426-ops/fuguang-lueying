package com.fuguang.lueying

import org.json.JSONArray

/** 旧聊天里还没换成作品 ID 的卡片，从归档里找回已生成的作品。 */
internal object ArchivedTeamWorks {
    private var completed = emptyMap<String, String>()

    fun load(prefs: Prefs) {
        completed = runCatching {
            val file = prefs.file("teams.json")
            if (!file.exists()) return@runCatching emptyMap()
            val records = JSONArray(file.readText())
            buildMap {
                for (index in 0 until records.length()) {
                    val record = records.optJSONObject(index) ?: continue
                    val key = record.optString("k")
                    val work = record.optString("d")
                    if (key.isNotBlank() && work.isNotBlank()) put(key, work)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun resolve(ref: String): String? =
        if (ref.startsWith("team:")) completed[ref.removePrefix("team:").substringBefore('|')]
        else ref
}
