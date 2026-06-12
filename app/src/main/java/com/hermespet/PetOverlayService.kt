package com.hermespet

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.hermespet.Prefs.moodCheckEnabled
import com.hermespet.Prefs.petScale
import com.hermespet.Prefs.petX
import com.hermespet.Prefs.petY
import com.hermespet.Prefs.sendMoodImage
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.abs
import kotlin.random.Random

class PetOverlayService : Service() {

    companion object {
        @Volatile var instance: PetOverlayService? = null
        const val ACTION_APPLY_SCALE = "com.hermespet.APPLY_SCALE"

        private const val BASE_SIZE_DP = 96
        private const val TICK_MS = 60_000L
        private const val MOOD_MS = 60 * 60_000L // tiap jam
        private const val WALK_FRAME_MS = 50L
    }

    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var bubble: TextView
    private lateinit var pet: PetView
    private lateinit var lp: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())

    // --- gerak jalan ---
    private var walkTargetX = -1
    private var pausedUntil = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildOverlay()
        handler.post(walkLoop)
        handler.postDelayed(tickLoop, 5_000)
        handler.postDelayed(moodLoop, 20_000) // cek pertama tak lama setelah start
        say("Halo! Aku hidup! 🐾", PetView.Emotion.HAPPY)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_APPLY_SCALE) applyScale()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        runCatching { wm.removeView(root) }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- overlay

    private fun buildOverlay() {
        pet = PetView(this)
        bubble = TextView(this).apply {
            setTextColor(Color.parseColor("#3E2723"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12f)
                setStroke(2, Color.parseColor("#3E2723"))
            }
            val p = dp(8f).toInt()
            setPadding(p, p / 2, p, p / 2)
            maxWidth = dp(220f).toInt()
            visibility = View.GONE
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(bubble)
            addView(pet)
        }

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = petX
            y = petY
        }

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

        root.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            if (ev.pointerCount > 1) return@setOnTouchListener true
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = lp.x; startY = lp.y
                    dragging = false
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
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        petX = lp.x; petY = lp.y
                    } else {
                        onPoked()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onPoked() {
        pet.emotion = PetView.Emotion.SURPRISED
        sendToBrain("poke", JSONObject())
    }

    // ---------------------------------------------------------------- jalan-jalan

    private val walkLoop = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val maxX = (resources.displayMetrics.widthPixels - pet.width).coerceAtLeast(1)
            if (now >= pausedUntil) {
                if (walkTargetX < 0 || abs(lp.x - walkTargetX) < 8) {
                    // sampai tujuan → istirahat, lalu pilih tujuan baru
                    walkTargetX = Random.nextInt(0, maxX)
                    pausedUntil = now + Random.nextLong(3_000, 15_000)
                    pet.walking = false
                } else {
                    val step = (4 * petScale).toInt().coerceAtLeast(2)
                    val dir = if (walkTargetX > lp.x) 1 else -1
                    lp.x = (lp.x + dir * step).coerceIn(0, maxX)
                    pet.facing = dir
                    pet.walking = true
                    runCatching { wm.updateViewLayout(root, lp) }
                }
            } else {
                pet.walking = false
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
            sendToBrain("tick", data)
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val moodLoop = object : Runnable {
        override fun run() {
            if (moodCheckEnabled && hasCameraPermission()) {
                MoodCamera.capture(this@PetOverlayService, sendMoodImage) { result ->
                    sendToBrain("mood", result)
                }
            }
            handler.postDelayed(this, MOOD_MS)
        }
    }

    fun onNotificationSeen(pkg: String, title: String, text: String) {
        val data = JSONObject().put("package", pkg).put("title", title).put("text", text)
        sendToBrain("notification", data)
    }

    private fun sendToBrain(type: String, data: JSONObject) {
        Brain.sendEvent(this, type, data) { reply ->
            reply ?: return@sendEvent
            val emotion = reply.optString("emotion", "")
            if (emotion.isNotEmpty()) pet.emotion = PetView.emotionFrom(emotion)
            val text = reply.optString("say", "")
            if (text.isNotEmpty()) say(text, null)
        }
    }

    private fun say(text: String, emotion: PetView.Emotion?) {
        emotion?.let { pet.emotion = it }
        bubble.text = text
        bubble.visibility = View.VISIBLE
        handler.removeCallbacks(hideBubble)
        handler.postDelayed(hideBubble, 8_000)
    }

    private val hideBubble = Runnable { bubble.visibility = View.GONE }

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
            startForeground(1, notif, type)
        } else {
            startForeground(1, notif)
        }
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
