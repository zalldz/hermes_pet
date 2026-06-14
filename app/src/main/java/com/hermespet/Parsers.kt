package com.hermespet

import org.json.JSONObject

/**
 * Fungsi parsing MURNI (tanpa Android) supaya bisa di-unit-test.
 * Memisahkan balasan model JSON & ekstraksi teks streaming.
 */
object Parsers {

    fun reply(say: String?, emotion: String?): JSONObject =
        JSONObject().apply {
            say?.let { put("say", it) }
            emotion?.let { put("emotion", it) }
        }

    /** Ambil objek balasan {say,emotion,action(s),remember} pertama dari teks model. */
    fun extractJson(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val obj = JSONObject(text.substring(start, end + 1))
            if (!obj.has("say")) return null
            val r = reply(obj.optString("say").take(1500), obj.optString("emotion", "neutral"))
            obj.optJSONObject("action")?.let { r.put("action", it) }
            obj.optJSONArray("actions")?.let { r.put("actions", it) }     // multi-step
            obj.optJSONObject("remember")?.let { r.put("remember", it) }  // auto-memori
            r
        } catch (e: Exception) {
            null
        }
    }

    /** Ekstrak nilai "say" (bisa parsial) dari JSON yang masih mengalir (streaming). */
    fun extractPartialSay(s: String): String? {
        var i = s.indexOf("\"say\"")
        if (i < 0) return null
        i = s.indexOf(':', i)
        if (i < 0) return null
        i++
        while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\t')) i++
        if (i >= s.length || s[i] != '"') return null
        i++
        val out = StringBuilder()
        var j = i
        while (j < s.length) {
            val c = s[j]
            if (c == '\\' && j + 1 < s.length) {
                out.append(
                    when (s[j + 1]) {
                        'n' -> '\n'; 't' -> '\t'; '"' -> '"'; '\\' -> '\\'; else -> s[j + 1]
                    }
                )
                j += 2; continue
            }
            if (c == '"') break // tutup → say lengkap
            out.append(c); j++
        }
        return out.toString()
    }
}
