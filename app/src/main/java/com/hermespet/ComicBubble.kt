package com.hermespet

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Gelembung bicara gaya komik: kotak membulat dengan garis tebal + ekor segitiga
 * yang menunjuk ke pet. [tailX] (piksel, relatif ke kiri gelembung) mengarahkan
 * ujung ekor ke posisi pet; negatif = otomatis di tengah.
 */
class ComicBubble(private val density: Float) : Drawable() {

    var tailX: Float = -1f
        set(value) { field = value; invalidateSelf() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2B2B3A")
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val body = Path()
    private val tail = Path()

    private fun px(v: Float) = v * density

    /** Tinggi ekor — dipakai pemanggil untuk menambah padding bawah. */
    fun tailHeightPx(): Int = px(13f).toInt()

    private fun rebuild(w: Float, h: Float) {
        val sw = line.strokeWidth
        val radius = px(17f)
        val tailH = px(13f)
        val tailW = px(20f)
        val bodyBottom = h - tailH

        body.reset()
        val rect = RectF(sw, sw, w - sw, bodyBottom)
        body.addRoundRect(rect, radius, radius, Path.Direction.CW)

        val lo = rect.left + tailW
        val hi = rect.right - tailW
        val cx = if (hi <= lo) w / 2f
        else (if (tailX < 0f) w / 2f else tailX).coerceIn(lo, hi)
        tail.reset()
        tail.moveTo(cx - tailW / 2f, bodyBottom - px(3f))
        tail.lineTo(cx - px(2f), h - sw)   // ujung menunjuk ke bawah (ke pet)
        tail.lineTo(cx + tailW / 2f, bodyBottom - px(3f))
        tail.close()

        path.reset()
        path.op(body, tail, Path.Op.UNION)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        rebuild(b.width().toFloat(), b.height().toFloat())
        canvas.drawPath(path, fill)
        canvas.drawPath(path, line)
    }

    // alpha & fade diatur lewat View.setAlpha (animasi gelembung), bukan di sini
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
