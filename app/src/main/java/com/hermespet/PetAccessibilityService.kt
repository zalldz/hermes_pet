package com.hermespet

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * "Mata & tangan" pet di aplikasi lain lewat Aksesibilitas: baca teks layar,
 * cari elemen, tap, ketik, scroll, tombol global. Semua dipanggil dari aksi
 * yang diminta pemilik ([Actions]) — service ini tidak berinisiatif sendiri.
 */
class PetAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: PetAccessibilityService? = null
        /** Paket aplikasi yang sedang tampil (untuk otomasi "app dibuka" & batas waktu). */
        @Volatile var currentPkg: String = ""
        /** Launcher sedang di depan (ikon jadi pijakan slime). */
        @Volatile var onHome = false
        private const val MAX_TEXT = 3500
    }

    override fun onServiceConnected() { instance = this }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    private val homePkgs: Set<String> by lazy {
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        ).map { it.activityInfo.packageName }.toSet()
    }

    /**
     * Posisi ikon launcher (HANYA kotak, tanpa teks) untuk jadi pijakan slime.
     * Sengaja di luar guard(): tidak ada isi layar yang dibaca. Launcher bukan app keuangan.
     */
    fun iconRects(): List<Rect> {
        val root = rootInActiveWindow ?: return emptyList()
        if (root.packageName?.toString() !in homePkgs) return emptyList()
        val dm = resources.displayMetrics
        val minPx = 24 * dm.density; val maxW = dm.widthPixels / 3
        val out = ArrayList<Rect>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 14 || out.size > 80) return
            if (n.isClickable && n.isVisibleToUser) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (r.width() >= minPx && r.height() >= minPx && r.width() <= maxW && r.height() <= maxW) out.add(r)
                return // anak dari tombol tidak perlu
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return out
    }

    /** Teks yang terakhir diseleksi pengguna (untuk riwayat clipboard saat "Salin" ditekan). */
    private var selected = ""

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) { // geser halaman → baca ulang ikon sebentar lagi
            if (onHome) PetOverlayService.instance?.onHomeScrolled()
            return
        }
        // app keuangan: jangan simpan teks apa pun; cukup catat paketnya (batas waktu, focus)
        val money = Guard.isMoneyApp(e.packageName?.toString())
        if (money) selected = ""
        if (!money && e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            val t = e.text?.joinToString("")?.toString().orEmpty()
            if (e.fromIndex in 0 until e.toIndex && e.toIndex <= t.length) selected = t.substring(e.fromIndex, e.toIndex)
            return
        }
        if (!money && e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val label = (e.text?.joinToString(" ") ?: "") + " " + (e.contentDescription ?: "")
            if (selected.isNotEmpty() && Regex("(?i)\\b(copy|salin|cut|potong)\\b").containsMatchIn(label)) {
                Clip.add(this, selected)
                selected = ""
            }
            return
        }
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = e.packageName?.toString() ?: return
            // abaikan diri sendiri, status bar & keyboard (bukan "aplikasi dibuka")
            if (pkg == packageName || pkg == "com.android.systemui" ||
                pkg.contains("inputmethod") || pkg.contains("keyboard")) return
            onHome = pkg in homePkgs
            if (pkg != currentPkg) {
                currentPkg = pkg
                PetOverlayService.instance?.onAppChanged(pkg)
            }
        }
    }

    /** Semua teks yang terlihat di layar aktif. Tombol/isian diberi tag + titik tengah (x,y). */
    fun screenText(): String = guard() ?: dump()

    /**
     * Pagar SEMUA aksi layar: app aktif harus di daftar putih & bukan app keuangan, dan isinya
     * bukan internet banking/OTP (browser). Null = boleh.
     */
    private fun guard(): String? {
        val root = rootInActiveWindow ?: return "(layar tidak terbaca)"
        Guard.screenBlock(root.packageName?.toString())?.let { return it }
        return if (Guard.isSensitiveText(dump())) Guard.MONEY_MSG else null
    }

    private fun dump(): String {
        val root = rootInActiveWindow ?: return "(layar tidak terbaca)"
        val sb = StringBuilder("App: ${root.packageName}\n")
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || sb.length > MAX_TEXT || depth > 40) return
            if (n.isVisibleToUser) {
                val label = label(n)
                if (label.isNotEmpty() || n.isEditable) {
                    val tag = when {
                        n.isEditable -> "[isian] "
                        n.isClickable || n.parent?.isClickable == true -> "[tombol] "
                        else -> ""
                    }
                    sb.append(tag).append(label.take(200))
                    if (tag.isNotEmpty()) {
                        val r = Rect().also { n.getBoundsInScreen(it) }
                        sb.append(" @").append(r.centerX()).append(',').append(r.centerY())
                    }
                    sb.append('\n')
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString().take(MAX_TEXT)
    }

    /** Teks layar tanpa tag/koordinat (untuk disalin ke clipboard). */
    fun plainText(): String = guard() ?: dump().lines().drop(1)
        .map { it.removePrefix("[isian] ").removePrefix("[tombol] ").replace(Regex(" @\\d+,\\d+$"), "") }
        .filter { it.isNotBlank() }.joinToString("\n")

    private fun label(n: AccessibilityNodeInfo): String =
        n.text?.toString()?.trim().orEmpty().ifEmpty { n.contentDescription?.toString()?.trim().orEmpty() }

    /** Elemen berlabel `q` (sama persis diutamakan, lalu yang mengandung). */
    private fun findNode(q: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val hits = root.findAccessibilityNodeInfosByText(q).filter { it.isVisibleToUser }
        return hits.firstOrNull { label(it).equals(q.trim(), ignoreCase = true) } ?: hits.firstOrNull()
    }

    /** Label elemen terdalam di titik (x,y) — untuk cek apakah tap itu tombol berisiko. */
    fun labelAt(x: Int, y: Int): String {
        if (guard() != null) return ""
        var best = ""
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (!r.contains(x, y)) return
            label(n).takeIf { it.isNotEmpty() }?.let { best = it }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootInActiveWindow)
        return best
    }

    fun findAndTap(q: String): String {
        guard()?.let { return it }
        if (q.isBlank()) return "Tap yang mana? 🤔"
        val n = findNode(q) ?: return "Tidak ketemu \"$q\" di layar 🔍"
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        if (c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return "Kutap \"$q\" 👆"
        val r = Rect().also { n.getBoundsInScreen(it) }
        return tap(r.centerX(), r.centerY())
    }

    fun tap(x: Int, y: Int): String {
        guard()?.let { return it }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return if (gesture(path, 60)) "Kutap ($x,$y) 👆" else "Gagal tap 😖"
    }

    /** Ketik ke kolom yang sedang fokus (atau kolom isian pertama di layar). */
    fun type(text: String): String {
        guard()?.let { return it }
        val root = rootInActiveWindow ?: return "Layar tidak terbaca 😖"
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: firstEditable(root) ?: return "Tidak ada kolom isian di layar 🤔"
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            "Kuketik: $text ⌨️" else "Gagal mengetik 😖"
    }

    private fun firstEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        n ?: return null
        if (n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) firstEditable(n.getChild(i))?.let { return it }
        return null
    }

    private fun firstScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        n ?: return null
        if (n.isScrollable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) firstScrollable(n.getChild(i))?.let { return it }
        return null
    }

    fun scroll(down: Boolean): String {
        guard()?.let { return it }
        val act = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (firstScrollable(rootInActiveWindow)?.performAction(act) == true)
            return if (down) "Scroll ke bawah ⬇️" else "Scroll ke atas ⬆️"
        // tidak ada elemen scrollable → usap layar
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val lo = dm.heightPixels * 0.72f; val hi = dm.heightPixels * 0.28f
        val path = Path().apply {
            if (down) { moveTo(x, lo); lineTo(x, hi) } else { moveTo(x, hi); lineTo(x, lo) }
        }
        return if (gesture(path, 300)) (if (down) "Usap ke bawah ⬇️" else "Usap ke atas ⬆️")
            else "Gagal scroll 😖"
    }

    fun press(key: String): String {
        // tombol sistem (back/home) boleh di app mana pun untuk navigasi, kecuali app keuangan
        if (Guard.isMoneyApp(rootInActiveWindow?.packageName?.toString())) return Guard.MONEY_MSG
        val a = when (key.lowercase().trim()) {
            "back", "kembali" -> GLOBAL_ACTION_BACK
            "home", "beranda" -> GLOBAL_ACTION_HOME
            "recents", "recent", "terbaru" -> GLOBAL_ACTION_RECENTS
            "notifications", "notif", "notifikasi" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return "Tombol \"$key\" tidak kukenal 🤔"
        }
        return if (performGlobalAction(a)) "Tekan $key ✓" else "Gagal tekan $key 😖"
    }

    private fun gesture(path: Path, ms: Long): Boolean = dispatchGesture(
        GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build(),
        null, null)
}
