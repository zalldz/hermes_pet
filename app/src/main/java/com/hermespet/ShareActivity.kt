package com.hermespet

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import java.io.ByteArrayOutputStream

/**
 * Menerima gambar (atau teks) yang di-Share ke HermesPet, mengubahnya jadi
 * JPEG base64, lalu menyerahkan ke pet untuk dipahami model vision.
 * Jauh lebih simpel & andal daripada MediaProjection — tanpa izin rekam.
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pet = PetOverlayService.instance
        if (pet == null) {
            Toast.makeText(this, "Hidupkan Mocipet dulu ya 🐾", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        val question = sharedText?.takeIf { it.isNotBlank() }
            ?: "Ini gambar apa? Jelaskan singkat, dan kalau aku minta carikan yang mirip, bantu cari."

        if (uri != null) {
            val b64 = runCatching { encode(uri) }.getOrNull()
            if (b64 != null) pet.onScreenCaptured(b64, question)
            else Toast.makeText(this, "Gagal baca gambarnya 😖", Toast.LENGTH_SHORT).show()
        } else if (!sharedText.isNullOrBlank()) {
            // hanya teks yang di-share → perlakukan sebagai chat biasa
            pet.onSharedText(sharedText)
        }
        finish()
    }

    /** Baca gambar dari URI, perkecil ke maks 1024px, JPEG base64. */
    private fun encode(uri: Uri): String {
        val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        // decode dengan sampel agar tidak OOM untuk foto besar
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: error("decode gagal")
        val scale = 1024f / maxOf(bmp.width, bmp.height)
        val small = if (scale < 1f)
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        else bmp
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
