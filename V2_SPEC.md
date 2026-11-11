# HERMESPET v2.0 — SUPER SMART PET (SPEC)

Repo: /home/boo/projects/hermes_pet (clone dari github.com/zalldz/hermes_pet)
Base: Kotlin Android, overlay pet slime, AI brain OpenAI-compatible (Brain.kt),
actions dasar (Actions.kt: volume, torch, brightness, openApp, whatsapp intent,
dnd, alarm, notes), senses (Senses.kt), notif listener (NotifListener.kt),
screen capture (ScreenCaptureService.kt), LAN link ke homelab (LanLink.kt),
memori + skills (Memory.kt, Skills.kt).

TARGET: pet yang BENAR-BENAR bisa mengelola smartphone. Bukan gimik — semua
fitur harus fungsional dan ter-test compile.

## FASE 1 — TELINGA & TANGAN (accessibility = kunci utama)

1. **PetAccessibilityService.kt (BARU)**
   - AccessibilityService: baca full teks layar aktif (per app), cari node by text,
     tap node/koordinat, ketik teks ke focused field, global action
     BACK/HOME/RECENTS/NOTIFICATIONS, scroll.
   - Daftarkan di AndroidManifest + accessibility config XML.
   - Permission wizard (Permissions.kt + WizardActivity) tambah langkah
     "Aktifkan aksesibilitas".
2. **Brain.kt — tool baru untuk AI** (mexposed via system prompt):
   - `screen_read` → teks layar saat ini
   - `screen_tap` / `screen_type` / `screen_scroll` / `press` (back/home/recents)
   - `find_and_tap` (cari elemen by label, tap)
   → dengan ini AI bisa: "buka WhatsApp, kirim pesan ke Budi: sono" multi-step.
3. **Parsers.kt** — parsing tool-call baru di atas.

## FASE 2 — PILL MODE (dynamic island)

4. **PetOverlayService.kt** — tambah state PILL vs PET:
   - PILL: rounded pill ~120x32dp top-center, warna = mood, pulse anim ringan,
     physics/jalan pet PAUSED (hemat baterai).
   - Tap pill → morph expand → slime drop → mode PET (jalan seperti sekarang).
   - PET idle tanpa interaksi 30 detik (configurable di Prefs) → collapse ke PILL.
   - Satu window, resize via WindowManager.updateViewLayout — JANGAN dua window.
   - Saat mode PET, area di luar slime harus touch-transparent.
5. Notif singkat masuk → pill flash + badge count.

## FASE 3 — OTOMASI (rules engine)

6. **Rules.kt (BARU)** — if-trigger → then-action, disimpan di Prefs/JSON:
   - Trigger: waktu/jam, hari, sambung wifi SSID tertentu, putus wifi, baterai
     < X%, penuh 100%, lokasi masuk/keluar radius, app tertentu dibuka.
   - Action: DND on/off, silent, battery saver, brightness, buka app, kirim
     perintah ke brain, notif ke pet.
   - Contoh default: 23:00 → DND on; baterai <20% → saver on.
7. **UsageStatsManager** — screen time per app hari ini; brain tool
   `usage_report` + `set_app_limit(app, menit)` → saat limit habis, overlay
   blocker sederhana ("Moci nahan lu, app ini udah 1 jam") dengan tombol override.

## FASE 4 — KOMUNIKASI & DATA

8. **Telepon/SMS** (Actions.kt):
   - `call <nama/kontak>` (baca kontak, perlu READ_CONTACTS)
   - `sms <nomor/nama> <teks>` via SmsManager (perlu SEND_SMS — guard konfirmasi)
   - `read_sms` (READ_SMS, opsional, off default)
   - `find_phone` — LanLink command dari homelab → ring max volume 30 detik +
     balikin GPS koordinat ke homelab (anti hilang).
9. **Backup foto ke homelab** (LanLink.kt):
   - job background ContentResolver MediaStore query foto baru → upload via
     LAN (HTTP ke homelab endpoint FileBrowser/WebDAV) saat wifi home SSID.
10. **Clipboard manager**: simpan history clipboard (accessibility event),
    brain tool `clipboard_get/set/search`.
11. **File ops**: `search_file <nama>` (MediaStore/Documents), `storage_report`
    (ukuran per kategori + cache besar), `clear_cache_app <app>`.

## FASE 5 — MODE PRAKTIS

12. **Driving mode**: notif masuk dibacakan TTS (filter: chat apps saja),
    auto-reply teks singkat per app (opsional off default).
13. **OCR on-screen**: ambil teks layar via accessibility (fase 1) → translate
    / rangkum / copy semua.
14. **Shake untuk summon**: shake HP → pill expand ke chat. (SensorManager)

## BATASAN
- Tidak ada aksi otomatis berbahaya tanpa konfirmasi user (SMS/kirim pesan:
  wajib konfirmasi; sesuai PERMISSIONS.md yang ada).
- Semua permission baru harus masuk PERMISSIONS.md + wizard.
- Xiaomi/MIUI: autostart + display overlay permission — tambah panduan di wizard.
- Compile: build via GitHub Actions sudah ada (.github/workflows/build.yml) —
  pastikan lulus. Gak perlu device test, cukup `assembleDebug` sukses.
- README.md update fitur baru (bahasa Indonesia, gaya README sekarang).

## URUTAN EKSEKUSI
Fase 1 → commit → Fase 2 → commit → dst. 1 commit per fase, push ke main.
Format laporan akhir: daftar fitur baru per fase, file yang berubah, status build CI.
