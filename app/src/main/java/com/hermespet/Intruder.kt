package com.hermespet

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import com.hermespet.Prefs.tgChatId
import com.hermespet.Prefs.tgToken
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

/**
 * Satpam HP: tiap PIN/pola salah di layar kunci → foto kamera depan + lokasi → Telegram (Bot API langsung).
 * Kamera dari latar hanya jalan kalau overlay Mocipet (FGS tipe camera) sedang hidup; kalau tidak, kirim teks saja.
 */
class IntruderAdmin : DeviceAdminReceiver() {
    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val n = runCatching { dpm.currentFailedPasswordAttempts }.getOrDefault(0)
        Intruder.alert(context.applicationContext, n)
    }
}

object Intruder {
    private val http = OkHttpClient()

    fun component(c: Context) = ComponentName(c, IntruderAdmin::class.java)
    fun active(c: Context) = c.getSystemService(DevicePolicyManager::class.java).isAdminActive(component(c))

    /** Intent aktivasi Device Admin (dialog sistem). */
    fun enableIntent(c: Context) = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(c))
        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Moci memotret siapa pun yang salah memasukkan PIN, lalu mengirimnya ke Telegram-mu.")

    fun alert(c: Context, attempts: Int) {
        if (c.tgToken.isBlank() || c.tgChatId.isBlank()) return
        val time = SimpleDateFormat("dd MMM HH:mm:ss", Locale("id")).format(Date())
        val caption = "🚨 PIN salah (percobaan ke-$attempts) — $time"
        MediaCapture.photo(c, front = true, sink = { jpg -> sendPhoto(c, jpg, caption) }) { msg ->
            if (!msg.startsWith("Foto tersimpan")) sendText(c, "$caption\n📷 foto gagal: $msg")
        }
        Thread { Comms.locateBlocking(c)?.let { sendLocation(c, it.latitude, it.longitude) } }.start()
    }

    fun sendPhoto(c: Context, jpg: ByteArray, caption: String, cb: ((String?) -> Unit)? = null) =
        post(c, "sendPhoto", MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", c.tgChatId)
            .addFormDataPart("caption", caption)
            .addFormDataPart("photo", "intruder.jpg", jpg.toRequestBody("image/jpeg".toMediaType()))
            .build(), cb)

    fun sendText(c: Context, text: String, cb: ((String?) -> Unit)? = null) =
        post(c, "sendMessage", FormBody.Builder().add("chat_id", c.tgChatId).add("text", text).build(), cb)

    private fun sendLocation(c: Context, lat: Double, lon: Double) =
        post(c, "sendLocation", FormBody.Builder().add("chat_id", c.tgChatId)
            .add("latitude", lat.toString()).add("longitude", lon.toString()).build(), null)

    /** cb(null) = sukses, cb(pesan) = gagal. Dipanggil dari thread OkHttp. */
    private fun post(c: Context, method: String, body: RequestBody, cb: ((String?) -> Unit)?) {
        val req = Request.Builder().url("https://api.telegram.org/bot${c.tgToken}/$method").post(body).build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                PetLog.add(c, "aksi", "🚨 telegram $method gagal: ${e.message}"); cb?.invoke(e.message ?: "koneksi gagal")
            }
            override fun onResponse(call: Call, response: Response) { response.use {
                val err = if (it.isSuccessful) null else "HTTP ${it.code}: ${it.body?.string()?.take(200)}"
                if (err != null) PetLog.add(c, "aksi", "🚨 telegram $method $err")
                cb?.invoke(err)
            } }
        })
    }
}
