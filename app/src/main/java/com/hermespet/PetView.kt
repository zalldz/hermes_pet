package com.hermespet

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Path
import android.view.View
import com.hermespet.Prefs.petSkin
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
        set(v) { if (field != v) onEmotionChange(v); field = v; invalidate() }

    var facing: Int = 1

    /**
     * Fase lompatan ala slime Stardew, di-set service tiap frame:
     * -1 = diam (denyut pelan), 0..1 = siklus lompat (mendekam→melayang→mendarat).
     */
    var hopT: Float = -1f

    /** Hidupkan/matikan animasi — dipakai untuk hemat baterai saat layar mati. */
    var awake: Boolean = true
        set(v) {
            if (field == v) return
            field = v
            if (v) post(animator) else removeCallbacks(animator)
        }

    // sprite slime ala Stardew Valley (berantena) 16x16: O outline, B badan, L kilau, S bayangan
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

    // warna badan = SUASANA HATI (ekspresif): [body, light, shade, outline]
    private fun palette(e: Emotion): IntArray = when (e) {
        Emotion.HAPPY, Emotion.LOVE -> intArrayOf(0xFF6FE3A6.toInt(), 0xFFCFF7E2.toInt(), 0xFF3BBE7C.toInt(), 0xFF1E7A4E.toInt()) // hijau ceria
        Emotion.SAD -> intArrayOf(0xFF6E86AE.toInt(), 0xFFAEC0DC.toInt(), 0xFF4A5E80.toInt(), 0xFF2A3852.toInt())   // biru kelam
        Emotion.ANGRY -> intArrayOf(0xFFE8736B.toInt(), 0xFFF7B0AB.toInt(), 0xFFB5443E.toInt(), 0xFF6B2420.toInt()) // merah
        Emotion.SLEEPY -> intArrayOf(0xFFA99BE0.toInt(), 0xFFD6CCF5.toInt(), 0xFF6F5FB0.toInt(), 0xFF3A2E66.toInt()) // ungu
        Emotion.SURPRISED -> intArrayOf(0xFFF2D06B.toInt(), 0xFFFBEAB0.toInt(), 0xFFCBA13E.toInt(), 0xFF6E5A1E.toInt()) // kuning
        else -> intArrayOf(0xFF4FC3F7.toInt(), 0xFFBEEBFF.toInt(), 0xFF2A9BD4.toInt(), 0xFF15577A.toInt())          // biru netral
    }

    /** Warna badan untuk emosi sekarang (dipakai pill mode). */
    fun bodyColor(): Int = palette(emotion)[0]

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

    // skin Kenney (grayscale, di-tint warna mood); null = slime piksel klasik
    private val spriteP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val spriteDst = RectF()
    private var skinName = "classic"
    private var skinBmp: Bitmap? = null
    private var skinCheckAt = 0L

    private fun refreshSkin() {
        val now = System.currentTimeMillis()
        if (now < skinCheckAt) return
        skinCheckAt = now + 1000
        val want = context.petSkin
        if (want == skinName) return
        skinName = want
        // gagal muat → null → kembali ke piksel klasik
        skinBmp = if (want == "classic") null else runCatching {
            context.assets.open(if (want.startsWith("animal_")) "animal/${want.removePrefix("animal_")}.png" else "art/slime_$want.png").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }
    private var blink = false
    private var nextBlinkAt = System.currentTimeMillis() + 2000

    // efek gepeng saat membentur (dipakai fisika lempar)
    private var impact = 0f
    private var impactAxisX = false
    fun bump(axisX: Boolean) { impact = 1f; impactAxisX = axisX; invalidate() }

    /** Energi musik 0..1 → slime ikut bergoyang (di-set service dari Visualizer). */
    var beat = 0f


    // --- partikel reaksi (hati/percik/marah) ---
    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float,
                           var life: Float, val kind: Char)
    private val particles = ArrayList<Particle>()
    private val sparkP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFE082") }
    private var vw = 0f; private var vh = 0f

    private fun onEmotionChange(v: Emotion) {
        val kind = when (v) {
            Emotion.LOVE -> 'H'; Emotion.HAPPY -> 'S'
            Emotion.SURPRISED -> 'S'; Emotion.ANGRY -> 'A'; else -> return
        }
        spawnParticles(kind, if (kind == 'A') 5 else 6)
    }

    private fun spawnParticles(kind: Char, n: Int) {
        if (vw <= 0f) return
        repeat(n) {
            val px = vw * (0.3f + Random.nextFloat() * 0.4f)
            val py = vh * 0.35f
            particles.add(Particle(px, py,
                (Random.nextFloat() - 0.5f) * vw * 0.012f,
                -vh * (0.006f + Random.nextFloat() * 0.006f),
                1f, kind))
        }
    }

    private val animator = object : Runnable {
        override fun run() {
            phase += 0.13f
            if (impact > 0.01f) impact *= 0.82f else impact = 0f
            if (beat > 0.01f) beat *= 0.86f else beat = 0f // luruh antar-ketukan
            if (particles.isNotEmpty()) {
                val it = particles.iterator()
                while (it.hasNext()) {
                    val p = it.next()
                    p.x += p.vx; p.y += p.vy; p.vy += vh * 0.0002f; p.life -= 0.02f
                    if (p.life <= 0f) it.remove()
                }
            }
            val now = System.currentTimeMillis()
            if (now > nextBlinkAt) {
                blink = !blink
                nextBlinkAt = now + if (blink) 130 else (1500 + Random.nextLong(3500))
            }
            invalidate()
            // hemat baterai: 60fps hanya saat sibuk (lompat/partikel/musik/benturan),
            // saat diam turun ke ~20fps (napas/kedip tetap halus)
            val busy = hopT in 0f..1f || particles.isNotEmpty() || beat > 0.01f || impact > 0.01f
            if (awake) postDelayed(this, if (busy) 16L else 50L)
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
        vw = w; vh = h
        // sprite hanya mengisi ~66% View → sisakan ruang (4 sel tiap sisi) agar
        // squash/stretch/condong/lompat tidak terpotong tepi (penyebab "kepotong")
        cell = minOf(w, h) / 24f
        offX = (w - cell * 16f) / 2f
        offY = (h - cell * 16f) / 2f

        refreshSkin()
        // transisi warna mulus menuju palette suasana hati
        val target = palette(emotion)
        for (i in cur.indices) cur[i] = lerp(cur[i], target[i], 0.12f)
        body.color = cur[0]; light.color = cur[1]; shade.color = cur[2]; outline.color = cur[3]

        // --- gerak ala slime Stardew Valley: diam = denyut pelan menempel tanah;
        // lompat = mendekam → busur → mendarat gepeng. Tanpa bayangan, tanpa shear.
        val bobPx: Float
        val squashSigned: Float // + = gepeng melebar, - = memanjang ke atas
        if (hopT in 0f..1f) {
            val t = hopT
            val arc = sin(t * Math.PI.toFloat())
            bobPx = arc * cell * 1.4f
            squashSigned = when {
                t < 0.18f -> (0.18f - t) / 0.18f * 0.16f   // mendekam sebelum lompat
                t > 0.85f -> (t - 0.85f) / 0.15f * 0.18f   // gepeng saat mendarat
                else -> -arc * 0.08f                        // memanjang tipis di udara
            }
        } else if (beat > 0.02f) {
            // berjoget mengikuti musik: memantul & sedikit gepeng sesuai energi
            bobPx = beat * cell * 1.3f
            squashSigned = -beat * 0.10f + sin(phase * 0.8f) * 0.03f
        } else {
            // denyut idle khas slime Stardew — dasar tetap MENEMPEL tanah
            bobPx = 0f
            squashSigned = sin(phase * 0.8f) * 0.045f
        }
        var sx = 1f + squashSigned
        var sy = 1f - squashSigned
        // benturan: gepeng pada sumbu tumbukan (lantai = pipih, dinding = ramping)
        if (impact > 0f) {
            if (impactAxisX) { sx *= 1f - 0.28f * impact; sy *= 1f + 0.20f * impact }
            else { sy *= 1f - 0.32f * impact; sx *= 1f + 0.24f * impact }
        }
        val baseY = offY + cell * 15f // dasar sprite = "tanah"

        canvas.save()
        canvas.translate(0f, -bobPx)
        canvas.scale(sx, sy, w / 2f, baseY)
        if (facing < 0) canvas.scale(-1f, 1f, w / 2f, h / 2f)

        val sprite = skinBmp
        val animal = sprite != null && skinName.startsWith("animal_")
        if (sprite != null) {
            spriteP.colorFilter = if (animal) null else LightingColorFilter(cur[0], 0)
            val hCells = 16f * sprite.height / sprite.width
            spriteDst.set(offX, offY + cell * (15f - hCells), offX + cell * 16f, offY + cell * 15f)
            canvas.drawBitmap(sprite, null, spriteDst, spriteP)
        } else for (r in base.indices) {
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

        // mata proporsional khas Stardew (2 piksel lebar) — ekspresi tetap terbaca
        // skin hewan sudah punya wajah sendiri → ekspresi lewat emote saja
        if (!animal) drawFace(canvas)

        canvas.restore()

        // emote Kenney melayang di atas kepala, ikut denyut
        Kenney.emoteFor(emotion)?.let { n ->
            Kenney.bitmap(context, "emote/$n.png")?.let { b ->
                val sz = cell * 5f
                val cx = w / 2f + cell * 4f
                val cy = offY - cell * 1.5f - bobPx + sin(phase * 1.2f) * cell * 0.4f
                spriteDst.set(cx - sz / 2, cy - sz / 2, cx + sz / 2, cy + sz / 2)
                spriteP.colorFilter = null; spriteP.isFilterBitmap = false
                canvas.drawBitmap(b, null, spriteDst, spriteP)
                spriteP.isFilterBitmap = true
            }
        }
        drawParticles(canvas)
    }

    private fun drawFace(canvas: Canvas) {
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
                val grow = if (emotion == Emotion.SURPRISED) 1 else 0
                for (r in (6 - grow)..(9 + grow)) {
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

    }

    private fun drawParticles(canvas: Canvas) {
        // partikel reaksi (di atas tubuh, koordinat layar)
        for (p in particles) {
            val a = (p.life.coerceIn(0f, 1f) * 255).toInt()
            val s = cell * 1.6f
            when (p.kind) {
                'H' -> { heart.alpha = a; drawHeartAt(canvas, p.x, p.y, s) }
                'A' -> { angryC.alpha = a; canvas.drawCircle(p.x, p.y, s * 0.45f, angryC) }
                else -> { sparkP.alpha = a; drawSpark(canvas, p.x, p.y, s * 0.7f) }
            }
        }
        heart.alpha = 255; angryC.alpha = 255; sparkP.alpha = 255
    }

    private fun drawHeartAt(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        canvas.drawCircle(cx - s * 0.3f, cy - s * 0.15f, s * 0.35f, heart)
        canvas.drawCircle(cx + s * 0.3f, cy - s * 0.15f, s * 0.35f, heart)
        val path = Path().apply {
            moveTo(cx - s * 0.6f, cy); lineTo(cx, cy + s * 0.6f); lineTo(cx + s * 0.6f, cy); close()
        }
        canvas.drawPath(path, heart)
    }

    private fun drawSpark(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        canvas.drawRect(cx - s * 0.12f, cy - s, cx + s * 0.12f, cy + s, sparkP)
        canvas.drawRect(cx - s, cy - s * 0.12f, cx + s, cy + s * 0.12f, sparkP)
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
