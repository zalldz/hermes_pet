package com.hermespet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardTest {

    @Test fun moneyApps_blocked() {
        for (p in listOf("com.bca", "id.co.bri.brimo", "id.bmri.livin", "src.com.bni", "com.jago.digitalBanking",
            "com.gojek.gopay", "ovo.id", "id.dana", "com.shopeepay.id",
            "com.example.mybank", "id.co.somebanking.mobile", "com.samsung.android.spay"))
            assertTrue(p, Guard.isMoneyApp(p))
    }

    @Test fun normalApps_notMoney() {
        for (p in listOf("com.whatsapp", "org.telegram.messenger", "com.android.chrome",
            "com.google.android.youtube", "com.spotify.music", "com.android.settings", "com.fabric.app"))
            assertFalse(p, Guard.isMoneyApp(p))
    }

    @Test fun screenControl_onlyAllowlist() {
        assertNull(Guard.screenBlock("com.whatsapp"))
        assertNull(Guard.screenBlock("org.telegram.messenger"))
        assertNull(Guard.screenBlock("com.android.chrome"))
        assertNotNull(Guard.screenBlock("com.instagram.android"))
        assertNotNull(Guard.screenBlock(null))
        assertTrue(Guard.screenBlock("com.bca") == Guard.MONEY_MSG)
    }

    @Test fun sensitiveText() {
        assertTrue(Guard.isSensitiveText("App: com.android.chrome\nklikbca.com/login"))
        assertTrue(Guard.isSensitiveText("Kode OTP Anda 123456, jangan berikan kode ini"))
        assertTrue(Guard.isSensitiveText("Your verification code is 9911"))
        assertFalse(Guard.isSensitiveText("Budi: otw bro, 5 menit lagi"))
        assertFalse(Guard.isSensitiveText("hotspot nyala"))
    }
}
