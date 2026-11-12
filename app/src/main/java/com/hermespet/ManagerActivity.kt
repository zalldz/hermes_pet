package com.hermespet

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
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

        // --- otomasi (v2) ---
        col.addView(header("⚙️ Otomasi (kalau → maka)"))
        val rules = Rules.list(this)
        if (rules.isEmpty()) col.addView(muted("(belum ada — minta pet: \"tiap jam 22 nyalakan DND\")"))
        rules.forEach { r ->
            col.addView(row(Rules.describe(r), checked = r.optBoolean("enabled", true),
                onToggle = { Rules.setEnabled(this, r.optString("name"), it) },
                onPlay = { testRule(r) }) {
                Rules.delete(this, r.optString("name")); render()
            })
        }
        col.addView(Button(this).apply { text = "➕ Tambah otomasi"; setOnClickListener { addRuleDialog() } })
        col.addView(Button(this).apply { text = "{ } Impor / ekspor JSON"; isAllCaps = false; setOnClickListener { jsonDialog() } })
        col.addView(Button(this).apply { text = "📚 Skill, MCP & script di homelab"; isAllCaps = false; setOnClickListener { catalogDialog(null) } })

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

    private fun row(text: String, checked: Boolean? = null, onToggle: ((Boolean) -> Unit)? = null,
                    onPlay: (() -> Unit)? = null, onDelete: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
            addView(TextView(context).apply {
                this.text = text; textSize = 13f
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (checked != null) addView(android.widget.Switch(context).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, on -> onToggle?.invoke(on) }
            })
            if (onPlay != null) addView(Button(context).apply {
                this.text = "▶"; isAllCaps = false
                setOnClickListener { onPlay() }
            })
            addView(Button(context).apply {
                this.text = "🗑"; isAllCaps = false
                setOnClickListener { onDelete() }
            })
        }

    private val triggers = listOf(
        "Jam (HH:MM)" to "time", "WiFi tersambung (nama)" to "wifi_connect", "WiFi putus (nama, boleh kosong)" to "wifi_disconnect",
        "Baterai di bawah (%)" to "battery_below", "Baterai penuh" to "battery_full", "Buka app (package)" to "app_open")
    private val actions = listOf(
        "Notifikasi (teks)" to "notify", "Tanya Moci (teks)" to "brain", "Skill homelab (nama; args opsional setelah |)" to "skill",
        "Tool MCP homelab (server.tool; args JSON setelah |)" to "mcp", "Script homelab (nama; args setelah |)" to "script", "Nyalakan DND" to "dnd", "Hemat baterai" to "battery_saver")

    private fun addRuleDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        fun spin(items: List<Pair<String, String>>) = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items.map { it.first })
        }
        val name = android.widget.EditText(this).apply { hint = "Nama otomasi" }
        val tSp = spin(triggers); val tVal = android.widget.EditText(this).apply { hint = "Nilai kondisi" }
        val aSp = spin(actions); val aVal = android.widget.EditText(this).apply { hint = "Nilai aksi" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0)
            val pick = Button(context).apply {
                text = "📚 Pilih dari homelab"; isAllCaps = false
                setOnClickListener { catalogDialog { type, value ->
                    aSp.setSelection(actions.indexOfFirst { it.second == type }); aVal.setText(value)
                } }
            }
            listOf<android.view.View>(name, TextView(context).apply { text = "KALAU" }, tSp, tVal,
                TextView(context).apply { text = "MAKA" }, aSp, aVal, pick).forEach { addView(it) }
        }
        android.app.AlertDialog.Builder(this).setTitle("Otomasi baru").setView(box)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan") { _, _ ->
                val tv = tVal.text.toString().trim(); val av = aVal.text.toString().trim()
                val t = JSONObject().put("type", triggers[tSp.selectedItemPosition].second)
                when (t.getString("type")) {
                    "time" -> tv.split(":").let { t.put("hour", it.getOrNull(0)?.trim()?.toIntOrNull() ?: -1).put("minute", it.getOrNull(1)?.trim()?.toIntOrNull() ?: 0) }
                    "wifi_connect", "wifi_disconnect" -> t.put("ssid", tv)
                    "battery_below" -> t.put("level", tv.toIntOrNull() ?: 20)
                    "app_open" -> t.put("package", tv)
                }
                val a = JSONObject().put("type", actions[aSp.selectedItemPosition].second)
                when (a.getString("type")) {
                    "notify", "brain" -> a.put("text", av)
                    "skill" -> av.split("|", limit = 2).let { a.put("name", it[0].trim()).put("args", it.getOrNull(1)?.trim() ?: "") }
                    "mcp" -> av.split("|", limit = 2).let { a.put("tool", it[0].trim()).put("args", it.getOrNull(1)?.trim() ?: "") }
                    "script" -> av.split("|", limit = 2).let { a.put("name", it[0].trim()).put("args", it.getOrNull(1)?.trim() ?: "") }
                    "dnd", "battery_saver" -> a.put("on", true)
                }
                val bad = (t.optString("type") == "time" && t.optInt("hour") !in 0..23) ||
                    (a.optString("type") in Homelab.TYPES && av.isBlank())
                if (bad) android.widget.Toast.makeText(this, "Nilai belum benar", android.widget.Toast.LENGTH_SHORT).show()
                else { Rules.add(this, name.text.toString().trim(), t, a); render() }
            }.show()
    }

    private fun toast(t: String) = android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show()

    /** ▶ uji coba: jalankan aksi "then" sekarang tanpa menunggu kondisinya. */
    private fun testRule(r: JSONObject) {
        val then = r.optJSONObject("then") ?: return toast("Rule tanpa aksi")
        val svc = PetOverlayService.instance ?: return toast("Nyalakan pet dulu untuk uji coba")
        svc.runRuleActions(listOf(then))
        toast("▶ \"${r.optString("name")}\" dijalankan — lihat balon pet")
    }

    /** Tempel JSON (objek atau array rule) untuk impor; isinya diawali ekspor rule saat ini. */
    private fun jsonDialog() {
        val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val edit = android.widget.EditText(this).apply {
            setText(Rules.exportJson(context)); textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
            minLines = 8; maxLines = 18
        }
        android.app.AlertDialog.Builder(this).setTitle("Otomasi sebagai JSON").setView(edit)
            .setMessage("Edit/tempel lalu Impor. Rule bernama sama ditimpa.")
            .setNeutralButton("Salin") { _, _ ->
                clip.setPrimaryClip(android.content.ClipData.newPlainText("rules", edit.text)); toast("JSON disalin")
            }
            .setNegativeButton("Batal", null)
            .setPositiveButton("Impor") { _, _ -> toast(Rules.importJson(this, edit.text.toString())); render() }
            .show()
    }

    /** Daftar skill, tool MCP & script di homelab. onPick != null → isi form; null → salin nama. */
    private fun catalogDialog(onPick: ((type: String, value: String) -> Unit)?) {
        toast("Memuat daftar homelab…")
        Homelab.catalog(this) { j, err ->
            if (j == null) return@catalog toast("Gagal memuat: $err")
            val items = ArrayList<Triple<String, String, String>>() // label, type, value
            j.optJSONArray("scripts")?.let { a -> for (i in 0 until a.length()) a.optString(i).let { items.add(Triple("📜 $it", "script", it)) } }
            j.optJSONArray("skills")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                items.add(Triple("✨ ${it.optString("name")} — ${it.optString("desc").take(70)}", "skill", it.optString("name"))) } }
            j.optJSONObject("mcp")?.let { m -> m.keys().forEach { srv ->
                val tools = m.optJSONArray(srv)
                if (tools == null) items.add(Triple("⚠️ $srv: tidak tersambung", "", ""))
                else for (i in 0 until tools.length()) tools.optJSONObject(i)?.let {
                    items.add(Triple("🔌 $srv.${it.optString("name")} — ${it.optString("desc").take(60)}", "mcp", "$srv.${it.optString("name")}")) }
            } }
            android.app.AlertDialog.Builder(this).setTitle("Di homelab (${items.size})")
                .setItems(items.map { it.first }.toTypedArray()) { _, i ->
                    val (_, type, value) = items[i]
                    if (type.isEmpty()) return@setItems
                    if (onPick != null) onPick(type, value) else {
                        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText(type, value)); toast("\"$value\" disalin")
                    }
                }.setNegativeButton("Tutup", null).show()
        }
    }
}
