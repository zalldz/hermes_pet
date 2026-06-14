package com.hermespet

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings

/**
 * Satu sumber kebenaran untuk semua izin pet: cek status, minta sekaligus,
 * dan tampilkan mana yang belum hijau. Dipakai MainActivity.
 */
object Permissions {

    data class Perm(val label: String, val granted: Boolean, val essential: Boolean)

    /** Izin runtime biasa yang bisa diminta lewat dialog sistem sekaligus. */
    private val runtime: List<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.READ_CALENDAR)
        if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun has(ctx: Context, perm: String) =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    fun overlay(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun notifListener(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver,
            "enabled_notification_listeners") ?: return false
        val cn = ComponentName(ctx, NotifListener::class.java)
        return flat.split(":").any { ComponentName.unflattenFromString(it) == cn }
    }

    fun writeSettings(ctx: Context) = Settings.System.canWrite(ctx)

    fun dnd(ctx: Context) =
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .isNotificationPolicyAccessGranted

    fun batteryOk(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    fun exactAlarm(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return (ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
            .canScheduleExactAlarms()
    }

    /** Daftar status lengkap untuk ditampilkan (merah/hijau). */
    fun status(ctx: Context): List<Perm> = listOf(
        Perm("Overlay (tampil di atas app)", overlay(ctx), essential = true),
        Perm("Baca notifikasi", notifListener(ctx), essential = false),
        Perm("Kamera (cek mood)", has(ctx, Manifest.permission.CAMERA), false),
        Perm("Mikrofon (perintah suara)", has(ctx, Manifest.permission.RECORD_AUDIO), false),
        Perm("Lokasi (cuaca otomatis)", has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION), false),
        Perm("Aktivitas (hitung langkah)",
            Build.VERSION.SDK_INT < 29 || has(ctx, Manifest.permission.ACTIVITY_RECOGNITION), false),
        Perm("Kalender (pengingat acara)", has(ctx, Manifest.permission.READ_CALENDAR), false),
        Perm("Notifikasi aplikasi",
            Build.VERSION.SDK_INT < 33 || has(ctx, Manifest.permission.POST_NOTIFICATIONS), false),
        Perm("Ubah setelan sistem (kecerahan)", writeSettings(ctx), false),
        Perm("Jangan Ganggu (DND)", dnd(ctx), false),
        Perm("Alarm tepat waktu (pengingat)", exactAlarm(ctx), false),
        Perm("Abaikan optimasi baterai", batteryOk(ctx), essential = true),
    )

    fun allGreen(ctx: Context) = status(ctx).all { it.granted }

    /** Minta semua izin runtime sekaligus lewat satu dialog sistem. */
    fun requestRuntime(act: Activity, code: Int) {
        val need = runtime.filter { !has(act, it) }
        if (need.isNotEmpty()) act.requestPermissions(need.toTypedArray(), code)
    }

    /**
     * Daftar layar "akses khusus" yang harus dibuka satu per satu (tidak bisa
     * lewat dialog runtime). Hanya yang BELUM diberikan.
     */
    fun pendingSpecial(ctx: Context): List<Pair<String, Intent>> = buildList {
        val pkg = Uri.parse("package:${ctx.packageName}")
        if (!overlay(ctx)) add("Overlay" to
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
        if (!notifListener(ctx)) add("Baca notifikasi" to
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        if (!writeSettings(ctx)) add("Ubah setelan sistem" to
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg))
        if (!dnd(ctx)) add("Jangan Ganggu" to
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        if (!batteryOk(ctx)) add("Optimasi baterai" to
            @Suppress("BatteryLife")
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
        if (!exactAlarm(ctx) && Build.VERSION.SDK_INT >= 31) add("Alarm tepat waktu" to
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
    }
}
