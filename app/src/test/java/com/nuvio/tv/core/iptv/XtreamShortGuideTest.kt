package com.nuvio.tv.core.iptv

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class XtreamShortGuideTest {
    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    @Test fun base64TitlesAndDescriptionsAreDecodedAndOrderedByStart() {
        val json = """{"epg_listings":[
            {"id":"2","title":"${b64("Später")}","description":"${b64("Zweite Sendung")}","lang":"de","start_timestamp":"1700003600","stop_timestamp":"1700007200"},
            {"id":"1","title":"${b64("Morning news")}","description":"","lang":"en","start_timestamp":1700000000,"stop_timestamp":1700003600}]}"""
        val programmes = XtreamShortGuide.parse(json, "42")
        assertEquals(listOf("Morning news", "Später"), programmes.map { it.titles.single().text })
        assertEquals(listOf("en", "de"), programmes.map { it.titles.single().language })
        assertEquals(emptyList<LocalizedGuideText>(), programmes[0].descriptions)
        assertEquals("Zweite Sendung", programmes[1].descriptions.single().text)
        assertEquals(1_700_000_000_000L, programmes[0].start.epochMillis)
        assertEquals("xtream:42", programmes[0].channelExternalId)
        assertTrue(programmes.all { it.canSchedulePrecisely })
    }

    @Test fun plainTextIsKeptWhenItIsNotBase64() {
        assertEquals("News", XtreamShortGuide.text("News"))
        assertEquals("Live: Football", XtreamShortGuide.text("Live: Football"))
        assertEquals("Café", XtreamShortGuide.text(b64("Café")))
        assertNull(XtreamShortGuide.text("  "))
        assertNull(XtreamShortGuide.text(7))
    }

    @Test fun invalidRowsAreSkippedAndTextAndCountAreBounded() {
        val rows = listOf(
            """{"title":"${b64("No times")}"}""",
            """{"title":"${b64("Reversed")}","start_timestamp":"200","stop_timestamp":"100"}""",
            """{"title":"","start_timestamp":"100","stop_timestamp":"200"}""",
            """{"title":"${b64("x".repeat(900))}","description":"${b64("word ".repeat(200))}","start_timestamp":"300","stop_timestamp":"400","lang":"<script>"}""",
        ) + (0 until 30).map { """{"title":"${b64("Slot $it")}","start_timestamp":"${1000 + it}","stop_timestamp":"${1001 + it}"}""" }
        val programmes = XtreamShortGuide.parse("""{"epg_listings":[${rows.joinToString(",")}]}""", "7", maxListings = 5)
        assertEquals(5, programmes.size)
        val long = programmes.first()
        assertTrue(long.titles.single().text.length <= 512)
        assertTrue(long.descriptions.single().text.length <= 400)
        assertNull(long.titles.single().language)
    }

    @Test fun emptyOrMissingListingsMeanNoProgrammes() {
        assertTrue(XtreamShortGuide.parse("""{"epg_listings":[]}""", "1").isEmpty())
        assertTrue(XtreamShortGuide.parse("""{"epg_listings":null}""", "1").isEmpty())
        assertTrue(XtreamShortGuide.parse("""{}""", "1").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { XtreamShortGuide.parse("""{"epg_listings":"x"}""", "1") }
    }
}
