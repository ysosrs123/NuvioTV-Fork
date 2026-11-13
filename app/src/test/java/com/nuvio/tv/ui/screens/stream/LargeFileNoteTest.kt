package com.nuvio.tv.ui.screens.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeFileNoteTest {
    private val gb = 1024L * 1024L * 1024L

    @Test
    fun smallBoxIsUnderOneAndAHalfGigabytes() {
        assertTrue(LargeFileNote.isSmallBox(1_400_000_000L))
        assertFalse(LargeFileNote.isSmallBox(1_900_000_000L))
        assertFalse(LargeFileNote.isSmallBox(0L))
    }

    @Test
    fun bitrateComesFromSizeAndRunningTime() {
        assertEquals(40_000_000L, LargeFileNote.bitsPerSecond(18_000_000_000L, 60))
        assertNull(LargeFileNote.bitsPerSecond(null, 120))
        assertNull(LargeFileNote.bitsPerSecond(10 * gb, null))
    }

    @Test
    fun onlyFilesAboveFortyMegabitsGetTheNote() {
        assertNull(LargeFileNote.kindFor(listOf("Dune 2160p WEB"), 20 * gb, 166))
        assertEquals(LargeFileKind.Uhd, LargeFileNote.kindFor(listOf("Dune.2160p.REMUX"), 78 * gb, 166))
        assertEquals(LargeFileKind.Uhd, LargeFileNote.kindFor(listOf("4K", "Dune Remux"), 78 * gb, 166))
        assertEquals(LargeFileKind.Other, LargeFileNote.kindFor(listOf("Dune.1080p.BluRay.REMUX"), 40 * gb, 120))
        assertNull(LargeFileNote.kindFor(listOf("Dune.1080p.REMUX"), 31 * gb, null))
    }

    @Test
    fun fourKInsideAWordIsNotUhd() {
        assertEquals(LargeFileKind.Other, LargeFileNote.kindFor(listOf("Pack4Kids 1080p"), 40 * gb, 120))
    }
}
