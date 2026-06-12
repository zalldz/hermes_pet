package com.hermespet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tubuh pet: blob lucu yang digambar di canvas (tanpa asset gambar).
 * Punya emosi, kedipan mata, dan goyangan saat berjalan.
 */
class PetView(context: Context) : View(context) {

    enum class Emotion { NEUTRAL, HAPPY, SAD, SLEEPY, SURPRISED, LOVE }

    var emotion: Emotion = Emotion.NEUTRAL
        set(v) { field = v; invalidate() }

    /** true saat service sedang menggerakkan pet — memicu animasi goyang */
    var walking: Boolean = false

    /** -1 = menghadap kiri, 1 = menghadap kanan */
    var facing: Int = 1

    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFB74D") }
    private val bodyDark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F57C00") }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3E2723") }
    private val blush = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#66FF8A80") }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3E2723"); style = Paint.Style.STROKE; strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    private var phase = 0f
    private var blink = false
    private var nextBlinkAt = System.currentTimeMillis() + 2000

    private val animator = object : Runnable {
        override fun run() {
            phase += 0.25f
            val now = System.currentTimeMillis()
            if (now > nextBlinkAt) {
                blink = !blink
                nextBlinkAt = now + if (blink) 140 else (1500 + Random.nextLong(3000))
            }
            invalidate()
            postDelayed(this, 50)
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

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val u = w / 100f // satuan relatif supaya scaling rapi

        val bob = if (walking) abs(sin(phase)) * 4f * u else sin(phase * 0.3f) * 1.5f * u
        canvas.save()
        canvas.translate(0f, -bob)
        if (facing < 0) {
            canvas.scale(-1f, 1f, w / 2f, h / 2f)
        }

        // kaki
        val legSwing = if (walking) sin(phase) * 6f * u else 0f
        canvas.drawOval(RectF(28f * u - legSwing, h - 16f * u, 46f * u - legSwing, h - 2f * u), bodyDark)
        canvas.drawOval(RectF(54f * u + legSwing, h - 16f * u, 72f * u + legSwing, h - 2f * u), bodyDark)

        // badan
        canvas.drawOval(RectF(10f * u, 14f * u, 90f * u, h - 10f * u), body)

        // telinga kecil
        canvas.drawOval(RectF(22f * u, 6f * u, 38f * u, 24f * u), bodyDark)
        canvas.drawOval(RectF(62f * u, 6f * u, 78f * u, 24f * u), bodyDark)

        // mata
        val eyeY = 40f * u
        val sleepy = emotion == Emotion.SLEEPY
        if (blink || sleepy) {
            canvas.drawLine(28f * u, eyeY, 42f * u, eyeY, stroke)
            canvas.drawLine(58f * u, eyeY, 72f * u, eyeY, stroke)
        } else if (emotion == Emotion.LOVE) {
            drawHeart(canvas, 35f * u, eyeY, 9f * u)
            drawHeart(canvas, 65f * u, eyeY, 9f * u)
        } else {
            val r = if (emotion == Emotion.SURPRISED) 9f * u else 7f * u
            canvas.drawCircle(35f * u, eyeY, r, white)
            canvas.drawCircle(65f * u, eyeY, r, white)
            canvas.drawCircle(36f * u, eyeY, r * 0.55f, black)
            canvas.drawCircle(66f * u, eyeY, r * 0.55f, black)
        }

        // pipi
        if (emotion == Emotion.HAPPY || emotion == Emotion.LOVE) {
            canvas.drawCircle(24f * u, 52f * u, 6f * u, blush)
            canvas.drawCircle(76f * u, 52f * u, 6f * u, blush)
        }

        // mulut
        val mouth = RectF(40f * u, 50f * u, 60f * u, 64f * u)
        when (emotion) {
            Emotion.HAPPY, Emotion.LOVE -> canvas.drawArc(mouth, 20f, 140f, false, stroke)
            Emotion.SAD -> {
                mouth.offset(0f, 8f * u)
                canvas.drawArc(mouth, 200f, 140f, false, stroke)
            }
            Emotion.SURPRISED -> canvas.drawCircle(50f * u, 58f * u, 5f * u, black)
            Emotion.SLEEPY -> canvas.drawCircle(50f * u, 59f * u, 3.5f * u, black)
            Emotion.NEUTRAL -> canvas.drawLine(43f * u, 58f * u, 57f * u, 58f * u, stroke)
        }

        canvas.restore()
    }

    private fun drawHeart(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E53935") }
        canvas.drawCircle(cx - s * 0.35f, cy - s * 0.2f, s * 0.42f, p)
        canvas.drawCircle(cx + s * 0.35f, cy - s * 0.2f, s * 0.42f, p)
        val path = android.graphics.Path().apply {
            moveTo(cx - s * 0.72f, cy - s * 0.05f)
            lineTo(cx, cy + s * 0.7f)
            lineTo(cx + s * 0.72f, cy - s * 0.05f)
            close()
        }
        canvas.drawPath(path, p)
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
