package com.hermespet

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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
        private const val MAX_TEXT = 3500
    }

    override fun onServiceConnected() { instance = this }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = e.packageName?.toString() ?: return
            // abaikan diri sendiri, status bar & keyboard (bukan "aplikasi dibuka")
            if (pkg == packageName || pkg == "com.android.systemui" ||
                pkg.contains("inputmethod") || pkg.contains("keyboard")) return
            if (pkg != currentPkg) {
                currentPkg = pkg
                PetOverlayService.instance?.onAppChanged(pkg)
            }
        }
    }

    /** Semua teks yang terlihat di layar aktif. Tombol/isian diberi tag + titik tengah (x,y). */
    fun screenText(): String {
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
        if (q.isBlank()) return "Tap yang mana? 🤔"
        val n = findNode(q) ?: return "Tidak ketemu \"$q\" di layar 🔍"
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        if (c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return "Kutap \"$q\" 👆"
        val r = Rect().also { n.getBoundsInScreen(it) }
        return tap(r.centerX(), r.centerY())
    }

    fun tap(x: Int, y: Int): String {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return if (gesture(path, 60)) "Kutap ($x,$y) 👆" else "Gagal tap 😖"
    }

    /** Ketik ke kolom yang sedang fokus (atau kolom isian pertama di layar). */
    fun type(text: String): String {
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
