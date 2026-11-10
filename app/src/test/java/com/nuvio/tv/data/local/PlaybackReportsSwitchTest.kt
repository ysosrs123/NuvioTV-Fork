package com.nuvio.tv.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReportsSwitchTest {
    @Test fun `on by default when the build can send reports`() {
        assertTrue(playbackReportsSwitch(stored = null, reportsAvailable = true))
    }

    @Test fun `a viewer's explicit off is kept`() {
        assertFalse(playbackReportsSwitch(stored = false, reportsAvailable = true))
    }

    @Test fun `off without a report server`() {
        assertFalse(playbackReportsSwitch(stored = null, reportsAvailable = false))
    }

    @Test fun `an explicit on is kept`() {
        assertTrue(playbackReportsSwitch(stored = true, reportsAvailable = true))
    }
}
