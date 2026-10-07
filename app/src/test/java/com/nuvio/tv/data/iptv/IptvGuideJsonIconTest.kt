package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.GuideProgramme
import com.nuvio.tv.core.iptv.GuideTimestamp
import com.nuvio.tv.core.iptv.LocalizedGuideText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class IptvGuideJsonIconTest {
    private val programme = GuideProgramme("one", GuideTimestamp(1_000, 14, "raw"), GuideTimestamp(2_000, 14, "raw"), listOf(LocalizedGuideText("Match", "en")), emptyList())

    @Test fun iconRoundTripsWithCategories() {
        val art = programme.copy(categories = listOf("Sports"), icon = "https://img.example/match.jpg")
        assertEquals(art, IptvGuideJson.programme(IptvGuideJson.programme(art)))
    }

    @Test fun rowsWithoutIconStayCompactAndReadBack() {
        val plain = IptvGuideJson.programme(programme)
        assertFalse("icon" in plain)
        assertNull(IptvGuideJson.programme(plain).icon)
    }

    @Test fun unsafeStoredIconsAreIgnored() {
        assertFalse("icon" in IptvGuideJson.programme(programme.copy(icon = "javascript:alert(1)")))
        val stored = IptvGuideJson.programme(programme).dropLast(1) + ",\"icon\":\"file:///sdcard/x.png\"}"
        assertNull(IptvGuideJson.programme(stored).icon)
    }
}
