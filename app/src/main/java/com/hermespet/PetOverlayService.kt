package com.hermespet

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.hermespet.Prefs.drivingMode
import com.hermespet.Prefs.latitude
import com.hermespet.Prefs.longitude
import com.hermespet.Prefs.musicDance
import com.hermespet.Prefs.petScale
import com.hermespet.Prefs.petVoice
import com.hermespet.Prefs.petX
import com.hermespet.Prefs.petY
import com.hermespet.Prefs.fadeIdleSec
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

class PetOverlayService : Service() {

    companion object {
        @Volatile var instance: PetOverlayService? = null
        const val ACTION_APPLY_SCALE = "com.hermespet.APPLY_SCALE"

        private const val BASE_SIZE_DP = 144 // View lebih besar; sprite isi ~66% + ruang gerak
        private const val FADE_ALPHA = 0.22f
        private const val TICK_MS = 60_000L
        private const val WALK_FRAME_MS = 33L // ~30 fps (hemat baterai)
        /** Aplikasi chat yang dibacakan saat mode nyetir. */
        private val CHAT_APPS = listOf("com.whatsapp", "org.telegram", "org.thunderdog.challegram",
            "jp.naver.line", "org.thoughtcrime.securesms", "com.facebook.orca",
            "com.instagram.android", "com.google.android.apps.messaging", "com.discord",
            "com.samsung.android.messaging", "com.android.mms")
    }

    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var bubble: TextView
    private lateinit var pet: PetView
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private var bubbleShown = false
    private val handler = Handler(Looper.getMainLooper())

    // --- gerak jalan (posisi float supaya mulus, dieasing) ---
    private var walkTargetX = -1
    private var pausedUntil = 0L
    private var posX = 0f
    // --- fisika lempar ---
    private var flinging = false
    private var velX = 0f; private var velY = 0f   // px/ms saat lepas tangan
    private var fx = 0f; private var fy = 0f        // posisi float saat melayang
    private var hopStart = 0L      // 0 = tidak sedang melompat
    private var hopFrom = 0f
    private var hopDist = 0f
    private var nextHopAt = 0L
    private val hopDurMs = 520L

    // --- memudar saat diam / transparan saat full screen (hemat baterai) ---
    private var faded = false
    private var fullscreen = false
    private val dormant get() = faded || fullscreen
    private var onPlatform = false
    private var lastActivityAt = System.currentTimeMillis()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Senses.start(this)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("id", "ID")
                pickBestVoice()
                tts?.setSpeechRate(0.95f)
            }
        }
        buildOverlay()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        handler.post(walkLoop)
        handler.postDelayed(tickLoop, 5_000)
        handler.post(moodTick)
        handler.postDelayed(musicLoop, 3_000)
        handler.postDelayed(fadeLoop, 5_000)
        setupFullscreenProbe()
        pet.emotion = Mood.current(this)
        say("Halo! Aku hidup! 🐾", PetView.Emotion.HAPPY)
        transientUntil = System.currentTimeMillis() + 8_000
    }

    // --- hemat baterai + sapaan proaktif ---
    @Volatile private var screenOn = true
    private var lastGreetAt = 0L
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> { // layar mati: sensor goyang ikut mati (hemat baterai)
                    screenOn = false; pet.awake = false; stopVisualizer()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true; pet.awake = !dormant
                    pet.emotion = Mood.current(this@PetOverlayService)
                }
                Intent.ACTION_USER_PRESENT -> maybeGreet() // buka kunci → sapa
            }
        }
    }

    /** Sapaan proaktif saat buka kunci — maksimal sekali tiap 2 jam, & hanya kalau
     *  sudah lama tak menyapa. Lewat otak agar mengikuti kepribadian (SOUL.md). */
    private fun maybeGreet() {
        val now = System.currentTimeMillis()
        if (now - lastGreetAt < 2 * 3_600_000L) return
        val gap = now - Mood.lastSeen(this)
        if (gap < 25 * 60_000L) return // baru saja pegang HP → jangan cerewet
        lastGreetAt = now
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val data = JSONObject().put("hour", hour).put("away_minutes", gap / 60_000)
        sendToBrain("greet", data)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_APPLY_SCALE) applyScale()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        Senses.stop(this)
        stopVisualizer()
        Comms.stopRing()
        tts?.shutdown()
        runCatching { unregisterReceiver(screenReceiver) }
        closeChat()
        hideBlocker()
        if (bubbleShown) runCatching { wm.removeView(bubble) }
        runCatching { wm.removeView(probe) }
        runCatching { wm.removeView(root) }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- overlay

    private fun buildOverlay() {
        pet = PetView(this)
        // pet di window-nya sendiri (kecil, fixed) — supaya gerak/drag mulus
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(pet)
        }

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, // anti-flicker
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = petX
            y = petY
        }
        posX = lp.x.toFloat()

        // gelembung bicara gaya komik: window TERPISAH, melebar dinamis ikut teks,
        // dengan ekor segitiga menunjuk ke pet
        val comic = ComicBubble(resources.displayMetrics.density)
        bubble = TextView(this).apply {
            setTextColor(Color.parseColor("#2B2B3A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(dp(2f), 1f)
            background = comic
            val px = dp(14f).toInt(); val py = dp(10f).toInt()
            // padding bawah ekstra untuk ruang ekor gelembung
            setPadding(px, py, px, py + comic.tailHeightPx())
        }
        bubbleLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, // lebar window ikut isi (dinamis)
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        // hanya BATAS maksimum teks (78% layar) — teks pendek = gelembung kecil,
        // teks panjang melebar sampai batas lalu turun ke bawah
        bubble.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()

        applyScale(addToWindow = true)
        attachTouch()
    }

    private fun applyScale(addToWindow: Boolean = false) {
        val size = (dp(BASE_SIZE_DP.toFloat()) * petScale).toInt()
        pet.layoutParams = LinearLayout.LayoutParams(size, size)
        if (addToWindow) wm.addView(root, lp) else wm.updateViewLayout(root, lp)
    }

    private fun attachTouch() {
        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    petScale *= d.scaleFactor
                    applyScale()
                    return true
                }
            })

        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var dragging = false
        var downAt = 0L
        var lastMoveX = 0f; var lastMoveY = 0f; var lastMoveT = 0L

        root.setOnTouchListener { _, ev ->
            if (fullscreen) return@setOnTouchListener false
            if (faded) wake() // sentuhan membangunkan, lalu diproses seperti biasa
            lastActivityAt = System.currentTimeMillis()
            scaleDetector.onTouchEvent(ev)
            if (ev.pointerCount > 1) return@setOnTouchListener true
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = lp.x; startY = lp.y
                    dragging = false
                    downAt = System.currentTimeMillis()
                    flinging = false // ditangkap saat melayang
                    velX = 0f; velY = 0f
                    lastMoveX = ev.rawX; lastMoveY = ev.rawY; lastMoveT = downAt
                    pausedUntil = System.currentTimeMillis() + 8_000 // jangan jalan saat dipegang
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) dragging = true
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        wm.updateViewLayout(root, lp)
                        if (bubbleShown) positionBubble()
                        // kecepatan sesaat (px/ms) untuk lemparan
                        val t = System.currentTimeMillis()
                        val dt = (t - lastMoveT).coerceAtLeast(1)
                        velX = (ev.rawX - lastMoveX) / dt
                        velY = (ev.rawY - lastMoveY) / dt
                        lastMoveX = ev.rawX; lastMoveY = ev.rawY; lastMoveT = t
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val now = System.currentTimeMillis()
                    when {
                        dragging -> {
                            petX = lp.x; petY = lp.y; posX = lp.x.toFloat()
                            // lempar kalau lepas dengan kecepatan cukup
                            val speedPxs = kotlin.math.hypot(velX.toDouble(), velY.toDouble()) * 1000
                            refreshPlatforms(true)
                            if (speedPxs > 600 && now - lastMoveT < 120) startFling()
                            else if (platforms.isNotEmpty()) { velX = 0f; velY = 0f; startFling() } // jatuh ke ikon
                        }
                        now - downAt > 400 -> toggleChat() // tahan = ngobrol via ketik
                        now - lastTapAt < 350 -> { // double-tap = ngobrol via suara
                            handler.removeCallbacks(pokeRunnable)
                            lastTapAt = 0
                            startVoiceInput()
                        }
                        else -> { // tunggu sebentar — bisa jadi tap kedua menyusul
                            lastTapAt = now
                            handler.postDelayed(pokeRunnable, 360)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ---------------------------------------------------------------- ngobrol

    private var chatView: View? = null

    private fun toggleChat() {
        if (chatView != null) { closeChat(); return }
        Kenney.sfx(this, "open_001")
        // Android 10+ hanya mengizinkan baca clipboard saat window kita fokus
        handler.postDelayed({ Clip.capture(this) }, 800)
        val input = EditText(this).apply {
            hint = "Ngobrol sama aku..."
            isSingleLine = true
            setTextColor(Color.parseColor("#3E2723"))
            setHintTextColor(Color.parseColor("#9E9E9E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F4F3FA"))
                cornerRadius = dp(12f)
            }
            val ip = dp(10f).toInt(); setPadding(ip, dp(8f).toInt(), ip, dp(8f).toInt())
        }
        // tombol-tombol bantu yang rata & ringkas (emoji + label biar jelas)
        fun toolBtn(emoji: String, label: String) = Button(this).apply {
            text = "$emoji\n$label"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#3E2723"))
            setLineSpacing(0f, 0.95f)
            minWidth = 0; minimumWidth = 0
            setPadding(0, dp(6f).toInt(), 0, dp(6f).toInt())
            stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EFEEF7"))
                cornerRadius = dp(12f)
            }
        }
        val eye = toolBtn("👁", "Lihat")  // lihat layar
        fun iconBtn(res: Int, label: String) = toolBtn("", label).apply {
            text = label
            setCompoundDrawablesWithIntrinsicBounds(0, res, 0, 0)
            compoundDrawables[1]?.setBounds(0, 0, dp(22f).toInt(), dp(22f).toInt())
        }
        val photoB = iconBtn(R.drawable.ic_photo, "Foto")
        val videoB = iconBtn(if (MediaCapture.mode == MediaCapture.Mode.VIDEO) R.drawable.ic_stop else R.drawable.ic_video,
            if (MediaCapture.mode == MediaCapture.Mode.VIDEO) "Stop" else "Video")
        val micB = iconBtn(if (MediaCapture.mode == MediaCapture.Mode.AUDIO) R.drawable.ic_stop else R.drawable.ic_mic,
            if (MediaCapture.mode == MediaCapture.Mode.AUDIO) "Stop" else "Suara")
        val send = Button(this).apply { // kirim ke AI (Moci)
            text = "➤"; isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.WHITE)
            minWidth = 0; minimumWidth = 0
            val sp = dp(14f).toInt(); setPadding(sp, dp(8f).toInt(), sp, dp(8f).toInt())
            stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#5B6CF0"))
                cornerRadius = dp(12f)
            }
        }

        val gap = dp(6f).toInt()
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(send, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = gap })
        }
        fun cell(first: Boolean) = LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (!first) marginStart = gap }
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8f).toInt(), 0, 0)
            addView(eye, cell(true))
            addView(photoB, cell(false))
            addView(videoB, cell(false))
            addView(micB, cell(false))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(20f)
                setStroke(dp(2f).toInt(), Color.parseColor("#33334D"))
            }
            val p = dp(10f).toInt()
            setPadding(p, p, p, p)
            elevation = dp(8f)
            addView(topRow)
            addView(bottomRow)
        }
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val panelW = (screenW * 0.92f).toInt().coerceAtMost(dp(360f).toInt())
        val clp = WindowManager.LayoutParams(
            panelW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, // fokusable → keyboard bisa muncul
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW - panelW) / 2 // selalu di tengah & utuh di layar
            // taruh di bawah pet, tapi jangan masuk area keyboard (bawah layar)
            y = (lp.y + root.height + dp(6f).toInt())
                .coerceIn(dp(40f).toInt(), (screenH * 0.42f).toInt())
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }

        fun submit() {
            val text = input.text.toString().trim()
            closeChat()
            val local = if (text.isEmpty()) null else Local.parse(text)
            if (local != null) {
                PetLog.add(this, "user", text)
                agentTask = ""; runActionQueue(local, true) // tanpa otak: cepat & offline
            } else if (text.isNotEmpty()) {
                PetLog.add(this, "user", text)
                say("🤔...", null)
                sendToBrain("chat", JSONObject().put("text", text))
            }
        }
        send.setOnClickListener { Kenney.sfx(this, "select_001"); submit() }
        input.setOnEditorActionListener { _, _, _ -> submit(); true }
        Kenney.bitmap(this, "icon/zoom.png")?.let { b ->
            eye.text = "Lihat"
            eye.setCompoundDrawablesWithIntrinsicBounds(null,
                android.graphics.drawable.BitmapDrawable(resources, b).apply {
                    setBounds(0, 0, dp(22f).toInt(), dp(22f).toInt()) }, null, null)
        }
        eye.setOnClickListener {
            Kenney.sfx(this, "click_001")
            val q = input.text.toString().trim()
                .ifBlank { "Apa yang kamu lihat di layar ini? Jelaskan singkat." }
            closeChat()
            lookAtScreen(q)
        }
        fun media(type: String, vararg kv: Pair<String, Any>) {
            Kenney.sfx(this, "click_001"); closeChat()
            runActionQueue(listOf(JSONObject().put("type", type).put("_ok", true)
                .apply { kv.forEach { put(it.first, it.second) } }), true)
        }
        photoB.setOnClickListener { media("capture_photo") }
        videoB.setOnClickListener {
            if (MediaCapture.mode == MediaCapture.Mode.VIDEO) media("capture_stop") else media("capture_video")
        }
        micB.setOnClickListener {
            if (MediaCapture.mode == MediaCapture.Mode.AUDIO) media("capture_stop") else media("capture_audio")
        }
        wm.addView(row, clp)
        chatView = row
        input.requestFocus()
        pausedUntil = System.currentTimeMillis() + 60_000 // diam selama ngobrol
    }

    // ---------------------------------------------------------------- otomasi & batas app (v2)

    private var prevRuleState: Rules.State? = null

    @Suppress("DEPRECATION")
    private fun currentSsid(): String? = runCatching {
        (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
            .connectionInfo?.ssid?.trim('"')
            ?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
    }.getOrNull()

    private fun ruleState(pkg: String? = prevRuleState?.pkg): Rules.State {
        val cal = Calendar.getInstance()
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return Rules.State(
            cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE),
            cal.get(Calendar.DAY_OF_WEEK), currentSsid(),
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), bm.isCharging,
            latitude.toDoubleOrNull(), longitude.toDoubleOrNull(), pkg)
    }

    /** Jalankan aksi dari rule yang baru menyala (rule dibuat pemilik → boleh beraksi). */
    private fun checkRules(cur: Rules.State) {
        val prev = prevRuleState
        prevRuleState = cur
        val acts = Rules.evaluate(this, prev, cur)
        if (acts.isNotEmpty()) runRuleActions(acts)
    }

    /** Aksi "then" otomasi; juga dipanggil tombol ▶ di panel Kelola untuk uji coba. */
    fun runRuleActions(acts: List<JSONObject>) {
        val queue = ArrayList<JSONObject>()
        for (a in acts) when (a.optString("type")) {
            "notify" -> { say("⚙️ ${a.optString("text")}", PetView.Emotion.SURPRISED); speak(a.optString("text")) }
            "brain" -> sendToBrain("chat", JSONObject().put("text", a.optString("text")))
            in Homelab.TYPES -> {
                say("⚙️ menjalankan ${a.optString("type")} di homelab…", PetView.Emotion.NEUTRAL)
                Homelab.run(this, a) { ok, out ->
                    PetLog.add(this, "aksi", "⚙️ ${a.optString("type")}: ${out.take(300)}")
                    say((if (ok) "✅ " else "⚠️ ") + out.take(280), if (ok) PetView.Emotion.HAPPY else PetView.Emotion.SAD)
                }
            }
            else -> queue.add(a)
        }
        PetLog.add(this, "aksi", "⚙️ otomasi: ${acts.joinToString { it.optString("type") }}")
        if (queue.isNotEmpty()) runActionQueue(queue, sayFeedback = true)
    }

    /** Dipanggil aksesibilitas saat aplikasi di depan berganti. */
    fun onAppChanged(pkg: String) = handler.post {
        if (blockerView != null && pkg != blockerPkg) hideBlocker()
        checkRules(ruleState(pkg))
        checkLimit(pkg)
        handler.postDelayed({ checkFullscreen() }, 400) // app baru bisa saja full screen
        platformsAt = 0L
    }

    private var blockerView: View? = null
    private var blockerPkg = ""

    private fun checkLimit(pkg: String) {
        if (blockerView != null || pkg == packageName) return
        val used = Usage.overLimit(this, pkg) ?: return
        showBlocker(pkg, used)
    }

    /** Layar penahan sederhana saat batas harian app habis; bisa di-override. */
    private fun showBlocker(pkg: String, used: Int) {
        val label = Usage.label(this, pkg)
        fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setOnClickListener { onClick() }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F0202030"))
            val p = dp(28f).toInt(); setPadding(p, p, p, p)
            addView(TextView(context).apply {
                text = "🐾✋"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f); gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "Moci nahan kamu — $label udah $used menit hari ini."
                setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                gravity = Gravity.CENTER; setPadding(0, dp(12f).toInt(), 0, dp(20f).toInt())
            })
            addView(btn("🏠 Udahan, tutup aplikasinya") {
                hideBlocker()
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            addView(btn("⏰ 15 menit lagi aja") { Usage.snooze(pkg, 15); hideBlocker() })
        }
        val blp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        runCatching { wm.addView(box, blp) }.onSuccess {
            blockerView = box; blockerPkg = pkg
            PetLog.add(this, "aksi", "⏳ batas $label tercapai ($used menit)")
        }
    }

    private fun hideBlocker() {
        blockerView?.let { runCatching { wm.removeView(it) } }
        blockerView = null; blockerPkg = ""
    }

    // ---------------------------------------------------------------- memudar & full screen

    private var saver = false

    /** Tiap 5 detik: diam > fadeIdleSec → memudar; baterai kecil → lebih cepat & hemat. */
    private val fadeLoop = object : Runnable {
        override fun run() {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            saver = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) < 20 && !bm.isCharging
            val idleMs = (if (saver) minOf(fadeIdleSec, 10) else fadeIdleSec) * 1000L
            val now = System.currentTimeMillis()
            if (!faded && idleMs > 0 && screenOn && chatView == null && !bubbleShown && !flinging &&
                now - lastActivityAt > idleMs && now > pausedUntil) fadeOut()
            handler.postDelayed(this, 5_000)
        }
    }

    private fun fadeOut() {
        faded = true
        pet.awake = false
        stopVisualizer()
        root.animate().alpha(FADE_ALPHA).setDuration(600).start()
    }

    private fun wake() {
        lastActivityAt = System.currentTimeMillis()
        if (!faded) return
        faded = false
        if (!fullscreen) { pet.awake = screenOn; root.animate().alpha(1f).setDuration(200).start() }
    }

    // Deteksi full screen: jendela 1px tanpa FLAG_LAYOUT_IN_SCREEN ditaruh di bawah status bar
    // saat status bar tampil (y>0) dan di y=0 saat disembunyikan app (video/game/immersive).
    private lateinit var probe: View
    private var sawBar = false
    private fun setupFullscreenProbe() {
        probe = View(this)
        val plp = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        probe.viewTreeObserver.addOnGlobalLayoutListener { checkFullscreen() }
        runCatching { wm.addView(probe, plp) }
    }

    private fun checkFullscreen() {
        val loc = IntArray(2)
        probe.getLocationOnScreen(loc)
        if (loc[1] > 0) sawBar = true
        // shortcut: baru percaya "full screen" setelah pernah melihat status bar (y>0); di ROM yang
        // tidak menggeser jendela overlay, deteksi mati dan slime tetap terlihat. Upgrade: pakai insets.
        val fs = sawBar && probe.isAttachedToWindow && loc[1] == 0
        if (fs == fullscreen) return
        fullscreen = fs
        lp.flags = if (fs) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                   else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (fs) {
            pet.awake = false
            stopVisualizer()
            root.animate().cancel(); root.alpha = 0f
            if (bubbleShown) hideBubble.run()
        } else {
            pet.awake = screenOn && !faded
            root.animate().alpha(if (faded) FADE_ALPHA else 1f).setDuration(300).start()
        }
        runCatching { wm.updateViewLayout(root, lp) }
    }

    private fun closeChat() {
        chatView?.let { runCatching { wm.removeView(it) } }
        chatView = null
    }

    private var lastTapAt = 0L
    private val pokeRunnable = Runnable { onPoked() }

    private fun onPoked() {
        pet.emotion = PetView.Emotion.SURPRISED
        sendToBrain("poke", JSONObject())
    }

    // ---------------------------------------------------------------- suara

    private var tts: TextToSpeech? = null

    private fun speak(text: String) {
        if (!petVoice) return
        // buang emoji/simbol supaya TTS tidak membacakannya
        val clean = text.filter { it.code < 0x2190 }.trim()
        if (clean.isNotEmpty()) tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "hermespet")
    }

    private fun startVoiceInput() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            say("Kasih aku izin mikrofon dulu ya (di pengaturan) 🥺", PetView.Emotion.SAD)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("HP-mu nggak punya mesin pengenal suara 😞", PetView.Emotion.SAD)
            return
        }
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
        }
        say("🎤 Aku dengerin...", PetView.Emotion.SURPRISED)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val text = results
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                sr.destroy()
                if (!text.isNullOrBlank()) {
                    PetLog.add(this@PetOverlayService, "user", "🎤 $text")
                    say("「$text」🤔...", null)
                    sendToBrain("chat", JSONObject().put("text", text).put("via", "voice"))
                } else {
                    say("Aku nggak nangkep, coba lagi 😅", PetView.Emotion.SAD)
                }
            }

            override fun onError(error: Int) {
                sr.destroy()
                say("Aku nggak denger jelas, coba lagi 😅", PetView.Emotion.SAD)
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        sr.startListening(intent)
    }

    // ---------------------------------------------------------------- jalan-jalan

    private fun startFling() {
        flinging = true
        pet.hopT = -1f
        fx = lp.x.toFloat(); fy = lp.y.toFloat()
        // px/ms → px/frame (~16ms)
        velX *= 16f; velY *= 16f
    }

    // --- ikon homescreen jadi pijakan (v2.0-plus) ---
    private var platforms: List<Rect> = emptyList()
    private var platformsAt = 0L
    private var offY = 0 // selisih koordinat layar vs koordinat window (status bar)

    /** Baca posisi ikon launcher (di-cache; hanya di homescreen, mati saat hemat baterai). */
    private fun refreshPlatforms(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val onHome = PetAccessibilityService.onHome
        if (!onHome || saver || dormant || !screenOn) { platforms = emptyList(); return }
        if (!force && now - platformsAt < 4_000) return
        platformsAt = now
        val loc = IntArray(2); root.getLocationOnScreen(loc); offY = loc[1] - lp.y
        platforms = PetAccessibilityService.instance?.iconRects() ?: emptyList()
    }

    fun onHomeScrolled() { platformsAt = System.currentTimeMillis() - 3_400 } // rescan ±0,6 dtk lagi

    private fun supportTop(cx: Float, bottomW: Float, tol: Float): Float? = platforms.firstOrNull { r ->
        cx >= r.left && cx <= r.right && abs(r.top - offY - bottomW) <= tol
    }?.let { (it.top - offY).toFloat() }

    /** Lompat ke ikon lain (busur parabola memakai gravitasi yang sama dengan lemparan). */
    private fun jumpTo(r: Rect) {
        val g = 1.4f
        val gain = lp.y - (r.top - offY - pet.height).toFloat() // >0 = ikon target lebih tinggi
        val margin = dp(40f)
        val vy = -kotlin.math.sqrt(2 * g * (gain.coerceAtLeast(0f) + margin))
        val t = (-vy + kotlin.math.sqrt(vy * vy - 2 * g * gain)) / g // frame sampai sejajar target
        val tx = (r.centerX() - pet.width / 2f)
        velX = (tx - lp.x) / t.coerceAtLeast(1f) / 16f // startFling mengalikan 16 lagi
        velY = vy / 16f
        pet.facing = if (tx > lp.x) 1 else -1
        startFling()
    }

    /** Satu langkah fisika lemparan: gravitasi + pantul tepi layar + redam. */
    private fun flingStep() {
        val maxX = (resources.displayMetrics.widthPixels - pet.width).toFloat().coerceAtLeast(1f)
        val maxY = (resources.displayMetrics.heightPixels - pet.height).toFloat().coerceAtLeast(1f)
        velY += 1.4f                     // gravitasi
        velX *= 0.995f                   // gesekan udara
        val prevFy = fy
        fx += velX; fy += velY
        // ikon homescreen = lantai kalau jatuh dari atasnya
        var floor = maxY
        if (velY > 0f) {
            val cx = fx + pet.width / 2f
            for (r in platforms) {
                val t = (r.top - offY - pet.height).toFloat()
                if (cx >= r.left && cx <= r.right && prevFy <= t + 1f && fy >= t && t < floor) floor = t
            }
        }
        val restitution = 0.62f
        if (fx <= 0f) { fx = 0f; velX = -velX * restitution; pet.bump(true) }
        else if (fx >= maxX) { fx = maxX; velX = -velX * restitution; pet.bump(true) }
        if (fy <= 0f) { fy = 0f; velY = -velY * restitution }
        else if (fy >= floor) { // mendarat di "lantai" (dasar layar atau ikon)
            fy = floor
            if (velY > 4f) pet.bump(false)
            velY = -velY * restitution
            velX *= 0.7f                  // gesekan lantai
        }
        lp.x = fx.toInt(); lp.y = fy.toInt()
        runCatching { wm.updateViewLayout(root, lp) }
        if (bubbleShown) positionBubble()
        // berhenti saat sudah menempel lantai & nyaris diam
        if (fy >= floor - 1f && kotlin.math.abs(velY) < 4f && kotlin.math.abs(velX) < 0.5f) {
            flinging = false
            onPlatform = floor < maxY
            posX = fx.coerceIn(0f, maxX); petX = lp.x; petY = lp.y
            pausedUntil = System.currentTimeMillis() + 1_500
            walkTargetX = -1
        }
    }

    private val walkLoop = object : Runnable {
        override fun run() {
            if (!screenOn || dormant) { // layar mati / memudar / full screen → diam total (hemat baterai)
                handler.postDelayed(this, 2_000)
                return
            }
            if (flinging) { flingStep(); handler.postDelayed(this, WALK_FRAME_MS); return }
            val now = System.currentTimeMillis()
            refreshPlatforms()
            if (onPlatform && hopStart == 0L && supportTop(posX + pet.width / 2f, (lp.y + pet.height).toFloat(), 8f) == null) {
                onPlatform = false; velX = 0f; velY = 0f; startFling() // pijakan hilang / berjalan keluar ikon → jatuh
                handler.postDelayed(this, WALK_FRAME_MS); return
            }
            val maxX = (resources.displayMetrics.widthPixels - pet.width).coerceAtLeast(1)
            if (now >= pausedUntil) {
                val dist = walkTargetX - posX
                when {
                    walkTargetX < 0 || abs(dist) < 8f -> {
                        // sampai tujuan → istirahat (denyut idle), lalu pilih tujuan baru
                        walkTargetX = Random.nextInt(0, maxX)
                        pausedUntil = now + Random.nextLong(4_000, 15_000)
                        if (platforms.isNotEmpty() && Random.nextInt(100) < 40) {
                            // kadang melompat ke ikon lain yang masih terjangkau
                            val reach = dp(300f)
                            platforms.filter { abs(it.centerX() - posX) < reach && it.top - offY - pet.height != lp.y }
                                .randomOrNull()?.let { jumpTo(it); pausedUntil = now + 2_000 }
                        }
                        hopStart = 0L
                        pet.hopT = -1f
                    }
                    hopStart == 0L -> {
                        // menunggu giliran lompat berikutnya (khas Stardew: hop-jeda-hop)
                        pet.hopT = -1f
                        if (now >= nextHopAt) {
                            hopStart = now
                            hopFrom = posX
                            hopDist = minOf(abs(dist),
                                Random.nextLong(40, 110).toFloat() * petScale)
                            pet.facing = if (dist > 0) 1 else -1
                        }
                    }
                    else -> {
                        val t = (now - hopStart) / hopDurMs.toFloat()
                        if (t >= 1f) {
                            // mendarat sempurna; jeda singkat sebelum lompat lagi
                            posX = (hopFrom + pet.facing * hopDist).coerceIn(0f, maxX.toFloat())
                            hopStart = 0L
                            nextHopAt = now + Random.nextLong(150, 650)
                            pet.hopT = -1f
                        } else {
                            pet.hopT = t
                            // bergeser horizontal HANYA saat melayang (setelah mendekam)
                            val air = ((t - 0.18f) / 0.67f).coerceIn(0f, 1f)
                            posX = (hopFrom + pet.facing * hopDist * air)
                                .coerceIn(0f, maxX.toFloat())
                        }
                        if (lp.x != posX.toInt()) {
                            lp.x = posX.toInt()
                            runCatching { wm.updateViewLayout(root, lp) }
                            if (bubbleShown) positionBubble()
                        }
                    }
                }
            } else {
                hopStart = 0L
                pet.hopT = -1f
            }
            // hemat baterai: saat pet istirahat (idle), perlambat loop; saat
            // bergerak/melompat, tetap 60fps untuk gerak mulus
            val idle = now < pausedUntil
            handler.postDelayed(this, if (idle) 220L else WALK_FRAME_MS)
        }
    }

    // ---------------------------------------------------------------- otak

    private val tickLoop = object : Runnable {
        override fun run() {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val data = JSONObject()
                .put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                .put("hour", Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
            // indra tambahan: cahaya, langkah, lokasi (cuaca otomatis), kalender
            val senses = Senses.snapshot(this@PetOverlayService)
            senses.keys().forEach { k -> data.put(k, senses.get(k)) }
            sendToBrain("tick", data)
            checkRules(ruleState())
            Usage.foreground(this@PetOverlayService)?.let { checkLimit(it) }
            handler.postDelayed(this, TICK_MS)
        }
    }

    // baseline emosi pet mengikuti suasana hati saat idle (tiap 15 detik),
    // kecuali sedang menampilkan reaksi sesaat (transientUntil)
    private var transientUntil = 0L
    private val moodTick = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() > transientUntil) {
                pet.emotion = Mood.current(this@PetOverlayService)
            }
            handler.postDelayed(this, 15_000)
        }
    }

    // --- goyang ikut musik (#6): Visualizer hanya saat ada musik & layar nyala ---
    private var visualizer: android.media.audiofx.Visualizer? = null
    private val musicLoop = object : Runnable {
        override fun run() {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val want = musicDance && screenOn && !dormant && am.isMusicActive
            if (want && visualizer == null) startVisualizer()
            else if (!want && visualizer != null) stopVisualizer()
            handler.postDelayed(this, 2_000)
        }
    }

    private fun startVisualizer() {
        try {
            visualizer = android.media.audiofx.Visualizer(0).apply {
                captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[0]
                setDataCaptureListener(
                    object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: android.media.audiofx.Visualizer?, wave: ByteArray?, rate: Int) {
                            wave ?: return
                            var sum = 0L
                            for (b in wave) { val a = (b.toInt() and 0xFF) - 128; sum += (a * a).toLong() }
                            val rms = kotlin.math.sqrt(sum.toDouble() / wave.size) / 128.0
                            val energy = ((rms - 0.04) * 3.2).coerceIn(0.0, 1.0).toFloat()
                            if (energy > pet.beat) pet.beat = energy
                        }
                        override fun onFftDataCapture(v: android.media.audiofx.Visualizer?, fft: ByteArray?, rate: Int) {}
                    },
                    android.media.audiofx.Visualizer.getMaxCaptureRate() / 2, true, false)
                enabled = true
            }
        } catch (e: Exception) { visualizer = null }
    }

    private fun stopVisualizer() {
        runCatching { visualizer?.enabled = false; visualizer?.release() }
        visualizer = null
        pet.beat = 0f
    }

    /** Pilih suara Indonesia terbaik yang terpasang: kualitas tertinggi, jaringan (neural) diutamakan. */
    private fun pickBestVoice() {
        val v = runCatching {
            tts?.voices?.filter { it.locale.language == "id" && !it.features.orEmpty().contains("notInstalled") }
                ?.maxByOrNull { it.quality * 2 + (if (it.isNetworkConnectionRequired) 1 else 0) }
        }.getOrNull()
        if (v != null) tts?.voice = v
    }

    // WA memposting ulang notifikasi yang sama (update grup, ringkasan, dibaca) → jangan dibacakan berulang
    private val spoken = HashMap<String, Long>()

    fun onNotificationSeen(pkg: String, title: String, text: String) {
        PetLog.add(this, "notif", "$pkg: $title — ${text.take(80)}")
        val now = System.currentTimeMillis()
        val key = "$pkg|$title|$text"
        spoken.entries.removeAll { now - it.value > 10 * 60_000 }
        val fresh = spoken.put(key, now) == null
        if (drivingMode && fresh && CHAT_APPS.any { pkg.startsWith(it) }) {
            // mode nyetir: bacakan walau suara pet dimatikan; antre supaya tidak saling potong
            val clean = "Pesan dari $title. $text".filter { it.code < 0x2190 }.take(300)
            tts?.speak(clean, TextToSpeech.QUEUE_ADD, null, "drive-${System.nanoTime()}")
        }
        val data = JSONObject().put("package", pkg).put("title", title).put("text", text)
        sendToBrain("notification", data)
    }

    // ---------------------------------------------------------------- lihat layar

    /** Mulai alur tangkap layar (minta izin MediaProjection lalu pahami via vision). */
    private fun lookAtScreen(question: String) {
        PetLog.add(this, "user", "👁 $question")
        say("👁 Lihat layar dulu...", PetView.Emotion.SURPRISED)
        startActivity(Intent(this, CaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("question", question))
    }

    /** Sembunyikan pet+gelembung sesaat agar tidak ikut tertangkap screenshot. */
    fun setOverlayHidden(hidden: Boolean) = handler.post {
        val v = if (hidden) View.INVISIBLE else View.VISIBLE
        root.visibility = v
        if (bubbleShown) bubble.visibility = v
    }

    /** Hasil tangkap layar dari ScreenCaptureService. */
    fun onScreenCaptured(imageB64: String?, question: String) {
        handler.post {
            if (imageB64 == null) {
                say("Yah, gagal lihat layarnya 😖", PetView.Emotion.SAD)
            } else {
                say("🤔 Mengamati...", null)
                Brain.askVision(this, question, imageB64) { reply -> applyReply("vision", reply) }
            }
        }
    }

    /** Teks yang di-share ke pet → diperlakukan sebagai chat. */
    fun onSharedText(text: String) = handler.post {
        PetLog.add(this, "user", "📩 $text")
        say("🤔...", null)
        sendToBrain("chat", JSONObject().put("text", text))
    }

    /** Dipanggil ReminderReceiver saat pengingat tiba & pet sedang hidup. */
    fun announceReminder(text: String) {
        handler.post {
            say("⏰ $text", PetView.Emotion.SURPRISED)
            speak("Pengingat! $text")
            transientUntil = System.currentTimeMillis() + 10_000
        }
    }

    private var streamingText = ""

    // --- loop agent (v2): tugas multi-langkah di aplikasi lain lewat aksesibilitas ---
    private var agentTask = ""
    private var agentStep = 0
    private val agentDone = StringBuilder()
    private val MAX_AGENT_STEPS = 8

    private fun sendToBrain(type: String, data: JSONObject) {
        // interaksi langsung memperbarui "kapan terakhir disapa" (anti-kesepian)
        if (type == "chat" || type == "poke") Mood.noteInteraction(this)
        if (type == "chat") { // perintah baru pemilik = tugas agent baru
            agentTask = data.optString("text"); agentStep = 0; agentDone.setLength(0)
        }
        // streaming hanya untuk chat (jawaban mengalir real-time)
        val onStream: ((String) -> Unit)? = if (type == "chat") { s -> streamSay(s) } else null
        Brain.sendEvent(this, type, data, onStream) { reply -> applyReply(type, reply) }
    }

    /** Tampilkan teks "say" yang masih mengalir (tanpa typewriter — streaming asli). */
    private fun streamSay(partial: String) {
        if (partial.isEmpty()) return
        handler.removeCallbacks(typeRunnable)
        streamingText = partial
        if (!bubbleShown) {
            runCatching { wm.addView(bubble, bubbleLp) }
            bubbleShown = true
            bubble.alpha = 1f; bubble.scaleX = 1f; bubble.scaleY = 1f
        }
        bubble.text = partial
        positionBubble()
        handler.removeCallbacks(hideBubble)
        handler.postDelayed(hideBubble, 30_000)
        pausedUntil = maxOf(pausedUntil, System.currentTimeMillis() + 8_000)
    }

    /** Terapkan balasan otak: emosi+mood, ucapan, dan aksi nyata. Dipakai chat & vision. */
    private fun applyReply(type: String, reply: JSONObject?) {
        reply ?: return
        val emotion = reply.optString("emotion", "")
        if (emotion.isNotEmpty()) {
            val emo = PetView.emotionFrom(emotion)
            pet.emotion = emo
            transientUntil = System.currentTimeMillis() + 12_000
            if (type == "chat" || type == "mood" || type == "vision") Mood.note(this, emo)
        }
        val text = reply.optString("say", "")
        if (fullscreen && type == "notification") { // full screen: jangan ganggu, cukup catat
            if (text.isNotEmpty()) PetLog.add(this, "pet", text)
            return
        }
        if (text.isNotEmpty()) {
            PetLog.add(this, "pet", text)
            // kalau teks sudah tampil lewat streaming → finalkan tanpa animasi ulang
            say(text, null, typewriter = (text != streamingText))
            speak(text)
        }
        streamingText = ""
        // memori pintar: simpan fakta penting yang dipelajari otak (otomatis)
        reply.optJSONObject("remember")?.let { mem ->
            val factText = mem.optString("text")
            if (factText.isNotEmpty()) {
                Memory.remember(this, factText, mem.optDouble("importance", 0.6))
                PetLog.add(this, "aksi", "🧠 ingat: $factText")
            }
        }
        // KEAMANAN: aksi nyata HANYA dijalankan dari perintah langsung pemilik
        // (chat/suara/lihat-layar). Notifikasi/tick/sapaan TIDAK boleh memicu aksi —
        // mencegah prompt-injection dari konten luar ("boomerang").
        // "agent" = putaran lanjutan dari tugas yang dimulai pemilik lewat chat
        val actionAllowed = type == "chat" || type == "vision" || type == "agent"
        if (!actionAllowed) return
        // multi-step: kumpulkan aksi tunggal ("action") + daftar ("actions")
        val queue = ArrayList<JSONObject>()
        reply.optJSONObject("action")?.let { queue.add(it) }
        reply.optJSONArray("actions")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { queue.add(it) }
        }
        if (queue.isNotEmpty()) runActionQueue(queue, sayFeedback = text.isEmpty())
    }

    /**
     * Jalankan rangkaian aksi (agentic) berurutan dengan jeda agar enak diikuti.
     * Aksi berisiko menunggu konfirmasi pemilik; hasil aksi pengamatan (screen_read)
     * dikumpulkan lalu dikirim balik ke otak untuk putaran berikutnya.
     */
    private fun runActionQueue(
        queue: List<JSONObject>, sayFeedback: Boolean, i: Int = 0,
        observed: StringBuilder = StringBuilder()
    ) {
        if (i >= queue.size) { continueAgent(observed.toString()); return }
        val act = queue[i]
        val type = act.optString("type")
        val ask = Actions.confirmPrompt(act)
        if (ask != null && !act.optBoolean("_ok")) {
            confirm(ask) { ok ->
                if (ok) { act.put("_ok", true); runActionQueue(queue, sayFeedback, i, observed) }
                else {
                    PetLog.add(this, "aksi", "❌ dibatalkan pemilik: $ask")
                    say("Oke, nggak jadi 👌", PetView.Emotion.NEUTRAL)
                    agentStep = MAX_AGENT_STEPS // hentikan loop agent
                }
            }
            return
        }
        val delay = if (type == "location") {
            fetchLocation(); 1200L
        } else {
            val feedback = Actions.run(this, act)
            agentDone.append(type).append(act.optString("label", act.optString("name", ""))
                .let { if (it.isEmpty()) "" else "($it)" }).append(", ")
            if (!feedback.isNullOrEmpty()) {
                if (type in Actions.OBSERVE) {
                    observed.append("[").append(type).append("]\n").append(feedback).append('\n')
                    PetLog.add(this, "aksi", "👀 $type (${feedback.length} huruf)")
                } else {
                    PetLog.add(this, "aksi", feedback)
                    if (sayFeedback) { say(feedback, null, typewriter = true); speak(feedback) }
                }
            }
            if (type in Actions.SLOW) 1500L else 700L
        }
        handler.postDelayed({ runActionQueue(queue, sayFeedback, i + 1, observed) },
            if (i + 1 < queue.size) delay else 0L)
    }

    /** Kirim hasil pengamatan ke otak untuk langkah berikutnya (dibatasi MAX_AGENT_STEPS). */
    private fun continueAgent(observed: String) {
        if (observed.isEmpty() || agentTask.isEmpty()) return
        if (++agentStep > MAX_AGENT_STEPS) {
            say("Udah $MAX_AGENT_STEPS langkah, aku berhenti dulu ya 🥵", PetView.Emotion.SLEEPY)
            return
        }
        say("🤔 (langkah $agentStep)...", null)
        sendToBrain("agent", JSONObject()
            .put("task", agentTask).put("step", agentStep)
            .put("done", agentDone.toString().takeLast(600))
            .put("observed", observed))
    }

    /** Dialog konfirmasi di atas aplikasi lain (butuh izin overlay yang sudah ada). */
    private fun confirm(msg: String, onResult: (Boolean) -> Unit) {
        var answered = false
        fun answer(ok: Boolean) { if (!answered) { answered = true; onResult(ok) } }
        val d = android.app.AlertDialog.Builder(
            android.view.ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog_Alert))
            .setTitle("Moci minta izin 🐾")
            .setMessage(msg)
            .setPositiveButton("Ya, lanjut") { _, _ -> answer(true) }
            .setNegativeButton("Batal") { _, _ -> answer(false) }
            .setOnDismissListener { answer(false) }
            .create()
        d.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        d.show()
        say("❓ $msg", PetView.Emotion.SURPRISED)
    }

    /** Cek lokasi aktif (GPS/fused) lalu laporkan + perbarui lat/lon untuk cuaca. */
    private fun fetchLocation() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            say("Beri aku izin lokasi dulu ya (di pengaturan) 📍🥺", PetView.Emotion.SAD)
            return
        }
        say("Sebentar, cek lokasi... 📍", PetView.Emotion.SURPRISED)
        val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val provider = when {
            Build.VERSION.SDK_INT >= 31 &&
                lm.isProviderEnabled(android.location.LocationManager.FUSED_PROVIDER) ->
                android.location.LocationManager.FUSED_PROVIDER
            lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ->
                android.location.LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ->
                android.location.LocationManager.NETWORK_PROVIDER
            else -> { say("GPS-nya mati, nyalakan dulu ya 🛰️", PetView.Emotion.SAD); return }
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, mainExecutor) { loc ->
                    if (loc != null) reportLocation(loc)
                    else lm.getLastKnownLocation(provider)?.let { reportLocation(it) }
                        ?: say("Belum dapat sinyal GPS, coba di tempat terbuka 🛰️", PetView.Emotion.SAD)
                }
            } else {
                @Suppress("DEPRECATION")
                lm.getLastKnownLocation(provider)?.let { reportLocation(it) }
                    ?: say("Belum dapat sinyal GPS 🛰️", PetView.Emotion.SAD)
            }
        } catch (e: SecurityException) {
            say("Izin lokasiku diblokir 😖", PetView.Emotion.SAD)
        }
    }

    private fun reportLocation(loc: android.location.Location) {
        latitude = String.format(java.util.Locale.US, "%.4f", loc.latitude)
        longitude = String.format(java.util.Locale.US, "%.4f", loc.longitude)
        val place = try {
            android.location.Geocoder(this, java.util.Locale("id"))
                .getFromLocation(loc.latitude, loc.longitude, 1)
                ?.firstOrNull()?.let { it.subLocality ?: it.locality ?: it.adminArea }
        } catch (e: Exception) { null }
        val where = if (place != null) "di sekitar $place" else "di ${latitude}, ${longitude}"
        val msg = "Kamu lagi $where 📍 (akurasi ±${loc.accuracy.toInt()}m)"
        say(msg, PetView.Emotion.HAPPY, typewriter = true); speak(msg)
        PetLog.add(this, "aksi", "cek lokasi: $where")
    }

    private var typeFull = ""
    private var typeIdx = 0
    private val typeRunnable = object : Runnable {
        override fun run() {
            typeIdx = (typeIdx + 1).coerceAtMost(typeFull.length)
            bubble.text = typeFull.substring(0, typeIdx)
            positionBubble()
            if (typeIdx < typeFull.length) handler.postDelayed(this, 26)
        }
    }

    private fun say(text: String, emotion: PetView.Emotion?, typewriter: Boolean = false) {
        wake()
        emotion?.let { if (pet.emotion != it) Kenney.sfxFor(this, it); pet.emotion = it }
        handler.removeCallbacks(typeRunnable)
        val firstShow = !bubbleShown
        if (typewriter && text.length > 6) {
            typeFull = text; typeIdx = 0; bubble.text = ""
            handler.postDelayed(typeRunnable, 120) // efek "mengetik"
        } else {
            bubble.text = text
        }
        if (firstShow) {
            runCatching { wm.addView(bubble, bubbleLp) }
            bubbleShown = true
        }
        positionBubble()
        if (firstShow) {
            bubble.alpha = 0f
            bubble.post {
                // animasi "pop" muncul: membesar dari kecil (titik tumpu di bawah, ke arah pet)
                bubble.pivotY = bubble.height.toFloat()
                bubble.pivotX = bubble.width / 2f
                bubble.scaleX = 0.6f; bubble.scaleY = 0.6f
                bubble.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start()
            }
        }
        handler.removeCallbacks(hideBubble)
        // durasi baca menyesuaikan panjang teks (6–40 detik) + waktu mengetik
        val typeMs = if (typewriter) text.length * 26L else 0L
        val duration = (5_000L + text.length * 70L + typeMs).coerceIn(6_000L, 45_000L)
        handler.postDelayed(hideBubble, duration)
        // berhenti jalan selama bicara biar enak dibaca
        pausedUntil = maxOf(pausedUntil,
            System.currentTimeMillis() + duration.coerceAtMost(12_000L))
    }

    /** Tempatkan gelembung tepat di atas pet, ditahan dalam batas layar. */
    private fun positionBubble() {
        bubble.post {
            val screenW = resources.displayMetrics.widthPixels
            val bw = bubble.width.coerceAtLeast(1)
            val bh = bubble.height
            val petCenterX = lp.x + pet.width / 2
            bubbleLp.x = (petCenterX - bw / 2).coerceIn(dp(4f).toInt(), screenW - bw - dp(4f).toInt())
            // ekor menempel di atas pet (sedikit menimpa) agar terlihat menyatu
            bubbleLp.y = (lp.y - bh + dp(4f).toInt()).coerceAtLeast(dp(24f).toInt())
            // arahkan ujung ekor ke tengah pet (relatif terhadap kiri gelembung)
            (bubble.background as? ComicBubble)?.tailX = (petCenterX - bubbleLp.x).toFloat()
            if (bubbleShown) runCatching { wm.updateViewLayout(bubble, bubbleLp) }
        }
    }

    private val hideBubble = Runnable {
        handler.removeCallbacks(typeRunnable)
        if (!bubbleShown) return@Runnable
        bubble.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setDuration(160)
            .withEndAction {
                if (bubbleShown) { runCatching { wm.removeView(bubble) }; bubbleShown = false }
            }.start()
    }

    // ---------------------------------------------------------------- foreground

    /** Dipanggil sebelum foto/video: izin bisa baru diberikan setelah service jalan. */
    fun refreshForegroundType() { runCatching { startAsForeground() } }

    /** Umpan balik dari MediaCapture (dipanggil di main thread). */
    fun report(msg: String) { PetLog.add(this, "aksi", msg); say(msg, null, typewriter = true) }

    private fun startAsForeground() {
        val channelId = "hermespet"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            // IMPORTANCE_MIN: tidak muncul di status bar, ringkas & senyap
            NotificationChannel(channelId, "Status pet", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            }
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("🐾 Mocipet")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 30) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            // tipe location hanya ditambah bila izin sudah ada → bisa baca GPS dari FGS
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(1, notif, type)
        } else {
            startForeground(1, notif)
        }
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
