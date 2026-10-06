package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class XtreamGuideReferenceTest {
    @Test fun referenceRoundTripsAndRejectsOtherEndpoints() {
        assertEquals("abc-1_2", XtreamGuideReference.sourceId(XtreamGuideReference.of("abc-1_2")))
        for (endpoint in listOf("https://example.invalid/xmltv.php", "xtream-guide:", "xtream-guide:../x", "content://x", "xtream-guide:a b"))
            assertNull(endpoint, XtreamGuideReference.sourceId(endpoint))
        try { XtreamGuideReference.of("bad/id"); fail() } catch (_: IllegalArgumentException) { }
    }
}
