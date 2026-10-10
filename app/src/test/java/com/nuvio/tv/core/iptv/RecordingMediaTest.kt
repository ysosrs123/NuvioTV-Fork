package com.nuvio.tv.core.iptv

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RecordingMediaTest {
    @Test fun mediaLocationIsValidAndKeptAsAChoice() {
        assertTrue(RecordingLocations.valid(RecordingLocations.MEDIA))
        assertNull(RecordingLocations.volumeId(RecordingLocations.MEDIA))
        assertNull(RecordingLocations.shareId(RecordingLocations.MEDIA))
        assertEquals(RecordingLocations.MEDIA, RecordingLocations.choice("media"))
        assertEquals(RecordingLocations.SHARE, RecordingLocations.choice("share"))
        assertEquals("volume:1234-ABCD", RecordingLocations.choice("volume:1234-ABCD"))
        assertEquals(RecordingLocations.INTERNAL, RecordingLocations.choice(null))
        assertEquals(RecordingLocations.INTERNAL, RecordingLocations.choice("Media"))
        assertEquals(RecordingLocations.INTERNAL, RecordingLocations.choice("media:1"))
        assertEquals(RecordingLocations.INTERNAL, RecordingLocations.choice("volume:../x"))
        assertFalse(RecordingLocations.valid("media:12"))
    }

    @Test fun onlyAndroid10AndLaterOffersTheMoviesFolder() {
        assertFalse(RecordingMedia.supported(28))
        assertTrue(RecordingMedia.supported(29))
        assertTrue(RecordingMedia.supported(34))
    }

    @Test fun contentIdsAreParsedStrictly() {
        assertEquals(42L, RecordingMedia.id("42"))
        assertEquals(1_000_000_000_000L, RecordingMedia.id("1000000000000"))
        assertNull(RecordingMedia.id(null))
        assertNull(RecordingMedia.id(""))
        assertNull(RecordingMedia.id("0"))
        assertNull(RecordingMedia.id("-4"))
        assertNull(RecordingMedia.id("12a"))
        assertNull(RecordingMedia.id("content://media/external_primary/video/media/12"))
        assertNull(RecordingMedia.id("9999999999999999999"))
        val name = RecordingFiles.name("BBC One", "News", 1_791_331_200_000L, ZoneId.of("UTC"))
        assertNull(RecordingMedia.id(name))
    }

    @Test fun displayNamesComeFromTheSafeRecordingName() {
        val name = RecordingFiles.name("CON", null, 1_791_331_200_000L, ZoneId.of("UTC"))
        assertEquals(name, RecordingMedia.displayName(name))
        assertEquals(name, RecordingMedia.displayName(RecordingFiles.partial(name)))
        assertEquals("show.ts", RecordingMedia.displayName("folder/show.ts.part"))
        assertEquals("show.ts", RecordingMedia.displayName("show"))
        assertEquals("_NUL.ts", RecordingMedia.displayName("NUL"))
        assertTrue(RecordingMedia.pending(RecordingFiles.partial(name)))
        assertFalse(RecordingMedia.pending(name))
        try { RecordingMedia.displayName(".part"); fail() } catch (_: IllegalArgumentException) { }
        try { RecordingMedia.displayName("a:b.ts"); fail() } catch (_: IllegalArgumentException) { }
        assertEquals("Movies/Nuvio Recordings/", RecordingMedia.RELATIVE_PATH)
        assertEquals("video/mp2t", RecordingMedia.MIME_TYPE)
    }

    @Test fun stateFollowsCopyAndPresence() {
        assertEquals(RecordingMediaState.COPYING, RecordingMedia.state(true, "show.ts", emptySet()))
        assertEquals(RecordingMediaState.PRESENT, RecordingMedia.state(false, "17", setOf(17L)))
        assertEquals(RecordingMediaState.MISSING, RecordingMedia.state(false, "17", setOf(18L)))
        assertEquals(RecordingMediaState.MISSING, RecordingMedia.state(false, "show.ts", setOf(17L)))
        assertEquals(RecordingMediaState.MISSING, RecordingMedia.state(false, null, setOf(17L)))
    }
}
