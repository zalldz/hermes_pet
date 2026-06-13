package com.hermespet

import android.content.Context
import android.util.Log
import com.hermespet.Prefs.apiBaseUrl
import com.hermespet.Prefs.apiKey
import com.hermespet.Prefs.embModel
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Memori jangka panjang pet, terinspirasi Mnemosyne (https://mnemosyne.site):
 * remember() menyimpan fakta + skor kepentingan, recall() mencari yang relevan.
 * Kalau model embeddings di-set → pencarian vektor (cosine similarity);
 * kalau tidak → fallback kecocokan kata kunci. Semuanya lokal di HP.
 */
object Memory {
    private const val TAG = "Memory"
    private const val MAX_MEMORIES = 500

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Mem(val text: String, val importance: Double, val ts: Long, val vec: FloatArray?)

    private fun file(ctx: Context) = File(ctx.filesDir, "memories.jsonl")

    // ------------------------------------------------------------- simpan

    fun remember(ctx: Context, text: String, importance: Double) {
        if (text.isBlank()) return
        embed(ctx, text) { vec -> save(ctx, text.take(300), importance, vec) }
    }

    @Synchronized
    private fun save(ctx: Context, text: String, importance: Double, vec: FloatArray?) {
        try {
            val o = JSONObject()
                .put("text", text)
                .put("importance", importance.coerceIn(0.1, 1.0))
                .put("ts", System.currentTimeMillis())
            vec?.let { v ->
                o.put("vec", JSONArray().apply { v.forEach { put(it.toDouble()) } })
            }
            file(ctx).appendText(o.toString() + "\n")
            // pangkas: buang yang paling tidak penting & paling tua
            val all = all(ctx)
            if (all.size > MAX_MEMORIES) {
                val keep = all.sortedByDescending { it.importance * recency(it.ts) }
                    .take(MAX_MEMORIES * 3 / 4)
                file(ctx).writeText(keep.joinToString("\n") { m ->
                    JSONObject().put("text", m.text).put("importance", m.importance)
                        .put("ts", m.ts).apply {
                            m.vec?.let { v ->
                                put("vec", JSONArray().apply { v.forEach { put(it.toDouble()) } })
                            }
                        }.toString()
                } + "\n")
            }
        } catch (e: Exception) {
            Log.w(TAG, "save: ${e.message}")
        }
    }

    @Synchronized
    fun all(ctx: Context): List<Mem> = try {
        file(ctx).readLines().mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                val vec = o.optJSONArray("vec")?.let { a ->
                    FloatArray(a.length()) { i -> a.getDouble(i).toFloat() }
                }
                Mem(o.getString("text"), o.getDouble("importance"), o.getLong("ts"), vec)
            }.getOrNull()
        }
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    fun delete(ctx: Context, ts: Long) {
        try {
            val kept = all(ctx).filter { it.ts != ts }
            file(ctx).writeText(kept.joinToString("\n") { m ->
                JSONObject().put("text", m.text).put("importance", m.importance)
                    .put("ts", m.ts).apply {
                        m.vec?.let { v ->
                            put("vec", JSONArray().apply { v.forEach { put(it.toDouble()) } })
                        }
                    }.toString()
            } + if (kept.isEmpty()) "" else "\n")
        } catch (_: Exception) {
        }
    }

    /** Satu ingatan acak (berbobot kepentingan) untuk diungkit pet. null = kosong. */
    fun randomMemory(ctx: Context): String? {
        val mems = all(ctx)
        if (mems.isEmpty()) return null
        val total = mems.sumOf { it.importance }
        var r = Math.random() * total
        for (m in mems) { r -= m.importance; if (r <= 0) return m.text }
        return mems.last().text
    }

    // ------------------------------------------------------------- recall

    fun recall(ctx: Context, query: String, k: Int, onResult: (List<String>) -> Unit) {
        val mems = all(ctx)
        if (mems.isEmpty()) { onResult(emptyList()); return }
        embed(ctx, query) { qv ->
            val scored = mems.map { m -> m to score(m, qv, query) }
                .sortedByDescending { it.second }
                .take(k)
                .filter { it.second > 0.08 }
                .map { it.first.text }
            onResult(scored)
        }
    }

    private fun score(m: Mem, queryVec: FloatArray?, query: String): Double {
        val sim = if (queryVec != null && m.vec != null && queryVec.size == m.vec.size) {
            cosine(queryVec, m.vec).toDouble()
        } else {
            keywordSim(query, m.text)
        }
        return sim * m.importance * (0.7 + 0.3 * recency(m.ts))
    }

    private fun recency(ts: Long): Double {
        val days = (System.currentTimeMillis() - ts) / 86_400_000.0
        return exp(-days / 90.0) // meluruh pelan, paruh waktu ~2 bulan
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val d = sqrt(na) * sqrt(nb)
        return if (d == 0f) 0f else dot / d
    }

    private fun keywordSim(query: String, text: String): Double {
        val q = tokenize(query); val t = tokenize(text)
        if (q.isEmpty() || t.isEmpty()) return 0.0
        val overlap = q.intersect(t).size.toDouble()
        return overlap / (q.size + t.size - overlap) * 2.0 // jaccard, sedikit diperkuat
    }

    private fun tokenize(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()

    // ------------------------------------------------------------- embeddings

    /** Ambil vektor via endpoint /embeddings (OpenAI-compatible). null = nonaktif/gagal. */
    private fun embed(ctx: Context, text: String, onResult: (FloatArray?) -> Unit) {
        val model = ctx.embModel
        if (model.isBlank() || ctx.apiKey.isBlank()) { onResult(null); return }
        val body = JSONObject().put("model", model).put("input", text.take(500))
        val req = Request.Builder()
            .url(ctx.apiBaseUrl.trimEnd('/') + "/embeddings")
            .header("Authorization", "Bearer ${ctx.apiKey}")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "embed gagal: ${e.message}")
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val vec = try {
                    response.use { r ->
                        JSONObject(r.body?.string() ?: "{}")
                            .optJSONArray("data")?.optJSONObject(0)
                            ?.optJSONArray("embedding")?.let { a ->
                                FloatArray(a.length()) { i -> a.getDouble(i).toFloat() }
                            }
                    }
                } catch (e: Exception) { null }
                onResult(vec)
            }
        })
    }
}
