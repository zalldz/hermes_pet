package com.hermespet

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Panel kelola: lihat & hapus ingatan jangka panjang + skill buatan pet. */
class ManagerActivity : Activity() {

    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(col) })
        render()
    }

    private fun render() {
        col.removeAllViews()
        col.addView(TextView(this).apply { text = "🧠 Memori & ✨ Skill"; textSize = 22f })

        // --- skill ---
        col.addView(header("✨ Skill yang dikuasai pet"))
        val skills = Skills.list(this)
        if (skills.isEmpty()) col.addView(muted("(belum ada — minta pet membuat skill)"))
        skills.forEach { (name, desc) ->
            col.addView(row("\"$name\" — $desc") {
                Skills.delete(this, name); render()
            })
        }

        // --- memori ---
        col.addView(header("🧠 Ingatan jangka panjang"))
        val fmt = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
        val mems = Memory.all(this).sortedByDescending { it.importance }
        if (mems.isEmpty()) col.addView(muted("(belum ada ingatan)"))
        mems.forEach { m ->
            val star = "★".repeat((m.importance * 5).toInt().coerceIn(1, 5))
            col.addView(row("$star  ${m.text}\n${fmt.format(Date(m.ts))}") {
                Memory.delete(this, m.ts); render()
            })
        }
    }

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 18f
        val p = (14 * resources.displayMetrics.density).toInt()
        setPadding(0, p, 0, p / 3)
    }

    private fun muted(t: String) = TextView(this).apply {
        text = t; setTextColor(0xFF888888.toInt()); setPadding(0, 8, 0, 8)
    }

    private fun row(text: String, onDelete: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
            addView(TextView(context).apply {
                this.text = text; textSize = 13f
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(context).apply {
                this.text = "🗑"; isAllCaps = false
                setOnClickListener { onDelete() }
            })
        }
}
