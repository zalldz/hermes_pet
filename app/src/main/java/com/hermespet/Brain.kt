package com.hermespet

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hermespet.Prefs.apiBaseUrl
import com.hermespet.Prefs.apiKey
import com.hermespet.Prefs.apiModel
import com.hermespet.Prefs.brainMode
import com.hermespet.Prefs.latitude
import com.hermespet.Prefs.longitude
import com.hermespet.Prefs.soulMd
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Otak pet. Dua mode:
 *  - "api"    : standalone — APK langsung memanggil model Hermes lewat endpoint
 *               OpenAI-compatible (Nous Portal, OpenRouter, dll). Tanpa Termux.
 *  - "termux" : meneruskan event ke server hermes-agent di Termux (HermesClient).
 * Tanpa API key pun pet tetap hidup dengan balasan bawaan.
 */
object Brain {
    private const val TAG = "Brain"
    private const val NOTIF_COOLDOWN_MS = 120_000L
    private const val WEATHER_INTERVAL_MS = 1_800_000L
    private const val LOW_BATT_COOLDOWN_MS = 1_800_000L

    private val main = Handler(Looper.getMainLooper())
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private var lastNotifSay = 0L
    private var lastWeatherSay = 0L
    private var lastLowBatt = 0L
    private var lastEventReminded = ""

    /** SOUL.md pengguna + aturan format yang tidak boleh dilanggar (untuk parsing). */
    private fun systemPrompt(ctx: Context): String =
        ctx.soulMd + "\n\nFORMAT WAJIB: balas HANYA dengan JSON satu baris: " +
            "{\"say\": \"<ucapanmu, bahasa Indonesia santai>\", " +
            "\"emotion\": \"<happy|sad|neutral|sleepy|surprised|love>\"}\n" +
            "Kamu juga bisa MELAKUKAN aksi di HP. Kalau (dan hanya kalau) pemilik " +
            "memintanya, tambahkan field \"action\" di JSON, salah satu dari:\n" +
            "{\"type\":\"volume\",\"level\":0-100} | {\"type\":\"volume_up\"} | " +
            "{\"type\":\"volume_down\"} | {\"type\":\"mute\"} | " +
            "{\"type\":\"torch\",\"on\":true/false} (senter) | " +
            "{\"type\":\"brightness\",\"level\":0-100} (kecerahan layar) | " +
            "{\"type\":\"media\",\"cmd\":\"play_pause|next|prev\"} (kontrol musik) | " +
            "{\"type\":\"open_app\",\"name\":\"<nama aplikasi>\"} | " +
            "{\"type\":\"wifi_panel\"} | {\"type\":\"bluetooth_panel\"}\n" +
            "Catatan: WiFi/Bluetooth hanya bisa kamu bukakan panelnya (aturan Android), " +
            "jelaskan itu dengan santai kalau diminta toggle."

    fun sendEvent(ctx: Context, type: String, data: JSONObject, onReply: (JSONObject?) -> Unit) {
        if (ctx.brainMode == "termux") {
            HermesClient.sendEvent(ctx, type, data, onReply)
            return
        }
        when (type) {
            "tick" -> handleTick(ctx, data, onReply)
            "notification" -> handleNotification(ctx, data, onReply)
            "mood" -> handleMood(ctx, data, onReply)
            "poke" -> handlePoke(ctx, onReply)
            "chat" -> handleChat(ctx, data, onReply)
            else -> onReply(null)
        }
    }

    // ------------------------------------------------------------- per event

    private fun handleTick(ctx: Context, data: JSONObject, onReply: (JSONObject?) -> Unit) {
        val now = System.currentTimeMillis()
        val battery = data.optInt("battery", -1)
        if (battery in 0..15 && now - lastLowBatt > LOW_BATT_COOLDOWN_MS) {
            lastLowBatt = now
            onReply(reply("Baterai tinggal $battery%! Cas dong 🥺", "sad"))
            return
        }
        // pengingat acara kalender yang sudah dekat (≤45 menit)
        val eventTitle = data.optString("next_event")
        val eventMin = data.optInt("next_event_min", -1)
        if (eventTitle.isNotEmpty() && eventMin in 1..45 && eventTitle != lastEventReminded) {
            lastEventReminded = eventTitle
            askHermes(ctx,
                "Sebentar lagi ($eventMin menit) pemilikmu ada acara di kalender: \"$eventTitle\". " +
                    "Ingatkan dia dengan singkat dan semangat.",
                "Psst, $eventMin menit lagi: $eventTitle ⏰", "surprised", onReply)
            return
        }
        if (now - lastWeatherSay > WEATHER_INTERVAL_MS) {
            lastWeatherSay = now
            fetchWeather(ctx) { w ->
                if (w == null) { onReply(null); return@fetchWeather }
                val desc = w.first; val temp = w.second
                val emo = if ("hujan" in desc || "badai" in desc) "sad" else "happy"
                val extra = buildString {
                    if (data.has("light_lux")) append(" Cahaya sekitar pemilik: ${data.optInt("light_lux")} lux.")
                    if (data.has("steps_since_pet_on")) append(" Langkah sejak pet menyala: ${data.optInt("steps_since_pet_on")}.")
                    append(" Jam sekarang: ${data.optInt("hour")}.")
                }
                askHermes(ctx,
                    "Cuaca sekarang $desc, suhu $temp°C.$extra " +
                        "Komentari singkat, beri saran kalau relevan (payung, istirahat, jalan-jalan, dll).",
                    "Di luar $desc, $temp°C nih!", emo, onReply)
            }
            return
        }
        onReply(null) // pet tidak perlu cerewet tiap menit
    }

    private fun handleNotification(ctx: Context, data: JSONObject, onReply: (JSONObject?) -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastNotifSay < NOTIF_COOLDOWN_MS) { onReply(null); return }
        lastNotifSay = now
        val title = data.optString("title")
        val text = data.optString("text").take(200)
        val pkg = data.optString("package")
        askHermes(ctx,
            "Pemilikmu dapat notifikasi dari aplikasi $pkg: '$title: $text'. " +
                "Komentari singkat dan relevan, ingatkan kalau kelihatan penting.",
            "Psst, ada pesan: $title 👀", "surprised", onReply)
    }

    private fun handleMood(ctx: Context, data: JSONObject, onReply: (JSONObject?) -> Unit) {
        if (!data.optBoolean("face_detected")) {
            onReply(reply("Loh, kamu di mana? 👀", "surprised"))
            return
        }
        val guess = data.optString("mood_guess", "neutral")
        val smile = data.optDouble("smile", -1.0)
        val fallback = when (guess) {
            "happy" -> "Senyummu cerah banget hari ini! 😄" to "love"
            "sleepy" -> "Matamu sayu... istirahat dulu yuk 😴" to "sleepy"
            "flat" -> "Kamu kelihatan lelah... aku temenin ya 🥺" to "sad"
            else -> "Semangat terus ya hari ini!" to "happy"
        }
        askHermes(ctx,
            "Kamu baru melihat wajah pemilikmu lewat kamera depan. Tebakan mood: $guess " +
                "(probabilitas senyum ${"%.2f".format(smile)}). Beri komentar empatik singkat.",
            fallback.first, fallback.second, onReply)
    }

    private fun handleChat(ctx: Context, data: JSONObject, onReply: (JSONObject?) -> Unit) {
        val text = data.optString("text").take(500)
        askHermes(ctx,
            "Pemilikmu berkata kepadamu: \"$text\". Jawab dengan hangat dan membantu — " +
                "untuk chat boleh menjawab panjang secukupnya, jangan dipotong.",
            "Aku dengar kamu! Tapi isi API key dulu di pengaturan biar aku bisa mikir 🥺",
            "love", onReply)
    }

    private fun handlePoke(ctx: Context, onReply: (JSONObject?) -> Unit) {
        val canned = listOf("Hihi geli! 😆", "Eh, apa? Aku lagi jalan-jalan nih!", "Poke balik! 🐾")
        askHermes(ctx, "Pemilikmu baru menyentuhmu (poke). Bereaksi singkat dan lucu.",
            canned[Random.nextInt(canned.size)], "happy", onReply)
    }

    // ------------------------------------------------------------- LLM API

    private fun askHermes(
        ctx: Context, context: String,
        fallbackSay: String, fallbackEmotion: String,
        onReply: (JSONObject?) -> Unit
    ) {
        val key = ctx.apiKey
        if (key.isBlank()) {
            // tanpa API key pet tetap hidup dengan balasan bawaan
            onReply(reply(fallbackSay, fallbackEmotion))
            return
        }
        val body = JSONObject()
            .put("model", ctx.apiModel)
            .put("max_tokens", 700) // cukup untuk jawaban chat panjang tanpa terpotong
            .put("temperature", 0.8)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt(ctx)))
                .put(JSONObject().put("role", "user").put("content", context)))
        val req = Request.Builder()
            .url(ctx.apiBaseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(jsonType))
            .build()

        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "api gagal: ${e.message}")
                main.post { onReply(reply(fallbackSay, fallbackEmotion)) }
            }

            override fun onResponse(call: Call, response: Response) {
                val parsed = try {
                    response.use { r ->
                        val content = JSONObject(r.body?.string() ?: "{}")
                            .optJSONArray("choices")?.optJSONObject(0)
                            ?.optJSONObject("message")?.optString("content") ?: ""
                        extractJson(content)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "parse gagal: ${e.message}"); null
                }
                main.post { onReply(parsed ?: reply(fallbackSay, fallbackEmotion)) }
            }
        })
    }

    /** Ambil objek JSON pertama dari teks jawaban model. */
    private fun extractJson(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val obj = JSONObject(text.substring(start, end + 1))
            if (!obj.has("say")) return null
            val r = reply(obj.optString("say").take(1500), obj.optString("emotion", "neutral"))
            obj.optJSONObject("action")?.let { r.put("action", it) }
            r
        } catch (e: Exception) { null }
    }

    private fun reply(say: String?, emotion: String?): JSONObject =
        JSONObject().apply {
            say?.let { put("say", it) }
            emotion?.let { put("emotion", it) }
        }

    // ------------------------------------------------------------- cuaca

    private val WMO = mapOf(
        0 to "cerah", 1 to "cerah berawan", 2 to "berawan", 3 to "mendung",
        45 to "berkabut", 48 to "berkabut", 51 to "gerimis", 53 to "gerimis", 55 to "gerimis",
        61 to "hujan ringan", 63 to "hujan", 65 to "hujan deras",
        80 to "hujan lokal", 81 to "hujan lokal", 82 to "hujan deras",
        95 to "badai petir", 96 to "badai petir", 99 to "badai petir",
    )

    /** Open-Meteo, gratis tanpa API key. onResult(desc, temp) di main thread. */
    private fun fetchWeather(ctx: Context, onResult: (Pair<String, Double>?) -> Unit) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${ctx.latitude}" +
            "&longitude=${ctx.longitude}&current_weather=true"
        http.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "cuaca gagal: ${e.message}")
                main.post { onResult(null) }
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { r ->
                        val cw = JSONObject(r.body?.string() ?: "{}")
                            .getJSONObject("current_weather")
                        val desc = WMO[cw.getInt("weathercode")] ?: "tidak jelas"
                        desc to cw.getDouble("temperature")
                    }
                } catch (e: Exception) { null }
                main.post { onResult(result) }
            }
        })
    }
}
