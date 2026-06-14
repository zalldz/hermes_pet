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
import com.hermespet.Prefs.latitude
import com.hermespet.Prefs.longitude
import com.hermespet.Prefs.moodCheckEnabled
import com.hermespet.Prefs.musicDance
import com.hermespet.Prefs.petScale
import com.hermespet.Prefs.petVoice
import com.hermespet.Prefs.petX
import com.hermespet.Prefs.petY
import com.hermespet.Prefs.sendMoodImage
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
        private const val TICK_MS = 60_000L
        private const val MOOD_MS = 60 * 60_000L // tiap jam
        private const val WALK_FRAME_MS = 16L // ~60 fps
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Senses.start(this)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale("id", "ID")
        }
        buildOverlay()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        handler.post(walkLoop)
        handler.postDelayed(tickLoop, 5_000)
        handler.postDelayed(moodLoop, 20_000) // cek pertama tak lama setelah start
        handler.post(moodTick)
        handler.postDelayed(musicLoop, 3_000)
        startLan()
        pet.emotion = Mood.current(this)
        say("Halo! Aku hidup! 🐾", PetView.Emotion.HAPPY)
        transientUntil = System.currentTimeMillis() + 8_000
    }

    /** Walkie-talkie LAN: dengarkan teks/suara dari teman di WiFi yang sama. */
    private fun startLan() {
        LanLink.start(this) { type, from, data ->
            handler.post {
                when (type) {
                    LanLink.TYPE_VOICE -> {
                        say("🔊 Pesan suara dari $from", PetView.Emotion.SURPRISED)
                        transientUntil = System.currentTimeMillis() + 6_000
                        VoiceMsg.play(this, data)
                        PetLog.add(this, "aksi", "🎙️ suara dari $from (LAN)")
                    }
                    LanLink.TYPE_TEXT -> {
                        val msg = String(data, Charsets.UTF_8)
                        say("💬 $from: $msg", PetView.Emotion.HAPPY)
                        transientUntil = System.currentTimeMillis() + 6_000
                        PetLog.add(this, "user", "[$from via LAN] $msg")
                    }
                }
            }
        }
    }

    // --- hemat baterai + sapaan proaktif ---
    @Volatile private var screenOn = true
    private var lastGreetAt = 0L
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; pet.awake = false; stopVisualizer() }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true; pet.awake = true
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
        LanLink.stop()
        VoiceMsg.stop()
        tts?.shutdown()
        runCatching { unregisterReceiver(screenReceiver) }
        closeChat()
        if (bubbleShown) runCatching { wm.removeView(bubble) }
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
                            if (speedPxs > 600 && now - lastMoveT < 120) startFling()
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
        val feed = toolBtn("🍬", "Makan") // kasih makan
        val eye = toolBtn("👁", "Lihat")  // lihat layar
        val fren = toolBtn("📡", "Teman")  // kirim teks ke teman di WiFi
        val ptt = toolBtn("🎙️", "Tahan")  // tahan = pesan suara ke teman
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
            addView(feed, cell(true))
            addView(eye, cell(false))
            addView(fren, cell(false))
            addView(ptt, cell(false))
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
            if (text.isNotEmpty()) {
                PetLog.add(this, "user", text)
                say("🤔...", null)
                sendToBrain("chat", JSONObject().put("text", text))
            }
        }
        send.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, _, _ -> submit(); true }
        eye.setOnClickListener {
            val q = input.text.toString().trim()
                .ifBlank { "Apa yang kamu lihat di layar ini? Jelaskan singkat." }
            closeChat()
            lookAtScreen(q)
        }
        feed.setOnClickListener { closeChat(); feedPet() }
        fren.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) {
                say("Ketik dulu pesannya 🙂", null)
                transientUntil = System.currentTimeMillis() + 2_000
                return@setOnClickListener
            }
            input.setText("")
            LanLink.sendText(this, text) { n ->
                handler.post {
                    if (n > 0) {
                        say("📡 Terkirim ke $n HP", PetView.Emotion.HAPPY)
                        PetLog.add(this, "user", "[ke teman LAN] $text")
                    } else {
                        say("Tidak ada teman di WiFi ini 🥺", PetView.Emotion.SAD)
                    }
                    transientUntil = System.currentTimeMillis() + 3_000
                }
            }
        }
        ptt.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (VoiceMsg.startRecording(this)) {
                        ptt.text = "🔴\nLepas"
                        say("🎙️ Merekam... lepas untuk kirim", PetView.Emotion.SURPRISED)
                        transientUntil = System.currentTimeMillis() + 30_000
                    } else {
                        say("Mic tidak bisa dipakai 🥺", PetView.Emotion.SAD)
                        transientUntil = System.currentTimeMillis() + 3_000
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ptt.text = "🎙️\nTahan"
                    val bytes = VoiceMsg.stopRecording()
                    if (bytes == null) {
                        say("Rekaman terlalu singkat 🙈", null)
                        transientUntil = System.currentTimeMillis() + 2_500
                    } else {
                        LanLink.sendVoice(this, bytes) { n ->
                            handler.post {
                                say(if (n > 0) "📡 Suara terkirim ke $n HP"
                                    else "Tidak ada teman di WiFi ini 🥺",
                                    if (n > 0) PetView.Emotion.HAPPY else PetView.Emotion.SAD)
                                transientUntil = System.currentTimeMillis() + 3_000
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }

        wm.addView(row, clp)
        chatView = row
        input.requestFocus()
        pausedUntil = System.currentTimeMillis() + 60_000 // diam selama ngobrol
    }

    private fun closeChat() {
        chatView?.let { runCatching { wm.removeView(it) } }
        chatView = null
    }

    private var lastTapAt = 0L
    private val pokeRunnable = Runnable { onPoked() }

    private fun onPoked() {
        pet.emotion = PetView.Emotion.SURPRISED
        Care.cheer(this)
        sendToBrain("poke", JSONObject())
    }

    private fun feedPet() {
        Care.feed(this)
        pet.emotion = PetView.Emotion.LOVE
        transientUntil = System.currentTimeMillis() + 4_000
        pet.bump(false) // gerakan "hap" saat makan
        val msg = listOf("Nyam nyam! 😋", "Enaak~ makasih! 🥰", "Hap! Kenyang 😊").random()
        say(msg, null, typewriter = true)
        speak(msg)
        PetLog.add(this, "aksi", "diberi makan — ${Care.status(this)}")
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

    /** Satu langkah fisika lemparan: gravitasi + pantul tepi layar + redam. */
    private fun flingStep() {
        val maxX = (resources.displayMetrics.widthPixels - pet.width).toFloat().coerceAtLeast(1f)
        val maxY = (resources.displayMetrics.heightPixels - pet.height).toFloat().coerceAtLeast(1f)
        velY += 1.4f                     // gravitasi
        velX *= 0.995f                   // gesekan udara
        fx += velX; fy += velY
        val restitution = 0.62f
        if (fx <= 0f) { fx = 0f; velX = -velX * restitution; pet.bump(true) }
        else if (fx >= maxX) { fx = maxX; velX = -velX * restitution; pet.bump(true) }
        if (fy <= 0f) { fy = 0f; velY = -velY * restitution }
        else if (fy >= maxY) { // mendarat di "lantai"
            fy = maxY
            if (velY > 4f) pet.bump(false)
            velY = -velY * restitution
            velX *= 0.7f                  // gesekan lantai
        }
        lp.x = fx.toInt(); lp.y = fy.toInt()
        runCatching { wm.updateViewLayout(root, lp) }
        if (bubbleShown) positionBubble()
        // berhenti saat sudah menempel lantai & nyaris diam
        if (fy >= maxY - 1f && kotlin.math.abs(velY) < 4f && kotlin.math.abs(velX) < 0.5f) {
            flinging = false
            posX = fx.coerceIn(0f, maxX); petX = lp.x; petY = lp.y
            pausedUntil = System.currentTimeMillis() + 1_500
            walkTargetX = -1
        }
    }

    private val walkLoop = object : Runnable {
        override fun run() {
            if (!screenOn) { // layar mati → jangan render/gerak (hemat baterai)
                handler.postDelayed(this, 1_000)
                return
            }
            if (flinging) { flingStep(); handler.postDelayed(this, WALK_FRAME_MS); return }
            val now = System.currentTimeMillis()
            val maxX = (resources.displayMetrics.widthPixels - pet.width).coerceAtLeast(1)
            if (now >= pausedUntil) {
                val dist = walkTargetX - posX
                when {
                    walkTargetX < 0 || abs(dist) < 8f -> {
                        // sampai tujuan → istirahat (denyut idle), lalu pilih tujuan baru
                        walkTargetX = Random.nextInt(0, maxX)
                        pausedUntil = now + Random.nextLong(4_000, 15_000)
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
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val moodLoop = object : Runnable {
        override fun run() {
            if (moodCheckEnabled && hasCameraPermission()) {
                MoodCamera.capture(this@PetOverlayService, sendMoodImage) { result ->
                    PetLog.add(this@PetOverlayService, "mood",
                        "wajah=${result.optBoolean("face_detected")} " +
                            "tebakan=${result.optString("mood_guess", "-")}")
                    sendToBrain("mood", result)
                }
            }
            handler.postDelayed(this, MOOD_MS)
        }
    }

    // baseline emosi pet mengikuti suasana hati + perawatan saat idle (tiap 15 detik),
    // kecuali sedang menampilkan reaksi sesaat (transientUntil)
    private var transientUntil = 0L
    private var lastHungerNag = 0L
    private val moodTick = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() > transientUntil) {
                val base = Mood.current(this@PetOverlayService)
                // saat mood netral, perawatan (lapar/bosan) yang menentukan ekspresi
                pet.emotion = if (base == PetView.Emotion.NEUTRAL)
                    (Care.moodHint(this@PetOverlayService) ?: base) else base
            }
            // rengekan lapar (tanpa API), maksimal tiap 30 menit
            val now = System.currentTimeMillis()
            if (Care.veryHungry(this@PetOverlayService) && now - lastHungerNag > 30 * 60_000L) {
                lastHungerNag = now
                val m = "Perutku keroncongan, kasih makan dong 🥺"
                say(m, PetView.Emotion.SAD, typewriter = true); speak(m)
            }
            handler.postDelayed(this, 15_000)
        }
    }

    // --- goyang ikut musik (#6): Visualizer hanya saat ada musik & layar nyala ---
    private var visualizer: android.media.audiofx.Visualizer? = null
    private val musicLoop = object : Runnable {
        override fun run() {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val want = musicDance && screenOn && am.isMusicActive
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

    fun onNotificationSeen(pkg: String, title: String, text: String) {
        PetLog.add(this, "notif", "$pkg: $title — ${text.take(80)}")
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

    private fun sendToBrain(type: String, data: JSONObject) {
        // interaksi langsung memperbarui "kapan terakhir disapa" (anti-kesepian)
        if (type == "chat" || type == "poke") { Mood.noteInteraction(this); Care.cheer(this) }
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
        val actionAllowed = type == "chat" || type == "vision"
        if (!actionAllowed) return
        // multi-step: kumpulkan aksi tunggal ("action") + daftar ("actions")
        val queue = ArrayList<JSONObject>()
        reply.optJSONObject("action")?.let { queue.add(it) }
        reply.optJSONArray("actions")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { queue.add(it) }
        }
        if (queue.isNotEmpty()) runActionQueue(queue, sayFeedback = text.isEmpty())
    }

    /** Jalankan rangkaian aksi (agentic) berurutan dengan jeda agar enak diikuti. */
    private fun runActionQueue(queue: List<JSONObject>, sayFeedback: Boolean, i: Int = 0) {
        if (i >= queue.size) return
        val act = queue[i]
        val delay = if (act.optString("type") == "location") {
            fetchLocation(); 1200L
        } else {
            val feedback = Actions.run(this, act)
            if (!feedback.isNullOrEmpty()) {
                PetLog.add(this, "aksi", feedback)
                if (sayFeedback) { say(feedback, null, typewriter = true); speak(feedback) }
            }
            700L
        }
        if (i + 1 < queue.size) handler.postDelayed({ runActionQueue(queue, sayFeedback, i + 1) }, delay)
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
        emotion?.let { pet.emotion = it }
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

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

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
            if (hasCameraPermission()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
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
