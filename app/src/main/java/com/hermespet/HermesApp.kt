package com.hermespet

import android.app.Application
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash reporting lokal: tangkap exception yang tak tertangani, tulis stack
 * trace ke file (bisa dilihat di pengaturan), lalu serahkan ke handler sistem.
 */
class HermesApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            runCatching { writeCrash(thread, ex) }
            prev?.uncaughtException(thread, ex)
        }
    }

    private fun writeCrash(thread: Thread, ex: Throwable) {
        val ts = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        val sw = java.io.StringWriter()
        ex.printStackTrace(java.io.PrintWriter(sw))
        val entry = "===== $ts (thread ${thread.name}) =====\n$sw\n\n"
        val f = File(filesDir, "crashes.txt")
        // simpan maksimal ~20KB terakhir
        val old = if (f.exists()) f.readText() else ""
        f.writeText((entry + old).take(20_000))
    }

    companion object {
        fun crashLog(app: Application): String =
            File(app.filesDir, "crashes.txt").let { if (it.exists()) it.readText() else "" }
    }
}
