package com.hermespet

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle

/**
 * Activity transparan yang meminta izin MediaProjection (dialog "mulai rekam?")
 * lalu menyalakan ScreenCaptureService. Tidak menampilkan UI.
 */
class CaptureActivity : Activity() {

    private var question: String = "Apa yang kamu lihat di layar?"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        question = intent.getStringExtra("question") ?: question
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 1001)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            val svc = Intent(this, ScreenCaptureService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
                .putExtra("question", question)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
        } else {
            PetOverlayService.instance?.onScreenCaptured(null, question)
        }
        finish()
    }
}
