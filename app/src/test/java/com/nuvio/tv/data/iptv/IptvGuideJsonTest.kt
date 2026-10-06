package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.GuideProgramme
import com.nuvio.tv.core.iptv.GuideTimestamp
import com.nuvio.tv.core.iptv.LocalizedGuideText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class IptvGuideJsonTest {
    private val programme = GuideProgramme("one", GuideTimestamp(1_000, 14, "raw"), null, listOf(LocalizedGuideText("Match", "en")), emptyList())

    @Test fun categoriesRoundTripAndOlderRowsReadWithout() {
        val sport = programme.copy(categories = listOf("Sports", "Football"))
        assertEquals(sport, IptvGuideJson.programme(IptvGuideJson.programme(sport)))
        val plain = IptvGuideJson.programme(programme)
        assertFalse("categories" in plain)
        assertEquals(programme, IptvGuideJson.programme(plain))
    }
}
