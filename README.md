# 🐾 HermesPet

Pet virtual slime cerdas yang jalan-jalan sebagai **overlay di atas semua aplikasi**
di Android. Bisa ngobrol, lihat layar, kontrol HP, dan punya perasaan — semua dalam
**satu aplikasi Android**, tanpa Termux.

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

**B. AI LOKAL di HP (tanpa internet, privat)**
Ya, cukup unduh model **`.gguf`** lalu jalankan lewat aplikasi runtime lokal yang
menyediakan **server OpenAI-compatible di `127.0.0.1`**, mis.:
- **Llama.cpp (server)**, **LM Playground**, **MLCChat**, **SmolChat**, atau sejenis
  yang punya opsi "OpenAI compatible server".
1. Unduh model GGUF kecil yang muat di HP (mis. Qwen2.5-3B-Instruct Q4, Gemma-2-2B Q4,
   Phi-3-mini Q4 — pilih ~2–4B parameter biar lancar).
2. Jalankan server lokalnya (biasanya port `8080`).
3. Di HermesPet, set Base URL ke `http://127.0.0.1:8080/v1`, Model = nama model lokalmu,
   API key boleh diisi apa saja.
> Catatan: model kecil lebih "polos" dari model cloud, tapi 100% offline & privat.
> Vision lokal masih jarang — untuk "lihat gambar" tetap pakai model cloud multimodal.

Tanpa API key pun pet tetap hidup dengan balasan bawaan (untuk coba-coba).

## Privasi & keamanan

Lihat [PERMISSIONS.md](PERMISSIONS.md) untuk daftar izin & review keamanan. Ringkas:
aksi nyata hanya jalan dari perintahmu langsung (bukan dari notifikasi), file dibatasi
ke `Documents/HermesPet`, dan tidak ada kirim-SMS/transaksi otomatis.
