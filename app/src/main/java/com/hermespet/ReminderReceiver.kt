package com.hermespet

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Pengingat terjadwal sungguhan via AlarmManager — bunyi tepat waktu walau
 * pet sedang idle. Otak menjadwalkannya lewat action "set_reminder".
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: "Pengingat!"
        // notifikasi (selalu, walau pet mati)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("hermespet-reminder", "Pengingat Mocipet",
                NotificationManager.IMPORTANCE_HIGH)
        )
        val pi = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(text.hashCode(), Notification.Builder(context, "hermespet-reminder")
            .setContentTitle("⏰ Mocipet mengingatkan")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build())
        // kalau pet hidup, suruh dia mengucapkannya juga
        PetOverlayService.instance?.announceReminder(text)
    }

    companion object {
        /** Jadwalkan pengingat. delayMs > 0. Balikan umpan balik untuk gelembung. */
        fun schedule(ctx: Context, delayMs: Long, text: String): String {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(ctx, ReminderReceiver::class.java).putExtra("text", text)
            val pi = PendingIntent.getBroadcast(
                ctx, (text + delayMs).hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val at = System.currentTimeMillis() + delayMs
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            try {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
            val mins = (delayMs / 60_000L).toInt()
            return if (mins >= 60) "Oke, kuingatkan ${mins / 60}j ${mins % 60}m lagi ⏰"
            else "Oke, kuingatkan $mins menit lagi: $text ⏰"
        }
    }
}
