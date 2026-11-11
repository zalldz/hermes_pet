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
            obj.optJSONObject("action")?.let { r.put("action", normalizeAction(it)) }
            obj.optJSONArray("actions")?.let { arr ->                      // multi-step
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { normalizeAction(it) }
                r.put("actions", arr)
            }
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

    /** Nama alias yang sering dipakai model → nama aksi resmi (in-place). */
    private val ALIASES = mapOf(
        "read_screen" to "screen_read", "tap" to "screen_tap", "click" to "screen_tap",
        "type" to "screen_type", "type_text" to "screen_type", "scroll" to "screen_scroll",
        "tap_text" to "find_and_tap", "click_text" to "find_and_tap",
    )
    private val KEYS = setOf("back", "home", "recents", "notifications")

    fun normalizeAction(a: JSONObject): JSONObject {
        // model kadang memakai "tool"/"name" untuk jenis aksi
        if (!a.has("type")) (a.optString("tool").ifEmpty { null })?.let { a.put("type", it) }
        val t = a.optString("type")
        when {
            t in KEYS -> { a.put("type", "press"); a.put("key", t) }
            t in ALIASES -> a.put("type", ALIASES.getValue(t))
        }
        if (a.optString("type") == "find_and_tap" && !a.has("label"))
            a.put("label", a.optString("text", a.optString("name")))
        return a
    }

    private val RISKY = Regex(
        "(?i)(kirim|send|bayar|pay|beli|buy|hapus|delete|transfer|checkout|konfirmasi|" +
            "confirm|posting|post|unggah|upload|uninstall|copot|panggil|call)")

    /** Label tombol yang efeknya sulit dibatalkan → wajib konfirmasi pemilik. */
    fun isRiskyLabel(label: String): Boolean = RISKY.containsMatchIn(label)
}
