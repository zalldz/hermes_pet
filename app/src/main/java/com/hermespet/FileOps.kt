package com.hermespet

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.Formatter

/** Cari file, laporan penyimpanan, dan bantu bersihkan cache app. */
object FileOps {

    private fun size(ctx: Context, b: Long) = Formatter.formatShortFileSize(ctx, b)

    /** Cari file berdasarkan nama di MediaStore (foto/video/audio/unduhan yang terlihat oleh app). */
    fun search(ctx: Context, name: String): String {
        if (name.isBlank()) return "Cari file apa? 🔍"
        val sb = StringBuilder()
        ctx.contentResolver.query(MediaStore.Files.getContentUri("external"),
            // DATA (path lengkap) dipakai karena RELATIVE_PATH baru ada di API 29
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
            var n = 0
            while (c.moveToNext() && n++ < 15)
                sb.append("- ${c.getString(1) ?: c.getString(0)} (${size(ctx, c.getLong(2))})\n")
        }
        return sb.toString().ifBlank {
            "Tidak ketemu \"$name\" (aku hanya bisa melihat foto/video/audio & file yang diizinkan)"
        }
    }

    private fun mediaBytes(ctx: Context, uri: Uri): Long = runCatching {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
            ?.use { c -> var s = 0L; while (c.moveToNext()) s += c.getLong(0); s } ?: 0L
    }.getOrDefault(0L)

    /** Total/sisa, ukuran per kategori media, dan 5 app dengan cache terbesar. */
    fun report(ctx: Context): String {
        val st = StatFs(Environment.getDataDirectory().path)
        val sb = StringBuilder("Penyimpanan: sisa ${size(ctx, st.availableBytes)} dari ${size(ctx, st.totalBytes)}\n")
        sb.append("- Foto: ${size(ctx, mediaBytes(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))}\n")
        sb.append("- Video: ${size(ctx, mediaBytes(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI))}\n")
        sb.append("- Audio: ${size(ctx, mediaBytes(ctx, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI))}\n")
        // ukuran cache app lain butuh akses "Data penggunaan" (sudah dipakai screen time)
        if (Usage.granted(ctx)) {
            val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
            val uuid = sm.getUuidForPath(Environment.getDataDirectory())
            val user = Process.myUserHandle()
            val caches = ctx.packageManager.getInstalledApplications(0).mapNotNull { ai ->
                runCatching { ai.packageName to ssm.queryStatsForPackage(uuid, ai.packageName, user).cacheBytes }
                    .getOrNull()
            }.sortedByDescending { it.second }.take(5)
            sb.append("Cache terbesar:\n")
            caches.forEach { (pkg, b) -> sb.append("- ${Usage.label(ctx, pkg)}: ${size(ctx, b)}\n") }
        } else sb.append("(beri akses Data penggunaan untuk melihat cache per app)\n")
        return sb.toString()
    }

    /** Android tidak mengizinkan app menghapus cache app lain → bukakan halaman info app-nya. */
    fun openAppStorage(ctx: Context, app: String): String {
        val pkg = Actions.findPackage(ctx, app) ?: return "Aku nggak nemu aplikasi \"$app\" 😞"
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Kubukakan info ${Usage.label(ctx, pkg)} — ketuk Penyimpanan → Hapus cache " +
            "(atau suruh aku lanjut kalau Aksesibilitas aktif) 🧹"
    }
}
