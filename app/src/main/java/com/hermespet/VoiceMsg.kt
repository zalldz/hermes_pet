package com.hermespet

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/** Rekam (push-to-talk) & putar pesan suara walkie-talkie. */
object VoiceMsg {
    private const val TAG = "VoiceMsg"
    private var recorder: MediaRecorder? = null
    private var recFile: File? = null

    fun startRecording(ctx: Context): Boolean {
        stop()
        return try {
            val f = File(ctx.cacheDir, "ptt_rec.m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx)
            else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(24000)
            r.setOutputFile(f.absolutePath)
            r.prepare(); r.start()
            recorder = r; recFile = f; true
        } catch (e: Exception) {
            Log.w(TAG, "rekam gagal: ${e.message}"); stop(); false
        }
    }

    /** Hentikan rekam → balikan bytes audio (null bila gagal/terlalu pendek). */
    fun stopRecording(): ByteArray? {
        val f = recFile
        val bytes = try {
            recorder?.stop()
            f?.readBytes()
        } catch (e: Exception) {
            Log.w(TAG, "stop rekam gagal: ${e.message}"); null
        } finally {
            runCatching { recorder?.release() }
            recorder = null
        }
        return bytes?.takeIf { it.size > 1000 } // buang klik super pendek
    }

    fun stop() {
        runCatching { recorder?.release() }
        recorder = null
    }

    fun play(ctx: Context, bytes: ByteArray) {
        try {
            val f = File(ctx.cacheDir, "ptt_play_${System.currentTimeMillis()}.m4a")
            f.writeBytes(bytes)
            val mp = MediaPlayer()
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { it.release(); runCatching { f.delete() } }
            mp.setOnErrorListener { p, _, _ -> p.release(); true }
            mp.prepare(); mp.start()
        } catch (e: Exception) {
            Log.w(TAG, "putar gagal: ${e.message}")
        }
    }
}
