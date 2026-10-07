package com.fuguang.lueying

import android.content.Context
import android.content.SharedPreferences

/** 本机存储，键名与网站 localStorage 保持一致。 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("fuguang", Context.MODE_PRIVATE)

    var theme: String
        get() = sp.getString("fuguang-theme", "dark") ?: "dark"
        set(v) = sp.edit().putString("fuguang-theme", v).apply()

    var dim: Int
        get() = sp.getInt("fuguang-dim", 16)
        set(v) = sp.edit().putInt("fuguang-dim", v).apply()

    var blur: Int
        get() = sp.getInt("fuguang-blur", 12)
        set(v) = sp.edit().putInt("fuguang-blur", v).apply()

    var note: String
        get() = sp.getString("fuguang-note", "") ?: ""
        set(v) = sp.edit().putString("fuguang-note", v).apply()

    var favorites: Set<String>
        get() = sp.getStringSet("luoying-favorites", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("luoying-favorites", v).apply()

    var fontSize: Int
        get() = sp.getInt("luoying-font-size", 16).coerceIn(14, 22)
        set(v) = sp.edit().putInt("luoying-font-size", v).apply()

    var lastChapter: String?
        get() = sp.getString("luoying-last", null)
        set(v) = sp.edit().putString("luoying-last", v).apply()

    fun progress(id: String): Int = sp.getInt("luoying-progress-$id", 0)
    fun setProgress(id: String, scroll: Int) = sp.edit().putInt("luoying-progress-$id", scroll).apply()
}
