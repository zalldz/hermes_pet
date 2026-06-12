package com.hermespet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tubuh pet: karakter pixel-art 8-bit kawaii (sprite 16x16 digambar per piksel,
 * tanpa asset gambar). Pinky pastel berkuping, mata besar berkilau, pipi merona,
 * bob naik-turun saat berjalan ala game retro.
 */
class PetView(context: Context) : View(context) {

    enum class Emotion { NEUTRAL, HAPPY, SAD, SLEEPY, SURPRISED, LOVE }

    var emotion: Emotion = Emotion.NEUTRAL
        set(v) { field = v; invalidate() }

    /** true saat service sedang menggerakkan pet — memicu bob 8-bit */
    var walking: Boolean = false

    /** -1 = menghadap kiri, 1 = menghadap kanan */
    var facing: Int = 1

    // sprite dasar 16x16: '.'=transparan, O=outline, B=badan, L=kilau
    private val base = arrayOf(
        "....OO....OO....",
        "...OBBO..OBBO...",
        "..OBBBBOOBBBBO..",
        ".OBBBBBBBBBBBBO.",
        ".OBLLBBBBBBBBBO.",
        "OBBLBBBBBBBBBBBO",
        "OBBBBBBBBBBBBBBO",
        "OBBBBBBBBBBBBBBO",
        "OBBBBBBBBBBBBBBO",
        "OBBBBBBBBBBBBBBO",
        "OBBBBBBBBBBBBBBO",
        ".OBBBBBBBBBBBBO.",
        ".OBBBBBBBBBBBBO.",
        "..OBBBBBBBBBBO..",
        "...OBBBBBBBBO...",
        "....OOOOOOOO....",
    )

    // piksel tajam: TANPA anti-alias, khas 8-bit
    private fun crisp(c: String) = Paint().apply { color = Color.parseColor(c) }

    private val outline = crisp("#5D4157")
    private val body = crisp("#FFD1DC")
    private val light = crisp("#FFEAF1")
    private val eye = crisp("#3A2E39")
    private val white = crisp("#FFFFFF")
    private val blush = crisp("#FF9EB5")
    private val heart = crisp("#FF5D8F")
    private val tear = crisp("#7EC8E3")

    private var phase = 0f
    private var blink = false
    private var nextBlinkAt = System.currentTimeMillis() + 2000

    private val animator = object : Runnable {
        override fun run() {
            phase += 0.35f
            val now = System.currentTimeMillis()
            if (now > nextBlinkAt) {
                blink = !blink
                nextBlinkAt = now + if (blink) 130 else (1500 + Random.nextLong(3500))
            }
            invalidate()
            postDelayed(this, 80) // ~12 fps, biar terasa retro
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

        // bob 8-bit: lompat 1 piksel, lebih hidup saat jalan
        val bob = if (walking) {
            if (sin(phase) > 0) -1 else 0
        } else {
            if (sin(phase * 0.4f) > 0.7f) -1 else 0
        }

        canvas.save()
        canvas.translate(0f, bob * cell)
        if (facing < 0) canvas.scale(-1f, 1f, w / 2f, h / 2f)

        // badan dasar
        for (r in base.indices) {
            val row = base[r]
            for (c in row.indices) {
                when (row[c]) {
                    'O' -> px(canvas, c, r, outline)
                    'B' -> px(canvas, c, r, body)
                    'L' -> px(canvas, c, r, light)
                }
            }
        }

        // mata
        val sleepy = emotion == Emotion.SLEEPY
        when {
            blink || sleepy -> {
                // mata terpejam: garis datar
                px(canvas, 4, 7, eye); px(canvas, 5, 7, eye)
                px(canvas, 10, 7, eye); px(canvas, 11, 7, eye)
            }
            emotion == Emotion.LOVE -> {
                drawPixelHeart(canvas, 3, 6)
                drawPixelHeart(canvas, 10, 6)
            }
            else -> {
                // mata 2x3 dengan kilau putih
                for (r in 6..8) {
                    px(canvas, 4, r, eye); px(canvas, 5, r, eye)
                    px(canvas, 10, r, eye); px(canvas, 11, r, eye)
                }
                px(canvas, 4, 6, white)
                px(canvas, 10, 6, white)
            }
        }

        // pipi merona
        px(canvas, 2, 9, blush); px(canvas, 3, 9, blush)
        px(canvas, 12, 9, blush); px(canvas, 13, 9, blush)

        // mulut + aksesori emosi
        when (emotion) {
            Emotion.HAPPY, Emotion.LOVE -> { // senyum U
                px(canvas, 6, 9, eye); px(canvas, 9, 9, eye)
                px(canvas, 7, 10, eye); px(canvas, 8, 10, eye)
            }
            Emotion.NEUTRAL -> {
                px(canvas, 7, 10, eye); px(canvas, 8, 10, eye)
            }
            Emotion.SAD -> { // cemberut ∩ + air mata
                px(canvas, 7, 10, eye); px(canvas, 8, 10, eye)
                px(canvas, 6, 11, eye); px(canvas, 9, 11, eye)
                px(canvas, 12, 8, tear); px(canvas, 12, 9, tear)
            }
            Emotion.SURPRISED -> { // mulut o 2x2
                px(canvas, 7, 9, eye); px(canvas, 8, 9, eye)
                px(canvas, 7, 10, eye); px(canvas, 8, 10, eye)
            }
            Emotion.SLEEPY -> { // mulut kecil + Z melayang
                px(canvas, 7, 10, eye)
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
