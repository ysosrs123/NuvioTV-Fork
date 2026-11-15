package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.data.repository.ReportStatus
import com.nuvio.tv.data.repository.SentReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyReportsStepsTest {
    private fun report(status: ReportStatus, vararg seen: Pair<ReportStatus, Long>, fixedIn: String? = null) =
        SentReport("ARC-4MFX", "playback", 100L, token = "abcd2345efgh", status = status, fixedIn = fixedIn, seenAt = seen.toMap())

    private fun List<ReportStepLine>.shape() = map { Triple(it.status, it.atMs, it.reached) }

    @Test fun `an open report shows the usual path with later steps not reached`() {
        assertEquals(
            listOf(
                Triple(ReportStatus.Sent, 100L, true),
                Triple(ReportStatus.Read, 200L, true),
                Triple(ReportStatus.CauseFound, null, false),
                Triple(ReportStatus.Fixed, null, false)
            ),
            reportSteps(report(ReportStatus.Read, ReportStatus.Read to 200L)).shape()
        )
    }

    @Test fun `a fixed report shows every step done, dated where Arc saw it`() {
        assertEquals(
            listOf(
                Triple(ReportStatus.Sent, 100L, true),
                Triple(ReportStatus.Read, null, true),
                Triple(ReportStatus.CauseFound, 300L, true),
                Triple(ReportStatus.Fixed, 400L, true)
            ),
            reportSteps(report(ReportStatus.Fixed, ReportStatus.CauseFound to 300L, ReportStatus.Fixed to 400L)).shape()
        )
    }

    @Test fun `not caused by Arc ends the path, cause found only when it was seen`() {
        assertEquals(
            listOf(ReportStatus.Sent, ReportStatus.Read, ReportStatus.Outside),
            reportSteps(report(ReportStatus.Outside, ReportStatus.Outside to 500L)).map { it.status }
        )
        assertEquals(
            listOf(ReportStatus.Sent, ReportStatus.Read, ReportStatus.CauseFound, ReportStatus.Closed),
            reportSteps(report(ReportStatus.Closed, ReportStatus.CauseFound to 300L)).map { it.status }
        )
    }

    @Test fun `a deleted report keeps what was seen and ends with deleted`() {
        assertEquals(
            listOf(ReportStatus.Sent, ReportStatus.Read, ReportStatus.Gone),
            reportSteps(report(ReportStatus.Gone, ReportStatus.Read to 200L, ReportStatus.Gone to 900L)).map { it.status }
        )
    }

    @Test fun `the update line shows only when the fix is newer than the installed Arc`() {
        val fixed = report(ReportStatus.Fixed, fixedIn = "1.2.0-arc.1")
        assertTrue(fixComesWithUpdate(fixed, installed = "1.1.0-arc.4"))
        assertTrue(fixComesWithUpdate(report(ReportStatus.Fixed, fixedIn = "1.1.0-arc.4"), installed = "1.1.0-arc.3.1"))
        assertFalse(fixComesWithUpdate(fixed, installed = "1.2.0-arc.1"))
        assertFalse(fixComesWithUpdate(fixed, installed = "1.2.0-arc.2"))
        assertFalse(fixComesWithUpdate(report(ReportStatus.Fixed), installed = "1.1.0-arc.4"))
        assertFalse(fixComesWithUpdate(report(ReportStatus.Read, fixedIn = "1.2.0-arc.1"), installed = "1.1.0-arc.4"))
    }
}
