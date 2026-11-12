package com.hermespet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.SoundPool
import com.hermespet.Prefs.sfxOn

/** Aset Kenney (CC0): efek suara dan emote. Semua gagal-diam kalau berkas tidak ada. */
object Kenney {
    private val bmps = HashMap<String, Bitmap?>()

    fun bitmap(ctx: Context, path: String): Bitmap? = bmps.getOrPut(path) {
        runCatching { ctx.assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull()
    }

    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private val ready = HashSet<Int>()

    private fun pool(ctx: Context): SoundPool = pool ?: SoundPool.Builder().setMaxStreams(3)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build().also { p ->
            p.setOnLoadCompleteListener { _, id, st -> if (st == 0) ready += id }
            pool = p
            for (n in listOf("open_001", "click_001", "confirmation_001", "error_001",
                "question_001", "select_001", "drop_001")) runCatching {
                ids[n] = p.load(ctx.applicationContext.assets.openFd("sfx/$n.ogg"), 1)
            }
        }

    fun sfx(ctx: Context, name: String) {
        if (!ctx.sfxOn) return
        val p = pool(ctx)
        val id = ids[name] ?: return
        if (id in ready) p.play(id, 0.6f, 0.6f, 1, 0, 1f)
    }

    /** Suara untuk emosi baru (netral = diam). */
    fun sfxFor(ctx: Context, e: PetView.Emotion) = when (e) {
        PetView.Emotion.HAPPY, PetView.Emotion.LOVE -> sfx(ctx, "confirmation_001")
        PetView.Emotion.SAD, PetView.Emotion.ANGRY -> sfx(ctx, "error_001")
        PetView.Emotion.SURPRISED -> sfx(ctx, "question_001")
        else -> {}
    }

    fun emoteFor(e: PetView.Emotion): String? = when (e) {
        PetView.Emotion.HAPPY -> "faceHappy"
        PetView.Emotion.LOVE -> "heart"
        PetView.Emotion.SAD -> "faceSad"
        PetView.Emotion.ANGRY -> "faceAngry"
        PetView.Emotion.SURPRISED -> "exclamation"
        PetView.Emotion.SLEEPY -> "sleeps"
        else -> null
    }

    val animals = listOf("penguin" to "Pinguin", "pig" to "Babi", "rabbit" to "Kelinci",
        "panda" to "Panda", "monkey" to "Monyet", "elephant" to "Gajah", "giraffe" to "Jerapah",
        "hippo" to "Kuda nil", "parrot" to "Beo", "snake" to "Ular")
}
