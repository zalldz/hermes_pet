package com.hermespet

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/**
 * Otak AI LOKAL on-device via MediaPipe LLM Inference (LiteRT) — seperti Google
 * AI Edge Gallery. Jalankan model .task offline; cukup arahkan path filenya.
 * 100% di HP, tanpa internet.
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
                .setMaxTokens(640)
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

    /** Hasilkan teks dari prompt tunggal (blocking — panggil di thread latar). */
    @Synchronized
    fun generate(prompt: String): String? = try {
        llm?.generateResponse(prompt)
    } catch (e: Throwable) {
        Log.w(TAG, "generate gagal: ${e.message}")
        null
    }

    @Synchronized
    fun close() {
        runCatching { llm?.close() }
        llm = null; loadedPath = null
    }
}
