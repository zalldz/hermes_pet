package com.hermespet

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/** Screen time per aplikasi (UsageStatsManager) + batas harian per aplikasi. */
object Usage {

    fun granted(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun settingsIntent() = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    private fun startOfDay(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun usm(ctx: Context) = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    /** Menit pemakaian hari ini per paket. */
    fun todayMinutes(ctx: Context): Map<String, Int> =
        usm(ctx).queryAndAggregateUsageStats(startOfDay(), System.currentTimeMillis())
            .mapValues { (it.value.totalTimeInForeground / 60_000).toInt() }
            .filterValues { it > 0 }

    fun label(ctx: Context, pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun report(ctx: Context): String {
        if (!granted(ctx)) {
            runCatching { ctx.startActivity(settingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return "Beri aku akses \"Data penggunaan\" dulu (kubukakan halamannya) 🙏"
        }
        val m = todayMinutes(ctx).entries.sortedByDescending { it.value }
        if (m.isEmpty()) return "Belum ada pemakaian tercatat hari ini"
        val total = m.sumOf { it.value }
        val limits = limits(ctx)
        return "Screen time hari ini: ${total / 60} jam ${total % 60} menit\n" +
            m.take(10).joinToString("\n") { (pkg, min) ->
                "- ${label(ctx, pkg)}: $min menit" + (limits.optInt(pkg, 0).takeIf { it > 0 }
                    ?.let { " (batas $it)" } ?: "")
            }
    }

    // ---- batas harian ----

    private fun limitFile(ctx: Context) = File(ctx.filesDir, "app_limits.json")

    fun limits(ctx: Context): JSONObject = try {
        JSONObject(limitFile(ctx).readText())
    } catch (e: Exception) { JSONObject() }

    fun setLimit(ctx: Context, appName: String, minutes: Int): String {
        val pkg = Actions.findPackage(ctx, appName) ?: return "Aku nggak nemu aplikasi \"$appName\" 😞"
        val l = limits(ctx)
        if (minutes <= 0) l.remove(pkg) else l.put(pkg, minutes)
        limitFile(ctx).writeText(l.toString())
        if (!granted(ctx)) runCatching {
            ctx.startActivity(settingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return if (minutes <= 0) "Batas ${label(ctx, pkg)} kuhapus ✓"
            else "Oke, ${label(ctx, pkg)} kubatasi $minutes menit/hari ⏳"
    }

    // override "kasih waktu lagi": pkg → berlaku sampai (ms)
    private val snoozed = HashMap<String, Long>()

    fun snooze(pkg: String, minutes: Int) {
        snoozed[pkg] = System.currentTimeMillis() + minutes * 60_000L
    }

    /** Menit terpakai kalau `pkg` sudah melewati batasnya (dan tidak di-snooze), else null. */
    fun overLimit(ctx: Context, pkg: String): Int? {
        val lim = limits(ctx).optInt(pkg, 0)
        if (lim <= 0 || !granted(ctx)) return null
        if ((snoozed[pkg] ?: 0L) > System.currentTimeMillis()) return null
        val used = todayMinutes(ctx)[pkg] ?: 0
        return used.takeIf { it >= lim }
    }

    /** Aplikasi di depan: dari aksesibilitas, atau event UsageStats 3 jam terakhir. */
    fun foreground(ctx: Context): String? {
        PetAccessibilityService.currentPkg.takeIf { it.isNotEmpty() }?.let { return it }
        if (!granted(ctx)) return null
        val now = System.currentTimeMillis()
        val ev = usm(ctx).queryEvents(now - 3 * 3_600_000L, now)
        val e = UsageEvents.Event()
        var last: String? = null
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            @Suppress("DEPRECATION")
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.packageName
        }
        return last
    }
}
