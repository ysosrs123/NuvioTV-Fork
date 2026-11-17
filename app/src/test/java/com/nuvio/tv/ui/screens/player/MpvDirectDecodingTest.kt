package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.MpvDirectDecodeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvDirectDecodingTest {

    @Test
    fun `every video size takes part, files without a picture do not`() {
        assertTrue(hasVideoTrack(3840, 2160))
        assertTrue(hasVideoTrack(1920, 1080))
        assertTrue(hasVideoTrack(720, 576))
        assertTrue(hasVideoTrack(null, 480))
        assertFalse(hasVideoTrack(null, null))
        assertFalse(hasVideoTrack(0, 0))
    }

    @Test
    fun `automatic and always ask for the direct decoder`() {
        assertEquals("mediacodec", mpvHwdecForDirectMode(MpvDirectDecodeMode.AUTOMATIC, false, false))
        assertEquals("mediacodec", mpvHwdecForDirectMode(MpvDirectDecodeMode.ALWAYS, false, false))
    }

    @Test
    fun `a box that refused keeps the copy route on automatic but not on always`() {
        assertNull(mpvHwdecForDirectMode(MpvDirectDecodeMode.AUTOMATIC, true, false))
        assertEquals("mediacodec", mpvHwdecForDirectMode(MpvDirectDecodeMode.ALWAYS, true, false))
    }

    @Test
    fun `off and software decoding leave the decoder alone`() {
        assertNull(mpvHwdecForDirectMode(MpvDirectDecodeMode.OFF, false, false))
        assertNull(mpvHwdecForDirectMode(MpvDirectDecodeMode.AUTOMATIC, false, true))
        assertNull(mpvHwdecForDirectMode(MpvDirectDecodeMode.ALWAYS, false, true))
    }

    @Test
    fun `the direct decoder in use means it worked`() {
        assertEquals(DirectDecodeOutcome.WORKED, directDecodeOutcome("mediacodec", "mediacodec-copy"))
        assertEquals(DirectDecodeOutcome.WORKED, directDecodeOutcome("mediacodec", ""))
    }

    @Test
    fun `the old decoder or no value yet means mpv has not switched`() {
        assertEquals(DirectDecodeOutcome.WAITING, directDecodeOutcome("mediacodec-copy", "mediacodec-copy"))
        assertEquals(DirectDecodeOutcome.WAITING, directDecodeOutcome("", "mediacodec-copy"))
        assertEquals(DirectDecodeOutcome.WAITING, directDecodeOutcome(null, "mediacodec-copy"))
        assertEquals(DirectDecodeOutcome.WAITING, directDecodeOutcome("mediacodec-copy", ""))
    }

    @Test
    fun `only a refusal after the copy decoder counts against the box`() {
        assertTrue(isCountableDirectRefusal("mediacodec-copy"))
        assertFalse(isCountableDirectRefusal("no"))
        assertFalse(isCountableDirectRefusal(""))
        assertFalse(isCountableDirectRefusal(null))
    }

    @Test
    fun `the same file refused again does not count twice`() {
        assertEquals(1, nextDirectRefusalCount(0, null, "http://host/a.mkv"))
        assertEquals(1, nextDirectRefusalCount(1, "http://host/a.mkv", "http://host/a.mkv"))
        assertEquals(2, nextDirectRefusalCount(1, "http://host/a.mkv", "http://host/b.mkv"))
        assertEquals(2, nextDirectRefusalCount(1, null, null))
    }

    @Test
    fun `the box counts as refusing after the second refusal`() {
        assertFalse(isDirectRefusedOnBox(0))
        assertFalse(isDirectRefusedOnBox(1))
        assertTrue(isDirectRefusedOnBox(2))
        assertTrue(isDirectRefusedOnBox(5))
    }

    @Test
    fun `software or another decoder after the request means the box refused`() {
        assertEquals(DirectDecodeOutcome.REFUSED, directDecodeOutcome("no", "mediacodec-copy"))
        assertEquals(DirectDecodeOutcome.REFUSED, directDecodeOutcome("no", ""))
    }
}
