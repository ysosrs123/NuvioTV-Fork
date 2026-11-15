package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The before-play grid per file type and box. */
class PrepareGridTest {
    private val hd = PrepareGrid.LATTICE_1080P
    private val hdHeavy = PrepareGrid.LATTICE_1080P_HEAVY
    private val webStrong = PrepareGrid.LATTICE_4K_WEB
    private val webSmall = PrepareGrid.LATTICE_4K_WEB_SMALL
    private val remuxStrong = PrepareGrid.LATTICE_4K_REMUX_STRONG
    private val remuxSmall = PrepareGrid.LATTICE_4K_REMUX

    /** (base strides, 4K, remux) for every file type and box class the engine tells apart. */
    private val cases = listOf(
        Triple(hd, false, false), Triple(hdHeavy, false, false),
        Triple(webStrong, true, false), Triple(webSmall, true, false),
        Triple(remuxStrong, true, true), Triple(remuxSmall, true, true),
    )

    private fun screen(c: Triple<List<Int>, Boolean, Boolean>): Int =
        PrepareGrid.screenStride(c.second, c.third, c.first.last())

    private fun strides(c: Triple<List<Int>, Boolean, Boolean>): List<Int> =
        PrepareGrid.strides(c.first, screen(c))

    /** A two hour film: 720 slots of 10 s. */
    private val slots = 720

    @Test fun thePassPerFileAndBoxIsTheOneOfBefore() {
        assertEquals(listOf(30, 6, 3, 1), PrepareGrid.base(fourK = false, remux = false, strongBox = true, heavy1080p = false))
        assertEquals(listOf(30, 6, 3), PrepareGrid.base(fourK = false, remux = false, strongBox = false, heavy1080p = true))
        assertEquals(listOf(30, 6, 3, 1), PrepareGrid.base(fourK = true, remux = false, strongBox = true, heavy1080p = true))
        assertEquals(listOf(30, 6, 3), PrepareGrid.base(fourK = true, remux = false, strongBox = false, heavy1080p = true))
        assertEquals(listOf(30, 12, 6, 3), PrepareGrid.base(fourK = true, remux = true, strongBox = true, heavy1080p = true))
        assertEquals(listOf(30, 12, 6), PrepareGrid.base(fourK = true, remux = true, strongBox = false, heavy1080p = true))
    }

    @Test fun theScreenDoublesTheFullSpacing() {
        // 1080p and 4K web: every minute. 4K remux: every 2 minutes.
        for (c in cases.take(4)) assertEquals(6, screen(c))
        for (c in cases.drop(4)) assertEquals(12, screen(c))
        for (c in cases) assertEquals(PrepareGrid.fullStride(c.second, c.third, c.first.last()) * 2, screen(c))
    }

    @Test fun theScreenGridSitsInThePassAndTheFirstSweepIsAMultipleOfIt() {
        for (c in cases) {
            val screen = screen(c)
            val st = strides(c)
            assertTrue("$c: $st", screen in st)
            assertEquals("$c: $st", 0, st.first() % screen)
            assertEquals("$c: $st", st.sortedDescending().distinct(), st)
        }
        assertEquals(listOf(30, 6, 3, 1), strides(cases[0]))
        assertEquals(listOf(36, 12, 6, 3), strides(cases[4]))
        assertEquals(listOf(36, 12, 6), strides(cases[5]))
    }

    @Test fun theFillAfterPlayIsUnchanged() {
        for (c in cases) assertEquals("$c", c.first.last(), strides(c).last())
    }

    @Test fun aTwoHourFilmsTotalOnTheScreen() {
        fun total(c: Triple<List<Int>, Boolean, Boolean>): Int = (0 until slots).count { it % screen(c) == 0 }
        assertEquals(120, total(cases[0]))
        assertEquals(120, total(cases[2]))
        assertEquals(60, total(cases[4]))
        assertEquals(60, total(cases[5]))
    }

    /** What the pass makes before the screen's last grid slot: exactly the screen's total. */
    @Test fun thePassMakesNothingTheScreenDoesNotCount() {
        for (c in cases) {
            val screen = screen(c)
            val order = SeekGrid.latticeOrder(slots, strides(c), 0, 60, 120, gridFirst = screen)
            val work = PrepareHandover.workLeft(order, screen) { true }
            assertEquals("$c", (0 until slots).count { it % screen == 0 }, work)
        }
    }

    /** The screen comes up after the first frame: the pass had started on the fine window and switches to the grid. */
    @Test fun aLateScreenSwitchesThePassToTheGrid() {
        val screen = screen(cases[0])
        val st = strides(cases[0])
        val done = HashSet(SeekGrid.latticeOrder(slots, st, 0, 60, 120).take(15))
        assertEquals((0 until 15).toSet(), done)
        val order = SeekGrid.latticeOrder(slots, st, 0, 60, 120, gridFirst = screen)
        val left = order.filter { it !in done }
        assertEquals((30 until slots step 30).toList(), left.take(23))
        assertTrue(left.take(117).all { it % screen == 0 })
        assertEquals(120 - 3, PrepareHandover.workLeft(order, screen) { it !in done })
        assertEquals(slots, order.size)
    }

    /** The screen goes away (Start watching now, or a handover): the detail around the playhead comes next. */
    @Test fun aDismissedScreenSwitchesThePassBackToThePlayhead() {
        val screen = screen(cases[0])
        val st = strides(cases[0])
        val done = HashSet(SeekGrid.latticeOrder(slots, st, 0, 60, 120, gridFirst = screen).take(30))
        assertTrue(done.all { it % screen == 0 })
        val order = SeekGrid.latticeOrder(slots, st, 0, 60, 120)
        val left = order.filter { it !in done }
        assertEquals(listOf(1, 2, 3, 4, 5), left.take(5))
        assertTrue(left.take(100).all { it <= 120 })
        assertEquals(slots, order.size)
    }
}
