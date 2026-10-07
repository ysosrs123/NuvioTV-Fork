package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class CatchupUrlTest {
    private val start = 1_791_331_200_000L
    private val window = CatchupWindow(start, start + 3_600_000, start + 7_200_000)

    @Test fun typesAndAliasesAreRecognised() {
        assertEquals(CatchupType.XTREAM, catchupType("xtream-codes", null))
        assertEquals(CatchupType.SHIFT, catchupType("timeshift", null))
        assertEquals(CatchupType.DEFAULT, catchupType(null, "http://archive.invalid/{utc}"))
        assertNull(catchupType(null, null))
        assertNull(catchupType("unknown", "x"))
    }

    @Test fun templatesExpandTimesDurationsAndDateParts() {
        assertEquals("http://a.invalid/1791331200-1791334800?d=60&o=120",
            expandCatchupTemplate("http://a.invalid/{utc}-{utcend}?d={duration:60}&o={offset:60}", window))
        assertEquals("http://a.invalid/2026/10/07/00", expandCatchupTemplate("http://a.invalid/{Y}/{m}/{d}/{H}", window))
        assertEquals("20261007000000", expandCatchupTemplate("{utc:YmdHMS}", window))
        assertEquals("20261007100000", expandCatchupTemplate("{start:YmdHMS}", window.copy(offsetMinutes = 600)))
        assertEquals("keep{unknown}", expandCatchupTemplate("keep{unknown}", window))
    }

    @Test fun appendShiftAndFlussonicBuildFromTheLiveAddress() {
        assertEquals("http://a.invalid/live.ts?utc=1791331200", catchupUrl(CatchupType.APPEND, "http://a.invalid/live.ts", "?utc={utc}", window))
        assertEquals("http://a.invalid/live.ts?t=1&utc=1791331200&lutc=1791338400", catchupUrl(CatchupType.SHIFT, "http://a.invalid/live.ts?t=1", null, window))
        assertEquals("http://a.invalid/ch/archive-1791331200-3600.m3u8?token=x", catchupUrl(CatchupType.FLUSSONIC, "http://a.invalid/ch/index.m3u8?token=x", null, window))
        assertEquals("http://a.invalid/ch/timeshift_abs-1791331200.ts", catchupUrl(CatchupType.FLUSSONIC, "http://a.invalid/ch/mpegts", null, window))
        assertNull(catchupUrl(CatchupType.DEFAULT, "http://a.invalid/live.ts", null, window))
    }

    @Test fun xtreamPathUsesMinutesAndProviderLocalTime() {
        assertEquals(60L to "2026-10-07:00-00", xtreamTimeshiftPath(window))
        assertEquals(60L to "2026-10-07:10-00", xtreamTimeshiftPath(window.copy(offsetMinutes = 600)))
    }

    @Test fun xtreamStylesTryRememberedFirstThenTheRestInOrder() {
        assertEquals(listOf(XtreamCatchupStyle.TIMESHIFT_TS, XtreamCatchupStyle.TIMESHIFT_HLS, XtreamCatchupStyle.TIMESHIFT_PHP), XtreamCatchupStyle.order(null))
        assertEquals(listOf(XtreamCatchupStyle.TIMESHIFT_PHP, XtreamCatchupStyle.TIMESHIFT_TS, XtreamCatchupStyle.TIMESHIFT_HLS),
            XtreamCatchupStyle.order(XtreamCatchupStyle.TIMESHIFT_PHP))
        assertEquals(XtreamCatchupStyle.TIMESHIFT_HLS, XtreamCatchupStyle.parse("TIMESHIFT_HLS"))
        assertNull(XtreamCatchupStyle.parse("other"))
        assertNull(XtreamCatchupStyle.worked(XtreamCatchupStyle.TIMESHIFT_TS, XtreamCatchupStyle.TIMESHIFT_TS))
        assertEquals(XtreamCatchupStyle.TIMESHIFT_PHP, XtreamCatchupStyle.worked(XtreamCatchupStyle.TIMESHIFT_TS, XtreamCatchupStyle.TIMESHIFT_PHP))
        assertEquals(XtreamCatchupStyle.TIMESHIFT_TS, XtreamCatchupStyle.worked(null, XtreamCatchupStyle.TIMESHIFT_TS))
    }
}
