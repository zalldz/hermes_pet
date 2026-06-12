#!/usr/bin/env python3
"""
HermesPet versi Termux-only — pet cerdas TANPA perlu build APK.

Pet "tinggal" di notifikasi persisten Android: membaca notifikasimu, berkomentar
dengan suara (TTS), melaporkan cuaca, mengecekmu lewat kamera depan tiap jam,
dan bisa diajak ngobrol lewat tombol di notifikasi.

SETUP (sekali saja):
    1. Instal aplikasi "Termux:API" dari F-Droid (harus sumber yang sama dengan Termux).
    2. Beri Termux:API akses notifikasi:
       Setelan Android > Notifikasi > Akses notifikasi > Termux:API > izinkan.
    3. Di Termux:
       pkg install python termux-api jq
       pip install requests
       mkdir -p ~/.hermespet && cp hermespet_termux.py pet-chat.sh ~/.hermespet/
       chmod +x ~/.hermespet/pet-chat.sh
    4. Buat file konfigurasi ~/.hermespet/config :
       HERMES_CMD="hermes -z"   # prompt ditambahkan sebagai argumen terakhir: hermes -z "<prompt>"
       LAT=-6.2
       LON=106.8
       PET_SPEAK=1        # 1 = pet bersuara (TTS), 0 = teks saja
       MOOD_CAMERA=0      # 1 = cek kamera depan tiap jam
    5. Jalankan:
       termux-wake-lock
       python ~/.hermespet/hermespet_termux.py

Tanpa HERMES_CMD pet tetap hidup dengan balasan bawaan (untuk tes).
"""

import json
import os
import random
import shlex
import subprocess
import time

import requests

HOME = os.path.expanduser("~")
CONF_PATH = os.path.join(HOME, ".hermespet", "config")
CHAT_SH = os.path.join(HOME, ".hermespet", "pet-chat.sh")

NOTIF_POLL = 20          # detik, cek notifikasi baru
WEATHER_INTERVAL = 1800  # komentar cuaca max tiap 30 menit
MOOD_INTERVAL = 3600     # cek kamera tiap jam
NOTIF_COOLDOWN = 120     # jeda min antar komentar notifikasi

IGNORE_PACKAGES = {
    "com.termux", "com.termux.api", "android", "com.android.systemui",
}

WMO = {
    0: "cerah", 1: "cerah berawan", 2: "berawan", 3: "mendung",
    45: "berkabut", 48: "berkabut", 51: "gerimis", 53: "gerimis", 55: "gerimis",
    61: "hujan ringan", 63: "hujan", 65: "hujan deras",
    80: "hujan lokal", 81: "hujan lokal", 82: "hujan deras",
    95: "badai petir", 96: "badai petir", 99: "badai petir",
}


def load_config():
    cfg = {}
    try:
        with open(CONF_PATH, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith("export "):
                    line = line[len("export "):]
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    cfg[k.strip()] = v.strip().strip('"').strip("'")
    except FileNotFoundError:
        pass
    return cfg


CFG = load_config()
HERMES_CMD = CFG.get("HERMES_CMD", os.environ.get("HERMES_CMD", ""))
LAT = CFG.get("LAT", "-6.2")
LON = CFG.get("LON", "106.8")
SPEAK = CFG.get("PET_SPEAK", "1") == "1"
MOOD_CAMERA = CFG.get("MOOD_CAMERA", "0") == "1"


# ------------------------------------------------------------------ util termux

def sh(cmd, stdin=None, timeout=30):
    """Jalankan perintah, balikan stdout (string kosong kalau gagal)."""
    try:
        r = subprocess.run(cmd, input=stdin.encode() if stdin else None,
                           capture_output=True, timeout=timeout)
        return r.stdout.decode(errors="replace")
    except Exception as e:
        print(f"  ! {cmd[0]}: {e}")
        return ""


def pet_show(text, emoji="🐾", speak=None):
    """Wajah pet = notifikasi persisten. Suara via TTS."""
    print(f"  {emoji} {text}")
    subprocess.Popen([
        "termux-notification", "--id", "hermespet", "--ongoing", "--alert-once",
        "--title", f"{emoji} HermesPet",
        "--content", text,
        "--button1", "💬 Ngobrol",
        "--button1-action", f"bash {CHAT_SH}",
    ])
    if SPEAK if speak is None else speak:
        subprocess.Popen(["termux-tts-speak", text])


EMOJI = {"happy": "😄", "sad": "🥺", "neutral": "🐾", "sleepy": "😴",
         "surprised": "😲", "love": "🥰"}


# ------------------------------------------------------------------ otak

def call_hermes(prompt):
    if not HERMES_CMD:
        return None
    try:
        out = subprocess.run(shlex.split(HERMES_CMD) + [prompt],
                             capture_output=True, timeout=90)
        return out.stdout.decode(errors="replace").strip() or None
    except Exception as e:
        print("  ! hermes:", e)
        return None


def hermes_reply(context, fallback_say, fallback_emotion):
    prompt = (
        "Kamu adalah pet virtual lucu milik penggunamu, tinggal di HP-nya, tugasmu "
        "membantu dan menemani. Balas HANYA JSON satu baris: "
        '{"say": "<max 120 huruf, bahasa Indonesia santai>", '
        '"emotion": "<happy|sad|neutral|sleepy|surprised|love>"}\n'
        f"Situasi: {context}"
    )
    raw = call_hermes(prompt)
    if raw:
        try:
            obj = json.loads(raw[raw.index("{"): raw.rindex("}") + 1])
            if isinstance(obj.get("say"), str):
                return obj["say"][:250], obj.get("emotion", "neutral")
        except Exception as e:
            print("  ! parse:", e)
    return fallback_say, fallback_emotion


# ------------------------------------------------------------------ kemampuan

def get_weather():
    try:
        r = requests.get("https://api.open-meteo.com/v1/forecast",
                         params={"latitude": LAT, "longitude": LON,
                                 "current_weather": "true"}, timeout=10)
        cw = r.json()["current_weather"]
        return {"temp": cw["temperature"], "desc": WMO.get(cw["weathercode"], "tidak jelas")}
    except Exception as e:
        print("  ! cuaca:", e)
        return None


def get_battery():
    out = sh(["termux-battery-status"])
    try:
        return json.loads(out).get("percentage", -1)
    except Exception:
        return -1


def list_notifications():
    out = sh(["termux-notification-list"])
    try:
        return json.loads(out)
    except Exception:
        return []


def mood_check():
    """Foto kamera depan; kalau hermes-agent bisa baca file gambar, minta dia menilai."""
    photo = os.path.join(HOME, ".hermespet", "mood.jpg")
    sh(["termux-camera-photo", "-c", "1", photo], timeout=25)
    if not os.path.exists(photo):
        return None, None
    say, emo = hermes_reply(
        f"Kamu baru memotret wajah pemilikmu, file: {photo} (bacalah kalau kamu bisa "
        "melihat gambar). Sapa dia dengan empatik, tanyakan kabarnya singkat.",
        random.choice([
            "Cek kamu sebentar... semoga harimu lancar ya! 🥰",
            "Halo! Jangan lupa minum air putih 💧",
            "Aku di sini kalau kamu butuh aku!",
        ]),
        "love",
    )
    try:
        os.remove(photo)  # jangan timbun foto
    except OSError:
        pass
    return say, emo


# ------------------------------------------------------------------ loop utama

def main():
    print("🐾 HermesPet (Termux) hidup!")
    print(f"   otak : {'hermes-agent via HERMES_CMD' if HERMES_CMD else 'balasan bawaan (HERMES_CMD belum di-set)'}")
    print(f"   suara: {'ON' if SPEAK else 'OFF'}   kamera mood: {'ON' if MOOD_CAMERA else 'OFF'}")

    seen = {n.get("key") for n in list_notifications()}  # jangan reaksi notif lama
    last = {"weather": 0.0, "mood": time.time(), "notif_say": 0.0, "notif_poll": 0.0,
            "low_batt": 0.0}
    pet_show("Halo! Aku hidup dan siap menemanimu 🐾", "😄")

    while True:
        now = time.time()

        # --- notifikasi baru ---
        if now - last["notif_poll"] > NOTIF_POLL:
            last["notif_poll"] = now
            for n in list_notifications():
                key = n.get("key") or f"{n.get('packageName')}/{n.get('id')}"
                if key in seen:
                    continue
                seen.add(key)
                pkg = n.get("packageName", "")
                title = n.get("title", "") or ""
                content = (n.get("content", "") or "")[:200]
                if pkg in IGNORE_PACKAGES or (not title and not content):
                    continue
                if now - last["notif_say"] < NOTIF_COOLDOWN:
                    continue
                last["notif_say"] = now
                say, emo = hermes_reply(
                    f"Pemilikmu dapat notifikasi dari {pkg}: '{title}: {content}'. "
                    "Komentari singkat, relevan, kalau penting ingatkan dia.",
                    f"Psst, ada pesan: {title} 👀", "surprised",
                )
                pet_show(say, EMOJI.get(emo, "🐾"))
            if len(seen) > 500:
                seen = set(list(seen)[-200:])

        # --- baterai lemah ---
        batt = get_battery()
        if 0 <= batt <= 15 and now - last["low_batt"] > 1800:
            last["low_batt"] = now
            pet_show(f"Baterai tinggal {batt}%! Cas dong 🥺", "🥺")

        # --- cuaca ---
        if now - last["weather"] > WEATHER_INTERVAL:
            last["weather"] = now
            w = get_weather()
            if w:
                say, emo = hermes_reply(
                    f"Cuaca sekarang {w['desc']}, {w['temp']}°C. Komentari singkat, "
                    "beri saran kalau relevan (payung, dll).",
                    f"Di luar {w['desc']}, {w['temp']}°C nih!",
                    "sad" if "hujan" in w["desc"] or "badai" in w["desc"] else "happy",
                )
                pet_show(say, EMOJI.get(emo, "🐾"))

        # --- mood kamera tiap jam ---
        if MOOD_CAMERA and now - last["mood"] > MOOD_INTERVAL:
            last["mood"] = now
            say, emo = mood_check()
            if say:
                pet_show(say, EMOJI.get(emo, "🥰"))

        time.sleep(NOTIF_POLL)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        subprocess.run(["termux-notification-remove", "hermespet"])
        print("\n🐾 HermesPet tidur. Dadah!")
