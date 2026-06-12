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
import com.hermespet.Prefs.moodCheckEnabled
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

        private const val BASE_SIZE_DP = 96
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
    private var speed = 0f

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
        })
        handler.post(walkLoop)
        handler.postDelayed(tickLoop, 5_000)
        handler.postDelayed(moodLoop, 20_000) // cek pertama tak lama setelah start
        handler.post(moodTick)
        pet.emotion = Mood.current(this)
        say("Halo! Aku hidup! 🐾", PetView.Emotion.HAPPY)
        transientUntil = System.currentTimeMillis() + 8_000
    }

    // --- hemat baterai: hentikan animasi & jalan saat layar mati ---
    @Volatile private var screenOn = true
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; pet.awake = false }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true; pet.awake = true
                    pet.emotion = Mood.current(this@PetOverlayService)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_APPLY_SCALE) applyScale()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        Senses.stop(this)
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

        // gelembung bicara: window TERPISAH yang melebar dinamis mengikuti teks
        bubble = TextView(this).apply {
            setTextColor(Color.parseColor("#3E2723"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(14f)
                setStroke(3, Color.parseColor("#33334D"))
            }
            val px = dp(12f).toInt(); val py = dp(9f).toInt()
            setPadding(px, py, px, py)
            elevation = dp(6f)
        }
        bubbleLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, // lebar mengikuti isi
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // batasi lebar maksimum ke 78% layar; teks panjang otomatis wrap ke bawah
            width = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        }
        bubble.maxWidth = bubbleLp.width

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

        root.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            if (ev.pointerCount > 1) return@setOnTouchListener true
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = lp.x; startY = lp.y
                    dragging = false
                    downAt = System.currentTimeMillis()
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
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val now = System.currentTimeMillis()
                    when {
                        dragging -> { petX = lp.x; petY = lp.y; posX = lp.x.toFloat() }
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
        }
        val send = Button(this).apply { text = "➤" }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16f)
                setStroke(2, Color.parseColor("#33334D"))
            }
            val p = dp(6f).toInt()
            setPadding(p, p, p, p)
            addView(input, LinearLayout.LayoutParams(dp(200f).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(send)
        }
        val clp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, // fokusable → keyboard bisa muncul
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lp.x
            y = lp.y + root.height + dp(4f).toInt()
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

    private val walkLoop = object : Runnable {
        override fun run() {
            if (!screenOn) { // layar mati → jangan render/gerak (hemat baterai)
                handler.postDelayed(this, 1_000)
                return
            }
            val now = System.currentTimeMillis()
            val maxX = (resources.displayMetrics.widthPixels - pet.width).coerceAtLeast(1)
            if (now >= pausedUntil) {
                val dist = walkTargetX - posX
                if (walkTargetX < 0 || abs(dist) < 4f) {
                    // sampai tujuan → istirahat, lalu pilih tujuan baru
                    walkTargetX = Random.nextInt(0, maxX)
                    pausedUntil = now + Random.nextLong(3_000, 15_000)
                    pet.walking = false
                    speed = 0f
                } else {
                    val dir = if (dist > 0) 1 else -1
                    // easing: melambat mendekati tujuan, akselerasi halus saat mulai
                    val maxSpeed = 2.2f * petScale
                    val targetSpeed = minOf(maxSpeed, abs(dist) / 25f).coerceAtLeast(0.5f)
                    speed += (targetSpeed - speed) * 0.07f
                    posX = (posX + dir * speed).coerceIn(0f, maxX.toFloat())
                    pet.facing = dir
                    pet.walking = true
                    if (lp.x != posX.toInt()) {
                        lp.x = posX.toInt()
                        runCatching { wm.updateViewLayout(root, lp) }
                        if (bubbleShown) positionBubble()
                    }
                }
            } else {
                pet.walking = false
                speed = 0f
            }
            handler.postDelayed(this, WALK_FRAME_MS)
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

    fun onNotificationSeen(pkg: String, title: String, text: String) {
        PetLog.add(this, "notif", "$pkg: $title — ${text.take(80)}")
        val data = JSONObject().put("package", pkg).put("title", title).put("text", text)
        sendToBrain("notification", data)
    }

    /** Dipanggil ReminderReceiver saat pengingat tiba & pet sedang hidup. */
    fun announceReminder(text: String) {
        handler.post {
            say("⏰ $text", PetView.Emotion.SURPRISED)
            speak("Pengingat! $text")
            transientUntil = System.currentTimeMillis() + 10_000
        }
    }

    private fun sendToBrain(type: String, data: JSONObject) {
        // interaksi langsung memperbarui "kapan terakhir disapa" (anti-kesepian)
        if (type == "chat" || type == "poke") Mood.noteInteraction(this)
        Brain.sendEvent(this, type, data) { reply ->
            reply ?: return@sendEvent
            val emotion = reply.optString("emotion", "")
            if (emotion.isNotEmpty()) {
                val emo = PetView.emotionFrom(emotion)
                pet.emotion = emo
                transientUntil = System.currentTimeMillis() + 12_000
                // chat & cek-mood kamera membentuk suasana hati yang menetap
                if (type == "chat" || type == "mood") Mood.note(this, emo)
            }
            val text = reply.optString("say", "")
            if (text.isNotEmpty()) {
                PetLog.add(this, "pet", text)
                say(text, null)
                speak(text)
            }
            // otak minta aksi nyata (volume, senter, buka app, dll.)
            reply.optJSONObject("action")?.let { act ->
                val feedback = Actions.run(this, act)
                if (!feedback.isNullOrEmpty()) {
                    PetLog.add(this, "aksi", feedback)
                    if (text.isEmpty()) {
                        say(feedback, null)
                        speak(feedback)
                    }
                }
            }
        }
    }

    private fun say(text: String, emotion: PetView.Emotion?) {
        emotion?.let { pet.emotion = it }
        bubble.text = text
        if (!bubbleShown) {
            runCatching { wm.addView(bubble, bubbleLp) }
            bubbleShown = true
        }
        positionBubble()
        handler.removeCallbacks(hideBubble)
        // durasi baca menyesuaikan panjang teks (6–40 detik), proporsional jumlah kata
        val duration = (5_000L + text.length * 70L).coerceIn(6_000L, 40_000L)
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
            bubbleLp.y = (lp.y - bh - dp(6f).toInt()).coerceAtLeast(dp(24f).toInt())
            if (bubbleShown) runCatching { wm.updateViewLayout(bubble, bubbleLp) }
        }
    }

    private val hideBubble = Runnable {
        if (bubbleShown) { runCatching { wm.removeView(bubble) }; bubbleShown = false }
    }

    // ---------------------------------------------------------------- foreground

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startAsForeground() {
        val channelId = "hermespet"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "HermesPet", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("HermesPet aktif")
            .setContentText("Pet sedang jalan-jalan di layarmu")
            .setSmallIcon(android.R.drawable.star_on)
            .setContentIntent(pi)
            .build()

        if (Build.VERSION.SDK_INT >= 30) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (hasCameraPermission()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(1, notif, type)
        } else {
            startForeground(1, notif)
        }
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
