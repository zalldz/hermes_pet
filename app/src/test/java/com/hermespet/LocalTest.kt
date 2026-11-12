package com.hermespet

import org.junit.Assert.*
import org.junit.Test

class LocalTest {
    private fun t(s: String) = Local.parse(s)?.firstOrNull()?.optString("type")

    @Test fun commands() {
        assertEquals("torch", t("nyalakan senter"))
        assertFalse(Local.parse("nyalakan senter")!![0].optBoolean("on").not())
        assertFalse(Local.parse("matikan senter")!![0].optBoolean("on"))
        assertEquals("open_app", t("buka kalkulator"))
        assertEquals("capture_photo", t("ambil foto"))
        assertEquals("capture_photo", t("ambil gambar"))
        assertEquals(10, Local.parse("rekam video 10 detik")!![0].optInt("seconds"))
        assertEquals("capture_audio", t("rekam suara"))
        assertEquals("capture_stop", t("stop rekam"))
    }

    @Test fun fallsThroughToBrain() {
        assertNull(t("ambil gambar layar ini lalu jelaskan"))
        assertNull(t("apa kabar hari ini"))
        assertNull(t("buka kunci"))
    }
}
