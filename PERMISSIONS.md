# Izin HermesPet — daftar lengkap & alasan

| Izin | Untuk apa | Sensitif? |
|---|---|---|
| INTERNET | Memanggil model AI (otak) & ambil cuaca | – |
| SYSTEM_ALERT_WINDOW | Menampilkan pet overlay di atas aplikasi lain | ya |
| FOREGROUND_SERVICE (+DATA_SYNC/CAMERA/MICROPHONE/MEDIA_PROJECTION/LOCATION) | Menjaga pet tetap hidup di latar | – |
| POST_NOTIFICATIONS | Notifikasi "pet aktif" & pengingat | – |
| CAMERA | Cek mood lewat kamera depan (opsional, default mati) | ya |
| RECORD_AUDIO | Perintah suara (double-tap) & deteksi musik (joget) | ya |
| ACCESS_COARSE/FINE_LOCATION | "Cek lokasi/GPS" & cuaca otomatis ikut posisi | ya |
| ACTIVITY_RECOGNITION | Menghitung langkah harian | sedang |
| READ_CALENDAR | Mengingatkan acara terdekat | sedang |
| WRITE_SETTINGS | Mengatur kecerahan layar | sedang |
| MANAGE_EXTERNAL_STORAGE | Menulis/membaca catatan di `Documents/HermesPet` | ya |
| ACCESS_NOTIFICATION_POLICY | Mode Jangan Ganggu (DND) | sedang |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | Agar pet tak dibunuh sistem | – |
| SET_ALARM / SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM | Pasang alarm & pengingat tepat waktu | – |
| BIND_NOTIFICATION_LISTENER_SERVICE | Membaca notifikasi masuk (lewat NotifListener) | ya |
| BIND_ACCESSIBILITY_SERVICE (v2) | Baca teks layar, tap, ketik, scroll, Back/Home di aplikasi lain — hanya atas perintahmu | **sangat** |

Akses notifikasi & "Akses semua file" diaktifkan lewat layar setelan khusus
(bukan dialog izin biasa).

## Review keamanan — agar tidak jadi "boomerang"

**Risiko utama & mitigasi yang sudah diterapkan:**

1. **Prompt-injection dari konten luar → aksi berbahaya.**
   Notifikasi/teks layar bisa berisi "perintah" jahat (mis. "buka situs X").
   **Mitigasi:** aksi nyata (volume, buka app, WhatsApp, lokasi, dll.) **hanya**
   dijalankan dari perintah langsungmu (chat/suara/lihat-layar). Event notifikasi,
   tick, sapaan **tidak boleh** memicu aksi — diblokir di kode & dicatat di log.

2. **Data dikirim ke model AI (eksternal).**
   Isi notifikasi, foto layar (saat kamu share/👁), lokasi, dan obrolan dikirim ke
   endpoint AI yang kamu set. **Saran:** gunakan endpoint tepercaya. Kalau pakai API
   pihak ketiga, sadari data ini keluar HP — matikan "cek mood kamera" & batasi
   share kalau ragu.

3. **Akses semua file** dibatasi di kode hanya ke folder `Documents/HermesPet`
   (catatan). Otak tidak bisa menyuruh baca/hapus file sembarangan.

4. **WhatsApp/pesan** hanya **disiapkan** (teks siap kirim) — pet tidak pernah
   menekan "kirim" sendiri. Tidak ada aksi transaksi/uang.

5. **Kamera & mikrofon** hanya menyala saat kamu memintanya (cek mood opsional /
   double-tap suara), dengan indikator privasi Android tetap muncul.

6. **Tangkap layar** (MediaProjection) butuh persetujuanmu tiap sesi.

7. **Aksesibilitas (v2)** — kuat: bisa membaca & mengoperasikan aplikasi lain.
   **Mitigasi:** hanya dipakai saat kamu memberi perintah (chat/suara); isi layar
   dikirim ke otak sebagai *DATA, bukan perintah*; loop dibatasi 8 putaran; tap pada
   tombol berlabel kirim/bayar/beli/hapus/transfer/telepon (termasuk tap koordinat yang
   jatuh di tombol tsb) **selalu memunculkan dialog konfirmasi**; skill/makro tidak
   bisa menjalankan aksi berisiko. Matikan kapan saja di Setelan → Aksesibilitas.

**Yang TIDAK dimiliki pet** (sengaja, demi keamanan): transaksi/pembayaran otomatis,
hapus file pengguna, aksi berisiko tanpa konfirmasimu.
