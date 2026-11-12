package com.hermespet

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.hermespet.Prefs.apiBaseUrl
import com.hermespet.Prefs.apiKey
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Bridge homelab (/v1/catalog, /v1/run): daftar & jalankan skill, tool MCP, script. Eksekusi di homelab. */
object Homelab {
    val TYPES = setOf("skill", "mcp", "script")

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(250, TimeUnit.SECONDS).build() // skill = Claude penuh, bisa lama

    private fun call(ctx: Context, path: String, body: JSONObject?, cb: (JSONObject?, String?) -> Unit) {
        val b = Request.Builder().url(ctx.apiBaseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer ${ctx.apiKey}")
        if (body != null) b.post(body.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(b.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { main.post { cb(null, e.message ?: "koneksi gagal") } }
            override fun onResponse(call: Call, response: Response) {
                val txt = response.body?.string().orEmpty()
                val j = runCatching { JSONObject(txt) }.getOrNull()
                main.post { if (response.isSuccessful && j != null) cb(j, null) else cb(null, "HTTP ${response.code}") }
            }
        })
    }

    /** {skills:[{name,desc}], mcp:{server:[{name,desc}] | {error}}, scripts:[name]} */
    fun catalog(ctx: Context, cb: (JSONObject?, String?) -> Unit) = call(ctx, "/catalog", null, cb)

    /** Jalankan aksi skill/mcp/script; cb(ok, keluaran singkat). */
    fun run(ctx: Context, action: JSONObject, cb: (Boolean, String) -> Unit) =
        call(ctx, "/run", action) { j, err -> cb(j?.optBoolean("ok") == true, j?.optString("out") ?: "gagal: $err") }
}
