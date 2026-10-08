package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.GuideChannel
import com.nuvio.tv.core.iptv.GuideProgramme
import com.nuvio.tv.core.iptv.GuideTimestamp
import com.nuvio.tv.core.iptv.LocalizedGuideText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvGuideJsonTest {
    private val programme = GuideProgramme("one", GuideTimestamp(1_000, 14, "raw"), null, listOf(LocalizedGuideText("Match", "en")), emptyList())

    @Test fun categoriesRoundTripAndOlderRowsReadWithout() {
        val sport = programme.copy(start = GuideTimestamp(1_000, 14), categories = listOf("Sports", "Football"))
        assertEquals(sport, IptvGuideJson.programme(IptvGuideJson.programme(sport), "one"))
        val plain = IptvGuideJson.programme(programme)
        assertFalse("\"c\"" in plain)
        assertEquals(programme.copy(start = GuideTimestamp(1_000, 14)), IptvGuideJson.programme(plain, "one"))
    }

    @Test fun payloadLeavesOutRawTimesAndTheChannelId() {
        val stored = IptvGuideJson.programme(programme.copy(channelExternalId = "channel-id-text", stop = GuideTimestamp(3_000, 12, "2026"),
            descriptions = listOf(LocalizedGuideText("About", null))))
        assertFalse("raw" in stored); assertFalse("channel-id-text" in stored)
        val read = IptvGuideJson.programme(stored, "channel-id-text")
        assertEquals("channel-id-text", read.channelExternalId)
        assertEquals(GuideTimestamp(3_000, 12), read.stop)
        assertEquals(listOf(LocalizedGuideText("About", null)), read.descriptions)
        assertTrue(stored.length < 80)
    }

    @Test fun legacyRowsStillRead() {
        val legacy = "{\"channel\":\"one\",\"start\":{\"ms\":1000,\"precision\":14,\"raw\":\"r\"},\"stop\":{\"ms\":2000,\"precision\":14,\"raw\":\"r\"}," +
            "\"titles\":[{\"text\":\"News\",\"language\":\"en\"}],\"descriptions\":[],\"categories\":[\"News\"]}"
        val read = IptvGuideJson.programme(legacy, "ignored")
        assertEquals("one", read.channelExternalId); assertEquals(2_000L, read.stop!!.epochMillis); assertEquals(listOf("News"), read.categories)
        assertEquals(GuideChannel("one", listOf(LocalizedGuideText("One", "en"))), IptvGuideJson.channel("{\"id\":\"one\",\"names\":[{\"text\":\"One\",\"language\":\"en\"}]}", "one"))
    }

    @Test fun channelsStoreNamesOnly() {
        val channel = GuideChannel("bbc.one@HD", listOf(LocalizedGuideText("BBC One", "en"), LocalizedGuideText("BBC 1", null)))
        val stored = IptvGuideJson.channel(channel)
        assertFalse("bbc.one@HD" in stored)
        assertEquals(channel, IptvGuideJson.channel(stored, "bbc.one@HD"))
    }
}
