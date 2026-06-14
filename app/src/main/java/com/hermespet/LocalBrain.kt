package com.hermespet

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log

/**
 * Klien AI lokal: bicara ke [LlmService] di proses ":llm" lewat AIDL.
 * Kalau proses itu crash (model tak cocok), kita dapat RemoteException —
 * dikembalikan null tanpa menjatuhkan pet (proses utama).
 */
object LocalBrain {
    private const val TAG = "LocalBrain"
    @Volatile private var svc: ILlmService? = null
    private val lock = Object()
    // circuit breaker: hentikan sementara kalau model gagal beruntun (hemat baterai)
    @Volatile private var failCount = 0
    @Volatile private var blockedUntil = 0L

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            synchronized(lock) { svc = ILlmService.Stub.asInterface(b); lock.notifyAll() }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) { svc = null } // proses :llm mati/di-recycle
        }
        override fun onBindingDied(name: ComponentName?) {
            synchronized(lock) { svc = null }
        }
    }

    private fun ensureBound(ctx: Context): ILlmService? {
        synchronized(lock) {
            svc?.let { return it }
            try {
                ctx.applicationContext.bindService(
                    Intent(ctx, LlmService::class.java), conn, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                Log.w(TAG, "bind gagal: ${e.message}"); return null
            }
            val deadline = System.currentTimeMillis() + 6_000
            while (svc == null && System.currentTimeMillis() < deadline) {
                try { lock.wait(500) } catch (e: InterruptedException) { break }
            }
            return svc
        }
    }

    /** Hasilkan teks lewat proses terisolasi. null = gagal (pet tetap aman). */
    fun generate(ctx: Context, modelPath: String, prompt: String): String? {
        if (System.currentTimeMillis() < blockedUntil) return null // sedang istirahat
        val s = ensureBound(ctx) ?: return fail()
        return try {
            val r = s.generate(modelPath, prompt)
            if (r.isNullOrBlank()) fail() else { failCount = 0; r }
        } catch (e: RemoteException) {
            // proses :llm crash saat inferensi → rebind di panggilan berikutnya
            Log.w(TAG, "proses llm mati: ${e.message}")
            synchronized(lock) { svc = null }
            fail()
        } catch (e: Exception) {
            Log.w(TAG, "generate gagal: ${e.message}")
            fail()
        }
    }

    private fun fail(): String? {
        if (++failCount >= 3) { blockedUntil = System.currentTimeMillis() + 5 * 60_000L; failCount = 0 }
        return null
    }
}
