package com.hermespet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tubuh pet: slime pixel-art 16x16 terinspirasi slime Stardew Valley —
 * badan lebar gepeng, antena berbola di kepala, mata besar gelap, gradasi
 * terang di atas / gelap di bawah — plus pipi merona biar kawaii.
 * Digambar per piksel tanpa anti-alias (tajam khas game retro).
 */
class PetView(context: Context) : View(context) {

    enum class Emotion { NEUTRAL, HAPPY, SAD, SLEEPY, SURPRISED, LOVE }

    var emotion: Emotion = Emotion.NEUTRAL
        set(v) { field = v; invalidate() }

    /** true saat service sedang menggerakkan pet — memicu bob 8-bit */
    var walking: Boolean = false

    /** -1 = menghadap kiri, 1 = menghadap kanan */
    var facing: Int = 1

    // sprite dasar 16x16: '.'=transparan, O=outline, B=badan,
    // L=kilau terang, S=bayangan gelap. Baris 0-2 = antena berbola.
    private val base = arrayOf(
        ".......OO.......",
        "......OBBO......",
        ".......OO.......",
        "....OOOOOOOO....",
        "..OOBLLBBBBBOO..",
        ".OBLLBBBBBBBBBO.",
        ".OBLBBBBBBBBBBO.",
        "OBBBBBBBBBBBBBBO",
        "OBBBBBBBBBBBBBBO",
        "OBBBBBBBBBBBBBBO",
        "OBBBBBBBBBBBBSBO",
        "OBBBBBBBBBBSSSBO",
        ".OSBBBBBBSSSSSO.",
        ".OSSSSSSSSSSSSO.",
        "..OOOOOOOOOOOO..",
        "................",
    )

    // piksel tajam: TANPA anti-alias, khas 8-bit
    private fun crisp(c: String) = Paint().apply { color = Color.parseColor(c) }

    private val outline = crisp("#2B4A23")
    private val body = crisp("#7EDB63")
    private val light = crisp("#B6F09C")
    private val shade = crisp("#4FA63E")
    private val eye = crisp("#2A2A35")
    private val white = crisp("#FFFFFF")
    private val blush = crisp("#FF9EB5")
    private val heart = crisp("#FF5D8F")
    private val tear = crisp("#7EC8E3")

    private var phase = 0f
    private var blink = false
    private var nextBlinkAt = System.currentTimeMillis() + 2000

    private val animator = object : Runnable {
        override fun run() {
            phase += 0.13f
            val now = System.currentTimeMillis()
            if (now > nextBlinkAt) {
                blink = !blink
                nextBlinkAt = now + if (blink) 130 else (1500 + Random.nextLong(3500))
            }
            invalidate()
            postDelayed(this, 16) // ~60 fps — piksel tetap tajam, gerak mulus
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(animator)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(animator)
        super.onDetachedFromWindow()
    }

    private var cell = 0f
    private var offX = 0f
    private var offY = 0f

    private fun px(canvas: Canvas, col: Int, row: Int, paint: Paint) {
        val x = offX + col * cell
        val y = offY + row * cell
        canvas.drawRect(x, y, x + cell, y + cell, paint)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        cell = minOf(w, h) / 16f
        offX = (w - cell * 16f) / 2f
        offY = (h - cell * 16f) / 2f

        // gerak halus sub-piksel: hop sinus saat jalan, "napas" pelan saat diam,
        // plus squash & stretch ala jelly berpusat di kaki
        val bobPx: Float
        val squash: Float
        if (walking) {
            val hop = kotlin.math.abs(sin(phase * 2.2f))
            bobPx = hop * cell * 1.3f
            squash = (1f - hop) * 0.09f // menyentuh tanah = sedikit gepeng
        } else {
            bobPx = (sin(phase * 0.45f) + 1f) * 0.5f * cell * 0.35f
            squash = sin(phase * 0.45f) * 0.03f
        }

        canvas.save()
        canvas.translate(0f, -bobPx)
        canvas.scale(1f + squash, 1f - squash, w / 2f, offY + cell * 15f)
        if (facing < 0) canvas.scale(-1f, 1f, w / 2f, h / 2f)

        // badan dasar
        for (r in base.indices) {
            val row = base[r]
            for (c in row.indices) {
                when (row[c]) {
                    'O' -> px(canvas, c, r, outline)
                    'B' -> px(canvas, c, r, body)
                    'L' -> px(canvas, c, r, light)
                    'S' -> px(canvas, c, r, shade)
                }
            }
        }

        // mata besar khas slime Stardew (2x4) dengan kilau putih
        val sleepy = emotion == Emotion.SLEEPY
        when {
            blink || sleepy -> {
                px(canvas, 4, 8, eye); px(canvas, 5, 8, eye)
                px(canvas, 10, 8, eye); px(canvas, 11, 8, eye)
            }
            emotion == Emotion.LOVE -> {
                drawPixelHeart(canvas, 3, 6)
                drawPixelHeart(canvas, 10, 6)
            }
            else -> {
                for (r in 6..9) {
                    px(canvas, 4, r, eye); px(canvas, 5, r, eye)
                    px(canvas, 10, r, eye); px(canvas, 11, r, eye)
                }
                px(canvas, 4, 6, white)
                px(canvas, 10, 6, white)
            }
        }

        // pipi merona
        px(canvas, 2, 10, blush); px(canvas, 3, 10, blush)
        px(canvas, 12, 10, blush); px(canvas, 13, 10, blush)

        // mulut + aksesori emosi
        when (emotion) {
            Emotion.HAPPY, Emotion.LOVE -> { // senyum U
                px(canvas, 6, 10, eye); px(canvas, 9, 10, eye)
                px(canvas, 7, 11, eye); px(canvas, 8, 11, eye)
            }
            Emotion.NEUTRAL -> {
                px(canvas, 7, 11, eye); px(canvas, 8, 11, eye)
            }
            Emotion.SAD -> { // cemberut ∩ + air mata
                px(canvas, 7, 10, eye); px(canvas, 8, 10, eye)
                px(canvas, 6, 11, eye); px(canvas, 9, 11, eye)
                px(canvas, 12, 8, tear); px(canvas, 12, 9, tear)
            }
            Emotion.SURPRISED -> { // mulut o 2x2
                px(canvas, 7, 10, eye); px(canvas, 8, 10, eye)
                px(canvas, 7, 11, eye); px(canvas, 8, 11, eye)
            }
            Emotion.SLEEPY -> { // mulut kecil + Z melayang
                px(canvas, 7, 11, eye)
                px(canvas, 12, 1, eye); px(canvas, 13, 1, eye); px(canvas, 14, 1, eye)
                px(canvas, 13, 2, eye)
                px(canvas, 12, 3, eye); px(canvas, 13, 3, eye); px(canvas, 14, 3, eye)
            }
        }

        canvas.restore()
    }

    /** Hati piksel 3x3, pojok kiri-atas di (col,row). */
    private fun drawPixelHeart(canvas: Canvas, col: Int, row: Int) {
        px(canvas, col, row, heart); px(canvas, col + 2, row, heart)
        px(canvas, col, row + 1, heart); px(canvas, col + 1, row + 1, heart)
        px(canvas, col + 2, row + 1, heart)
        px(canvas, col + 1, row + 2, heart)
    }

    companion object {
        fun emotionFrom(s: String?): Emotion = when (s?.lowercase()) {
            "happy", "senang" -> Emotion.HAPPY
            "sad", "sedih" -> Emotion.SAD
            "sleepy", "ngantuk" -> Emotion.SLEEPY
            "surprised", "kaget" -> Emotion.SURPRISED
            "love", "sayang" -> Emotion.LOVE
            else -> Emotion.NEUTRAL
        }
    }
}
