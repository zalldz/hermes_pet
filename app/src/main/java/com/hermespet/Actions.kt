package com.hermespet

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONObject

/**
 * "Tangan" pet: aksi nyata di ponsel yang diminta otak lewat field "action"
 * di balasan JSON. Batas Android yang tidak bisa dilewati aplikasi biasa:
 * WiFi/Bluetooth tidak boleh di-toggle langsung (sejak Android 10/13) —
 * pet hanya bisa membukakan panel/setelannya.
 */
object Actions {

    /** Jalankan aksi; balikan umpan balik singkat untuk gelembung (atau null). */
    fun run(ctx: Context, action: JSONObject): String? = try {
        when (action.optString("type")) {
            "volume" -> setVolume(ctx, action.optInt("level", 50))
            "volume_up" -> adjust(ctx, AudioManager.ADJUST_RAISE)
            "volume_down" -> adjust(ctx, AudioManager.ADJUST_LOWER)
            "mute" -> setVolume(ctx, 0)
            "torch" -> torch(ctx, action.optBoolean("on", true))
            "brightness" -> brightness(ctx, action.optInt("level", 50))
            "media" -> media(ctx, action.optString("cmd", "play_pause"))
            "open_app" -> openApp(ctx, action.optString("name"))
            "open_url" -> openUrl(ctx, action.optString("url"))
            "web_search" -> webSearch(ctx, action.optString("query"))
            "whatsapp" -> whatsapp(ctx, action.optString("number"), action.optString("text"))
            "wifi_panel" -> wifiPanel(ctx)
            "bluetooth_panel" -> openSettings(ctx, Settings.ACTION_BLUETOOTH_SETTINGS,
                "Kubukakan setelan Bluetooth 📡")
            "set_reminder" -> {
                val mins = action.optInt("minutes", 0)
                val delay = if (mins > 0) mins * 60_000L
                else alarmDelay(action.optInt("hour", -1), action.optInt("minute", 0))
                if (delay <= 0) "Kapan mau kuingatkan? 🤔"
                else ReminderReceiver.schedule(ctx, delay, action.optString("text", "Pengingat!"))
            }
            "dnd" -> dnd(ctx, action.optBoolean("on", true))
            "set_alarm" -> setAlarm(ctx, action.optInt("hour", 7),
                action.optInt("minute", 0), action.optString("label", "HermesPet"))
            "save_note" -> saveNote(ctx, action.optString("filename", "catatan.txt"),
                action.optString("text"))
            "read_note" -> readNote(ctx, action.optString("filename", "catatan.txt"))
            "remember" -> {
                Memory.remember(ctx, action.optString("text"),
                    action.optDouble("importance", 0.6))
                "Oke, kuingat itu 🧠"
            }
            "save_skill" -> Skills.save(ctx, action.optString("name"),
                action.optString("description"),
                action.optJSONArray("steps") ?: org.json.JSONArray())
            "use_skill" -> Skills.run(ctx, action.optString("name"))
            "delete_skill" -> Skills.delete(ctx, action.optString("name"))
            else -> null
        }
    } catch (e: Exception) {
        "Aduh, gagal: ${e.message} 😖"
    }

    // ------------------------------------------------------------- volume

    private fun audio(ctx: Context) = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun setVolume(ctx: Context, level: Int): String {
        val am = audio(ctx)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = (level.coerceIn(0, 100) * max / 100)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        return if (v == 0) "Volume kumatikan 🤫" else "Volume kuatur ke $level% 🔊"
    }

    private fun adjust(ctx: Context, dir: Int): String {
        audio(ctx).adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
        return if (dir == AudioManager.ADJUST_RAISE) "Volume naik 🔊" else "Volume turun 🔉"
    }

    private fun media(ctx: Context, cmd: String): String {
        val key = when (cmd) {
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = audio(ctx)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return when (cmd) {
            "next" -> "Lagu berikutnya ⏭"
            "prev" -> "Lagu sebelumnya ⏮"
            else -> "⏯"
        }
    }

    // ------------------------------------------------------------- senter

    private fun torch(ctx: Context, on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "HP-mu nggak punya lampu flash 😞"
        cm.setTorchMode(id, on)
        return if (on) "Senter nyala! 🔦" else "Senter mati"
    }

    // ------------------------------------------------------------- kecerahan

    private fun brightness(ctx: Context, level: Int): String {
        if (!Settings.System.canWrite(ctx)) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = android.net.Uri.parse("package:${ctx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return "Beri aku izin 'Ubah setelan sistem' dulu ya (kubukakan halamannya) 🙏"
        }
        Settings.System.putInt(ctx.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(ctx.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS, level.coerceIn(0, 100) * 255 / 100)
        return "Kecerahan kuatur ke $level% ☀️"
    }

    // ------------------------------------------------------------- aplikasi & panel

    private fun openApp(ctx: Context, name: String): String {
        if (name.isBlank()) return "Aplikasi apa? 🤔"
        val pm = ctx.packageManager
        val launchables = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val q = name.lowercase()
        val match = launchables.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: launchables.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: return "Aku nggak nemu aplikasi \"$name\" 😞"
        val label = match.loadLabel(pm)
        pm.getLaunchIntentForPackage(match.activityInfo.packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(it)
            return "Kubukakan $label 🚀"
        }
        return "Gagal membuka $label 😖"
    }

    private fun openUrl(ctx: Context, url: String): String {
        if (url.isBlank()) return "URL-nya mana? 🤔"
        val full = if (url.startsWith("http")) url else "https://$url"
        ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(full))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Kubukakan $full 🌐"
    }

    private fun webSearch(ctx: Context, query: String): String {
        if (query.isBlank()) return "Mau cari apa? 🔍"
        val url = "https://www.google.com/search?q=" + android.net.Uri.encode(query)
        ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Kucarikan \"$query\" di browser 🔍"
    }

    /** Buka chat WhatsApp ke nomor dengan teks siap kirim (user tinggal tap kirim). */
    private fun whatsapp(ctx: Context, number: String, text: String): String {
        val num = number.filter { it.isDigit() }
        val uri = if (num.isNotEmpty())
            "https://wa.me/$num?text=" + android.net.Uri.encode(text)
        else
            "https://wa.me/?text=" + android.net.Uri.encode(text)
        ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Kubukakan WhatsApp dengan pesan siap kirim 💬 (tinggal kamu tap kirim)"
    }

    private fun wifiPanel(ctx: Context): String {
        return if (Build.VERSION.SDK_INT >= 29) {
            ctx.startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Ini panel WiFi-nya, tinggal ketuk 📶 (Android melarangku menyentuhnya langsung)"
        } else {
            openSettings(ctx, Settings.ACTION_WIFI_SETTINGS, "Kubukakan setelan WiFi 📶")
        }
    }

    private fun openSettings(ctx: Context, action: String, feedback: String): String {
        ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return feedback
    }

    // ------------------------------------------------------------- jangan ganggu

    private fun dnd(ctx: Context, on: Boolean): String {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Beri aku akses 'Jangan Ganggu' dulu ya (kubukakan halamannya) 🙏"
        }
        nm.setInterruptionFilter(
            if (on) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        )
        return if (on) "Mode Jangan Ganggu nyala 🌙" else "Jangan Ganggu kumatikan 🔔"
    }

    // ------------------------------------------------------------- alarm

    /** Selisih ms menuju jam:menit berikutnya (hari ini atau besok). */
    private fun alarmDelay(hour: Int, minute: Int): Long {
        if (hour < 0) return 0
        val now = java.util.Calendar.getInstance()
        val t = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(java.util.Calendar.MINUTE, minute.coerceIn(0, 59))
            set(java.util.Calendar.SECOND, 0)
        }
        if (t.timeInMillis <= now.timeInMillis) t.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return t.timeInMillis - now.timeInMillis
    }

    private fun setAlarm(ctx: Context, hour: Int, minute: Int, label: String): String {
        ctx.startActivity(Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return "Alarm %02d:%02d (%s) kusetel ⏰".format(hour, minute, label)
    }

    // ------------------------------------------------------------- catatan (storage)

    private fun storageOk(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun askStorage(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= 30) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return "Beri aku izin 'Akses semua file' dulu ya (kubukakan halamannya) 🙏"
    }

    private fun noteDir(): java.io.File = java.io.File(
        android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOCUMENTS), "HermesPet")

    private fun safeName(filename: String): String =
        filename.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "catatan.txt" }

    private fun saveNote(ctx: Context, filename: String, text: String): String {
        if (text.isBlank()) return "Catat apa? 🤔"
        if (!storageOk(ctx)) return askStorage(ctx)
        val dir = noteDir().apply { mkdirs() }
        java.io.File(dir, safeName(filename)).appendText(text + "\n")
        return "Kucatat di Documents/HermesPet/${safeName(filename)} 📝"
    }

    private fun readNote(ctx: Context, filename: String): String {
        if (!storageOk(ctx)) return askStorage(ctx)
        val f = java.io.File(noteDir(), safeName(filename))
        if (!f.exists()) return "Catatan ${safeName(filename)} belum ada 🤔"
        return "Isi ${safeName(filename)}:\n" + f.readText().takeLast(1000)
    }
}
