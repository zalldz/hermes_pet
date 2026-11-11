package com.hermespet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit test untuk parser balasan model & streaming. Jalankan: gradle test */
class ParsersTest {

    @Test fun extractJson_basic() {
        val r = Parsers.extractJson("""{"say":"halo","emotion":"happy"}""")!!
        assertEquals("halo", r.optString("say"))
        assertEquals("happy", r.optString("emotion"))
    }

    @Test fun extractJson_withSurroundingText() {
        val r = Parsers.extractJson("""bla {"say":"oke","action":{"type":"torch","on":true}} end""")!!
        assertEquals("torch", r.optJSONObject("action")?.optString("type"))
    }

    @Test fun extractJson_withActionsArray() {
        val r = Parsers.extractJson("""{"say":"siap","actions":[{"type":"dnd"},{"type":"mute"}]}""")!!
        assertEquals(2, r.optJSONArray("actions")?.length())
    }

    @Test fun extractJson_invalidReturnsNull() {
        assertNull(Parsers.extractJson("tidak ada json di sini"))
    }

    @Test fun extractJson_noSayReturnsNull() {
        assertNull(Parsers.extractJson("""{"emotion":"happy"}"""))
    }

    @Test fun partialSay_stillStreaming() {
        assertEquals("hal", Parsers.extractPartialSay("""{"say":"hal"""))
    }

    @Test fun partialSay_complete() {
        assertEquals("halo", Parsers.extractPartialSay("""{"say":"halo","emotion":"happy"}"""))
    }

    @Test fun partialSay_escapedQuote() {
        assertEquals("a\"b", Parsers.extractPartialSay("""{"say":"a\"b"}"""))
    }

    @Test fun partialSay_noneReturnsNull() {
        assertNull(Parsers.extractPartialSay("{}"))
    }

    @Test fun normalizeAction_aliases() {
        val r = Parsers.extractJson("""{"say":"ok","actions":[{"type":"back"},{"tool":"tap_text","text":"Budi"}]}""")!!
        val a = r.optJSONArray("actions")!!
        assertEquals("press", a.getJSONObject(0).optString("type"))
        assertEquals("back", a.getJSONObject(0).optString("key"))
        assertEquals("find_and_tap", a.getJSONObject(1).optString("type"))
        assertEquals("Budi", a.getJSONObject(1).optString("label"))
    }

    @Test fun riskyLabel() {
        org.junit.Assert.assertTrue(Parsers.isRiskyLabel("Kirim"))
        org.junit.Assert.assertTrue(Parsers.isRiskyLabel("Send message"))
        org.junit.Assert.assertFalse(Parsers.isRiskyLabel("Budi Santoso"))
    }
}
