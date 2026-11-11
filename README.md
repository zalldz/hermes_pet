# 🐾 Mocipet — v2.0 "super smart pet"

**Moci** adalah pet virtual slime cerdas yang jalan-jalan sebagai **overlay di atas
semua aplikasi** di Android. Bisa ngobrol (streaming), lihat layar, kontrol HP,
multi-step, punya memori & perasaan — semua dalam **satu aplikasi Android** yang ramping.

## Fitur

- **Slime hidup** — jalan ala Stardew, berganti warna sesuai mood, dilempar memantul,
  bisa di-resize (pinch) & dipindah (drag), joget saat ada musik.
- **Ngobrol** — tahan slime untuk kotak chat (ketik), double-tap untuk perintah suara;
  jawabannya dibacakan (TTS) dan muncul mengetik.
- **Lihat gambar** — Share foto ke HermesPet, atau tombol 👁, lalu pet menjelaskannya
  (butuh model vision, lihat di bawah).
- **Kontrol HP** — volume, senter, kecerahan, musik, buka aplikasi apa pun, panel
  WiFi/Bluetooth, Jangan Ganggu, alarm, pengingat, catatan, cek lokasi/GPS.
- **Indra** — notifikasi masuk, cuaca (ikut lokasi), cahaya, langkah, kalender, baterai,
  cek mood lewat kamera depan (opsional).
- **Rawat (Tamagotchi)** — kasih makan 🍬, dia senang; diabaikan, dia lesu.
- **Memori & skill** — ingat hal penting tentangmu; bisa membuat skill/makro sendiri.

## Baru di v2.0 — Moci mengelola HP-mu

**Fase 1 — Tangan (Aksesibilitas)**
- Moci bisa **membaca layar, tap, ketik, scroll, tekan Back/Home/Recents** di aplikasi lain.
- Tugas multi-langkah: *"buka WhatsApp, kirim pesan ke Budi: otw"* → Moci membuka app,
  membaca layar, mencari kontak, mengetik, lalu **minta konfirmasimu** sebelum menekan Kirim.
- Maksimal 8 putaran per perintah; tombol kirim/bayar/hapus/telepon selalu dikonfirmasi.
- Aktifkan di wizard: **🖐 Aktifkan aksesibilitas**. Di Android 13+ (APK sideload) kalau
  tombolnya abu-abu: Info aplikasi → ⋮ → *Izinkan setelan terbatas*.

**Fase 2 — Pill mode (dynamic island)**
- Diam tanpa disentuh 30 detik → Moci menyusut jadi **pill kecil di atas-tengah layar**,
  warnanya ikut mood & berdenyut pelan; jalan/fisika/animasi slime **berhenti** (hemat baterai).
- **Ketuk pill** → mekar, slime jatuh memantul ke bawah, kembali jadi pet.
- Notifikasi masuk saat mode pill → pill berkedip + **badge jumlah** (🔔 3), tanpa membuka.
- Atur lama diamnya di Pengaturan → Pet (0 = tidak pernah jadi pill). Satu window yang
  di-resize; di luar badan slime/pill sentuhan tembus ke aplikasi di bawahnya.

## Pasang & jalankan

1. **Build APK** lewat GitHub Actions (tab Actions → unduh artifact `hermespet-debug-apk`),
   atau buka proyek di Android Studio → Build APK.
2. Instal di HP, buka **HermesPet**.
3. Tekan **✨ Berikan semua izin** → ikuti panduannya.
4. Isi **API key** AI (lihat di bawah) → **Simpan pengaturan otak**.
5. Tekan **▶ Hidupkan pet**. Selesai 🐾

## Otak AI — pilih salah satu

HermesPet bicara ke endpoint **OpenAI-compatible**. Atur di pengaturan (Base URL, API
key, Model).

**A. Cloud (paling mudah) — OpenRouter**
- Base URL: `https://openrouter.ai/api/v1`
- Model: mis. `google/gemini-flash-1.5` (murah, cepat, bisa lihat gambar)
- Untuk fitur "lihat layar", isi juga **Model vision** dengan model multimodal.

**B. AI LOKAL di HP (tanpa internet, privat) — TERTANAM di aplikasi**
Mesin MediaPipe LLM Inference (LiteRT) sudah ditanam di HermesPet, seperti Google AI
Edge Gallery. Tidak perlu server terpisah — cukup arahkan ke file model.
1. Unduh model **`.litertlm`** (LiteRT) dari HuggingFace (`litert-community`) atau
   Kaggle/Google AI Edge — mis. **Gemma 3n E2B** atau model ~1–2B agar lancar.
2. Taruh di HP (mis. `/sdcard/Download/gemma-3n-E2B-it.litertlm`).
3. Di HermesPet → pengaturan Otak → nyalakan **🔒 Pakai AI LOKAL** → isi path-nya →
   Simpan. Butuh izin **Semua file**.
> Catatan: model kecil lebih "polos" dari cloud, tapi 100% offline & privat.
> Vision lokal masih jarang — untuk "lihat gambar" tetap pakai model cloud multimodal.

Tanpa API key pun pet tetap hidup dengan balasan bawaan (untuk coba-coba).

## Privasi & keamanan

Lihat [PERMISSIONS.md](PERMISSIONS.md) untuk daftar izin & review keamanan. Ringkas:
aksi nyata hanya jalan dari perintahmu langsung (bukan dari notifikasi), file dibatasi
ke `Documents/HermesPet`, dan tidak ada kirim-SMS/transaksi otomatis.
