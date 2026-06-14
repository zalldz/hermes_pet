package com.hermespet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import java.io.ByteArrayOutputStream

/**
 * Menangkap SATU frame layar lewat MediaProjection (Android 14 mewajibkan ini
 * di foreground service bertipe mediaProjection), lalu menyerahkan hasilnya
 * (JPEG base64) ke pet untuk dipahami model vision. Setelah itu berhenti sendiri.
 */
class ScreenCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")
        val question = intent?.getStringExtra("question") ?: "Apa yang kamu lihat di layar?"
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        // sembunyikan pet sebentar supaya tidak ikut tertangkap
        PetOverlayService.instance?.setOverlayHidden(true)
        Handler(Looper.getMainLooper()).postDelayed({ capture(code, data, question) }, 120)
        return START_NOT_STICKY
    }

    private fun capture(code: Int, data: Intent, question: String) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(code, data)
        if (projection == null) { finish(null, question); return }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        // Android 14 wajib registerCallback sebelum createVirtualDisplay
        projection.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))
        var vd: VirtualDisplay? = null
        vd = projection.createVirtualDisplay(
            "hermespet-capture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )

        var done = false
        reader.setOnImageAvailableListener({ r ->
            if (done) return@setOnImageAvailableListener
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            done = true
            val b64 = try {
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val rowPadding = rowStride - pixelStride * w
                val bmp = Bitmap.createBitmap(
                    w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                encode(cropped)
            } catch (e: Exception) { null } finally {
                image.close()
            }
            runCatching { vd?.release() }
            runCatching { reader.close() }
            runCatching { projection.stop() }
            finish(b64, question)
        }, Handler(Looper.getMainLooper()))

        // jaring pengaman kalau frame tak kunjung datang
        Handler(Looper.getMainLooper()).postDelayed({
            if (!done) {
                done = true
                runCatching { vd?.release() }; runCatching { reader.close() }
                runCatching { projection.stop() }
                finish(null, question)
            }
        }, 2_500)
    }

    /** Perkecil ke lebar maks 1024 px lalu JPEG base64 (hemat token vision). */
    private fun encode(bmp: Bitmap): String {
        val scale = 1024f / maxOf(bmp.width, bmp.height)
        val small = if (scale < 1f)
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        else bmp
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun finish(b64: String?, question: String) {
        PetOverlayService.instance?.setOverlayHidden(false)
        PetOverlayService.instance?.onScreenCaptured(b64, question)
        stopSelf()
    }

    private fun startAsForeground() {
        val ch = "hermespet-capture"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ch, "Tangkap layar", NotificationManager.IMPORTANCE_LOW))
        val notif = Notification.Builder(this, ch)
            .setContentTitle("Mocipet melihat layar…")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(7, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(7, notif)
        }
    }
}
