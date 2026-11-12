package com.hermespet

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Meneruskan notifikasi yang masuk ke pet (kalau pet sedang aktif).
 * Pet lalu mengirimnya ke hermes-agent untuk direaksikan.
 */
class NotifListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return // abaikan notifikasi sendiri
        if (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return // ringkasan "N pesan baru"
        if (!sbn.isClearable) return // skip notifikasi persisten (musik, FGS, dll.)

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        if (title.isEmpty() && text.isEmpty()) return
        // notif app keuangan & OTP tidak disimpan/diteruskan sama sekali
        if (Guard.isMoneyApp(sbn.packageName) || Guard.isSensitiveText("$title $text")) return

        PetOverlayService.instance?.onNotificationSeen(sbn.packageName, title, text)
    }
}
