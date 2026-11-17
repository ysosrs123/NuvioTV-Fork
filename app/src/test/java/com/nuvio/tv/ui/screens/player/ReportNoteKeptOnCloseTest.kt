package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportNoteKeptOnCloseTest {
    @Test
    fun `typed text is kept when the box closes without Save`() {
        assertEquals("Stutters every few minutes", noteKeptOnClose("  Stutters every few minutes ", ""))
        assertEquals("longer note", noteKeptOnClose("longer note", "short"))
    }

    @Test
    fun `nothing new typed closes as before`() {
        assertNull(noteKeptOnClose("", ""))
        assertNull(noteKeptOnClose("   ", ""))
        assertNull(noteKeptOnClose("same note ", "same note"))
    }
}
