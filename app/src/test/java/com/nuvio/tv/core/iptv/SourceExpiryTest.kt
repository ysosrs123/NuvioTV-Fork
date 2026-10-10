package com.nuvio.tv.core.iptv

import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class SourceExpiryTest {
    private val utc = ZoneOffset.UTC
    private val now = ZonedDateTime.of(2026, 10, 10, 12, 0, 0, 0, utc).toInstant().toEpochMilli()
    private fun at(day: Int, month: Int = 10, year: Int = 2026, hour: Int = 12) = ZonedDateTime.of(year, month, day, hour, 0, 0, 0, utc).toEpochSecond()

    @Test fun statusShowsTheDateUntilAWeekBefore() {
        assertEquals(ExpiryStatus.None, SourceExpiries.status(SourceExpiries.NONE, now, utc))
        assertEquals(ExpiryStatus.Until("12-Mar-27"), SourceExpiries.status(at(12, 3, 2027), now, utc))
        assertEquals(ExpiryStatus.Until("18-Oct-26"), SourceExpiries.status(at(18), now, utc))
        assertEquals(ExpiryStatus.Soon(7), SourceExpiries.status(at(17), now, utc))
        assertEquals(ExpiryStatus.Soon(3), SourceExpiries.status(at(13, hour = 1), now, utc))
        assertEquals(ExpiryStatus.Soon(0), SourceExpiries.status(at(10, hour = 23), now, utc))
        assertEquals(ExpiryStatus.Expired(0), SourceExpiries.status(at(10, hour = 1), now, utc))
        assertEquals(ExpiryStatus.Expired(2), SourceExpiries.status(at(8), now, utc))
        assertTrue(SourceExpiries.status(Long.MAX_VALUE, now, utc) is ExpiryStatus.Until)
    }

    @Test fun daysFollowTheLocalCalendar() {
        val sydney = ZoneId.of("Australia/Sydney")
        val expiry = ZonedDateTime.of(2026, 10, 11, 9, 0, 0, 0, sydney).toEpochSecond()
        val evening = ZonedDateTime.of(2026, 10, 10, 22, 0, 0, 0, sydney).toInstant().toEpochMilli()
        assertEquals(ExpiryStatus.Soon(1), SourceExpiries.status(expiry, evening, sydney))
    }

    @Test fun soonestWarningSkipsDistantAndUnlimitedSources() {
        val sources = listOf(SourceExpiry("a", "Alpha", at(12, 3, 2027)), SourceExpiry("b", "Beta", SourceExpiries.NONE),
            SourceExpiry("c", "Gamma", at(15)), SourceExpiry("d", "Delta", at(13)))
        assertEquals(ExpiryWarning("d", "Delta", ExpiryStatus.Soon(3)), SourceExpiries.soonest(sources, now, utc))
        assertEquals(ExpiryWarning("e", "Echo", ExpiryStatus.Expired(2)),
            SourceExpiries.soonest(sources + SourceExpiry("e", "Echo", at(8)), now, utc))
        assertNull(SourceExpiries.soonest(sources.take(2), now, utc))
        assertNull(SourceExpiries.soonest(emptyList(), now, utc))
    }

    @Test fun stalkerBillingDateIsReadFromTheProfile() {
        assertEquals(at(12, 3, 2027, hour = 0), SourceExpiries.stalkerProfile("""{"js":{"expire_billing_date":"2027-03-12 00:00:00"}}""", utc))
        assertEquals(at(12, 3, 2027, hour = 0), SourceExpiries.stalkerProfile("""{"js":{"expire_billing_date":"2027-03-12"}}""", utc))
        assertNull(SourceExpiries.stalkerProfile("""{"js":{"expire_billing_date":"0000-00-00 00:00:00"}}"""))
        assertNull(SourceExpiries.stalkerProfile("""{"js":{"expire_billing_date":"2027-02-30 00:00:00"}}"""))
        assertNull(SourceExpiries.stalkerProfile("""{"js":{"expire_billing_date":null}}"""))
        assertNull(SourceExpiries.stalkerProfile("""{"js":{}}"""))
        assertNull(SourceExpiries.stalkerProfile("not json"))
    }

    @Test fun stalkerBillingDateUsesThePortalTimeZoneThenTheDevice() {
        val sydney = ZoneId.of("Australia/Sydney")
        val midnight = ZonedDateTime.of(2027, 3, 12, 0, 0, 0, 0, ZoneId.of("Europe/Kiev")).toEpochSecond()
        assertEquals(midnight, SourceExpiries.stalkerProfile("""{"js":{"expire_billing_date":"2027-03-12 00:00:00","default_timezone":"Europe/Kiev"}}""", sydney))
        assertEquals(ZonedDateTime.of(2027, 3, 12, 0, 0, 0, 0, sydney).toEpochSecond(),
            SourceExpiries.stalkerProfile("""{"js":{"expire_billing_date":"2027-03-12 00:00:00","default_timezone":"Nowhere/Else"}}""", sydney))
        assertEquals(ZonedDateTime.of(2027, 3, 12, 0, 0, 0, 0, sydney).toEpochSecond(),
            SourceExpiries.stalkerProfile("""{"js":{"expire_billing_date":"2027-03-12"}}""", sydney))
    }
}
