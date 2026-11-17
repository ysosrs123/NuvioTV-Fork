package com.nuvio.tv.core.calendar

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarStreamingTimesTest {
    private val friday = LocalDate.of(2026, 11, 27)

    @Test fun paramountPlusIsThreeInTheMorningInNewYork() {
        assertEquals(LocalTime.of(3, 0), calendarUsualStreamingTime("Paramount+", friday, ZoneId.of("America/New_York")))
        assertEquals(LocalTime.of(19, 0), calendarUsualStreamingTime("Paramount+", friday, ZoneId.of("Australia/Melbourne")))
        assertEquals(LocalTime.MIDNIGHT, calendarUsualStreamingTime("paramount+ ", friday, ZoneId.of("America/Los_Angeles")))
    }

    @Test fun huluFollowsNewYorkSummerTime() {
        val july = LocalDate.of(2026, 7, 10)
        assertEquals(LocalTime.of(14, 0), calendarUsualStreamingTime("Hulu", july, ZoneId.of("Australia/Melbourne")))
        assertEquals(LocalTime.of(16, 0), calendarUsualStreamingTime("Hulu", friday, ZoneId.of("Australia/Melbourne")))
    }

    @Test fun noEstimateWhenTheHourFallsOnAnotherDay() {
        assertNull(calendarUsualStreamingTime("Hulu", friday, ZoneId.of("Pacific/Honolulu")))
    }

    @Test fun otherNamesOfTheSameServiceCount() {
        val newYork = ZoneId.of("America/New_York")
        assertEquals(LocalTime.of(3, 0), calendarUsualStreamingTime("Paramount+ with Showtime", friday, newYork))
        assertEquals(LocalTime.MIDNIGHT, calendarUsualStreamingTime("Peacock Premium", friday, newYork))
        assertNull(calendarUsualStreamingTime("Hulu Japan", friday, newYork))
    }

    @Test fun servicesWithoutOneHourGetNoEstimate() {
        listOf("Netflix", "Prime Video", "Disney+", "Apple TV", "HBO Max", null).forEach { service ->
            assertNull(calendarUsualStreamingTime(service, friday, ZoneId.of("America/New_York")))
        }
    }
}
