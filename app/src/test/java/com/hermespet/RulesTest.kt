package com.hermespet

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logika transisi trigger otomasi ([Rules.fires]). */
class RulesTest {
    private fun st(min: Int = 600, ssid: String? = null, batt: Int = 50, chg: Boolean = false,
                   lat: Double? = null, lon: Double? = null, pkg: String? = null) =
        Rules.State(min, 2, ssid, batt, chg, lat, lon, pkg)

    @Test fun time_firesOnceWhenCrossing() {
        val t = JSONObject("""{"type":"time","hour":23,"minute":0}""")
        assertTrue(Rules.fires(t, st(1379), st(1380)))
        assertFalse(Rules.fires(t, st(1380), st(1381)))
        assertFalse(Rules.fires(t, null, st(1380)))
    }

    @Test fun time_midnight() {
        val t = JSONObject("""{"type":"time","hour":0,"minute":0}""")
        assertTrue(Rules.fires(t, st(1439), st(0)))
    }

    @Test fun battery_below_transition() {
        val t = JSONObject("""{"type":"battery_below","level":20}""")
        assertTrue(Rules.fires(t, st(batt = 20), st(batt = 19)))
        assertFalse(Rules.fires(t, st(batt = 19), st(batt = 18)))
    }

    @Test fun wifi_connect_disconnect() {
        val c = JSONObject("""{"type":"wifi_connect","ssid":"Rumah"}""")
        val d = JSONObject("""{"type":"wifi_disconnect","ssid":"rumah"}""")
        assertTrue(Rules.fires(c, st(ssid = null), st(ssid = "Rumah")))
        assertTrue(Rules.fires(d, st(ssid = "Rumah"), st(ssid = null)))
        assertFalse(Rules.fires(d, st(ssid = "Kantor"), st(ssid = null)))
    }

    @Test fun geo_enter() {
        val t = JSONObject("""{"type":"geo_enter","lat":-6.2,"lon":106.8,"radius":300}""")
        assertTrue(Rules.fires(t, st(lat = -6.25, lon = 106.8), st(lat = -6.2005, lon = 106.8)))
    }

    @Test fun parse_import() {
        val one = """{"name":"a","trigger":{"type":"battery_full"},"then":{"type":"script","name":"cek-disk"}}"""
        assertTrue(Rules.parseImport(one).size == 1)
        val arr = """[$one, {"name":"","trigger":{"type":"x"},"then":{"type":"y"}}, {"name":"b","trigger":{}}]"""
        assertTrue(Rules.parseImport(arr).map { it.optString("name") } == listOf("a"))
    }
}
