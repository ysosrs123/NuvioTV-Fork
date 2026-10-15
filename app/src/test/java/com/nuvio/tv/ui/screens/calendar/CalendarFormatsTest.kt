package com.nuvio.tv.ui.screens.calendar

import com.nuvio.tv.ui.screens.settings.calendarZoneOptions
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarFormatsTest {
    private val australia = Locale.forLanguageTag("en-AU")
    private val german = Locale.GERMANY
    private val now = Instant.parse("2026-10-05T23:00:00Z")

    @Test fun timesFollowTheBoxTwelveOrTwentyFourHourChoice() {
        assertEquals("hm", CalendarFormats.timeSkeleton(is24Hour = false))
        assertEquals("Hm", CalendarFormats.timeSkeleton(is24Hour = true))
        val evening = LocalTime.of(20, 30)
        assertEquals("8:30 pm", CalendarFormats.plain(australia, is24Hour = false).time(evening).lowercase())
        assertEquals("20:30", CalendarFormats.plain(australia, is24Hour = true).time(evening))
        assertEquals("20:30", CalendarFormats.plain(german, is24Hour = true).time(evening))
        assertEquals("08:05", CalendarFormats.plain(german, is24Hour = true).time(LocalTime.of(8, 5)))
    }

    @Test fun datesAreInTheAppLanguage() {
        val date = LocalDate.of(2026, 10, 8)
        assertEquals("Thursday 8 October", CalendarFormats.plain(australia, false).longDate(date))
        assertEquals("Donnerstag 8 Oktober", CalendarFormats.plain(german, true).longDate(date))
        assertEquals("Oktober 2026", CalendarFormats.plain(german, true).month(java.time.YearMonth.of(2026, 10)))
    }

    @Test fun weeksStartOnTheLocalesFirstDay() {
        assertEquals(DayOfWeek.MONDAY, CalendarFormats.plain(german, true).firstDayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, CalendarFormats.plain(Locale.US, false).firstDayOfWeek)
    }

    @Test fun zoneNameIsThePlaceAndItsShortNameForTheDate() {
        val sydney = calendarZoneName(ZoneId.of("Australia/Sydney"), now, australia)
        assertEquals("Sydney", sydney.city)
        assertEquals("AEDT", sydney.short)
        val winter = calendarZoneName(ZoneId.of("Australia/Sydney"), Instant.parse("2026-07-01T00:00:00Z"), australia)
        assertEquals("AEST", winter.short)
        assertEquals("Buenos Aires", calendarZoneName(ZoneId.of("America/Argentina/Buenos_Aires"), now, australia).city)
        assertEquals("Wien", calendarZoneName(ZoneId.of("Europe/Vienna"), now, german) { "Wien" }.city)
        assertNull(calendarZoneName(ZoneOffset.UTC, now, australia).city)
    }

    @Test fun runtimeUsesTheAppsShortForm() {
        fun runtime(minutes: Int?) = calendarRuntime(minutes, "%1\$dh %2\$dm", "%1\$dh", "%1\$dm")
        assertEquals("44m", runtime(44))
        assertEquals("1h 4m", runtime(64))
        assertEquals("2h", runtime(120))
        assertNull(runtime(0))
        assertNull(runtime(null))
        assertEquals("1 Std. 4 Min.", calendarRuntime(64, "%1\$d Std. %2\$d Min.", "%1\$d Std.", "%1\$d Min."))
    }

    @Test fun zoneChoicesArePlacesOrderedWestToEast() {
        val options = calendarZoneOptions(now)
        assertTrue(options.size > 300)
        assertTrue(options.none { it.id.startsWith("Etc/") || '/' !in it.id })
        val sydney = options.single { it.id == "Australia/Sydney" }
        assertEquals("Sydney", sydney.city)
        assertEquals("GMT+11:00", sydney.offset)
        val offsets = options.map { ZoneId.of(it.id).rules.getOffset(now).totalSeconds }
        assertEquals(offsets.sorted(), offsets)
    }

    @Test fun zoneRegionsCanBeTranslated() {
        val options = calendarZoneOptions(now) { region -> if (region == "America") "Amerika" else region }
        assertEquals("Amerika / Argentina", options.single { it.id == "America/Argentina/Buenos_Aires" }.region)
        assertEquals("Europe", options.single { it.id == "Europe/Berlin" }.region)
    }

    @Test fun namesJoinWithTheLanguagesPattern() {
        assertEquals("", calendarJoinPlain(emptyList(), ", ", "%1\$s and %2\$s"))
        assertEquals("NCIS", calendarJoinPlain(listOf("NCIS"), ", ", "%1\$s and %2\$s"))
        assertEquals("NCIS and FBI", calendarJoinPlain(listOf("NCIS", "FBI"), ", ", "%1\$s and %2\$s"))
        assertEquals("NCIS, FBI und Signal", calendarJoinPlain(listOf("NCIS", "FBI", "Signal"), ", ", "%1\$s und %2\$s"))
        assertEquals("NCIS y FBI", calendarNameList(listOf("NCIS", "FBI"), Locale.ROOT) { calendarJoinPlain(it, ", ", "%1\$s y %2\$s") })
    }

    @Test fun networkNamesLoseAClosingFullStopButAbbreviationsKeepIt() {
        assertEquals("Life Style", calendarNetworkName("Life Style."))
        assertEquals("Life Style", calendarNetworkName(" Life Style. "))
        assertEquals("CBS", calendarNetworkName("CBS"))
        assertEquals("Apple TV", calendarNetworkName("Apple TV"))
        assertEquals("Acme Inc.", calendarNetworkName("Acme Inc."))
        assertEquals("Warner Bros.", calendarNetworkName("Warner Bros."))
        assertEquals("Fox U.S.", calendarNetworkName("Fox U.S."))
        assertEquals("Wait...", calendarNetworkName("Wait..."))
        assertNull(calendarNetworkName("."))
        assertNull(calendarNetworkName("  "))
        assertNull(calendarNetworkName(null))
    }
}
