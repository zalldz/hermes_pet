package com.hermespet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Riwayat clipboard (50 terakhir, hanya di HP). Android 10+ melarang app latar membaca
 * clipboard, jadi sumbernya: listener (saat Moci fokus / Android ≤9) + aksesibilitas
 * (teks yang diseleksi lalu tombol "Salin/Copy" ditekan).
 */
object Clip {
    private const val MAX = 50

    private fun file(ctx: Context) = File(ctx.filesDir, "clipboard.json")

    fun history(ctx: Context): List<String> = try {
        val a = JSONArray(file(ctx).readText())
        (0 until a.length()).map { a.optString(it) }
    } catch (e: Exception) { emptyList() }

    fun add(ctx: Context, text: String?) {
        val t = text?.trim()?.take(2000)
        if (t.isNullOrEmpty() || Guard.isSensitiveText(t)) return // OTP/bank tidak disimpan
        val h = listOf(t) + history(ctx).filter { it != t }
        file(ctx).writeText(JSONArray(h.take(MAX)).toString())
    }

    /** Ambil isi clipboard sekarang (berhasil kalau Moci sedang fokus / Android ≤9). */
    fun capture(ctx: Context) = runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        add(ctx, cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString())
    }

    fun get(ctx: Context, n: Int = 5): String = history(ctx).take(n.coerceIn(1, 20))
        .mapIndexed { i, s -> "${i + 1}. ${s.take(300)}" }.joinToString("\n")
        .ifBlank { "Riwayat clipboard masih kosong" }

    fun search(ctx: Context, q: String): String = history(ctx)
        .filter { it.contains(q, ignoreCase = true) }.take(10)
        .joinToString("\n") { "- ${it.take(300)}" }.ifBlank { "Tidak ada clipboard berisi \"$q\"" }

    fun set(ctx: Context, text: String): String {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Mocipet", text))
        add(ctx, text)
        return "Tersalin ke clipboard 📋"
    }
}
