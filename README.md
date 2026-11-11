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
- **Indra** — notifikasi masuk, cuaca (ikut lokasi), kalender, baterai.
- **Memori & skill** — ingat hal penting tentangmu; bisa membuat skill/makro sendiri.

## Baru di v2.0 — Moci mengelola HP-mu

**Fase 1 — Tangan (Aksesibilitas)**
- Moci bisa **membaca layar, tap, ketik, scroll, tekan Back/Home/Recents** di aplikasi lain.
- Tugas multi-langkah: *"buka WhatsApp, kirim pesan ke Budi: otw"* → Moci membuka app,
  membaca layar, mencari kontak, mengetik, lalu **minta konfirmasimu** sebelum menekan Kirim.
- Maksimal 8 putaran per perintah; tombol kirim/bayar/hapus/telepon selalu dikonfirmasi.
- Aktifkan di wizard: **🖐 Aktifkan aksesibilitas**. Di Android 13+ (APK sideload) kalau
  tombolnya abu-abu: Info aplikasi → ⋮ → *Izinkan setelan terbatas*.

**v2.1 — Ghost Orb** (pengganti pill)
- Diam 30 detik → Moci menyusut jadi **orb bulat 28dp semi-transparan** di tepi kanan,
  mirip tombol aksesibilitas melayang. Jalan/fisika/joget slime **berhenti** (hemat baterai).
- Tak disentuh beberapa detik → orb **menyelinap** (60% terlihat) & makin lama makin pudar.
- **Tap** → slime keluar memantul • **Tap 2x** → kotak chat • **Tahan** → menu mini
  (Sembunyikan 1 jam, dll.) • **Seret** → pindah, nempel ke tepi terdekat (posisi diingat).
- Orb **bersinar pelan**: 🔵 chat masuk (WA/Telegram/…), 🟡 rule otomasi jalan,
  🔴 batas app nyaris/udah habis (≤ 5 menit).
- Satu window overlay yang di-resize; di luar orb sentuhan tembus ke aplikasi di bawahnya.

**Fase 3 — Otomasi & screen time**
- **Rules "kalau → maka"**: jam/hari, sambung/putus WiFi (SSID), baterai < X% / penuh,
  masuk/keluar radius lokasi, aplikasi tertentu dibuka → DND, senyap, hemat baterai,
  kecerahan, buka app, notif dari Moci, atau perintah ke otak. Cukup bilang ke Moci:
  *"tiap nyambung WiFi Rumah, matikan senyap"* (Moci minta konfirmasi sebelum memasang).
- Bawaan: **23:00 → DND**, **baterai < 20% → hemat baterai**. Lihat/hapus di 🧠 Kelola.
- **Screen time**: *"berapa lama aku main HP hari ini?"* (`usage_report`).
- **Batas app**: *"batasi TikTok 60 menit"* → saat habis muncul layar *"Moci nahan kamu…"*
  dengan tombol tutup / 15 menit lagi.
- Hemat baterai hanya bisa di-toggle langsung kalau diberi izin sekali lewat PC:
  `adb shell pm grant com.hermespet android.permission.WRITE_SECURE_SETTINGS`
  (tanpa itu Moci membukakan setelannya). Trigger WiFi butuh izin lokasi aktif.

**Fase 4 — Komunikasi & data**
- **Telepon & SMS**: *"telepon Budi"*, *"SMS Ibu: aku pulang telat"* — nomor dicari dari
  kontak; Moci **selalu minta konfirmasi** sebelum menelepon/mengirim. *"baca SMS terbaru"*
  hanya kalau dinyalakan di Pengaturan → Data & homelab (default mati).
- **Cari HP dari homelab**: kirim `{"cmd":"find_phone","token":"…"}` ke HP port `47474`
  (frame LanLink tipe 3) → HP bunyi keras 30 detik & membalas koordinat GPS.
  Contoh: `python3 scripts/find_phone.py <ip-hp> <token>`.
- **Backup foto ke homelab**: isi folder WebDAV (+user/pass) & nama WiFi rumah → foto baru
  diunggah otomatis saat tersambung WiFi rumah (maks 50 per putaran), atau *"backup foto"*.
- **Clipboard manager**: riwayat 50 teks yang kamu salin (lewat aksesibilitas / saat chat
  dibuka) — *"apa yang tadi kusalin?"*, *"cari clipboard berisi alamat"*, *"salin teks ini"*.
- **File & penyimpanan**: *"cari file invoice"*, *"laporan penyimpanan"* (sisa ruang, foto/
  video/audio, 5 app dengan cache terbesar), *"bersihkan cache Instagram"* (dibukakan
  halaman app-nya — Android tidak mengizinkan app menghapus cache app lain).

**Fase 5 — Mode praktis**
- **Mode nyetir** 🚗: chat masuk (WhatsApp, Telegram, LINE, Signal, Messenger, Instagram,
  Discord, SMS) dibacakan TTS — antre, tidak saling potong, jalan walau suara pet mati.
  Nyalakan di Pengaturan → Pet atau *"Moci, mode nyetir"*. Butuh akses Baca notifikasi.
  Moci **tidak** membalas otomatis.
- **Teks layar (OCR via aksesibilitas)**: *"salin semua teks di layar"* (`copy_screen`),
  *"rangkum layar ini"*, *"terjemahkan layar ini ke Inggris"* — tanpa screenshot, langsung
  dari teks yang terbaca aksesibilitas.

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
