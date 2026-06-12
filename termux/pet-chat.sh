#!/data/data/com.termux/files/usr/bin/bash
# Ngobrol dengan pet — dipanggil dari tombol "💬 Ngobrol" di notifikasi pet.
# Butuh: pkg install jq termux-api

CONF="$HOME/.hermespet/config"
[ -f "$CONF" ] && source "$CONF"

TEXT=$(termux-dialog text -t "💬 Ngobrol dengan pet" | jq -r '.text // empty')
[ -z "$TEXT" ] && exit 0

if [ -n "$HERMES_CMD" ]; then
    PROMPT="Kamu pet virtual lucu yang membantu pemilikmu lewat HP. Jawab singkat, santai, bahasa Indonesia. Pemilikmu berkata: $TEXT"
    REPLY=$($HERMES_CMD "$PROMPT" 2>>"$HOME/.hermespet/chat-error.log")
    [ -z "$REPLY" ] && REPLY="(hermes tidak menjawab — coba 'hermes -z halo' di Termux, error tersimpan di ~/.hermespet/chat-error.log)"
else
    REPLY="(HERMES_CMD belum di-set di ~/.hermespet/config 🥺)"
fi

termux-notification --id hermespet-chat --title "🐾 Pet menjawab" --content "$REPLY"
if [ "${PET_SPEAK:-1}" = "1" ]; then
    termux-tts-speak "$REPLY"
fi
