package com.hermespet

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("hermespet", Context.MODE_PRIVATE)

    var Context.agentUrl: String
        get() = sp(this).getString("agent_url", "http://127.0.0.1:8765") ?: "http://127.0.0.1:8765"
        set(v) = sp(this).edit().putString("agent_url", v).apply()

    /** "api" = otak bawaan via endpoint OpenAI-compatible; "termux" = server di Termux */
    var Context.brainMode: String
        get() = sp(this).getString("brain_mode", "api") ?: "api"
        set(v) = sp(this).edit().putString("brain_mode", v).apply()

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

    var Context.petX: Int
        get() = sp(this).getInt("pet_x", 100)
        set(v) = sp(this).edit().putInt("pet_x", v).apply()

    var Context.petY: Int
        get() = sp(this).getInt("pet_y", 600)
        set(v) = sp(this).edit().putInt("pet_y", v).apply()
}
