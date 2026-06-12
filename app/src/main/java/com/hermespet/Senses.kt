package com.hermespet

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build
import android.provider.CalendarContract
import com.hermespet.Prefs.latitude
import com.hermespet.Prefs.longitude
import org.json.JSONObject
import java.util.Locale

/**
 * Indra pet: sensor cahaya, penghitung langkah, lokasi terakhir (membuat
 * cuaca otomatis mengikuti posisi pemilik), dan acara kalender berikutnya.
 * Semua dibaca lokal di HP, lalu disertakan dalam event "tick" untuk otak.
 */
object Senses : SensorEventListener {

    private var lux = -1f
    private var stepsAtStart = -1f
    private var stepsNow = -1f

    fun start(ctx: Context) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        val stepsAllowed = Build.VERSION.SDK_INT < 29 || has(ctx, Manifest.permission.ACTIVITY_RECOGNITION)
        if (stepsAllowed) {
            sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    fun stop(ctx: Context) {
        (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_LIGHT -> lux = e.values[0]
            Sensor.TYPE_STEP_COUNTER -> {
                stepsNow = e.values[0]
                if (stepsAtStart < 0) stepsAtStart = stepsNow
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun snapshot(ctx: Context): JSONObject {
        val o = JSONObject()
        if (lux >= 0) o.put("light_lux", lux.toInt())
        if (stepsNow >= 0 && stepsAtStart >= 0) {
            o.put("steps_since_pet_on", (stepsNow - stepsAtStart).toInt())
        }
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
