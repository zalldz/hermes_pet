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

    /** Cara bergerak: IDLE diam, SLIDE meluncur/oozing rendah, HOP melompat. */
    var gait: String = "IDLE"

    /** Hidupkan/matikan animasi — dipakai untuk hemat baterai saat layar mati. */
    var awake: Boolean = true
        set(v) {
            if (field == v) return
            field = v
            if (v) post(animator) else removeCallbacks(animator)
        }

    // sprite slime 16x16: O outline, B badan, L kilau, S bayangan
    private val base = arrayOf(
        ".....OOOOOO.....",
        "...OOBBBBBBOO...",
        "..OBBBBBBBBBBO..",
        ".OBLLBBBBBBBBBO.",
        ".OLLLBBBBBBBBBO.",
        "OBLLBBBBBBBBBBBO",
        "OBLBBBBBBBBBBBBO",
        "OBBBBBBBBBBBBBBO",
        "OBBBBBBBBBBBBBBO",
        "OBBBBBBBBBBBBBBO",
        "OBBBBBBBBBBBBBBO",
        "OBBBBBBBBBBBBSBO",
        ".OBBBBBBBBBSSBO.",
        ".OSBBBBBBSSSSBO.",
        "..OOSSSSSSSSOO..",
        "....OOOOOOOO....",
    )

    // warna per-mood: [body, light, shade, outline]. Identitas slime = BIRU ceria;
    // sedih jadi biru lebih kelam & kusam supaya tetap beda.
    private fun palette(e: Emotion): IntArray = when (e) {
        Emotion.SAD -> intArrayOf(0xFF6E86AE.toInt(), 0xFFAEC0DC.toInt(), 0xFF4A5E80.toInt(), 0xFF2A3852.toInt())
        Emotion.ANGRY -> intArrayOf(0xFFE8736B.toInt(), 0xFFF7B0AB.toInt(), 0xFFB5443E.toInt(), 0xFF6B2420.toInt())
        Emotion.LOVE -> intArrayOf(0xFFFF9EC4.toInt(), 0xFFFFD0E4.toInt(), 0xFFD45F92.toInt(), 0xFF7A2E50.toInt())
        Emotion.SLEEPY -> intArrayOf(0xFFA99BE0.toInt(), 0xFFD6CCF5.toInt(), 0xFF6F5FB0.toInt(), 0xFF3A2E66.toInt())
        else -> intArrayOf(0xFF4FC3F7.toInt(), 0xFFBEEBFF.toInt(), 0xFF2A9BD4.toInt(), 0xFF15577A.toInt())
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
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#332A2A35") }

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
        // sprite hanya mengisi ~66% View → sisakan ruang (4 sel tiap sisi) agar
        // squash/stretch/condong/lompat tidak terpotong tepi (penyebab "kepotong")
        cell = minOf(w, h) / 24f
        offX = (w - cell * 16f) / 2f
        offY = (h - cell * 16f) / 2f

        // transisi warna mulus menuju palette mood saat ini
        val target = palette(emotion)
        for (i in cur.indices) cur[i] = lerp(cur[i], target[i], 0.12f)
        body.color = cur[0]; light.color = cur[1]; shade.color = cur[2]; outline.color = cur[3]

        // --- gerak slime: bervariasi (IDLE / SLIDE meluncur / HOP melompat) ---
        var bobPx: Float
        var squashSigned: Float // + = gepeng melebar, - = memanjang ke atas
        var shear: Float
        when (gait) {
            "HOP" -> {
                // siklus lompatan: meleleh dulu (mendekam) → melonjak memanjang → mendarat gepeng
                val t = (phase * 0.42f) % 1f
                val arc = sin(t.coerceIn(0f, 1f) * Math.PI.toFloat())
                bobPx = arc * cell * 1.7f
                squashSigned = when {
                    t < 0.22f -> (0.22f - t) / 0.22f * 0.24f      // mencair/mendekam (anticipation)
                    t > 0.86f -> (t - 0.86f) / 0.14f * 0.22f      // mendarat: gepeng
                    else -> -arc * 0.10f                          // di udara: memanjang
                }
                shear = facing * 0.035f                          // condong tipis ke arah lompat
            }
            "SLIDE" -> {
                // meluncur/oozing: tetap rendah & agak gepeng, riak halus, condong maju
                bobPx = (sin(phase * 1.1f) + 1f) * 0.5f * cell * 0.18f
                squashSigned = 0.11f + sin(phase * 2.0f) * 0.025f
                shear = facing * 0.11f + sin(phase * 1.3f) * 0.015f // condong maju (seperti mengalir)
            }
            else -> {
                // diam: napas lembut + goyangan SANGAT kalem
                val b = (sin(phase * 0.5f) + 1f) * 0.5f
                bobPx = b * cell * 0.35f
                squashSigned = (1f - b) * 0.05f
                shear = sin(phase * 1.2f) * 0.018f               // gooey dikurangi → lebih kalem
            }
        }
        val sx = 1f + squashSigned
        val sy = 1f - squashSigned

        val baseY = offY + cell * 15.5f // titik tumpu di kaki slime

        // bayangan memantul: melebar & pekat saat menyentuh tanah, mengecil saat melompat
        val onGround = (1f - (bobPx / (cell * 1.7f))).coerceIn(0f, 1f)
        val shadowHalf = cell * (4.2f + onGround * 2.3f)
        shadow.alpha = (45 + onGround * 55).toInt().coerceIn(0, 255)
        canvas.drawOval(
            w / 2f - shadowHalf, baseY + cell * 0.2f,
            w / 2f + shadowHalf, baseY + cell * 1.4f, shadow
        )

        canvas.save()
        canvas.translate(0f, -bobPx)
        // shear di sekitar kaki → atas bergoyang, dasar menempel (efek gooey)
        canvas.translate(w / 2f, baseY)
        canvas.skew(shear, 0f)
        canvas.translate(-w / 2f, -baseY)
        canvas.scale(sx, sy, w / 2f, baseY) // gepeng = melebar, lonjak = memanjang
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

        // mata BESAR: 3 piksel lebar (kiri cols 3-5, kanan cols 10-12)
        val sleepy = emotion == Emotion.SLEEPY
        when {
            blink || sleepy -> {
                for (c in 3..5) px(canvas, c, 8, eye)
                for (c in 10..12) px(canvas, c, 8, eye)
            }
            emotion == Emotion.LOVE -> {
                drawPixelHeart(canvas, 3, 6); drawPixelHeart(canvas, 10, 6)
            }
            else -> {
                val grow = if (emotion == Emotion.SURPRISED) 1 else 0
                for (r in (6 - grow)..(10 + grow)) {
                    for (c in 3..5) px(canvas, c, r, eye)
                    for (c in 10..12) px(canvas, c, r, eye)
                }
                // dua titik kilau tiap mata → efek berbinar besar
                px(canvas, 3, 6, white); px(canvas, 4, 6, white)
                px(canvas, 10, 6, white); px(canvas, 11, 6, white)
                px(canvas, 5, 9, white); px(canvas, 12, 9, white)
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
