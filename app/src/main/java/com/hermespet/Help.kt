package com.hermespet

import android.app.Activity
import android.app.AlertDialog

/** Bantuan singkat cara berinteraksi dengan pet (dipakai wizard & pengaturan). */
object Help {
    fun gestures(act: Activity) {
        val msg = """
            👆  Tap        → colek pet (dia kaget)
            ✋  Tahan      → buka kotak chat (ketik)
            👆👆 Double-tap → perintah suara 🎤
            ✋  Seret      → pindahkan pet
            🤾  Lempar     → lempar, dia memantul
            🤏  Cubit      → ubah ukuran
            👁  (di chat)  → suruh dia lihat layar

            Diam 30 detik → Moci jadi ORB bulat transparan di tepi layar:
            ⚪ Tap → Moci keluar • Tap 2x → chat
            ⚪ Tahan → Glimpse (rangkum layar) / Ringkas notif / Sembunyikan 1 jam
            ⚪ Seret → pindah, nempel ke tepi terdekat
            Orb bersinar: biru notif chat • kuning otomasi • merah batas app

            Pet juga berganti WARNA sesuai perasaannya:
            hijau senang • biru sedih • merah kesal • ungu ngantuk
        """.trimIndent()
        AlertDialog.Builder(act)
            .setTitle("Cara main dengan pet 🐾")
            .setMessage(msg)
            .setPositiveButton("Oke!", null)
            .show()
    }
}
