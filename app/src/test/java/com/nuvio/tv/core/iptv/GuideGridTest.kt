package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class GuideGridTest {
    private val minute = 60_000L
    private val base = 1_791_000_000_000L - 1_791_000_000_000L % (30 * minute)
    private fun at(minutes: Long) = base + minutes * minute
    private fun ts(minutes: Long, digits: Int = 14) = GuideTimestamp(at(minutes), digits, "fixture")
    private fun show(from: Long, to: Long?, title: String = "P$from", digits: Int = 14) =
        GuideProgramme("one", ts(from, digits), to?.let { ts(it, digits) }, listOf(LocalizedGuideText(title, null)), emptyList())
    private val window = GuideGridWindow(at(0), at(180))

    @Test fun windowSnapsToSlotsAndReportsFractions() {
        val w = GuideGridWindow.around(at(47) + 1234)
        assertEquals(at(30), w.startMillis); assertEquals(at(210), w.endMillis)
        assertEquals(0.5f, w.fractionAt(at(120)), 0.0001f); assertEquals(1f, w.fractionAt(at(999)), 0f)
        assertEquals(at(60), w.shifted(30 * minute).startMillis)
    }

    @Test fun programmesAreClampedAndGapsBecomeNoInformationCells() {
        val row = layoutGuideRow(listOf(show(-30, 30), show(60, 120), show(150, 240)), window)
        val shape = row.cells.map { it::class.simpleName to (it.startMillis - base) / minute to (it.endMillis - base) / minute }
        assertEquals(listOf("GuideProgrammeCell" to 0L to 30L, "GuideGapCell" to 30L to 60L, "GuideProgrammeCell" to 60L to 120L,
            "GuideGapCell" to 120L to 150L, "GuideProgrammeCell" to 150L to 180L), shape)
        val first = row.cells.first() as GuideProgrammeCell; val last = row.cells.last() as GuideProgrammeCell
        assertTrue(first.continuesBefore); assertFalse(first.continuesAfter); assertTrue(last.continuesAfter)
    }

    @Test fun overlapsTrimLaterStartsAndCoveredEntriesAreDropped() {
        val row = layoutGuideRow(listOf(show(0, 60, "A"), show(30, 90, "B"), show(40, 50, "C")), window)
        val programmes = row.cells.filterIsInstance<GuideProgrammeCell>()
        assertEquals(listOf("A", "B"), programmes.map { it.programme.titles.single().text })
        assertEquals(at(60), programmes[1].startMillis); assertTrue(programmes[1].continuesBefore)
        assertEquals(2, row.overlapped)
    }

    @Test fun missingStopRunsToNextStartAndIsMarkedOpenEnded() {
        val row = layoutGuideRow(listOf(show(0, null, "A"), show(45, 90, "B"), show(120, null, "C")), window)
        val cells = row.cells.filterIsInstance<GuideProgrammeCell>()
        assertEquals(at(45), cells[0].endMillis); assertTrue(cells[0].openEnded); assertFalse(cells[1].openEnded)
        assertEquals(at(180), cells[2].endMillis); assertTrue(cells[2].openEnded)
    }

    @Test fun imprecisePlacementIsCountedNotGuessedAndSliversAreAbsorbed() {
        val row = layoutGuideRow(listOf(show(0, 60, digits = 8), show(10, 59, "A"), show(59 + 0, 60, "B"), show(60, 180, "C")), window)
        assertEquals(1, row.unplaceable)
        assertTrue(row.cells.first() is GuideGapCell)
        assertTrue(row.cells.none { it.endMillis - it.startMillis < minute })
        assertEquals(at(180), row.cells.last().endMillis)
    }

    @Test fun emptyRowIsOneGapAndFocusKeepsTheTimeAnchor() {
        assertEquals(listOf(GuideGapCell(at(0), at(180))), layoutGuideRow(emptyList(), window).cells)
        val row = layoutGuideRow(listOf(show(0, 30), show(30, 120), show(120, 180)), window)
        assertEquals(1, row.indexAt(at(75))); assertEquals(0, row.indexAt(at(-5))); assertEquals(2, row.indexAt(at(500)))
        assertEquals(at(75), row.anchorFor(1, at(75))); assertEquals(at(120) - 1, row.anchorFor(1, at(150)))
        assertEquals(at(30), row.anchorFor(1, at(5)))
    }
}
