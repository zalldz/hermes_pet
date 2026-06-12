package com.hermespet

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hermespet.Prefs.agentUrl
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

/**
 * Jembatan ke "otak": server kecil di Termux yang membungkus hermes-agent.
 * Protokol: POST {agentUrl}/pet/event  body {type, data, ts}
 * Balasan: {"say": String?, "emotion": "happy|sad|neutral|sleepy|surprised|love"?}
 */
object HermesClient {
    private const val TAG = "HermesClient"
    private val json = "application/json; charset=utf-8".toMediaType()
    private val main = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // LLM bisa lambat menjawab
        .build()

    fun sendEvent(ctx: Context, type: String, data: JSONObject, onReply: (JSONObject?) -> Unit) {
        val body = JSONObject()
            .put("type", type)
            .put("data", data)
            .put("ts", System.currentTimeMillis() / 1000)
        val req = Request.Builder()
            .url(ctx.agentUrl.trimEnd('/') + "/pet/event")
            .post(body.toString().toRequestBody(json))
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "agent unreachable: ${e.message}")
                main.post { onReply(null) }
            }

            override fun onResponse(call: Call, response: Response) {
                val reply = try {
                    response.use { r ->
                        if (r.isSuccessful) JSONObject(r.body?.string() ?: "{}") else null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "bad reply: ${e.message}")
                    null
                }
                main.post { onReply(reply) }
            }
        })
    }
}
