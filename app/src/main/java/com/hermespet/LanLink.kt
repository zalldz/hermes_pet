package com.hermespet

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.hermespet.Prefs.lanDisplayName
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

    fun isRunning() = running
    fun peerNames(): List<String> = peers.keys.sorted()
    fun peerCount() = peers.size

    /** Mulai: buka server, daftar NSD, mulai cari teman. */
    fun start(ctx: Context, onReceive: (type: Int, from: String, data: ByteArray) -> Unit) {
        if (running) return
        val mgr = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        this.onReceive = onReceive
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
            val ss = ServerSocket(0) // port dinamis
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
                if (len in 1..MAX_PAYLOAD) {
                    val data = ByteArray(len)
                    ins.readFully(data)
                    onReceive?.invoke(type, from, data)
                }
            } catch (e: Exception) {
                Log.w(TAG, "terima gagal: ${e.message}")
            }
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
