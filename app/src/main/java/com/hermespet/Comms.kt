package com.hermespet

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.SmsManager
import com.hermespet.Prefs.smsReadEnabled
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Telepon, SMS, kontak, dan "cari HP" (bunyi + lokasi). Kirim/telepon selalu dikonfirmasi. */
object Comms {

    private fun has(ctx: Context, p: String) =
        ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** (nama, nomor) dari nomor langsung atau nama kontak; null kalau tidak ketemu. */
    fun resolve(ctx: Context, who: String): Pair<String, String>? {
        val w = who.trim()
        if (w.isEmpty()) return null
        if (w.count { it.isDigit() } >= 5 && w.all { it.isDigit() || it in "+-() " }) return w to w
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return null
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$w%"),
            null)?.use { c ->
            // nama yang sama persis diutamakan
            var first: Pair<String, String>? = null
            while (c.moveToNext()) {
                val p = c.getString(0) to c.getString(1)
                if (p.first.equals(w, ignoreCase = true)) return p
                if (first == null) first = p
            }
            return first
        }
        return null
    }

    private fun who(ctx: Context, w: String) = resolve(ctx, w)
        ?.let { if (it.first == it.second) it.second else "${it.first} (${it.second})" } ?: w

    fun confirmCall(ctx: Context, w: String) = "Telepon ${who(ctx, w)}?"
    fun confirmSms(ctx: Context, w: String, text: String) = "Kirim SMS ke ${who(ctx, w)}:\n\"$text\"?"

    private fun needContacts(ctx: Context, w: String) =
        if (!has(ctx, Manifest.permission.READ_CONTACTS))
            "Aku belum boleh baca kontak — beri izin Kontak dulu, atau sebut nomornya 🙏"
        else "Kontak \"$w\" tidak ketemu 🔍"

    fun call(ctx: Context, w: String): String {
        val (name, num) = resolve(ctx, w) ?: return needContacts(ctx, w)
        val direct = has(ctx, Manifest.permission.CALL_PHONE)
        ctx.startActivity(Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL,
            Uri.parse("tel:" + Uri.encode(num))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return if (direct) "Menelepon $name 📞" else "Nomor $name siap di dialer 📞"
    }

    fun sms(ctx: Context, w: String, text: String): String {
        if (text.isBlank()) return "Isi SMS-nya apa? 🤔"
        val (name, num) = resolve(ctx, w) ?: return needContacts(ctx, w)
        if (!has(ctx, Manifest.permission.SEND_SMS)) {
            ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(num)))
                .putExtra("sms_body", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Belum ada izin kirim SMS — kubukakan aplikasi SMS dengan pesan siap kirim 💬"
        }
        val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
            else SmsManager.getDefault()
        sm.sendMultipartTextMessage(num, null, sm.divideMessage(text), null, null)
        return "SMS terkirim ke $name ✉️"
    }

    /** 10 SMS masuk terakhir — hanya kalau pemilik menyalakannya (default mati). */
    fun readSms(ctx: Context): String {
        if (!ctx.smsReadEnabled) return "Baca SMS dimatikan — nyalakan di Pengaturan → Data & homelab"
        if (!has(ctx, Manifest.permission.READ_SMS)) return "Beri izin baca SMS dulu ya 🙏"
        val sb = StringBuilder()
        ctx.contentResolver.query(Uri.parse("content://sms/inbox"),
            arrayOf("address", "body", "date"), null, null, "date DESC")?.use { c ->
            val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
            var n = 0
            while (c.moveToNext() && n++ < 10) sb.append("- ${fmt.format(java.util.Date(c.getLong(2)))} " +
                "${c.getString(0)}: ${c.getString(1).take(160)}\n")
        }
        return sb.toString().ifBlank { "Kotak masuk SMS kosong" }
    }

    // ---------------------------------------------------------------- cari HP

    private var player: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())

    /** Bunyikan alarm volume maksimal selama `seconds` detik (walau mode senyap). */
    fun ring(ctx: Context, seconds: Int = 30) = main.post {
        stopRing()
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM).build())
                setDataSource(ctx, uri)
                isLooping = true
                prepare(); start()
            }
        }.getOrNull()
        main.postDelayed({ stopRing() }, seconds * 1000L)
    }

    fun stopRing() {
        runCatching { player?.stop(); player?.release() }
        player = null
    }

    /** Lokasi terkini (tunggu fix maks ~20 detik; fallback lokasi terakhir). Panggil dari thread latar. */
    fun locateBlocking(ctx: Context): android.location.Location? {
        if (!has(ctx, Manifest.permission.ACCESS_FINE_LOCATION) &&
            !has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        return try {
            var fix: android.location.Location? = null
            if (Build.VERSION.SDK_INT >= 30) {
                val provider = listOf(android.location.LocationManager.GPS_PROVIDER,
                    android.location.LocationManager.NETWORK_PROVIDER)
                    .firstOrNull { lm.isProviderEnabled(it) }
                if (provider != null) {
                    val latch = CountDownLatch(1)
                    lm.getCurrentLocation(provider, null, ctx.mainExecutor) { fix = it; latch.countDown() }
                    latch.await(20, TimeUnit.SECONDS)
                }
            }
            fix ?: lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) { null }
    }
}
