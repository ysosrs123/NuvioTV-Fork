package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class XtreamCatalogueTest {
    @Test fun integerAndStringIdsCollapseButLeadingZeroIsDistinct() {
        val result = XtreamCatalogueParser().parse("""[{"stream_id":101,"name":"One"},{"stream_id":"101","name":"One"},{"stream_id":"0101","name":"Other"}]""")
        assertTrue(result.canPublish); assertEquals(listOf("101", "0101"), result.channels.map { it.providerId })
    }
    @Test fun booleanFractionalAndNullIdsAreInvalid() {
        val result = XtreamCatalogueParser().parse("""[{"stream_id":true,"name":"One"},{"stream_id":1.5,"name":"Two"},{"stream_id":null,"name":"Three"}]""")
        assertEquals(3, result.invalidRows); assertFalse(result.canPublish)
    }
    @Test fun conflictingIdentityPreventsPromotion() {
        val result = XtreamCatalogueParser().parse("""[{"stream_id":1,"name":"One"},{"stream_id":1,"name":"Different"}]""")
        assertEquals(1, result.invalidRows); assertFalse(result.canPublish)
    }
    @Test fun disabledArchiveCannotBeRevivedByPositiveDuration() {
        val result = XtreamCatalogueParser().parse("""[{"stream_id":1,"name":"One","tv_archive":0,"tv_archive_duration":7}]""")
        assertEquals(ArchiveAvailability.UNAVAILABLE, result.channels.single().archive)
        assertNull(result.channels.single().archiveDays)
    }
    @Test fun absentArchiveFlagStaysUnknown() {
        val result = XtreamCatalogueParser().parse("""[{"stream_id":1,"name":"One","tv_archive_duration":7}]""")
        assertEquals(ArchiveAvailability.UNKNOWN, result.channels.single().archive)
    }
    @Test fun positiveArchiveFlagIsAdvertisedNotProven() {
        val result = XtreamCatalogueParser().parse("""[{"stream_id":1,"name":"One","tv_archive":"1","tv_archive_duration":"7"}]""")
        assertEquals(ArchiveAvailability.ADVERTISED, result.channels.single().archive)
        assertEquals(7, result.channels.single().archiveDays)
    }
    @Test(expected = IllegalArgumentException::class) fun channelLimitIsEnforced() {
        XtreamCatalogueParser(maxChannels = 1).parse("""[{"stream_id":1,"name":"One"},{"stream_id":2,"name":"Two"}]""")
    }
    @Test fun emptyResponseDoesNotAuthoriseDeletion() { assertFalse(XtreamCatalogueParser().parse("[]").canPublish) }
}
