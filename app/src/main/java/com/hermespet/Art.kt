package com.hermespet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Aset visual v2.1 (Kenney, CC0 — lihat assets/CREDITS.md). Dimuat sekali; kalau
 * gagal, nilainya null dan OrbView/PetView kembali ke gambar prosedural lama.
 */
object Art {
    /** Bulatan orb (UI Pack: button_round_line, abu). */
    var orb: Bitmap? = null; private set
    /** Cahaya lembut untuk glow orb (Particle Pack), putih → di-tint warna glow. */
    var glow: Bitmap? = null; private set
    /** Badan slime grayscale tanpa wajah (New Platformer Pack) → di-tint warna mood. */
    var slime: Bitmap? = null; private set

    fun load(ctx: Context) {
        if (orb != null || slime != null) return
        orb = read(ctx, "art/orb.png")
        glow = read(ctx, "art/glow.png")
        slime = read(ctx, "art/slime.png")
    }

    private fun read(ctx: Context, path: String): Bitmap? = runCatching {
        ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}
