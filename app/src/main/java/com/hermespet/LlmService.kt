package com.hermespet

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.File

/**
 * Menjalankan AI lokal (MediaPipe LiteRT) di PROSES TERPISAH (":llm").
 * Kalau mesin native crash (SIGSEGV) karena model tak cocok, hanya proses ini
 * yang mati — pet di proses utama tetap hidup & menerima DeadObjectException.
 */
class LlmService : Service() {
    private val TAG = "LlmService"
    private var llm: LlmInference? = null
    private var loadedPath: String? = null

    private val binder = object : ILlmService.Stub() {
        override fun generate(modelPath: String?, prompt: String?): String? {
            if (modelPath.isNullOrBlank() || prompt.isNullOrBlank()) return null
            if (!File(modelPath).exists()) return null
            synchronized(this@LlmService) {
                if (llm == null || loadedPath != modelPath) {
                    runCatching { llm?.close() }
                    llm = null; loadedPath = null
                    try {
                        val opts = LlmInference.LlmInferenceOptions.builder()
                            .setModelPath(modelPath)
                            .setMaxTokens(2048)
                            .build()
                        llm = LlmInference.createFromOptions(applicationContext, opts)
                        loadedPath = modelPath
                    } catch (e: Throwable) {
                        Log.w(TAG, "muat model gagal: ${e.message}")
                        return null
                    }
                }
                val engine = llm ?: return null
                var session: LlmInferenceSession? = null
                return try {
                    session = LlmInferenceSession.createFromOptions(
                        engine,
                        LlmInferenceSession.LlmInferenceSessionOptions.builder()
                            .setTopK(40)
                            .setTemperature(0.8f)
                            .build()
                    )
                    session.addQueryChunk(prompt)
                    session.generateResponse()
                } catch (e: Throwable) {
                    Log.w(TAG, "generate gagal: ${e.message}")
                    null
                } finally {
                    runCatching { session?.close() }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runCatching { llm?.close() }
        llm = null; loadedPath = null
        super.onDestroy()
    }
}
