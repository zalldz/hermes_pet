package com.hermespet

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Layar riwayat chat & log kegiatan pet (terbaru di atas). */
class LogActivity : Activity() {

    private lateinit var listCol: LinearLayout
    private var chatOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(TextView(this).apply { text = "📜 Riwayat & Log"; textSize = 22f })

        @Suppress("UseSwitchCompatOrMaterialCode")
        val filter = Switch(this).apply {
            text = "Tampilkan chat saja"
            setOnCheckedChangeListener { _, on -> chatOnly = on; render() }
        }
        col.addView(filter)

        listCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listCol)
        setContentView(ScrollView(this).apply { addView(col) })
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        listCol.removeAllViews()
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        val icon = mapOf(
            "user" to "🧑", "pet" to "🐾", "aksi" to "⚙️",
            "notif" to "🔔", "mood" to "📷"
        )
        PetLog.read(this).asReversed()
            .filter { !chatOnly || it.kind == "user" || it.kind == "pet" }
            .take(300)
            .forEach { e ->
                listCol.addView(TextView(this).apply {
                    text = "${fmt.format(Date(e.ts))}  ${icon[e.kind] ?: "•"}  ${e.text}"
                    textSize = 13f
                    setPadding(0, 10, 0, 10)
                })
            }
        if (listCol.childCount == 0) {
            listCol.addView(TextView(this).apply {
                text = "(masih kosong — ajak pet-mu ngobrol dulu!)"
                setPadding(0, 24, 0, 0)
            })
        }
    }
}
