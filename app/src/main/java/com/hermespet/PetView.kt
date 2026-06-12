package com.hermespet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tubuh pet: slime kawaii yang digambar di canvas (tanpa asset gambar).
 * Gel mint transparan, kilau glossy, mata besar berbinar, pipi merona,
 * dan animasi squish kenyal saat berjalan.
 */
class PetView(context: Context) : View(context) {

    enum class Emotion { NEUTRAL, HAPPY, SAD, SLEEPY, SURPRISED, LOVE }

    var emotion: Emotion = Emotion.NEUTRAL
        set(v) { field = v; invalidate() }

    /** true saat service sedang menggerakkan pet — memicu squish kenyal */
    var walking: Boolean = false

    /** -1 = menghadap kiri, 1 = menghadap kanan */
    var facing: Int = 1

    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7FE3C9"); alpha = 235
    }
    private val bodyShade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#54C7A8"); alpha = 235
    }
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; alpha = 110
    }
    private val sparkleWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; alpha = 200
    }
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33334D") }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val blushP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFA6C9"); alpha = 150
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33334D"); style = Paint.Style.STROKE
        strokeWidth = 4f; strokeCap = Paint.Cap.ROUND
    }
    private val tearP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7EC8E3") }
    private val heartP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF6B81") }
    private val zzz = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33334D"); alpha = 160
        textSize = 1f // di-set ulang di onDraw sesuai ukuran
        isFakeBoldText = true
    }

    private var phase = 0f
    private var blink = false
    private var nextBlinkAt = System.currentTimeMillis() + 2000

    private val animator = object : Runnable {
        override fun run() {
            phase += 0.22f
            val now = System.currentTimeMillis()
            if (now > nextBlinkAt) {
                blink = !blink
                nextBlinkAt = now + if (blink) 130 else (1500 + Random.nextLong(3500))
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
        val u = w / 100f
        stroke.strokeWidth = 1.2f * u
        zzz.textSize = 11f * u

        // squash & stretch kenyal, berpusat di dasar slime
        val squish = sin(phase) * (if (walking) 0.07f else 0.025f)
        canvas.save()
        canvas.scale(1f + squish, 1f - squish, w / 2f, h - 2f * u)
        if (facing < 0) canvas.scale(-1f, 1f, w / 2f, h / 2f)

        // badan: genangan bawah (lebih gelap, kesan jelly) + kubah
        canvas.drawOval(RectF(6f * u, 68f * u, 94f * u, 97f * u), bodyShade)
        canvas.drawOval(RectF(13f * u, 12f * u, 87f * u, 94f * u), body)

        // kilau glossy
        canvas.drawOval(RectF(22f * u, 20f * u, 42f * u, 38f * u), gloss)
        canvas.drawCircle(58f * u, 24f * u, 3.5f * u, sparkleWhite)

        // mata
        val sleepy = emotion == Emotion.SLEEPY
        if (blink || sleepy) {
            // mata terpejam: lengkung ∩ yang damai
            canvas.drawArc(RectF(28f * u, 48f * u, 42f * u, 60f * u), 180f, 180f, false, stroke)
            canvas.drawArc(RectF(58f * u, 48f * u, 72f * u, 60f * u), 180f, 180f, false, stroke)
        } else if (emotion == Emotion.LOVE) {
            drawHeart(canvas, 35f * u, 53f * u, 10f * u)
            drawHeart(canvas, 65f * u, 53f * u, 10f * u)
        } else {
            val grow = if (emotion == Emotion.SURPRISED) 1.6f * u else 0f
            // mata oval besar berbinar
            canvas.drawOval(RectF(29f * u - grow, 45f * u - grow, 41f * u + grow, 62f * u + grow), eye)
            canvas.drawOval(RectF(59f * u - grow, 45f * u - grow, 71f * u + grow, 62f * u + grow), eye)
            // binar: satu besar di atas, satu kecil di bawah
            canvas.drawCircle(32.5f * u, 49f * u, 2.4f * u, white)
            canvas.drawCircle(62.5f * u, 49f * u, 2.4f * u, white)
            canvas.drawCircle(38f * u, 57f * u, 1.2f * u, white)
            canvas.drawCircle(68f * u, 57f * u, 1.2f * u, white)
        }

        // pipi merona
        canvas.drawOval(RectF(17f * u, 60f * u, 29f * u, 67f * u), blushP)
        canvas.drawOval(RectF(71f * u, 60f * u, 83f * u, 67f * u), blushP)

        // mulut
        when (emotion) {
            Emotion.HAPPY, Emotion.LOVE ->
                canvas.drawArc(RectF(42f * u, 62f * u, 58f * u, 74f * u), 20f, 140f, false, stroke)
            Emotion.NEUTRAL ->
                canvas.drawArc(RectF(45f * u, 64f * u, 55f * u, 72f * u), 30f, 120f, false, stroke)
            Emotion.SAD -> {
                canvas.drawArc(RectF(43f * u, 70f * u, 57f * u, 80f * u), 200f, 140f, false, stroke)
                drawTear(canvas, 70f * u, 62f * u, 4f * u)
            }
            Emotion.SURPRISED ->
                canvas.drawOval(RectF(46f * u, 66f * u, 54f * u, 76f * u), eye)
            Emotion.SLEEPY -> {
                canvas.drawCircle(50f * u, 70f * u, 3f * u, eye)
                canvas.drawText("z", 76f * u, 30f * u, zzz)
                canvas.drawText("z", 84f * u, 20f * u, zzz)
            }
        }

        canvas.restore()
    }

    private fun drawHeart(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        canvas.drawCircle(cx - s * 0.35f, cy - s * 0.2f, s * 0.42f, heartP)
        canvas.drawCircle(cx + s * 0.35f, cy - s * 0.2f, s * 0.42f, heartP)
        val path = Path().apply {
            moveTo(cx - s * 0.72f, cy - s * 0.05f)
            lineTo(cx, cy + s * 0.7f)
            lineTo(cx + s * 0.72f, cy - s * 0.05f)
            close()
        }
        canvas.drawPath(path, heartP)
    }

    private fun drawTear(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val path = Path().apply {
            moveTo(cx, cy - s)
            quadTo(cx + s, cy + s * 0.6f, cx, cy + s)
            quadTo(cx - s, cy + s * 0.6f, cx, cy - s)
            close()
        }
        canvas.drawPath(path, tearP)
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
