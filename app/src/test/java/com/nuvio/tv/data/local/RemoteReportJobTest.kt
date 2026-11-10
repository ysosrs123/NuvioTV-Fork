package com.nuvio.tv.data.local

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteReportJobTest {
    @Test fun `the report job can be given to a spare key and survives saving`() {
        val map = RemoteButtonMap.DEFAULT.with(KeyEvent.KEYCODE_PROG_RED, RemoteButtonBinding(RemoteButtonJob.REPORT_MARK))
        val read = RemoteButtonMap.decode(map.encode())
        assertEquals(RemoteButtonJob.REPORT_MARK, read.overrideFor(KeyEvent.KEYCODE_PROG_RED)?.job)
        assertTrue(RemoteButtonJob.REPORT_MARK in RemoteButtonMap.jobsFor(KeyEvent.KEYCODE_PROG_RED))
        assertTrue(RemoteButtonJob.REPORT_MARK in RemoteButtonMap.jobsFor(KeyEvent.KEYCODE_PROG_RED, RemotePressKind.HOLD))
    }

    @Test fun `the saved name is stable and no key has the job unless it was chosen`() {
        assertEquals("report_mark", RemoteButtonJob.REPORT_MARK.id)
        assertNotNull(RemoteButtonJob.fromId("report_mark"))
        assertTrue(RemoteButtonMap.DEFAULTS.values.none { it.job == RemoteButtonJob.REPORT_MARK })
    }

    @Test fun `the report job can be a hold job too`() {
        val map = RemoteButtonMap.DEFAULT.with(
            KeyEvent.KEYCODE_PROG_BLUE, RemotePressKind.HOLD, RemoteButtonBinding(RemoteButtonJob.REPORT_MARK)
        )
        assertEquals(RemoteButtonJob.REPORT_MARK, RemoteButtonMap.decode(map.encode()).holdFor(KeyEvent.KEYCODE_PROG_BLUE)?.job)
    }
}
