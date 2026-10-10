package com.nuvio.tv.core.iptv

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LiveWidgetsTest {
    private fun widths(width: Int, height: Int, layout: LiveWidgetLayout) = LiveWidgets.columns(width, height, layout).map { it.width }
    private fun slots(width: Int, height: Int, layout: LiveWidgetLayout) = LiveWidgets.columns(width, height, layout).map { it.slots }

    @Test fun everyLayoutFitsTheGapOnA1080pScreen() {
        val width = 792
        for (layout in LiveWidgetLayout.entries) assertTrue(layout.name, LiveWidgets.fits(width, 240, layout))
        assertEquals(listOf(220), widths(width, 240, LiveWidgetLayout.TALL))
        assertEquals(listOf(240), widths(width, 240, LiveWidgetLayout.SQUARE))
        assertEquals(listOf(360), widths(width, 240, LiveWidgetLayout.ONE))
        assertEquals(listOf(360), widths(width, 240, LiveWidgetLayout.STACKED))
        assertEquals(listOf(190, 190), widths(width, 240, LiveWidgetLayout.TWO))
        assertEquals(listOf(190, 190), widths(width, 240, LiveWidgetLayout.THREE))
        assertEquals(listOf(listOf(0), listOf(1, 2)), slots(width, 240, LiveWidgetLayout.THREE))
        assertEquals(listOf(240, 114), widths(width, 240, LiveWidgetLayout.SQUARES))
        assertEquals(listOf(114, 114), widths(width, 240, LiveWidgetLayout.FOUR))
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), slots(width, 240, LiveWidgetLayout.FOUR))
        assertEquals(114, LiveWidgets.columns(width, 240, LiveWidgetLayout.STACKED).single().tileHeight)
    }

    @Test fun roomyScreensUsePreferredSizes() {
        assertEquals(listOf(220, 220), widths(1400, 240, LiveWidgetLayout.TWO))
        assertEquals(listOf(220, 220), widths(1400, 240, LiveWidgetLayout.THREE))
        assertEquals(listOf(360), widths(1400, 240, LiveWidgetLayout.ONE))
    }

    @Test fun narrowScreensDropTrailingColumnsAndShortTilesCollapse() {
        assertEquals(listOf(198), widths(598, 170, LiveWidgetLayout.TWO))
        assertEquals(listOf(listOf(0)), slots(598, 170, LiveWidgetLayout.THREE))
        assertEquals(listOf(listOf(0)), slots(598, 170, LiveWidgetLayout.FOUR))
        assertEquals(listOf(170), widths(598, 170, LiveWidgetLayout.FOUR))
        assertFalse(LiveWidgets.fits(598, 170, LiveWidgetLayout.THREE))
        assertTrue(LiveWidgets.fits(598, 170, LiveWidgetLayout.TALL))
        assertTrue(LiveWidgets.columns(539, 240, LiveWidgetLayout.THREE).isEmpty())
        assertTrue(LiveWidgets.columns(0, 240, LiveWidgetLayout.ONE).isEmpty())
        assertTrue(LiveWidgets.columns(1000, 0, LiveWidgetLayout.ONE).isEmpty())
    }

    @Test fun columnsNeverCrowdTheInfoPanel() {
        for (width in 0..2000 step 7) for (height in listOf(120, 150, 170, 200, 240)) for (layout in LiveWidgetLayout.entries) {
            val columns = LiveWidgets.columns(width, height, layout)
            assertTrue(columns.flatMap { it.slots }.all { it in 0 until layout.slots })
            assertTrue(columns.all { it.width >= LiveWidgets.SQUARE_MIN && it.tileHeight >= LiveWidgets.SHORT_MIN })
            if (columns.isNotEmpty()) assertTrue(width - columns.sumOf { it.width } - LiveWidgets.GAP * (columns.size - 1) - LiveWidgets.SPACING >= LiveWidgets.INFO_MIN)
        }
    }

    @Test fun oldStoredLayoutsStayValid() {
        for (name in listOf("ONE", "TWO", "THREE")) assertNotNull(LiveWidgetLayout.entries.firstOrNull { it.name == name })
        assertEquals(3, LiveWidgetLayout.THREE.slots)
        assertTrue(LiveWidgetLayout.entries.all { it.slots <= LiveWidgets.MAX_SLOTS })
    }

    @Test fun savedKindsFallBackToDefaults() {
        assertEquals(listOf(LiveWidgetKind.CLOCKS, LiveWidgetKind.UP_NEXT, LiveWidgetKind.RECORDINGS, LiveWidgetKind.STREAM), LiveWidgets.kinds(emptyList()))
        assertEquals(listOf(LiveWidgetKind.RECORDINGS, LiveWidgetKind.UP_NEXT, LiveWidgetKind.EMPTY, LiveWidgetKind.STREAM),
            LiveWidgets.kinds(listOf("RECORDINGS", "WEATHER", "EMPTY")))
    }

    @Test fun everyCityHasAValidZoneAndAUniqueName() {
        assertTrue(LiveWidgets.cities.size >= 80)
        assertEquals(LiveWidgets.cities.size, LiveWidgets.cities.map { it.name }.distinct().size)
        LiveWidgets.cities.forEach { ZoneId.of(it.zone) }
        assertEquals(LiveWidgets.cities.map { LiveWidgets.fold(it.name) }.sorted(), LiveWidgets.cities.map { LiveWidgets.fold(it.name) })
    }

    @Test fun cityLookupAcceptsNamesAndZones() {
        assertEquals("Australia/Sydney", LiveWidgets.city("Sydney")?.zone)
        val zone = LiveWidgets.city("America/Argentina/Cordoba")
        assertEquals("Cordoba", zone?.name)
        assertEquals("America/Argentina/Cordoba", zone?.id)
        assertNull(LiveWidgets.city("Atlantis"))
        assertNull(LiveWidgets.city("Mars/Olympus_Mons"))
        assertNull(LiveWidgets.city(null))
    }

    @Test fun defaultsStartWithTheDeviceCity() {
        assertEquals(listOf("Sydney", "London", "New York"), LiveWidgets.defaultCities("Australia/Sydney"))
        assertEquals(listOf("London", "New York", "Tokyo"), LiveWidgets.defaultCities("Europe/London"))
        assertEquals(listOf("New York", "London", "Tokyo"), LiveWidgets.defaultCities("America/New_York"))
        assertEquals(listOf("Melbourne", "London", "New York"), LiveWidgets.defaultCities("Australia/Melbourne"))
        assertEquals(listOf("Asia/Novosibirsk", "London", "New York"), LiveWidgets.defaultCities("Asia/Novosibirsk"))
        assertEquals(listOf("London", "New York", "Tokyo"), LiveWidgets.defaultCities("GMT"))
    }

    @Test fun savedCitiesAreCleanedAndCapped() {
        assertEquals(listOf("Paris", "Tokyo"), LiveWidgets.savedCities(listOf("Paris", "Nowhere", "Tokyo", "Paris"), "UTC"))
        assertEquals(4, LiveWidgets.savedCities(listOf("Paris", "Tokyo", "Lima", "Oslo", "Rome"), "UTC").size)
        assertEquals(emptyList<String>(), LiveWidgets.savedCities(emptyList(), "Australia/Sydney"))
        assertEquals(LiveWidgets.defaultCities("Australia/Sydney"), LiveWidgets.savedCities(null, "Australia/Sydney"))
    }

    @Test fun searchIgnoresCaseAndAccentsAndPutsPrefixesFirst() {
        assertEquals("São Paulo", LiveWidgets.search("sao").first().name)
        assertEquals(listOf("Bogotá"), LiveWidgets.search("BOGOTA").map { it.name })
        assertTrue(LiveWidgets.search("lond", exclude = listOf("London")).isEmpty())
        assertEquals(LiveWidgets.cities.size - 1, LiveWidgets.search("", exclude = listOf("Paris")).size)
        assertEquals("Perth", LiveWidgets.search("pe").first().name)
    }

    @Test fun dayOffsetAndDaytimeFollowEachZone() {
        val now = Instant.parse("2026-10-09T22:30:00Z")
        val london = ZoneId.of("Europe/London")
        assertEquals(1, LiveWidgets.dayOffset(now, ZoneId.of("Australia/Sydney"), london))
        assertEquals(0, LiveWidgets.dayOffset(now, ZoneId.of("America/New_York"), london))
        assertEquals(-1, LiveWidgets.dayOffset(Instant.parse("2026-10-09T02:00:00Z"), ZoneId.of("America/Los_Angeles"), london))
        assertFalse(LiveWidgets.daytime(now, london))
        assertTrue(LiveWidgets.daytime(now, ZoneId.of("Australia/Sydney")))
        assertEquals("09:30", LiveWidgets.time(now, ZoneId.of("Australia/Sydney")))
        assertEquals("23:30", LiveWidgets.time(now, london))
    }

    @Test fun clocksResolveSavedIds() {
        val now = Instant.parse("2026-01-15T12:05:00Z").toEpochMilli()
        val clocks = LiveWidgets.clocks(listOf("Tokyo", "Gone", "America/Argentina/Cordoba"), now, "Europe/London")
        assertEquals(listOf("Tokyo", "Cordoba"), clocks.map { it.city.name })
        assertEquals("21:05", clocks[0].time)
        assertEquals(0, clocks[0].dayOffset)
        assertFalse(clocks[0].day)
        assertEquals("09:05", clocks[1].time)
        assertTrue(clocks[1].day)
        assertEquals(1, LiveWidgets.clocks(listOf("Auckland"), Instant.parse("2026-01-15T12:05:00Z").toEpochMilli(), "Europe/London")[0].dayOffset)
    }

    @Test fun clockTicksOnTheMinute() {
        assertEquals(60_000L, LiveWidgets.nextMinute(120_000L))
        assertEquals(1L, LiveWidgets.nextMinute(179_999L))
        assertEquals(30_000L, LiveWidgets.nextMinute(90_000L))
    }

    @Test fun streamLabels() {
        assertEquals("8.4 Mb/s", LiveWidgets.bitrate(8_400_000))
        assertEquals("850 kb/s", LiveWidgets.bitrate(849_600))
        assertNull(LiveWidgets.bitrate(-1))
        assertEquals("1920×1080", LiveWidgets.resolution(1920, 1080))
        assertNull(LiveWidgets.resolution(0, 1080))
        assertEquals("HEVC", LiveWidgets.codec("video/hevc"))
        assertNull(LiveWidgets.codec("video/unknown"))
    }

    @Test fun richStreamLabels() {
        assertEquals("50 fps", LiveWidgets.frameRate(50.01f))
        assertEquals("59.94 fps", LiveWidgets.frameRate(59.94f))
        assertNull(LiveWidgets.frameRate(-1f))
        assertEquals("HLG", LiveWidgets.range("video/hevc", "hvc1.2.4.L153", LiveWidgets.TRANSFER_HLG))
        assertEquals("HDR10", LiveWidgets.range("video/hevc", null, LiveWidgets.TRANSFER_PQ))
        assertEquals("Dolby Vision", LiveWidgets.range("video/hevc", "dvh1.05.06", LiveWidgets.TRANSFER_PQ))
        assertEquals("SDR", LiveWidgets.range("video/avc", null, 3))
        assertEquals("4K", LiveWidgets.quality(3840, 2160))
        assertEquals("1080p", LiveWidgets.quality(1920, 1080))
        assertEquals("720p", LiveWidgets.quality(1280, 720))
        assertEquals("SD", LiveWidgets.quality(720, 576))
        assertEquals("Dolby Digital Plus", LiveWidgets.audioCodec("audio/eac3"))
        assertEquals("AAC", LiveWidgets.audioCodec("audio/mp4a-latm"))
        assertEquals("5.1", LiveWidgets.channels(6))
        assertEquals("2.0", LiveWidgets.channels(2))
        assertEquals("HLS", LiveWidgets.container("application/x-mpegURL"))
        assertEquals("MPEG-TS", LiveWidgets.container("video/mp2t"))
        assertEquals("6.2 s", LiveWidgets.buffer(6_200))
        assertEquals(2, LiveWidgets.bufferHealth(6_000))
        assertEquals(-1, LiveWidgets.bufferHealth(900))
        assertEquals(listOf("4K", "HLG", "50p", "5.1"), LiveWidgets.badges(LiveStreamFacts(3840, 2160, 50f, "video/hevc", transfer = LiveWidgets.TRANSFER_HLG, channels = 6)))
        assertEquals(listOf("1080p"), LiveWidgets.badges(LiveStreamFacts(1920, 1080, 25f, "video/avc", channels = 2)))
    }
}
