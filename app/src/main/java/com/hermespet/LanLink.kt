package com.hermespet

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.hermespet.Prefs.backupPass
import com.hermespet.Prefs.backupUrl
import com.hermespet.Prefs.backupUser
import com.hermespet.Prefs.homelabToken
import com.hermespet.Prefs.lanDisplayName
import com.hermespet.Prefs.lastBackupSec
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * Walkie-talkie + chat antar Mocipet dalam SATU jaringan WiFi — peer-to-peer,
 * TANPA server/internet. Pakai NSD (mDNS) bawaan Android untuk saling
 * menemukan otomatis, lalu kirim teks/suara lewat socket TCP langsung.
 *
 * Tiap HP: mengumumkan diri ("_mocipet._tcp") + mendengarkan koneksi masuk +
 * menemukan teman di WiFi. Kirim = broadcast ke semua teman yang terdeteksi.
 */
object LanLink {
    private const val TAG = "LanLink"
    private const val SERVICE_TYPE = "_mocipet._tcp."
    const val TYPE_TEXT = 1
    const val TYPE_VOICE = 2
    /** v2: perintah dari homelab (JSON {cmd, token}); balasan JSON ditulis di socket yang sama. */
    const val TYPE_CMD = 3
    /** Port tetap supaya homelab cukup tahu IP HP (fallback port acak kalau terpakai). */
    const val FIXED_PORT = 47474
    private const val MAX_PAYLOAD = 20 * 1024 * 1024 // 20MB batas aman

    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private var server: ServerSocket? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    @Volatile private var running = false
    @Volatile private var myName = ""

    // teman terdeteksi (sudah resolve host+port): nama -> info
    private val peers = ConcurrentHashMap<String, NsdServiceInfo>()
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    @Volatile private var resolving = false
    private var onReceive: ((Int, String, ByteArray) -> Unit)? = null
    private var appCtx: Context? = null

    fun isRunning() = running
    fun peerNames(): List<String> = peers.keys.sorted()
    fun peerCount() = peers.size

    /** Mulai: buka server, daftar NSD, mulai cari teman. */
    fun start(ctx: Context, onReceive: (type: Int, from: String, data: ByteArray) -> Unit) {
        if (running) return
        val mgr = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        this.onReceive = onReceive
        appCtx = ctx.applicationContext
        nsd = mgr
        myName = ctx.lanDisplayName()
        running = true
        // sebagian HP butuh multicast lock agar bisa terima mDNS (penemuan teman)
        runCatching {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE)
                as? android.net.wifi.WifiManager
            multicastLock = wifi?.createMulticastLock("mocipet-nsd")?.apply {
                setReferenceCounted(false); acquire()
            }
        }
        try {
            val ss = runCatching { ServerSocket(FIXED_PORT) }.getOrElse { ServerSocket(0) }
            server = ss
            val port = ss.localPort
            thread(name = "lanlink-accept") { acceptLoop(ss) }
            registerService(mgr, myName, port)
            startDiscovery(mgr)
        } catch (e: Exception) {
            Log.w(TAG, "start gagal: ${e.message}"); stop()
        }
    }

    fun stop() {
        running = false
        val mgr = nsd
        regListener?.let { runCatching { mgr?.unregisterService(it) } }; regListener = null
        discListener?.let { runCatching { mgr?.stopServiceDiscovery(it) } }; discListener = null
        runCatching { server?.close() }; server = null
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }; multicastLock = null
        peers.clear(); resolveQueue.clear(); resolving = false
        onReceive = null; nsd = null
    }

    /** Kirim teks ke semua teman. onResult(jumlahTujuan). */
    fun sendText(ctx: Context, text: String, onResult: (Int) -> Unit) =
        broadcast(TYPE_TEXT, text.toByteArray(Charsets.UTF_8), onResult)

    /** Kirim suara ke semua teman. onResult(jumlahTujuan). */
    fun sendVoice(ctx: Context, audio: ByteArray, onResult: (Int) -> Unit) =
        broadcast(TYPE_VOICE, audio, onResult)

    private fun broadcast(type: Int, data: ByteArray, onResult: (Int) -> Unit) {
        val targets = peers.values.toList()
        onResult(targets.size)
        if (targets.isEmpty() || data.isEmpty()) return
        val from = myName
        thread(name = "lanlink-send") {
            for (info in targets) {
                try {
                    val host = info.host ?: continue
                    Socket().use { sock ->
                        sock.connect(InetSocketAddress(host, info.port), 3000)
                        val out = DataOutputStream(sock.getOutputStream())
                        out.writeByte(type)
                        out.writeUTF(from)
                        out.writeInt(data.size)
                        out.write(data)
                        out.flush()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "kirim ke ${info.serviceName} gagal: ${e.message}")
                }
            }
        }
    }

    // ---- terima ----

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val sock = try {
                ss.accept()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept: ${e.message}"); break
            }
            thread { handleConn(sock) }
        }
    }

    private fun handleConn(sock: Socket) {
        sock.use {
            try {
                val ins = DataInputStream(it.getInputStream())
                val type = ins.readByte().toInt()
                val from = ins.readUTF()
                val len = ins.readInt()
                if (type == TYPE_CMD && len in 1..4096) {
                    val data = ByteArray(len)
                    ins.readFully(data)
                    val reply = handleCmd(from, String(data, Charsets.UTF_8))
                    DataOutputStream(it.getOutputStream()).apply { writeUTF(reply.toString()); flush() }
                } else if (len in 1..MAX_PAYLOAD) {
                    val data = ByteArray(len)
                    ins.readFully(data)
                    onReceive?.invoke(type, from, data)
                }
            } catch (e: Exception) {
                Log.w(TAG, "terima gagal: ${e.message}")
            }
        }
    }

    // ---- perintah homelab ----

    /** find_phone: bunyikan HP 30 detik + balas koordinat GPS. Wajib token yang cocok. */
    private fun handleCmd(from: String, json: String): JSONObject {
        val ctx = appCtx ?: return JSONObject().put("ok", false).put("error", "not ready")
        val req = runCatching { JSONObject(json) }.getOrNull()
            ?: return JSONObject().put("ok", false).put("error", "bad json")
        val token = ctx.homelabToken
        if (token.isEmpty() || !java.security.MessageDigest.isEqual(
                token.toByteArray(), req.optString("token").toByteArray())) {
            Log.w(TAG, "perintah ditolak dari $from (token salah)")
            return JSONObject().put("ok", false).put("error", "unauthorized")
        }
        onReceive?.invoke(TYPE_CMD, from, req.optString("cmd").toByteArray())
        return when (req.optString("cmd")) {
            "ping" -> JSONObject().put("ok", true).put("name", myName)
            "stop_ring" -> { Comms.stopRing(); JSONObject().put("ok", true) }
            "find_phone" -> {
                Comms.ring(ctx, req.optInt("seconds", 30).coerceIn(5, 120))
                val loc = Comms.locateBlocking(ctx)
                JSONObject().put("ok", true).apply {
                    if (loc != null) put("lat", loc.latitude).put("lon", loc.longitude)
                        .put("accuracy_m", loc.accuracy.toInt()).put("time", loc.time)
                    else put("error", "no location")
                }
            }
            else -> JSONObject().put("ok", false).put("error", "unknown cmd")
        }
    }

    // ---- backup foto ke homelab (WebDAV PUT) ----

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build()
    }
    @Volatile private var backupRunning = false

    /**
     * Unggah foto yang ditambahkan sejak backup terakhir (maks 50 per putaran) ke folder
     * WebDAV `backupUrl`. Dipanggil service saat tersambung WiFi rumah. onDone(jumlah, error?).
     */
    fun backupPhotos(ctx: Context, onDone: (Int, String?) -> Unit) {
        val base = ctx.backupUrl.trimEnd('/')
        if (base.isEmpty()) { onDone(0, "URL backup belum diisi"); return }
        if (backupRunning) { onDone(0, "backup masih berjalan"); return }
        backupRunning = true
        thread(name = "photo-backup") {
            var n = 0
            var err: String? = null
            try {
                val col = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                ctx.contentResolver.query(col,
                    arrayOf(android.provider.MediaStore.Images.Media._ID,
                        android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                        android.provider.MediaStore.Images.Media.DATE_ADDED),
                    "${android.provider.MediaStore.Images.Media.DATE_ADDED} > ?",
                    arrayOf(ctx.lastBackupSec.toString()),
                    "${android.provider.MediaStore.Images.Media.DATE_ADDED} ASC")?.use { c ->
                    while (c.moveToNext() && n < 50) {
                        val uri = android.content.ContentUris.withAppendedId(col, c.getLong(0))
                        val bytes = ctx.contentResolver.openInputStream(uri)?.use { s -> s.readBytes() }
                            ?: continue
                        val req = Request.Builder()
                            .url(base + "/" + android.net.Uri.encode(c.getString(1)))
                            .put(bytes.toRequestBody("image/*".toMediaType()))
                            .apply {
                                if (ctx.backupUser.isNotEmpty())
                                    header("Authorization", Credentials.basic(ctx.backupUser, ctx.backupPass))
                            }.build()
                        http.newCall(req).execute().use { r ->
                            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                        }
                        ctx.lastBackupSec = c.getLong(2)
                        n++
                    }
                } ?: run { err = "tidak bisa membaca galeri (izin foto?)" }
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "backup gagal: $err")
            } finally {
                backupRunning = false
            }
            onDone(n, err)
        }
    }

    // ---- NSD: daftar diri + temukan teman ----

    private fun registerService(mgr: NsdManager, name: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { myName = i.serviceName }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {
                Log.w(TAG, "daftar gagal: $code")
            }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        regListener = l
        mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    private fun startDiscovery(mgr: NsdManager) {
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) {
                runCatching { mgr.stopServiceDiscovery(this) }
            }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName == myName) return // diri sendiri
                enqueueResolve(mgr, info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                peers.remove(info.serviceName)
            }
        }
        discListener = l
        mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    // NSD lawas hanya bisa resolve 1 layanan dalam satu waktu → antrekan.
    private fun enqueueResolve(mgr: NsdManager, info: NsdServiceInfo) {
        resolveQueue.add(info); pumpResolve(mgr)
    }

    private fun pumpResolve(mgr: NsdManager) {
        if (resolving) return
        val info = resolveQueue.poll() ?: return
        resolving = true
        mgr.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, code: Int) {
                resolving = false; pumpResolve(mgr)
            }
            override fun onServiceResolved(i: NsdServiceInfo) {
                if (i.serviceName != myName) peers[i.serviceName] = i
                resolving = false; pumpResolve(mgr)
            }
        })
    }
}
