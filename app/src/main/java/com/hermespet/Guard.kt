package com.hermespet

/**
 * Pagar keamanan (MURNI, tanpa Android — di-unit-test).
 * 1. Aplikasi uang (bank, dompet digital, investasi, autentikator) TIDAK BOLEH disentuh:
 *    tidak dibaca, ditap, diketik, di-screenshot, notifnya tidak disimpan. Tidak bisa dimatikan.
 * 2. Kendali layar (baca/tap/ketik) hanya di aplikasi daftar putih: WhatsApp, Telegram, browser.
 */
object Guard {

    private val MONEY_PKGS = setOf(
        // bank
        "com.bca", "com.bca.mybca.omni.android", "com.bcadigital.blu", "id.co.bri.brimo",
        "id.bmri.livin", "id.bmri.livinmerchant", "com.bankmandiri.mandirionline", "src.com.bni",
        "id.co.bni.mobile", "com.jago.digitalBanking", "com.btpn.dc", "id.co.seabank.app",
        "id.co.cimbniaga.mobile.android", "com.cimbniaga.octo", "net.myinfosys.PermataMobileX",
        "co.id.bankbsi.superapp", "com.bsm.activity2", "com.ocbcnisp.onemobileapp",
        "com.alloapp.yump", "com.bnc.finance", "com.danamon.dbank", "id.co.btn.mobilebanking",
        "com.superbank.app", "id.co.linebank",
        // dompet digital & paylater
        "com.gojek.gopay", "com.gojek.app", "ovo.id", "id.dana", "com.shopeepay.id",
        "com.telkom.mwallet", "id.flip", "com.kredivo.userapp", "com.akulaku.loan",
        "com.google.android.apps.walletnfcrel", "com.paypal.android.p2pmobile",
        // investasi & kripto
        "com.ajaib.android", "com.bibit.bibitapp", "com.stockbit.android", "id.co.indodax",
        "com.pintu.app", "com.tokocrypto.app", "com.binance.dev", "com.pluang.app",
        // autentikator / OTP
        "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy",
    )

    // potongan nama paket yang hampir pasti aplikasi uang (cadangan untuk bank yang tak terdaftar)
    private val MONEY_WORD = Regex("(?i)(bank|banking|wallet|pay|finance|fintech|crypto|invest|sekuritas|securities|authenticator|otp)")

    /** WhatsApp, Telegram, browser. */
    val ALLOW = setOf(
        "com.whatsapp", "com.whatsapp.w4b",
        "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
        "com.android.chrome", "com.chrome.beta", "org.mozilla.firefox", "org.mozilla.firefox_beta",
        "org.mozilla.focus", "com.brave.browser", "com.microsoft.emmx", "com.opera.browser",
        "com.opera.mini.native", "com.sec.android.app.sbrowser", "com.duckduckgo.mobile.android",
        "com.vivaldi.browser", "com.kiwibrowser.browser", "com.mi.globalbrowser",
        "com.heytap.browser", "com.android.browser",
    )

    fun isMoneyApp(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return pkg in MONEY_PKGS || pkg.split('.', '_').any { MONEY_WORD.containsMatchIn(it) }
    }

    fun canControl(pkg: String?): Boolean = !isMoneyApp(pkg) && pkg in ALLOW

    // internet banking di browser & kode OTP di pesan/notif
    private val SENSITIVE = Regex(
        "(?i)(klikbca|mybca|ibank\\.|internet ?banking|ib\\.bri|bri\\.co\\.id|bankmandiri|livin'? ?by ?mandiri|" +
            "bni\\.co\\.id|jenius\\.com|\\botp\\b|kode (verifikasi|otp|rahasia|keamanan)|verification code|" +
            "one[- ]time pass|jangan (berikan|bagikan) kode|m-?pin\\b)")

    fun isSensitiveText(text: String?): Boolean = !text.isNullOrEmpty() && SENSITIVE.containsMatchIn(text)

    const val MONEY_MSG = "Aplikasi keuangan tidak boleh kusentuh 🔒"

    /** Alasan menolak kendali layar di app `pkg`, atau null kalau boleh. */
    fun screenBlock(pkg: String?): String? = when {
        isMoneyApp(pkg) -> MONEY_MSG
        pkg !in ALLOW -> "Aku cuma boleh mengendalikan layar di WhatsApp, Telegram & browser 🔒"
        else -> null
    }
}
