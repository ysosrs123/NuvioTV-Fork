package com.nuvio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class SentReportsListTest {
    private fun report(n: Int) = SentReport("ARC-%04d".format(n), "bug", n.toLong())

    @Test fun `the newest report comes first`() {
        val list = SentReportsList.added(listOf(report(1)), report(2))
        assertEquals(listOf(report(2), report(1)), list)
    }

    @Test fun `only twenty are kept`() {
        val list = (1..25).fold(emptyList<SentReport>()) { kept, n -> SentReportsList.added(kept, report(n)) }
        assertEquals(20, list.size)
        assertEquals(report(25), list.first())
        assertEquals(report(6), list.last())
    }

    @Test fun `the same code is not listed twice`() {
        assertEquals(1, SentReportsList.added(listOf(report(1)), report(1).copy(timeMs = 99L)).size)
    }

    @Test fun `a list survives saving and reading`() {
        val list = listOf(report(3), SentReport("ARC-ABCD", "playback", 1762560000000L), SentReport("ARC-EFGH", "idea", 5L))
        assertEquals(list, SentReportsList.decode(SentReportsList.encode(list)))
    }

    @Test fun `damaged lines and nothing saved read as no reports`() {
        assertEquals(emptyList<SentReport>(), SentReportsList.decode(null))
        assertEquals(emptyList<SentReport>(), SentReportsList.decode("nonsense\nARC-1\tbug\tnot-a-time"))
    }

    @Test fun `spaces and new lines cannot get into a saved row`() {
        val saved = SentReportsList.added(emptyList(), SentReport("ARC\t-1\n", "bu g", 7L))
        assertEquals(saved, SentReportsList.decode(SentReportsList.encode(saved)))
        assertEquals("ARC-1", saved.single().code)
    }
}
