package com.hermespet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tubuh pet: slime pixel-art 16x16 ala Stardew Valley yang BERGANTI WARNA
 * sesuai suasana hati — senang/hijau, sedih/biru, marah/merah, sayang/pink,
 * ngantuk/ungu — transisi warna mulus. Digambar per piksel tanpa anti-alias.
 */
class PetView(context: Context) : View(context) {

    enum class Emotion { NEUTRAL, HAPPY, SAD, SLEEPY, SURPRISED, LOVE, ANGRY }

    var emotion: Emotion = Emotion.NEUTRAL
        set(v) { field = v; invalidate() }

    var walking: Boolean = false
    var facing: Int = 1

    /** Hidupkan/matikan animasi — dipakai untuk hemat baterai saat layar mati. */
    var awake: Boolean = true
        set(v) {
            if (field == v) return
            field = v
            if (v) post(animator) else removeCallbacks(animator)
        }

    // sprite dasar 16x16: O=outline, B=badan, L=kilau, S=bayangan
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

    // warna per-mood: [body, light, shade, outline]
    private fun palette(e: Emotion): IntArray = when (e) {
        Emotion.SAD -> intArrayOf(0xFF6FB7E8.toInt(), 0xFFB3DEF7.toInt(), 0xFF3E84B5.toInt(), 0xFF244B66.toInt())
        Emotion.ANGRY -> intArrayOf(0xFFE8736B.toInt(), 0xFFF7B0AB.toInt(), 0xFFB5443E.toInt(), 0xFF6B2420.toInt())
        Emotion.LOVE -> intArrayOf(0xFFFF9EC4.toInt(), 0xFFFFD0E4.toInt(), 0xFFD45F92.toInt(), 0xFF7A2E50.toInt())
        Emotion.SLEEPY -> intArrayOf(0xFFA99BE0.toInt(), 0xFFD6CCF5.toInt(), 0xFF6F5FB0.toInt(), 0xFF3A2E66.toInt())
        else -> intArrayOf(0xFF7EDB63.toInt(), 0xFFB6F09C.toInt(), 0xFF4FA63E.toInt(), 0xFF2B4A23.toInt())
    }

    // warna saat ini (di-lerp menuju target tiap frame) → transisi mulus
    private val cur = palette(Emotion.NEUTRAL).copyOf()

    private fun crisp() = Paint()
    private val outline = crisp(); private val body = crisp()
    private val light = crisp(); private val shade = crisp()
    private val eye = Paint().apply { color = Color.parseColor("#2A2A35") }
    private val white = Paint().apply { color = Color.WHITE }
    private val blush = Paint().apply { color = Color.parseColor("#FF9EB5") }
    private val heart = Paint().apply { color = Color.parseColor("#FF5D8F") }
    private val tear = Paint().apply { color = Color.parseColor("#7EC8E3") }
    private val angryC = Paint().apply { color = Color.parseColor("#C62828") }

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
            if (awake) postDelayed(this, 16) // ~60fps; dimatikan saat layar off
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (awake) post(animator)
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

    private fun lerp(a: Int, b: Int, t: Float): Int {
        val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
        val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
        val r = (ar + (br - ar) * t).toInt()
        val g = (ag + (bg - ag) * t).toInt()
        val bl = (ab + (bb - ab) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        cell = minOf(w, h) / 16f
        offX = (w - cell * 16f) / 2f
        offY = (h - cell * 16f) / 2f

        // transisi warna mulus menuju palette mood saat ini
        val target = palette(emotion)
        for (i in cur.indices) cur[i] = lerp(cur[i], target[i], 0.12f)
        body.color = cur[0]; light.color = cur[1]; shade.color = cur[2]; outline.color = cur[3]

        val bobPx: Float
        val squash: Float
        if (walking) {
            val hop = kotlin.math.abs(sin(phase * 2.2f))
            bobPx = hop * cell * 1.3f
            squash = (1f - hop) * 0.09f
        } else {
            bobPx = (sin(phase * 0.45f) + 1f) * 0.5f * cell * 0.35f
            squash = sin(phase * 0.45f) * 0.03f
        }

        canvas.save()
        canvas.translate(0f, -bobPx)
        canvas.scale(1f + squash, 1f - squash, w / 2f, offY + cell * 15f)
        if (facing < 0) canvas.scale(-1f, 1f, w / 2f, h / 2f)

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

        val sleepy = emotion == Emotion.SLEEPY
        when {
            blink || sleepy -> {
                px(canvas, 4, 8, eye); px(canvas, 5, 8, eye)
                px(canvas, 10, 8, eye); px(canvas, 11, 8, eye)
            }
            emotion == Emotion.LOVE -> {
                drawPixelHeart(canvas, 3, 6); drawPixelHeart(canvas, 10, 6)
            }
            else -> {
                for (r in 6..9) {
                    px(canvas, 4, r, eye); px(canvas, 5, r, eye)
                    px(canvas, 10, r, eye); px(canvas, 11, r, eye)
                }
                px(canvas, 4, 6, white); px(canvas, 10, 6, white)
            }
        }

        // alis marah (diagonal) di atas mata
        if (emotion == Emotion.ANGRY) {
            px(canvas, 3, 5, angryC); px(canvas, 4, 5, eye); px(canvas, 5, 6, eye)
            px(canvas, 12, 5, angryC); px(canvas, 11, 5, eye); px(canvas, 10, 6, eye)
        }

        // pipi merona (tidak saat marah)
        if (emotion != Emotion.ANGRY) {
            px(canvas, 2, 10, blush); px(canvas, 3, 10, blush)
            px(canvas, 12, 10, blush); px(canvas, 13, 10, blush)
        }

        when (emotion) {
            Emotion.HAPPY, Emotion.LOVE -> {
                px(canvas, 6, 10, eye); px(canvas, 9, 10, eye)
                px(canvas, 7, 11, eye); px(canvas, 8, 11, eye)
            }
            Emotion.NEUTRAL -> { px(canvas, 7, 11, eye); px(canvas, 8, 11, eye) }
            Emotion.SAD -> {
                px(canvas, 7, 10, eye); px(canvas, 8, 10, eye)
                px(canvas, 6, 11, eye); px(canvas, 9, 11, eye)
                px(canvas, 12, 8, tear); px(canvas, 12, 9, tear)
            }
            Emotion.ANGRY -> { // mulut menggeram + uap
                px(canvas, 6, 11, eye); px(canvas, 7, 11, eye)
                px(canvas, 8, 11, eye); px(canvas, 9, 11, eye)
                px(canvas, 7, 10, eye); px(canvas, 8, 10, eye)
                px(canvas, 13, 4, white); px(canvas, 14, 3, white)
            }
            Emotion.SURPRISED -> {
                px(canvas, 7, 10, eye); px(canvas, 8, 10, eye)
                px(canvas, 7, 11, eye); px(canvas, 8, 11, eye)
            }
            Emotion.SLEEPY -> {
                px(canvas, 7, 11, eye)
                px(canvas, 12, 1, eye); px(canvas, 13, 1, eye); px(canvas, 14, 1, eye)
                px(canvas, 13, 2, eye)
                px(canvas, 12, 3, eye); px(canvas, 13, 3, eye); px(canvas, 14, 3, eye)
            }
        }

        canvas.restore()
    }

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
            "angry", "marah" -> Emotion.ANGRY
            else -> Emotion.NEUTRAL
        }
    }
}
