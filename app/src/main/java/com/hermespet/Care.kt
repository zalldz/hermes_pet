package com.hermespet

import android.content.Context
import com.hermespet.Prefs.happiness
import com.hermespet.Prefs.hunger
import com.hermespet.Prefs.lastCareUpdate

/**
 * Rawat ala Tamagotchi: slime punya lapar & senang yang menurun seiring waktu,
 * naik saat diberi makan / diajak main. Memengaruhi suasana hati ([[Mood]]).
 * Hemat baterai — murni hitungan waktu, tanpa sensor.
 */
object Care {
    private const val HUNGER_PER_HOUR = 4.0f   // kenyang habis ~25 jam
    private const val HAPPY_PER_HOUR = 3.0f

    /** Terapkan penurunan sejak update terakhir; balikan (lapar, senang) 0..100. */
    fun refresh(ctx: Context): Pair<Float, Float> {
        val now = System.currentTimeMillis()
        val dtH = (now - ctx.lastCareUpdate) / 3_600_000f
        if (dtH > 0f) {
            ctx.hunger = ctx.hunger - HUNGER_PER_HOUR * dtH
            ctx.happiness = ctx.happiness - HAPPY_PER_HOUR * dtH
            ctx.lastCareUpdate = now
        }
        return ctx.hunger to ctx.happiness
    }

    fun feed(ctx: Context) {
        refresh(ctx)
        ctx.hunger = ctx.hunger + 35f
        ctx.happiness = ctx.happiness + 8f
    }

    /** Interaksi (poke/chat/main) menambah senang sedikit. */
    fun cheer(ctx: Context) {
        refresh(ctx)
        ctx.happiness = ctx.happiness + 5f
    }

    /** Pengaruh ke emosi baseline (null = tak memengaruhi). */
    fun moodHint(ctx: Context): PetView.Emotion? {
        val (h, hap) = refresh(ctx)
        return when {
            h < 18f -> PetView.Emotion.SAD          // kelaparan
            hap < 22f -> PetView.Emotion.SAD         // bosan/diabaikan
            hap > 78f && h > 55f -> PetView.Emotion.HAPPY
            else -> null
        }
    }

    fun veryHungry(ctx: Context): Boolean = refresh(ctx).first < 15f

    fun status(ctx: Context): String {
        val (h, hap) = refresh(ctx)
        return "🍽 Lapar ${(100 - h).toInt()}%   😊 Senang ${hap.toInt()}%"
    }
}
