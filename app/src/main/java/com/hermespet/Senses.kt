package com.hermespet

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.CalendarContract
import com.hermespet.Prefs.latitude
import com.hermespet.Prefs.longitude
import org.json.JSONObject
import java.util.Locale

/**
 * Indra pet: lokasi terakhir (membuat cuaca otomatis mengikuti posisi pemilik)
 * dan acara kalender berikutnya. Dibaca lokal di HP, disertakan dalam event "tick".
 */
object Senses {

    fun snapshot(ctx: Context): JSONObject {
        val o = JSONObject()
        readLocation(ctx, o)
        readNextEvent(ctx, o)
        return o
    }

    private fun has(ctx: Context, p: String) =
        ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Lokasi terakhir yang diketahui — sekalian memperbarui lat/lon untuk cuaca. */
    private fun readLocation(ctx: Context, o: JSONObject) {
        if (!has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) &&
            !has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        ) return
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.allProviders
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time } ?: return
            ctx.latitude = String.format(Locale.US, "%.3f", loc.latitude)
            ctx.longitude = String.format(Locale.US, "%.3f", loc.longitude)
            o.put("location_known", true)
        } catch (_: Exception) {
        }
    }

    /** Acara kalender terdekat dalam 24 jam ke depan. */
    private fun readNextEvent(ctx: Context, o: JSONObject) {
        if (!has(ctx, Manifest.permission.READ_CALENDAR)) return
        try {
            val now = System.currentTimeMillis()
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, now)
            ContentUris.appendId(builder, now + 24 * 3_600_000L)
            ctx.contentResolver.query(
                builder.build(),
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN),
                null, null,
                CalendarContract.Instances.BEGIN + " ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val begin = c.getLong(1)
                    if (begin > now) {
                        o.put("next_event", c.getString(0) ?: "(tanpa judul)")
                        o.put("next_event_min", ((begin - now) / 60_000L).toInt())
                        break
                    }
                }
            }
        } catch (_: Exception) {
        }
    }
}
