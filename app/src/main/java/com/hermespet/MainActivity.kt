package com.hermespet

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.hermespet.Prefs.agentUrl
import com.hermespet.Prefs.apiBaseUrl
import com.hermespet.Prefs.apiKey
import com.hermespet.Prefs.apiModel
import com.hermespet.Prefs.brainMode
import com.hermespet.Prefs.embModel
import com.hermespet.Prefs.visionModel
import com.hermespet.Prefs.latitude
import com.hermespet.Prefs.longitude
import com.hermespet.Prefs.moodCheckEnabled
import com.hermespet.Prefs.musicDance
import com.hermespet.Prefs.petScale
import com.hermespet.Prefs.petVoice
import com.hermespet.Prefs.sendMoodImage
import com.hermespet.Prefs.soulMd

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var permPanel: LinearLayout
    private var guiding = false

    private fun grantAll() {
        guiding = true
        val needRuntime = Permissions.run {
            // ada izin runtime yang belum? minta sekaligus
            status(this@MainActivity).any { !it.granted }
        }
        Permissions.requestRuntime(this, 100)
        // kalau tidak ada dialog runtime yang muncul, langsung lanjut ke akses khusus
        if (!needRuntime || Permissions.pendingSpecial(this).isNotEmpty()) {
            permPanel.postDelayed({ openNextSpecial() }, 300)
        }
    }

    private fun openNextSpecial() {
        if (!guiding) return
        val next = Permissions.pendingSpecial(this).firstOrNull()
        if (next == null) {
            guiding = false
            android.widget.Toast.makeText(this, "Semua izin sudah diatur 🎉",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        android.widget.Toast.makeText(this, "Izinkan: ${next.first}",
            android.widget.Toast.LENGTH_SHORT).show()
        runCatching { startActivity(next.second) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
        if (guiding) permPanel.postDelayed({ openNextSpecial() }, 300)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(col) })

        col.addView(TextView(this).apply { text = "🐾 HermesPet"; textSize = 24f })
        status = TextView(this).apply { setPadding(0, pad / 2, 0, pad) }
        col.addView(status)

        // --- izin (satu tombol + status) ---
        col.addView(section("1. Izin"))
        col.addView(button("✨ Berikan semua izin (1 tombol)") { grantAll() })
        permPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(permPanel)

        // --- koneksi otak ---
        col.addView(section("2. Otak (model Hermes)"))
        @Suppress("UseSwitchCompatOrMaterialCode")
        val apiModeSwitch = Switch(this).apply {
            text = "Otak bawaan via API — standalone, tanpa Termux"
            isChecked = brainMode == "api"
            setOnCheckedChangeListener { _, on -> brainMode = if (on) "api" else "termux" }
        }
        col.addView(apiModeSwitch)

        col.addView(TextView(this).apply { text = "API key (Nous Portal / OpenRouter):" })
        val keyInput = EditText(this).apply {
            setText(apiKey); hint = "sk-... (kosong = balasan bawaan)"
        }
        col.addView(keyInput)

        col.addView(TextView(this).apply { text = "Model:" })
        val modelInput = EditText(this).apply { setText(apiModel) }
        col.addView(modelInput)

        col.addView(TextView(this).apply { text = "Base URL API:" })
        val baseInput = EditText(this).apply { setText(apiBaseUrl) }
        col.addView(baseInput)

        col.addView(TextView(this).apply {
            text = "Model embeddings — memori vektor (opsional, kosong = kata kunci):"
        })
        val embInput = EditText(this).apply {
            setText(embModel)
            hint = "mis. text-embedding-3-small"
        }
        col.addView(embInput)

        col.addView(TextView(this).apply {
            text = "Model vision — untuk 'lihat layar' (wajib multimodal):"
        })
        val visionInput = EditText(this).apply {
            setText(visionModel)
            hint = "mis. google/gemini-flash-1.5 (kosong = model utama)"
        }
        col.addView(visionInput)

        col.addView(TextView(this).apply { text = "Lokasi untuk cuaca (lat, lon):" })
        val latInput = EditText(this).apply { setText(latitude); hint = "-6.2" }
        val lonInput = EditText(this).apply { setText(longitude); hint = "106.8" }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(latInput, LinearLayout.LayoutParams(0, -2, 1f))
            addView(lonInput, LinearLayout.LayoutParams(0, -2, 1f))
        })

        col.addView(TextView(this).apply { text = "Alamat server Termux (hanya mode Termux):" })
        val urlInput = EditText(this).apply {
            setText(agentUrl)
            hint = "http://127.0.0.1:8765"
        }
        col.addView(urlInput)

        col.addView(TextView(this).apply {
            text = "SOUL.md — kepribadian pet (silakan edit sesukamu):"
        })
        val soulInput = EditText(this).apply {
            setText(soulMd)
            minLines = 5
            gravity = android.view.Gravity.TOP
            textSize = 13f
        }
        col.addView(soulInput)
        col.addView(button("Kembalikan SOUL.md bawaan") {
            soulInput.setText(Prefs.DEFAULT_SOUL)
        })

        col.addView(button("Simpan pengaturan otak") {
            apiKey = keyInput.text.toString().trim()
            apiModel = modelInput.text.toString().trim()
            apiBaseUrl = baseInput.text.toString().trim()
            embModel = embInput.text.toString().trim()
            visionModel = visionInput.text.toString().trim()
            latitude = latInput.text.toString().trim()
            longitude = lonInput.text.toString().trim()
            agentUrl = urlInput.text.toString().trim()
            soulMd = soulInput.text.toString()
            toastStatus("Pengaturan otak disimpan")
        })

        // --- pet ---
        col.addView(section("3. Pet"))
        col.addView(TextView(this).apply { text = "Ukuran pet (bisa juga pinch langsung di pet):" })
        col.addView(SeekBar(this).apply {
            max = 200 // 0..200 → 0.5x..2.5x
            progress = ((petScale - 0.5f) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    petScale = 0.5f + p / 100f
                    if (PetOverlayService.instance != null) {
                        startService(Intent(this@MainActivity, PetOverlayService::class.java)
                            .setAction(PetOverlayService.ACTION_APPLY_SCALE))
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        @Suppress("UseSwitchCompatOrMaterialCode")
        val voiceSwitch = Switch(this).apply {
            text = "Pet bersuara (membacakan ucapannya, TTS)"
            isChecked = petVoice
            setOnCheckedChangeListener { _, on -> petVoice = on }
        }
        col.addView(voiceSwitch)

        @Suppress("UseSwitchCompatOrMaterialCode")
        val danceSwitch = Switch(this).apply {
            text = "Slime berjoget saat ada musik 🎵"
            isChecked = musicDance
            setOnCheckedChangeListener { _, on -> musicDance = on }
        }
        col.addView(danceSwitch)

        col.addView(TextView(this).apply {
            text = Care.status(this@MainActivity) + "  (kasih makan 🍬 lewat menu chat)"
            setPadding(0, 8, 0, 8)
        })

        @Suppress("UseSwitchCompatOrMaterialCode")
        val moodSwitch = Switch(this).apply {
            text = "Cek mood lewat kamera depan tiap jam"
            isChecked = moodCheckEnabled
            setOnCheckedChangeListener { _, on -> moodCheckEnabled = on }
        }
        col.addView(moodSwitch)

        @Suppress("UseSwitchCompatOrMaterialCode")
        val imgSwitch = Switch(this).apply {
            text = "Kirim foto mood ke agent (bukan cuma ringkasan)"
            isChecked = sendMoodImage
            setOnCheckedChangeListener { _, on -> sendMoodImage = on }
        }
        col.addView(imgSwitch)

        col.addView(button("📜 Riwayat chat & log") {
            startActivity(Intent(this, LogActivity::class.java))
        })
        col.addView(button("🧠 Kelola memori & skill") {
            startActivity(Intent(this, ManagerActivity::class.java))
        })

        // --- start/stop ---
        col.addView(section("4. Jalankan"))
        col.addView(button("▶  Hidupkan pet") {
            if (!Settings.canDrawOverlays(this)) {
                toastStatus("Beri izin overlay dulu (tombol di atas)!")
            } else {
                startForegroundService(Intent(this, PetOverlayService::class.java))
                toastStatus("Pet dihidupkan 🐾")
            }
        })
        col.addView(button("⏹  Matikan pet") {
            stopService(Intent(this, PetOverlayService::class.java))
            toastStatus("Pet dimatikan")
        })

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // melanjutkan panduan izin saat kembali dari layar setelan
        if (guiding) permPanel.postDelayed({ openNextSpecial() }, 400)
    }

    private fun refreshStatus() {
        val perms = Permissions.status(this)
        val running = if (PetOverlayService.instance != null) "✅ aktif" else "⏸ mati"
        val notGreen = perms.count { !it.granted }
        status.text = if (notGreen == 0) "Semua izin hijau ✅   Pet: $running"
            else "$notGreen izin belum aktif ⚠️   Pet: $running"

        if (!::permPanel.isInitialized) return
        permPanel.removeAllViews()
        perms.filter { !it.granted }.forEach { p ->
            permPanel.addView(TextView(this).apply {
                text = "❌ ${p.label}" + if (p.essential) "  (penting)" else ""
                setTextColor(android.graphics.Color.parseColor(
                    if (p.essential) "#C62828" else "#EF6C00"))
                setPadding(0, 6, 0, 6)
            })
        }
        val granted = perms.count { it.granted }
        permPanel.addView(TextView(this).apply {
            text = "✅ $granted/${perms.size} izin aktif"
            setTextColor(android.graphics.Color.parseColor("#2E7D32"))
            setPadding(0, 8, 0, 6)
        })
    }

    private fun toastStatus(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    private fun section(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 18f
        val p = (12 * resources.displayMetrics.density).toInt()
        setPadding(0, p, 0, p / 3)
    }

    private fun button(label: String, onClick: (View) -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener(onClick)
        }
}
