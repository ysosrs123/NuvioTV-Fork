package com.nuvio.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateInstallLogTest {
    @Test fun `keeps only the newest steps`() {
        var stored: String? = null
        listOf("a", "b", "c", "d").forEach { stored = UpdateInstallLog.append(stored, it, keep = 3) }
        assertEquals("b\nc\nd", stored)
    }

    @Test fun `blank stored lines are dropped`() {
        assertEquals("x\ny", UpdateInstallLog.append("\nx\n\n", "y"))
    }
}
