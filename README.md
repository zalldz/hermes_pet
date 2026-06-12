# 🐾 HermesPet

Pet virtual cerdas yang jalan-jalan sebagai **overlay di atas semua aplikasi** di Android.

**Dua mode otak** (bisa diganti di pengaturan aplikasi):

1. **Standalone (default)** — APK langsung memanggil model Hermes lewat endpoint
   OpenAI-compatible (Nous Portal `https://inference-api.nousresearch.com/v1`,
   OpenRouter, atau apa pun). Cukup isi API key di pengaturan. **Tanpa Termux.**
   Tanpa API key pun pet tetap hidup dengan balasan bawaan.
2. **Termux** — event diteruskan ke hermes-agent yang berjalan di Termux lewat
   HTTP `localhost` (arsitektur di bawah).

```
┌──────────────────────────────┐   HTTP 127.0.0.1:8765   ┌─────────────────────────┐
│  APK HermesPet ("tubuh")     │ ◄─────────────────────► │  Termux ("otak")        │
│  • Pet overlay: jalan, drag, │                         │  hermes_body_server.py  │
│    pinch-resize, emosi,      │                         │   • panggil hermes-agent│
│    gelembung bicara          │                         │   • ambil cuaca         │
│  • Baca notifikasi           │                         │     (Open-Meteo)        │
│  • Cek mood kamera depan     │                         │   • putuskan reaksi pet │
│    tiap jam (ML Kit lokal)   │                         │                         │
└──────────────────────────────┘                         └─────────────────────────┘
```

## Fitur

- **Jalan-jalan di layar** — pet bergerak sendiri, bisa di-drag, di-tap (poke), dan
  di-resize dengan pinch dua jari atau slider di pengaturan.
- **Tahu cuaca** — otak mengambil data Open-Meteo (gratis, tanpa API key) dan pet
  berkomentar.
- **Baca notifikasi** — notifikasi diteruskan ke hermes-agent, pet bereaksi
  (ada cooldown 2 menit supaya tidak cerewet).
- **Cek mood tiap jam** *(opsional, mati secara default)* — kamera depan mengambil satu
  frame, dianalisis **di HP** dengan ML Kit (senyum/mata terbuka). Foto hanya dikirim
  ke agent kalau kamu mengaktifkan opsinya. Ingat: Android menampilkan indikator hijau
  setiap kamera dipakai — itu normal.

## 🚀 Jalur cepat: pet tanpa APK (mulai dari sini!)

Tidak perlu build apa-apa. Pakai [termux/hermespet_termux.py](termux/hermespet_termux.py) —
pet tinggal di notifikasi persisten: baca notifikasimu, bersuara (TTS), lapor cuaca,
cek kamera tiap jam, dan bisa diajak ngobrol lewat tombol di notifikasi.
Petunjuk setup lengkap ada di komentar atas file itu. Ringkasnya:

```bash
# 1. Instal aplikasi Termux:API dari F-Droid, beri dia akses notifikasi di Setelan Android
pkg install python termux-api jq
pip install requests
mkdir -p ~/.hermespet
cp hermespet_termux.py pet-chat.sh ~/.hermespet/ && chmod +x ~/.hermespet/pet-chat.sh
nano ~/.hermespet/config        # isi HERMES_CMD, LAT, LON (lihat contoh di file .py)
termux-wake-lock
python ~/.hermespet/hermespet_termux.py
```

Kalau sudah puas dengan otaknya dan ingin badan visual yang jalan-jalan di layar,
lanjut ke APK di bawah — keduanya memakai hermes-agent yang sama.

## Cara build APK

**Opsi A — GitHub Actions (tanpa instal apa-apa):**
1. Push folder ini ke repo GitHub.
2. Buka tab **Actions** → workflow "Build APK" jalan otomatis.
3. Unduh artifact `hermespet-debug-apk`, salin ke HP, instal.

**Opsi B — Android Studio:** buka folder ini, biarkan sync, lalu **Build > Build APK(s)**.

## Setup di HP

1. Instal APK, buka **HermesPet**.
2. Beri izin: **overlay** (wajib), **baca notifikasi**, **kamera** (kalau mau cek mood),
   dan izin notifikasi aplikasi (Android 13+).
3. Di **Termux**:
   ```bash
   pkg install python
   pip install flask requests
   # lokasi untuk cuaca (contoh: Jakarta)
   export LAT=-6.2 LON=106.8
   # sambungkan hermes-agent-mu (prompt via stdin, jawaban via stdout):
   export HERMES_CMD="hermes-agent --oneshot"   # ← sesuaikan dengan CLI agent-mu
   python hermes_body_server.py
   ```
   Tanpa `HERMES_CMD`, server tetap jalan dengan balasan bawaan — bagus untuk tes awal.
4. Kembali ke aplikasi → **▶ Hidupkan pet**. Selesai 🐾

> Supaya server Termux tidak dimatikan sistem, jalankan `termux-wake-lock` dulu, dan
> matikan optimasi baterai untuk Termux & HermesPet di pengaturan Android.

## Protokol otak (kalau mau ganti server sendiri)

`POST /pet/event` body:
```json
{"type": "tick|notification|mood|poke", "data": {...}, "ts": 1718...}
```
Balasan (semua field opsional; kosongkan untuk diam):
```json
{"say": "teks gelembung bicara", "emotion": "happy|sad|neutral|sleepy|surprised|love"}
```

| type | data |
|---|---|
| `tick` (tiap 1 menit) | `battery`, `hour` |
| `notification` | `package`, `title`, `text` |
| `mood` (tiap jam, opsional) | `face_detected`, `smile`, `eyes_open`, `mood_guess`, `image_b64?` |
| `poke` (pet disentuh) | — |

## Privasi

- Semua komunikasi hanya lewat `127.0.0.1` — tidak ada data keluar HP kecuali
  panggilan cuaca (koordinat saja) dan apa pun yang hermes-agent-mu sendiri lakukan.
- Analisis wajah berjalan on-device; pengiriman foto ke agent adalah opt-in.
- Isi notifikasi dikirim ke agent lokal — sadari ini kalau agent-mu memanggil LLM cloud.
