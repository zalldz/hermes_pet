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
            "wifi_panel" -> wifiPanel(ctx)
            "bluetooth_panel" -> openSettings(ctx, Settings.ACTION_BLUETOOTH_SETTINGS,
                "Kubukakan setelan Bluetooth 📡")
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
}
