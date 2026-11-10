package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.PlayerControlAction
import com.nuvio.tv.data.local.PlayerControlLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerControlReportsUnavailableTest {
    private val report = PlayerControlAction.REPORT
    private val vod = PlaybackTimelineState(duration = 90_000)

    private fun available(state: PlayerUiState, reports: Boolean) =
        playerControlAvailability(state, vod, nativeVideoSelected = false, appliedTunneling = false, reportsAvailable = reports)

    @Test fun `report control stays hidden without a report server even when the switch is on`() {
        for (status in PlaybackIssueReportStatus.entries) {
            val state = PlayerUiState(playbackIssueReportsEnabled = true, playbackIssueReportStatus = status)
            assertFalse(report in available(state, reports = false))
        }
        assertTrue(report in available(PlayerUiState(playbackIssueReportsEnabled = true), reports = true))
    }

    @Test fun `a saved report leaves no dead report control behind`() {
        val state = PlayerUiState(playbackIssueReportsEnabled = true, playbackIssueReportStatus = PlaybackIssueReportStatus.Saved)
        assertFalse(report in available(state, reports = true))
    }

    @Test fun `arrangeable list leaves out the report control only when reports are unavailable`() {
        assertEquals(PlayerControlAction.entries.toSet() - report, PlayerControlAction.offered(reportsAvailable = false))
        assertEquals(PlayerControlAction.entries.toSet(), PlayerControlAction.offered(reportsAvailable = true))
    }

    @Test fun `saved layout with a visible report control skips it without an empty slot and keeps the saved value`() {
        val saved = PlayerControlLayout.default().encode()
        assertTrue(saved.contains("report,"))
        val layout = PlayerControlLayout.decode(saved)!!
        assertEquals(saved, layout.encode())
        val state = PlayerUiState(playbackIssueReportsEnabled = true)
        val withReports = layout.focusOrder(available(state, reports = true))
        val withoutReports = layout.focusOrder(available(state, reports = false))
        assertTrue(report in withReports)
        assertEquals(withReports - report, withoutReports)
    }

    @Test fun `editor preview has no report cell when the build cannot offer it`() {
        val offered = PlayerControlAction.offered(reportsAvailable = false)
        val layout = PlayerControlLayout.original(true)
        val plan = playerControlPrimaryDeckPlan(layout, offered, offered.associateWith { PlayerControlDeckSize(40, 20) },
            320, 10, true, 15)
        assertFalse(plan.cells.any { it.action == report })
        assertEquals(offered, plan.cells.map { it.action }.toSet())
    }
}
