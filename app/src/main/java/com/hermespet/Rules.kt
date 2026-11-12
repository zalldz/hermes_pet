package com.hermespet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Otomasi "kalau X → lakukan Y" (rules engine). Disimpan di rules.json.
 *
 * Trigger: time{hour,minute,days?[1=Min..7=Sab]} | wifi_connect{ssid} |
 *   wifi_disconnect{ssid?} | battery_below{level} | battery_full |
 *   geo_enter / geo_exit{lat,lon,radius(m)} | app_open{package}
 * Then: aksi apa pun dari [Actions] + "notify"{text} / "brain"{text} (dijalankan service).
 *
 * Mesin berbasis TRANSISI: rule menyala sekali saat kondisinya baru berubah jadi benar,
 * tidak tiap menit selama kondisinya benar.
 */
object Rules {

    /** Kondisi HP saat ini (murni data → bisa di-unit-test). */
    data class State(
        val minuteOfDay: Int, val dayOfWeek: Int, val ssid: String?,
        val battery: Int, val charging: Boolean,
        val lat: Double?, val lon: Double?, val pkg: String?,
    )

    private fun file(ctx: Context) = File(ctx.filesDir, "rules.json")

    fun load(ctx: Context): JSONArray {
        val f = file(ctx)
        if (!f.exists()) { save(ctx, defaults()); return defaults() }
        return try { JSONArray(f.readText()) } catch (e: Exception) { JSONArray() }
    }

    private fun save(ctx: Context, rules: JSONArray) = file(ctx).writeText(rules.toString())

    /** Contoh bawaan: 23:00 → DND; baterai < 20% → hemat baterai. */
    fun defaults(): JSONArray = JSONArray()
        .put(JSONObject().put("name", "Tidur: DND jam 23").put("enabled", true)
            .put("trigger", JSONObject().put("type", "time").put("hour", 23).put("minute", 0))
            .put("then", JSONObject().put("type", "dnd").put("on", true)))
        .put(JSONObject().put("name", "Baterai lemah: hemat").put("enabled", true)
            .put("trigger", JSONObject().put("type", "battery_below").put("level", 20))
            .put("then", JSONObject().put("type", "battery_saver").put("on", true)))

    fun add(ctx: Context, name: String, trigger: JSONObject?, then: JSONObject?): String {
        if (name.isBlank() || trigger == null || then == null) return "Rule-nya belum lengkap 🤔"
        val all = load(ctx)
        val kept = JSONArray()
        for (i in 0 until all.length()) all.optJSONObject(i)
            ?.takeIf { it.optString("name") != name }?.let { kept.put(it) }
        kept.put(JSONObject().put("name", name).put("enabled", true)
            .put("trigger", trigger).put("then", then))
        save(ctx, kept)
        return "Otomasi \"$name\" kupasang ⚙️"
    }

    fun delete(ctx: Context, name: String): String {
        val all = load(ctx)
        val kept = JSONArray()
        var found = false
        for (i in 0 until all.length()) all.optJSONObject(i)?.let {
            if (it.optString("name").equals(name, true)) found = true else kept.put(it)
        }
        save(ctx, kept)
        return if (found) "Otomasi \"$name\" kuhapus 🗑" else "Otomasi \"$name\" tidak ada 🤔"
    }

    fun setEnabled(ctx: Context, name: String, on: Boolean) {
        val all = load(ctx)
        for (i in 0 until all.length()) all.optJSONObject(i)
            ?.takeIf { it.optString("name") == name }?.put("enabled", on)
        save(ctx, all)
    }

    /** Aksi "skill"/"mcp" dijalankan di homelab oleh otak (Claude) — HP hanya mengirim perintahnya. */
    fun homelabPrompt(a: JSONObject): String? = when (a.optString("type")) {
        "skill" -> "Otomasi menyala. Jalankan skill \"${a.optString("name")}\"" +
            a.optString("args").let { if (it.isBlank()) "" else " dengan: $it" } + ". Lapor singkat hasilnya."
        "mcp" -> "Otomasi menyala. Panggil tool MCP \"${a.optString("tool")}\"" +
            a.optString("args").let { if (it.isBlank()) "" else " dengan argumen: $it" } + ". Lapor singkat hasilnya."
        else -> null
    }

    fun list(ctx: Context): List<JSONObject> {
        val all = load(ctx)
        return (0 until all.length()).mapNotNull { all.optJSONObject(it) }
    }

    fun describe(r: JSONObject): String =
        "${r.optString("name")}: kalau ${r.optJSONObject("trigger")} → ${r.optJSONObject("then")}"

    fun listText(ctx: Context): String =
        list(ctx).joinToString("\n") { describe(it) }.ifBlank { "(belum ada otomasi)" }

    /** Aksi "then" dari rule yang baru menyala antara keadaan prev → cur. */
    fun evaluate(ctx: Context, prev: State?, cur: State): List<JSONObject> =
        list(ctx).filter { it.optBoolean("enabled", true) }
            .filter { fires(it.optJSONObject("trigger") ?: JSONObject(), prev, cur) }
            .mapNotNull { it.optJSONObject("then") }

    /** Logika transisi trigger — MURNI, di-unit-test. */
    fun fires(t: JSONObject, prev: State?, cur: State): Boolean {
        prev ?: return false // perlu keadaan sebelumnya untuk mendeteksi perubahan
        return when (t.optString("type")) {
            "time" -> {
                val at = t.optInt("hour", -1) * 60 + t.optInt("minute", 0)
                val days = t.optJSONArray("days")
                val dayOk = days == null || (0 until days.length()).any { days.optInt(it) == cur.dayOfWeek }
                // menyeberangi menit target (tahan jeda tick, tidak menyala ulang)
                val p = if (cur.minuteOfDay < prev.minuteOfDay) -1 else prev.minuteOfDay // lewat tengah malam
                dayOk && at >= 0 && p < at && cur.minuteOfDay >= at
            }
            "wifi_connect" -> cur.ssid != prev.ssid && cur.ssid != null &&
                cur.ssid.equals(t.optString("ssid"), ignoreCase = true)
            "wifi_disconnect" -> prev.ssid != null && cur.ssid != prev.ssid &&
                t.optString("ssid").let { it.isEmpty() || it.equals(prev.ssid, ignoreCase = true) }
            "battery_below" -> t.optInt("level", 20).let { prev.battery >= it && cur.battery in 0 until it }
            "battery_full" -> prev.battery < 100 && cur.battery >= 100 && cur.charging
            "geo_enter", "geo_exit" -> {
                val r = t.optDouble("radius", 200.0)
                val lat = t.optDouble("lat"); val lon = t.optDouble("lon")
                if (cur.lat == null || cur.lon == null || prev.lat == null || prev.lon == null) false
                else {
                    val was = meters(prev.lat, prev.lon, lat, lon) <= r
                    val now = meters(cur.lat, cur.lon, lat, lon) <= r
                    if (t.optString("type") == "geo_enter") !was && now else was && !now
                }
            }
            "app_open" -> cur.pkg != null && cur.pkg != prev.pkg &&
                cur.pkg.equals(t.optString("package"), ignoreCase = true)
            else -> false
        }
    }

    /** Jarak haversine (meter). */
    fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }
}
