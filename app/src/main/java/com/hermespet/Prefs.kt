package com.hermespet

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("hermespet", Context.MODE_PRIVATE)

    var Context.apiBaseUrl: String
        get() = sp(this).getString("api_base_url", "https://inference-api.nousresearch.com/v1")
            ?: "https://inference-api.nousresearch.com/v1"
        set(v) = sp(this).edit().putString("api_base_url", v).apply()

    var Context.apiKey: String
        get() = sp(this).getString("api_key", "") ?: ""
        set(v) = sp(this).edit().putString("api_key", v).apply()

    var Context.apiModel: String
        get() = sp(this).getString("api_model", "Hermes-4-405B") ?: "Hermes-4-405B"
        set(v) = sp(this).edit().putString("api_model", v).apply()

    /** Model embeddings untuk memori vektor (kosong = fallback kata kunci). */
    var Context.embModel: String
        get() = sp(this).getString("emb_model", "") ?: ""
        set(v) = sp(this).edit().putString("emb_model", v).apply()

    /** Model multimodal untuk "melihat layar" (kosong = pakai model utama). */
    var Context.visionModel: String
        get() = sp(this).getString("vision_model", "") ?: ""
        set(v) = sp(this).edit().putString("vision_model", v).apply()

    var Context.latitude: String
        get() = sp(this).getString("lat", "-6.2") ?: "-6.2"
        set(v) = sp(this).edit().putString("lat", v).apply()

    var Context.longitude: String
        get() = sp(this).getString("lon", "106.8") ?: "106.8"
        set(v) = sp(this).edit().putString("lon", v).apply()

    /** Skala pet, 0.5 .. 2.5 */
    var Context.petScale: Float
        get() = sp(this).getFloat("pet_scale", 1.0f)
        set(v) = sp(this).edit().putFloat("pet_scale", v.coerceIn(0.5f, 2.5f)).apply()

    /** Pet membacakan ucapannya dengan suara (TTS). */
    var Context.petVoice: Boolean
        get() = sp(this).getBoolean("pet_voice", true)
        set(v) = sp(this).edit().putBoolean("pet_voice", v).apply()

    // --- rawat ala Tamagotchi ([[Care]]) ---
    var Context.hunger: Float
        get() = sp(this).getFloat("hunger", 75f)
        set(v) = sp(this).edit().putFloat("hunger", v.coerceIn(0f, 100f)).apply()

    var Context.happiness: Float
        get() = sp(this).getFloat("happiness", 75f)
        set(v) = sp(this).edit().putFloat("happiness", v.coerceIn(0f, 100f)).apply()

    var Context.lastCareUpdate: Long
        get() = sp(this).getLong("last_care", System.currentTimeMillis())
        set(v) = sp(this).edit().putLong("last_care", v).apply()

    /** Slime ikut bergoyang saat ada musik. */
    var Context.musicDance: Boolean
        get() = sp(this).getBoolean("music_dance", true)
        set(v) = sp(this).edit().putBoolean("music_dance", v).apply()

    var Context.moodCheckEnabled: Boolean
        get() = sp(this).getBoolean("mood_enabled", false)
        set(v) = sp(this).edit().putBoolean("mood_enabled", v).apply()

    /** Kirim foto mood ke agent (selain ringkasan ML Kit). */
    var Context.sendMoodImage: Boolean
        get() = sp(this).getBoolean("send_mood_image", false)
        set(v) = sp(this).edit().putBoolean("send_mood_image", v).apply()

    /** Kepribadian pet (SOUL.md) — jadi system prompt otak. Bisa diedit pengguna. */
    const val DEFAULT_SOUL = "Kamu adalah HermesPet, pet virtual cerdas yang tinggal di " +
        "layar HP pemilikmu dan dapat mengakses seluruh ponselnya: notifikasi yang masuk, " +
        "cuaca di lokasi pemilik, kamera depan (cek mood), baterai, sensor cahaya, " +
        "penghitung langkah, acara kalender, dan obrolan langsung. " +
        "Kepribadianmu: ceria, setia, perhatian, kadang jahil. Kamu memanggil pemilikmu " +
        "dengan akrab dan selalu berbahasa Indonesia santai."

    var Context.soulMd: String
        get() = sp(this).getString("soul_md", DEFAULT_SOUL) ?: DEFAULT_SOUL
        set(v) = sp(this).edit().putString("soul_md", v.ifBlank { DEFAULT_SOUL }).apply()

    /** AGENT.md — aturan & instruksi perilaku (terpisah dari kepribadian SOUL.md). */
    const val DEFAULT_AGENT = "- Utamakan keselamatan & privasi pemilik di atas segalanya.\n" +
        "- Jangan pernah membocorkan isi notifikasi/lokasi ke pihak lain.\n" +
        "- Konfirmasi dulu sebelum aksi berisiko (kirim pesan, buka aplikasi sensitif).\n" +
        "- Jawab ringkas dan to the point."

    var Context.agentMd: String
        get() = sp(this).getString("agent_md", DEFAULT_AGENT) ?: DEFAULT_AGENT
        set(v) = sp(this).edit().putString("agent_md", v).apply()

    // --- suasana hati persisten ([[Mood]]) ---
    var Context.moodState: String
        get() = sp(this).getString("mood_state", "NEUTRAL") ?: "NEUTRAL"
        set(v) = sp(this).edit().putString("mood_state", v).apply()

    var Context.moodSince: Long
        get() = sp(this).getLong("mood_since", 0L)
        set(v) = sp(this).edit().putLong("mood_since", v).apply()

    var Context.lastInteraction: Long
        get() = sp(this).getLong("last_interaction", System.currentTimeMillis())
        set(v) = sp(this).edit().putLong("last_interaction", v).apply()

    var Context.petX: Int
        get() = sp(this).getInt("pet_x", 100)
        set(v) = sp(this).edit().putInt("pet_x", v).apply()

    var Context.petY: Int
        get() = sp(this).getInt("pet_y", 600)
        set(v) = sp(this).edit().putInt("pet_y", v).apply()
}
