#!/usr/bin/env python3
"""
HermesPet "otak" — jalankan di Termux, di HP yang sama dengan aplikasi HermesPet.

    pkg install python
    pip install flask requests
    python hermes_body_server.py

Aplikasi HermesPet (APK) akan POST event ke http://127.0.0.1:8765/pet/event
dan server ini membalas dengan apa yang harus pet katakan/rasakan:

    balasan: {"say": "teks gelembung bicara" | null,
              "emotion": "happy|sad|neutral|sleepy|surprised|love" | null}

Integrasi hermes-agent:
  Set env HERMES_CMD ke perintah CLI agent-mu. Prompt ditambahkan sebagai
  argumen terakhir, jawaban dibaca dari stdout. Contoh:
      export HERMES_CMD="hermes -z"   # jadi: hermes -z "<prompt>"
  Kalau HERMES_CMD tidak di-set, server memakai balasan bawaan (rule-based)
  supaya pet tetap hidup — cocok untuk testing.
"""

import json
import os
import random
import shlex
import subprocess
import time

import requests
from flask import Flask, jsonify, request

# Memori jangka panjang opsional via Mnemosyne (https://mnemosyne.site)
#   pip install mnemosyne-memory
#   (atau integrasikan langsung ke hermes-agent: python -m mnemosyne.install)
try:
    from mnemosyne import remember, recall  # type: ignore
    print("mnemosyne: memori jangka panjang AKTIF")
except Exception:
    remember = recall = None

app = Flask(__name__)

# ---------------------------------------------------------------- konfigurasi
PORT = int(os.environ.get("PORT", "8765"))
LAT = os.environ.get("LAT", "-6.2")     # default Jakarta — ganti dengan lokasimu
LON = os.environ.get("LON", "106.8")
HERMES_CMD = os.environ.get("HERMES_CMD", "")

NOTIF_COOLDOWN = 120      # detik — pet tidak berkomentar tiap notifikasi
WEATHER_INTERVAL = 1800   # detik — komentar cuaca maksimal tiap 30 menit

_state = {"last_notif_say": 0.0, "last_weather_say": 0.0, "weather_cache": (0.0, None)}

WMO = {
    0: "cerah", 1: "cerah berawan", 2: "berawan", 3: "mendung",
    45: "berkabut", 48: "berkabut", 51: "gerimis", 53: "gerimis", 55: "gerimis",
    61: "hujan ringan", 63: "hujan", 65: "hujan deras",
    80: "hujan lokal", 81: "hujan lokal", 82: "hujan deras",
    95: "badai petir", 96: "badai petir", 99: "badai petir",
}


def get_weather():
    """Cuaca saat ini dari Open-Meteo (gratis, tanpa API key). Cache 30 menit."""
    ts, cached = _state["weather_cache"]
    if cached and time.time() - ts < WEATHER_INTERVAL:
        return cached
    try:
        r = requests.get(
            "https://api.open-meteo.com/v1/forecast",
            params={"latitude": LAT, "longitude": LON, "current_weather": "true"},
            timeout=10,
        )
        cw = r.json()["current_weather"]
        w = {
            "temp": cw["temperature"],
            "desc": WMO.get(cw["weathercode"], "tidak jelas"),
        }
        _state["weather_cache"] = (time.time(), w)
        return w
    except Exception as e:
        print("weather error:", e)
        return None


def call_hermes(prompt):
    """Kirim prompt ke hermes-agent lewat HERMES_CMD; balikan stdout (atau None)."""
    if not HERMES_CMD:
        return None
    try:
        out = subprocess.run(
            shlex.split(HERMES_CMD) + [prompt],
            capture_output=True,
            timeout=90,
        )
        text = out.stdout.decode(errors="replace").strip()
        return text or None
    except Exception as e:
        print("hermes error:", e)
        return None


def hermes_reply(context, fallback_say, fallback_emotion):
    """Minta hermes-agent membalas sebagai pet; fallback ke rule-based."""
    prompt = (
        "Kamu adalah pet virtual lucu di layar HP pemilikmu. "
        "Balas HANYA dengan JSON satu baris: "
        '{"say": "<komentar singkat max 100 huruf, bahasa Indonesia santai>", '
        '"emotion": "<happy|sad|neutral|sleepy|surprised|love>"}\n'
        f"Situasi: {context}"
    )
    raw = call_hermes(prompt)
    if raw:
        try:
            # ambil JSON pertama yang ditemukan di output
            start = raw.index("{")
            end = raw.rindex("}") + 1
            obj = json.loads(raw[start:end])
            if isinstance(obj.get("say"), str):
                return {"say": obj["say"][:200], "emotion": obj.get("emotion", "neutral")}
        except Exception as e:
            print("parse hermes output gagal:", e)
    return {"say": fallback_say, "emotion": fallback_emotion}


# ---------------------------------------------------------------- handler event

def handle_tick(data):
    now = time.time()
    battery = data.get("battery", -1)
    if 0 <= battery <= 15:
        return {"say": f"Baterai tinggal {battery}%! Cas dong 🥺", "emotion": "sad"}
    if now - _state["last_weather_say"] > WEATHER_INTERVAL:
        w = get_weather()
        if w:
            _state["last_weather_say"] = now
            emotion = "sad" if "hujan" in w["desc"] or "badai" in w["desc"] else "happy"
            return hermes_reply(
                f"Cuaca sekarang {w['desc']}, suhu {w['temp']}°C. Komentari singkat.",
                f"Di luar {w['desc']}, {w['temp']}°C nih!",
                emotion,
            )
    return {}  # diam — pet tidak perlu cerewet tiap menit


def handle_notification(data):
    now = time.time()
    if now - _state["last_notif_say"] < NOTIF_COOLDOWN:
        return {}
    _state["last_notif_say"] = now
    title = data.get("title", "")
    text = data.get("text", "")[:200]
    pkg = data.get("package", "")
    return hermes_reply(
        f"Pemilikmu dapat notifikasi dari aplikasi {pkg}: '{title}: {text}'. "
        "Beri komentar singkat yang relevan/lucu.",
        f"Psst, ada pesan: {title} 👀",
        "surprised",
    )


def handle_mood(data):
    if not data.get("face_detected"):
        return {"say": "Loh, kamu di mana? 👀", "emotion": "surprised"}
    guess = data.get("mood_guess", "neutral")
    smile = data.get("smile", -1)
    fallback = {
        "happy": ("Senyummu cerah banget hari ini! 😄", "love"),
        "sleepy": ("Matamu sayu... istirahat dulu yuk 😴", "sleepy"),
        "flat": ("Kamu kelihatan lelah... aku temenin ya 🥺", "sad"),
        "neutral": ("Semangat terus ya hari ini!", "happy"),
    }.get(guess, ("Aku selalu di sini buat kamu!", "love"))
    return hermes_reply(
        f"Kamu baru melihat wajah pemilikmu lewat kamera. Tebakan mood: {guess} "
        f"(probabilitas senyum {smile:.2f}). Beri komentar empatik singkat.",
        fallback[0],
        fallback[1],
    )


def handle_poke(_data):
    return hermes_reply(
        "Pemilikmu baru menyentuhmu (poke). Bereaksi singkat dan lucu.",
        random.choice(["Hihi geli! 😆", "Eh, apa? Aku lagi jalan-jalan nih!", "Poke balik! 🐾"]),
        "happy",
    )


def handle_chat(data):
    text = data.get("text", "")[:500]
    mem_ctx = ""
    if recall:
        try:
            mems = [m.get("content", "") for m in (recall(text) or [])[:3] if isinstance(m, dict)]
            if mems:
                mem_ctx = "(Ingatan relevanmu: " + "; ".join(mems) + ") "
        except Exception as e:
            print("mnemosyne recall:", e)
    reply = hermes_reply(
        f"{mem_ctx}Pemilikmu berkata kepadamu: '{text}'. Jawab hangat, membantu.",
        "Aku dengar kamu! Tapi HERMES_CMD belum di-set, jadi aku belum bisa mikir 🥺",
        "love",
    )
    if remember:
        try:
            remember(f"Pemilik berkata: {text} | Pet menjawab: {reply.get('say', '')}",
                     importance=0.5)
        except Exception as e:
            print("mnemosyne remember:", e)
    return reply


HANDLERS = {
    "tick": handle_tick,
    "notification": handle_notification,
    "mood": handle_mood,
    "poke": handle_poke,
    "chat": handle_chat,
}


@app.post("/pet/event")
def pet_event():
    body = request.get_json(force=True, silent=True) or {}
    etype = body.get("type", "")
    data = body.get("data", {}) or {}
    print(f"[{time.strftime('%H:%M:%S')}] event={etype} data={json.dumps(data)[:120]}")
    handler = HANDLERS.get(etype)
    reply = handler(data) if handler else {}
    return jsonify(reply)


@app.get("/")
def index():
    return "HermesPet brain hidup 🐾"


if __name__ == "__main__":
    print(f"HermesPet brain di http://127.0.0.1:{PORT}")
    print(f"hermes-agent: {'TERHUBUNG via HERMES_CMD' if HERMES_CMD else 'TIDAK di-set (pakai balasan bawaan)'}")
    app.run(host="127.0.0.1", port=PORT)
