package com.hermespet

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Meneruskan notifikasi yang masuk ke pet (kalau pet sedang aktif).
 * Pet lalu mengirimnya ke hermes-agent untuk direaksikan.
 */
class NotifListener : NotificationListenerService() {

    companion object {
        /** 50 notif terakhir (terbaru di depan) — hanya di memori, untuk notif triage. */
        val recent = java.util.concurrent.ConcurrentLinkedDeque<Triple<String, String, String>>()
        private const val MAX = 50

        fun digest(ctx: android.content.Context, n: Int): String {
            if (!Permissions.notifListener(ctx)) return "Akses Baca notifikasi belum diberikan 🙏"
            return Parsers.notifDigest(recent.take(n.coerceIn(1, MAX))) { Usage.label(ctx, it) }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return // abaikan notifikasi sendiri
        if (!sbn.isClearable) return // skip notifikasi persisten (musik, FGS, dll.)

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        if (title.isEmpty() && text.isEmpty()) return
        recent.addFirst(Triple(sbn.packageName, title, text))
        while (recent.size > MAX) recent.pollLast()

        PetOverlayService.instance?.onNotificationSeen(sbn.packageName, title, text)
    }
}
