package com.hermespet

import android.content.Context
import com.hermespet.Prefs.lastInteraction
import com.hermespet.Prefs.moodSince
import com.hermespet.Prefs.moodState
import java.util.Calendar

/**
 * Suasana hati pet yang MENETAP (bukan cuma per-balasan). Dipengaruhi interaksi
 * & hasil cek mood kamera, meluruh ke netral seiring waktu, dan punya perilaku
 * kontekstual: ngantuk saat tengah malam, sedih/kesepian kalau lama diabaikan.
 * Warna tubuh pet otomatis mengikuti emosi ini (lihat PetView.palette).
 */
object Mood {
    private const val DECAY_MS = 3 * 3_600_000L      // mood luruh ke netral setelah 3 jam
    private const val LONELY_MS = 8 * 3_600_000L     // diabaikan 8 jam → kesepian

    fun note(ctx: Context, emotion: PetView.Emotion) {
        ctx.moodState = emotion.name
        ctx.moodSince = System.currentTimeMillis()
        ctx.lastInteraction = System.currentTimeMillis()
    }

    fun noteInteraction(ctx: Context) {
        ctx.lastInteraction = System.currentTimeMillis()
    }

    /** Emosi baseline pet sekarang (untuk ditampilkan saat idle). */
    fun current(ctx: Context): PetView.Emotion {
        val now = System.currentTimeMillis()
        val stored = runCatching { PetView.Emotion.valueOf(ctx.moodState) }
            .getOrDefault(PetView.Emotion.NEUTRAL)
        val base = if (now - ctx.moodSince > DECAY_MS) PetView.Emotion.NEUTRAL else stored

        // mood kuat (marah/sayang/sedih) tetap dominan; netral bisa digeser konteks
        if (base != PetView.Emotion.NEUTRAL) return base

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour >= 23 || hour < 5) return PetView.Emotion.SLEEPY
        if (now - ctx.lastInteraction > LONELY_MS) return PetView.Emotion.SAD
        return PetView.Emotion.NEUTRAL
    }
}
