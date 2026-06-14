package com.hermespet

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hermespet.Prefs.apiBaseUrl
import com.hermespet.Prefs.apiKey
import com.hermespet.Prefs.apiModel
import com.hermespet.Prefs.setupDone

/** Wizard 3 langkah untuk pemula: izin → hubungkan AI → mulai. */
class WizardActivity : Activity() {

    private lateinit var status: TextView
    private var guiding = false
    private lateinit var keyInput: EditText
    private lateinit var testResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(col) })

        col.addView(TextView(this).apply { text = "Selamat datang di Mocipet 🐾"; textSize = 24f })
        col.addView(TextView(this).apply {
            text = "Tiga langkah singkat biar pet-mu siap."
            setPadding(0, 8, 0, 16)
        })
        status = TextView(this).apply { setPadding(0, 0, 0, 12) }
        col.addView(status)

        // --- Langkah 1: izin ---
        col.addView(step("1️⃣  Beri izin"))
        col.addView(button("✨ Berikan semua izin sekaligus") { grantAll() })
        col.addView(hint("Pet butuh tampil di atas aplikasi & beberapa izin. Tombol ini " +
            "memandumu satu per satu."))

        // --- Langkah 2: hubungkan AI ---
        col.addView(step("2️⃣  Hubungkan otak AI"))
        col.addView(button("Pakai OpenRouter (disarankan)") {
            apiBaseUrl = "https://openrouter.ai/api/v1"
            apiModel = "google/gemini-flash-1.5"
            toast("Preset OpenRouter dipasang. Sekarang tempel API key di bawah.")
        })
        col.addView(button("🌐 Buka OpenRouter untuk ambil API key") {
            open("https://openrouter.ai/keys")
        })
        col.addView(TextView(this).apply { text = "Tempel API key di sini:"; setPadding(0, 8, 0, 0) })
        keyInput = EditText(this).apply { setText(apiKey); hint = "sk-or-..." }
        col.addView(keyInput)
        col.addView(button("✅ Tes koneksi") { testConnection() })
        testResult = TextView(this).apply { setPadding(0, 4, 0, 0) }
        col.addView(testResult)
        col.addView(hint("Tanpa API key, pet tetap hidup tapi jawabannya bawaan saja. " +
            "Kamu juga bisa pakai endpoint lain di Pengaturan lengkap."))

        // --- Langkah 3: mulai ---
        col.addView(step("3️⃣  Mulai!"))
        col.addView(button("❓ Lihat cara main dengan pet") { Help.gestures(this) })
        col.addView(button("▶  Hidupkan pet sekarang") { finishSetup() })
        col.addView(button("Buka Pengaturan lengkap") {
            startActivity(Intent(this, MainActivity::class.java))
        })

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (guiding) status.postDelayed({ openNextSpecial() }, 400)
    }

    // ---- izin (pola sama dgn pusat izin) ----
    private fun grantAll() {
        guiding = true
        Permissions.requestRuntime(this, 100)
        if (Permissions.pendingSpecial(this).isNotEmpty()) status.postDelayed({ openNextSpecial() }, 300)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(c, p, g)
        refreshStatus()
        if (guiding) status.postDelayed({ openNextSpecial() }, 300)
    }

    private fun openNextSpecial() {
        if (!guiding) return
        val next = Permissions.pendingSpecial(this).firstOrNull()
        if (next == null) { guiding = false; toast("Izin selesai 🎉"); return }
        toast("Izinkan: ${next.first}")
        runCatching { startActivity(next.second) }
    }

    private fun testConnection() {
        apiKey = keyInput.text.toString().trim()
        if (apiKey.isBlank()) { testResult.text = "Tempel API key dulu ya 🔑"; return }
        testResult.text = "Menguji koneksi…"
        Brain.testConnection(this) { ok, msg ->
            testResult.text = (if (ok) "✅ " else "❌ ") + msg
        }
    }

    private fun finishSetup() {
        apiKey = keyInput.text.toString().trim()
        if (!Settings.canDrawOverlays(this)) {
            toast("Beri izin overlay dulu di Langkah 1 ya!")
            return
        }
        setupDone = true
        startForegroundService(Intent(this, PetOverlayService::class.java))
        toast("Pet dihidupkan 🐾 Selamat bermain!")
        finish()
    }

    private fun refreshStatus() {
        val perms = Permissions.status(this)
        val notGreen = perms.count { !it.granted }
        status.text = if (notGreen == 0) "✅ Semua izin siap" else "⚠️ $notGreen izin belum aktif"
    }

    private fun open(url: String) =
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.let {}

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun step(t: String) = TextView(this).apply {
        text = t; textSize = 19f
        val p = (16 * resources.displayMetrics.density).toInt()
        setPadding(0, p, 0, p / 3)
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(0xFF888888.toInt()); setPadding(0, 2, 0, 4)
    }

    private fun button(label: String, onClick: (View) -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener(onClick)
    }
}
