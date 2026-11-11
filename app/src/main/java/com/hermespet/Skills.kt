package com.hermespet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Skill buatan pet sendiri: makro bernama berisi urutan aksi (Actions).
 * Otak bisa membuat skill baru lewat action "save_skill" lalu memakainya
 * dengan "use_skill" — pet memperluas kemampuannya sendiri, tersimpan permanen.
 */
object Skills {
    private const val MAX_SKILLS = 30
    private const val MAX_STEPS = 8

    private fun file(ctx: Context) = File(ctx.filesDir, "skills.json")

    @Synchronized
    private fun load(ctx: Context): JSONObject = try {
        JSONObject(file(ctx).readText())
    } catch (_: Exception) {
        JSONObject()
    }

    @Synchronized
    private fun persist(ctx: Context, skills: JSONObject) {
        runCatching { file(ctx).writeText(skills.toString()) }
    }

    fun save(ctx: Context, name: String, description: String, steps: JSONArray): String {
        if (name.isBlank()) return "Skill butuh nama 🤔"
        if (steps.length() == 0) return "Skill \"$name\" butuh minimal satu langkah aksi"
        val skills = load(ctx)
        if (!skills.has(name) && skills.length() >= MAX_SKILLS) {
            return "Kantong skillku penuh ($MAX_SKILLS) — hapus dulu yang lama"
        }
        // batasi langkah & larang skill memanggil skill (anti-rekursi)
        val clean = JSONArray()
        for (i in 0 until minOf(steps.length(), MAX_STEPS)) {
            val s = steps.optJSONObject(i) ?: continue
            val t = s.optString("type")
            if (t == "use_skill" || t == "save_skill" || t == "delete_skill") continue
            clean.put(s)
        }
        if (clean.length() == 0) return "Langkah-langkahnya tidak valid 😖"
        skills.put(name, JSONObject().put("description", description).put("steps", clean))
        persist(ctx, skills)
        return "Skill baru kupelajari: \"$name\" ✨ (${clean.length()} langkah)"
    }

    fun delete(ctx: Context, name: String): String {
        val skills = load(ctx)
        return if (skills.has(name)) {
            skills.remove(name)
            persist(ctx, skills)
            "Skill \"$name\" kulupakan 🗑"
        } else "Aku nggak punya skill \"$name\" 🤔"
    }

    fun run(ctx: Context, name: String): String {
        val skill = load(ctx).optJSONObject(name)
            ?: return "Aku belum punya skill \"$name\" — minta aku membuatnya dulu!"
        val steps = skill.optJSONArray("steps") ?: return "Skill \"$name\" kosong"
        val results = mutableListOf<String>()
        for (i in 0 until steps.length()) {
            steps.optJSONObject(i)?.let { step ->
                // skill berjalan tanpa dialog → aksi berisiko (kirim/bayar/hapus) dilewati
                if (Actions.confirmPrompt(step) != null) results.add("⚠️ aksi berisiko dilewati")
                else Actions.run(ctx, step)?.let { results.add(it) }
            }
        }
        return "Skill \"$name\" kujalankan: " + results.joinToString(" · ").ifBlank { "beres ✓" }
    }

    /** Pasangan (nama, deskripsi) untuk ditampilkan di UI. */
    fun list(ctx: Context): List<Pair<String, String>> {
        val skills = load(ctx)
        return skills.keys().asSequence().map { name ->
            name to (skills.optJSONObject(name)?.optString("description") ?: "")
        }.toList()
    }

    /** Daftar skill untuk disuntikkan ke system prompt otak. */
    fun listForPrompt(ctx: Context): String {
        val skills = load(ctx)
        if (skills.length() == 0) return "(belum ada)"
        return skills.keys().asSequence().joinToString("; ") { name ->
            "\"$name\" (${skills.optJSONObject(name)?.optString("description")?.take(60) ?: ""})"
        }
    }
}
