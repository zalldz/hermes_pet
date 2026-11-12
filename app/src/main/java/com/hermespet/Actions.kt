package com.hermespet

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import com.hermespet.Prefs.drivingMode
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
                action.optInt("minute", 0), action.optString("label", "Mocipet"))
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
            "screen_read", "screen_tap", "screen_type", "screen_scroll", "press",
            "find_and_tap" -> screen(action)
            // --- v2 fase 3: otomasi & screen time ---
            "silent" -> silent(ctx, action.optBoolean("on", true))
            "battery_saver" -> batterySaver(ctx, action.optBoolean("on", true))
            "usage_report" -> Usage.report(ctx)
            "set_app_limit" -> Usage.setLimit(ctx, action.optString("app"), action.optInt("minutes", 60))
            "add_rule" -> addRule(ctx, action)
            "delete_rule" -> Rules.delete(ctx, action.optString("name"))
            "list_rules" -> Rules.listText(ctx)
            // --- v2 fase 4: komunikasi & data ---
            "call" -> Comms.call(ctx, action.optString("to"))
            "sms" -> Comms.sms(ctx, action.optString("to"), action.optString("text"))
            "read_sms" -> Comms.readSms(ctx)
            "find_phone" -> { Comms.ring(ctx, action.optInt("seconds", 30)); "Kubunyikan HP-ku 🔔" }
            "stop_ring" -> { Comms.stopRing(); "Bunyi dimatikan 🔕" }
            "clipboard_get" -> Clip.get(ctx, action.optInt("n", 5))
            "clipboard_set" -> Clip.set(ctx, action.optString("text"))
            "clipboard_search" -> Clip.search(ctx, action.optString("query"))
            "search_file" -> FileOps.search(ctx, action.optString("name"))
            "storage_report" -> FileOps.report(ctx)
            "clear_cache_app" -> FileOps.openAppStorage(ctx, action.optString("app"))
            // --- v2 fase 5: mode praktis ---
            "driving_mode" -> {
                ctx.drivingMode = action.optBoolean("on", true)
                if (ctx.drivingMode) "Mode nyetir nyala 🚗 — chat masuk kubacakan" else "Mode nyetir mati"
            }
            "copy_screen" -> PetAccessibilityService.instance?.let { Clip.set(ctx, it.plainText()) }
                ?: "Aktifkan dulu Aksesibilitas \"Mocipet\" ya 🙏"
            else -> null
        }
    } catch (e: Exception) {
        "Aduh, gagal: ${e.message} 😖"
    }

    /** Aksi yang hasilnya dikirim balik ke otak (loop agent), bukan sekadar diucapkan. */
    val OBSERVE = setOf("screen_read", "usage_report", "list_rules", "read_sms", "clipboard_get",
        "clipboard_search", "search_file", "storage_report")

    /** Aksi yang mengubah layar → beri jeda lebih lama sebelum langkah berikutnya. */
    val SLOW = setOf("open_app", "screen_tap", "find_and_tap", "press", "screen_scroll", "screen_type")

    /**
     * Teks pertanyaan konfirmasi kalau aksi berisiko (kirim/bayar/hapus/telepon), atau
     * null kalau aman dijalankan langsung. Tap koordinat dicek label elemen di titik itu
     * supaya model tidak bisa "mengakali" konfirmasi lewat koordinat.
     */
    fun confirmPrompt(action: JSONObject): String? = when (action.optString("type")) {
        "find_and_tap" -> action.optString("label").takeIf { Parsers.isRiskyLabel(it) }
            ?.let { "Moci mau menekan \"$it\". Lanjut?" }
        "screen_tap" -> PetAccessibilityService.instance
            ?.labelAt(action.optInt("x"), action.optInt("y"))
            ?.takeIf { Parsers.isRiskyLabel(it) }
            ?.let { "Moci mau menekan \"$it\". Lanjut?" }
        "add_rule" -> "Moci mau memasang otomasi \"${action.optString("name")}\": kalau " +
            "${action.optJSONObject("trigger")} → ${action.optJSONObject("then")}. Lanjut?"
        "call" -> PetOverlayService.instance?.let { Comms.confirmCall(it, action.optString("to")) }
            ?: "Telepon ${action.optString("to")}?"
        "sms" -> PetOverlayService.instance?.let {
            Comms.confirmSms(it, action.optString("to"), action.optString("text"))
        } ?: "Kirim SMS ke ${action.optString("to")}?"
        else -> null
    }

    // ------------------------------------------------------------- otomasi

    private fun addRule(ctx: Context, a: JSONObject): String {
        val trigger = a.optJSONObject("trigger")
        // app_open boleh pakai nama aplikasi → simpan sebagai nama paket
        if (trigger?.optString("type") == "app_open" && !trigger.has("package")) {
            val pkg = findPackage(ctx, trigger.optString("app"))
                ?: return "Aku nggak nemu aplikasi \"${trigger.optString("app")}\" 😞"
            trigger.put("package", pkg)
        }
        return Rules.add(ctx, a.optString("name"), trigger, a.optJSONObject("then"))
    }

    private fun silent(ctx: Context, on: Boolean): String = try {
        audio(ctx).ringerMode = if (on) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
        if (on) "Mode senyap nyala 🔕" else "Mode senyap mati 🔔"
    } catch (e: SecurityException) {
        dnd(ctx, on) // butuh akses DND; minta lewat halaman yang sama
    }

    /**
     * Hemat baterai hanya bisa di-toggle aplikasi yang punya WRITE_SECURE_SETTINGS
     * (beri sekali lewat adb, lihat README). Tanpa itu: bukakan setelannya.
     */
    private fun batterySaver(ctx: Context, on: Boolean): String {
        if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Settings.Global.putInt(ctx.contentResolver, "low_power", if (on) 1 else 0)
            return if (on) "Hemat baterai nyala 🔋" else "Hemat baterai mati ⚡"
        }
        openSettings(ctx, Settings.ACTION_BATTERY_SAVER_SETTINGS, "")
        return "Kubukakan setelan hemat baterai 🔋 (Android melarangku menyalakannya langsung)"
    }

    // ------------------------------------------------------------- layar (aksesibilitas)

    private fun screen(a: JSONObject): String {
        val s = PetAccessibilityService.instance
            ?: return "Aktifkan dulu Aksesibilitas \"Mocipet\" di Setelan → Aksesibilitas ya 🙏"
        return when (a.optString("type")) {
            "screen_read" -> s.screenText()
            "screen_tap" -> s.tap(a.optInt("x"), a.optInt("y"))
            "screen_type" -> s.type(a.optString("text"))
            "screen_scroll" -> s.scroll(a.optString("dir", "down") != "up")
            "press" -> s.press(a.optString("key", "back"))
            else -> s.findAndTap(a.optString("label"))
        }
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

    /** Nama paket aplikasi yang bisa diluncurkan dengan label paling cocok, atau null. */
    fun findPackage(ctx: Context, name: String): String? {
        if (name.isBlank()) return null
        val pm = ctx.packageManager
        val launchables = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val q = name.lowercase().trim()
        fun lbl(r: android.content.pm.ResolveInfo) = r.loadLabel(pm).toString().lowercase()
        return (launchables.firstOrNull { lbl(it) == q }
            ?: launchables.firstOrNull { lbl(it).startsWith(q) }
            ?: launchables.firstOrNull { lbl(it).contains(q) }
            ?: launchables.firstOrNull { it.activityInfo.packageName.lowercase().contains(q) })
            ?.activityInfo?.packageName?.takeIf { !Guard.isMoneyApp(it) }
    }

    private fun openApp(ctx: Context, name: String): String {
        if (name.isBlank()) return "Aplikasi apa? 🤔"
        val pm = ctx.packageManager
        val launchables = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val q = name.lowercase().trim()
        fun lbl(r: android.content.pm.ResolveInfo) = r.loadLabel(pm).toString().lowercase()
        // cocokkan: sama persis → diawali → mengandung → mengandung tanpa spasi
        val match = launchables.firstOrNull { lbl(it) == q }
            ?: launchables.firstOrNull { lbl(it).startsWith(q) }
            ?: launchables.firstOrNull { lbl(it).contains(q) }
            ?: launchables.firstOrNull { lbl(it).replace(" ", "").contains(q.replace(" ", "")) }
            ?: launchables.firstOrNull { it.activityInfo.packageName.lowercase().contains(q) }
            ?: return "Aku nggak nemu aplikasi \"$name\" 😞"
        if (Guard.isMoneyApp(match.activityInfo.packageName)) return Guard.MONEY_MSG
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
        if (Guard.isSensitiveText(url)) return Guard.MONEY_MSG
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

    // catatan disimpan di folder privat aplikasi (TANPA izin — ramah Play Store)
    private fun noteDir(ctx: Context): java.io.File =
        java.io.File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
            ?: ctx.filesDir, "catatan").apply { mkdirs() }

    private fun safeName(filename: String): String =
        filename.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "catatan.txt" }

    private fun saveNote(ctx: Context, filename: String, text: String): String {
        if (text.isBlank()) return "Catat apa? 🤔"
        java.io.File(noteDir(ctx), safeName(filename)).appendText(text + "\n")
        return "Sudah kucatat: ${safeName(filename)} 📝"
    }

    private fun readNote(ctx: Context, filename: String): String {
        val f = java.io.File(noteDir(ctx), safeName(filename))
        if (!f.exists()) return "Catatan ${safeName(filename)} belum ada 🤔"
        return "Isi ${safeName(filename)}:\n" + f.readText().takeLast(1000)
    }
}
