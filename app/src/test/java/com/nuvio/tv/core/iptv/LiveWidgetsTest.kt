package com.nuvio.tv.core.iptv

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LiveWidgetsTest {
    @Test fun slotsFillTheGapUpToTheChosenLayout() {
        assertEquals(listOf(240, 240), LiveWidgets.slots(1000, LiveWidgetLayout.TWO))
        assertEquals(listOf(360), LiveWidgets.slots(1000, LiveWidgetLayout.ONE))
        assertEquals(listOf(192, 192, 192), LiveWidgets.slots(1000, LiveWidgetLayout.THREE))
        assertEquals(listOf(200, 200, 200), LiveWidgets.slots(1200, LiveWidgetLayout.THREE))
    }

    @Test fun narrowSlotsAreDroppedAndATinyGapShowsNone() {
        assertEquals(listOf(143, 143), LiveWidgets.slots(698, LiveWidgetLayout.THREE))
        assertEquals(listOf(280), LiveWidgets.slots(680, LiveWidgetLayout.TWO))
        assertEquals(listOf(140), LiveWidgets.slots(540, LiveWidgetLayout.TWO))
        assertTrue(LiveWidgets.slots(539, LiveWidgetLayout.THREE).isEmpty())
        assertTrue(LiveWidgets.slots(0, LiveWidgetLayout.ONE).isEmpty())
        for (width in 0..2000 step 7) for (layout in LiveWidgetLayout.entries) {
            val slots = LiveWidgets.slots(width, layout)
            assertTrue(slots.size <= layout.slots && slots.all { it >= LiveWidgets.SLOT_MIN })
            if (slots.isNotEmpty()) assertTrue(width - slots.sum() - LiveWidgets.GAP * (slots.size - 1) - LiveWidgets.SPACING >= LiveWidgets.INFO_MIN)
        }
    }

    @Test fun savedKindsFallBackToDefaults() {
        assertEquals(listOf(LiveWidgetKind.CLOCKS, LiveWidgetKind.UP_NEXT, LiveWidgetKind.RECORDINGS), LiveWidgets.kinds(emptyList()))
        assertEquals(listOf(LiveWidgetKind.RECORDINGS, LiveWidgetKind.UP_NEXT, LiveWidgetKind.EMPTY),
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
}
