package com.nuvio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HeavySetupLimitsTest {
    @Test
    fun `a source with thousands of streams keeps its first ones in order`() {
        val few = (1..300).toList()
        assertSame(few, capStreamsPerSource(few))
        val many = (1..1671).toList()
        assertEquals((1..300).toList(), capStreamsPerSource(many))
        assertEquals(listOf(1, 2), capStreamsPerSource(many, limit = 2))
    }

    @Test
    fun `a huge catalogue answer is taken in slices`() {
        assertEquals(0, catalogPageTake(0))
        assertEquals(100, catalogPageTake(100))
        assertEquals(MAX_CATALOG_PAGE_ITEMS, catalogPageTake(21_859))
    }

    @Test
    fun `open files are summed up by kind, largest first`() {
        val targets = List(400) { "/dmabuf:" } + List(30) { "socket:[$it]" } + listOf("anon_inode:[eventpoll]", "/data/user/0/app/files/a", "/dev/null")
        assertEquals("picture buffer 400, connection 30, eventpoll 1, app file 1, device 1", OpenFilesGuard.kinds(targets))
    }

    @Test
    fun `small boxes keep a quarter of the styled subtitle cache`() {
        val small = com.nuvio.tv.ui.screens.player.assCacheConfig(smallBox = true)
        assertEquals(32, small.cacheSize)
        assertEquals(3_000, small.glyphSize)
        assertEquals(com.nuvio.tv.libass.media.AssHandlerConfig(), com.nuvio.tv.ui.screens.player.assCacheConfig(smallBox = false))
    }
}
