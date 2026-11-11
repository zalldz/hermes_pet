package com.hermespet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.view.View
import kotlin.math.sin

/**
 * Ghost Orb (v2.1): bulatan 28dp semi-transparan ala tombol aksesibilitas
 * melayang. View-nya sedikit lebih besar dari bulatan supaya glow tidak terpotong
 * dan target sentuh tetap nyaman. Animasi hanya berjalan saat glow aktif.
 */
class OrbView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    val orbRadius = 14f * density

    /** Warna glow (0 = tidak bersinar). Biru = notif penting, kuning = otomasi, merah = batas app. */
    var glowColor: Int = 0
        set(v) {
            field = v
            removeCallbacks(pulse)
            if (v != 0) post(pulse)
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(102, 245, 245, 245) } // ~40%
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = Color.argb(140, 120, 120, 120)
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 110 } // ~40%
    private val glowArtP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private var phase = 0f

    private val pulse = object : Runnable {
        override fun run() {
            phase += 0.08f
            invalidate()
            if (glowColor != 0) postDelayed(this, 33L) // ~30fps cukup untuk denyut pelan
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(pulse)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val glowArt = Art.glow
        if (glowColor != 0 && glowArt != null) {
            val k = (sin(phase) + 1f) / 2f
            val r = orbRadius * (1.35f + 0.2f * k)
            glowArtP.colorFilter = PorterDuffColorFilter(glowColor, PorterDuff.Mode.SRC_IN)
            glowArtP.alpha = (120 + 110 * k).toInt()
            dst.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawBitmap(glowArt, null, dst, glowArtP)
        } else if (glowColor != 0) {
            val k = (sin(phase) + 1f) / 2f // 0..1
            for (i in 3 downTo 1) {
                glowP.color = glowColor
                glowP.alpha = (40 + 50 * k).toInt() / i
                canvas.drawCircle(cx, cy, orbRadius + i * 2.5f * density * (0.6f + 0.4f * k), glowP)
            }
        }
        val art = Art.orb
        if (art != null) {
            dst.set(cx - orbRadius, cy - orbRadius, cx + orbRadius, cy + orbRadius)
            canvas.drawBitmap(art, null, dst, artP)
        } else { // fallback prosedural
            canvas.drawCircle(cx, cy, orbRadius, fill)
            canvas.drawCircle(cx, cy, orbRadius, ring)
            canvas.drawCircle(cx, cy, orbRadius * 0.45f, core)
        }
    }
}
