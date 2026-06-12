package com.hermespet

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Catatan kegiatan pet + riwayat chat, disimpan lokal sebagai JSON-lines
 * di penyimpanan privat aplikasi. Dipangkas otomatis supaya tidak membengkak.
 */
object PetLog {
    private const val MAX_LINES = 600
    private const val KEEP_LINES = 400

    data class Entry(val ts: Long, val kind: String, val text: String)

    @Synchronized
    fun add(ctx: Context, kind: String, text: String) {
        try {
            val f = file(ctx)
            f.appendText(
                JSONObject()
                    .put("ts", System.currentTimeMillis())
                    .put("kind", kind)
                    .put("text", text)
                    .toString() + "\n"
            )
            val lines = f.readLines()
            if (lines.size > MAX_LINES) {
                f.writeText(lines.takeLast(KEEP_LINES).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun read(ctx: Context): List<Entry> = try {
        file(ctx).readLines().mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                Entry(o.getLong("ts"), o.getString("kind"), o.getString("text"))
            }.getOrNull()
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "petlog.jsonl")
}
