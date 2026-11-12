package com.fuguang.lueying

import android.os.SystemClock
import android.util.AtomicFile
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Execution records belong to the job, even after the reply's work card changes to a finished work. */
internal object ArtworkRuns {
    data class Entry(val id: Long, val version: Int, val status: String, val ms: Long = 0, val output: String = "", val kind: String = "render")

    private val active = mutableStateMapOf<String, List<Entry>>()
    fun live(key: String) = active[key]

    suspend fun load(key: String): List<Entry> = withContext(Dispatchers.IO) {
        val path = ArtworkJob.directory(key)?.let { File(it, "runs.json") } ?: return@withContext emptyList()
        runCatching {
            val json = JSONArray(AtomicFile(path).readFully().decodeToString())
            List(json.length()) { i ->
                val row = json.getJSONObject(i)
                Entry(row.getLong("id"), row.getInt("version"), row.getString("status"),
                    row.optLong("ms"), row.optString("output"), row.optString("kind", "render"))
            }.map { if (it.status == "running") it.copy(status = "stopped") else it }
        }.getOrDefault(emptyList())
    }

    suspend fun note(key: String, kind: String, text: String, version: Int = 0) {
        if (text.isBlank()) return
        val rows = active[key] ?: load(key)
        val entry = Entry((rows.lastOrNull()?.id ?: 0) + 1, version, "success", output = text, kind = kind)
        save(key, (rows + entry).takeLast(32))
    }

    suspend fun beginThought(key: String): Entry {
        val rows = active[key] ?: load(key)
        val entry = Entry((rows.lastOrNull()?.id ?: 0) + 1, 0, "running", kind = "thought")
        active[key] = (rows + entry).takeLast(32)
        return entry
    }

    suspend fun finishThought(key: String, entry: Entry, text: String) {
        val rows = active[key].orEmpty().mapNotNull {
            if (it.id != entry.id) it else text.takeIf(String::isNotBlank)?.let {
                entry.copy(status = "success", output = it)
            }
        }
        save(key, rows)
    }

    suspend fun begin(key: String, version: Int): Entry = withContext(NonCancellable) {
        val rows = active[key] ?: load(key)
        val entry = Entry((rows.lastOrNull()?.id ?: 0) + 1, version, "running")
        save(key, (rows + entry).takeLast(32))
        entry
    }

    suspend fun finish(key: String, entry: Entry, startedAt: Long, status: String, output: String = "") {
        withContext(NonCancellable) {
            val result = entry.copy(status = status, ms = SystemClock.elapsedRealtime() - startedAt, output = output.take(500))
            save(key, active[key].orEmpty().map { if (it.id == entry.id) result else it })
        }
    }

    private suspend fun save(key: String, rows: List<Entry>) {
        active[key] = rows
        withContext(Dispatchers.IO) {
            val folder = ArtworkJob.directory(key) ?: return@withContext
            val file = AtomicFile(File(folder, "runs.json"))
            runCatching {
                val json = JSONArray(rows.map {
                    JSONObject().put("id", it.id).put("version", it.version).put("status", it.status)
                        .put("ms", it.ms).put("output", it.output).put("kind", it.kind)
                })
                val stream = file.startWrite()
                try {
                    stream.write(json.toString().toByteArray())
                    file.finishWrite(stream)
                } catch (e: Exception) {
                    file.failWrite(stream)
                    throw e
                }
            }
        }
    }
}
