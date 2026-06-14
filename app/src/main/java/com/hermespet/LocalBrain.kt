package com.hermespet

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.File

/**
 * Otak AI LOKAL on-device via MediaPipe LLM Inference (LiteRT) — seperti Google
 * AI Edge Gallery. Memuat model **.litertlm** (atau .task) offline; cukup
 * arahkan path filenya. 100% di HP, tanpa internet.
 */
object LocalBrain {
    private const val TAG = "LocalBrain"
    private var llm: LlmInference? = null
    private var loadedPath: String? = null

    /** true bila model di path siap dipakai (memuat ulang bila path berubah). */
    @Synchronized
    fun ensureLoaded(ctx: Context, path: String): Boolean {
        if (path.isBlank() || !File(path).exists()) return false
        if (llm != null && loadedPath == path) return true
        close()
        return try {
            val opts = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(1024)
                .build()
            llm = LlmInference.createFromOptions(ctx, opts)
            loadedPath = path
            true
        } catch (e: Throwable) {
            Log.w(TAG, "gagal muat model: ${e.message}")
            llm = null; loadedPath = null
            false
        }
    }

    /** Hasilkan teks dari prompt (blocking — panggil di thread latar). */
    @Synchronized
    fun generate(prompt: String): String? {
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

    @Synchronized
    fun close() {
        runCatching { llm?.close() }
        llm = null; loadedPath = null
    }
}
