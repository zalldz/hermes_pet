package com.hermespet

import org.json.JSONObject

/**
 * Perintah sederhana yang dijalankan LANGSUNG di HP tanpa otak/AI (cepat, offline).
 * Mengembalikan null kalau bukan perintah yang dikenali → lanjut ke Brain.
 * shortcut: regex kata kunci, bukan pemahaman bahasa; tambah pola kalau ada kalimat yang meleset.
 */
object Local {
    private fun a(type: String, vararg kv: Pair<String, Any>) =
        JSONObject().put("type", type).apply { kv.forEach { put(it.first, it.second) } }.put("_ok", true)

    private val ON = Regex("nyala|hidup|aktif|\\bon\\b")
    private val OFF = Regex("mati|\\boff\\b|tutup")
    private val SEC = Regex("(\\d+)\\s*(detik|dtk|s\\b)")

    fun parse(raw: String): List<JSONObject>? {
        val t = raw.lowercase().trim().trimEnd('.', '!', '?')
        if (t.length > 60) return null // kalimat panjang → biar otak yang paham
        val sec = SEC.find(t)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 600)
        val front = Regex("depan|selfie").containsMatchIn(t)
        return when {
            Regex("^(stop|berhenti|selesai)\\b.*(rekam|video|suara)").containsMatchIn(t) ->
                listOf(a("capture_stop"))
            "senter" in t || "flash" in t || "lampu" in t && "hp" in t ->
                listOf(a("torch", "on" to !(OFF.containsMatchIn(t) && !ON.containsMatchIn(t))))
            Regex("rekam\\s+(suara|audio)|voice note").containsMatchIn(t) ->
                listOf(a("capture_audio", "seconds" to (sec ?: 0)))
            Regex("(rekam|ambil|buat|shoot)\\s+(sebuah\\s+)?video").containsMatchIn(t) ->
                listOf(a("capture_video", "seconds" to (sec ?: 0), "front" to front))
            Regex("^(tolong\\s+)?(ambil|ambilkan|jepret|foto)\\b.*(foto|gambar|selfie|kamera)|^(foto|selfie)\\b")
                .containsMatchIn(t) && "layar" !in t ->
                listOf(a("capture_photo", "front" to front))
            t == "mute" || t == "bisukan" || t == "senyap" -> listOf(a("mute"))
            Regex("^volume\\s+(naik|tambah)").containsMatchIn(t) -> listOf(a("volume_up"))
            Regex("^volume\\s+(turun|kurang)").containsMatchIn(t) -> listOf(a("volume_down"))
            Regex("^volume\\s+(\\d{1,3})$").find(t) != null ->
                listOf(a("volume", "level" to Regex("\\d+").find(t)!!.value.toInt().coerceIn(0, 100)))
            else -> Regex("^(tolong\\s+)?(buka|bukakan|jalankan|open)\\s+(aplikasi\\s+|app\\s+)?([\\p{L}\\d ]{2,30})$")
                .find(t)?.groupValues?.get(4)?.trim()
                ?.takeIf { it !in setOf("kunci", "layar", "hp") }
                ?.let { listOf(a("open_app", "name" to it)) }
        }
    }
}
