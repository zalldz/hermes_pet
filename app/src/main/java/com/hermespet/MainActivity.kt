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
import com.hermespet.Prefs.latitude
import com.hermespet.Prefs.longitude
import com.hermespet.Prefs.moodCheckEnabled
import com.hermespet.Prefs.petScale
import com.hermespet.Prefs.sendMoodImage

class MainActivity : Activity() {

    private lateinit var status: TextView

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

        // --- izin ---
        col.addView(section("1. Izin"))
        col.addView(button("Izin tampil di atas aplikasi (overlay)") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        col.addView(button("Izin baca notifikasi") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        col.addView(button("Izin kamera (untuk cek mood)") {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        })
        if (Build.VERSION.SDK_INT >= 33) {
            col.addView(button("Izin notifikasi aplikasi") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            })
        }

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

        col.addView(button("Simpan pengaturan otak") {
            apiKey = keyInput.text.toString().trim()
            apiModel = modelInput.text.toString().trim()
            apiBaseUrl = baseInput.text.toString().trim()
            latitude = latInput.text.toString().trim()
            longitude = lonInput.text.toString().trim()
            agentUrl = urlInput.text.toString().trim()
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
    }

    private fun refreshStatus() {
        val overlay = if (Settings.canDrawOverlays(this)) "✅" else "❌"
        val cam = if (checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) "✅" else "❌"
        val notif = if (isNotifListenerEnabled()) "✅" else "❌"
        val running = if (PetOverlayService.instance != null) "✅ aktif" else "⏸ mati"
        status.text = "Overlay $overlay   Kamera $cam   Notifikasi $notif\nPet: $running"
    }

    private fun isNotifListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        val cn = ComponentName(this, NotifListener::class.java)
        return flat.split(":").any { ComponentName.unflattenFromString(it) == cn }
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
