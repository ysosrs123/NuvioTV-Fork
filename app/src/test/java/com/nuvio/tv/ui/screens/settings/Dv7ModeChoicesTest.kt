package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.data.local.Dv7HandlingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Dv7ModeChoicesTest {

    @Test
    fun `native FEL is offered only on the FEL kernel`() {
        assertTrue(Dv7HandlingMode.NATIVE_FEL in dv7ModeChoices(true))
        assertFalse(Dv7HandlingMode.NATIVE_FEL in dv7ModeChoices(false))
        assertFalse(Dv7HandlingMode.NATIVE_FEL in dv7ModeChoices(null))
    }

    @Test
    fun `the other choices stay in their order everywhere`() {
        val others = listOf(
            Dv7HandlingMode.AUTO, Dv7HandlingMode.HDR10_BASE_LAYER, Dv7HandlingMode.DV81_LIBDOVI,
            Dv7HandlingMode.STRIP_DV, Dv7HandlingMode.OFF
        )
        assertEquals(others, dv7ModeChoices(false))
        assertEquals(others + Dv7HandlingMode.NATIVE_FEL, dv7ModeChoices(true))
    }

    @Test
    fun `a saved native FEL shows as Auto off the FEL kernel`() {
        assertEquals(Dv7HandlingMode.AUTO, dv7ModeShown(Dv7HandlingMode.NATIVE_FEL, false))
        assertEquals(Dv7HandlingMode.NATIVE_FEL, dv7ModeShown(Dv7HandlingMode.NATIVE_FEL, true))
    }

    @Test
    fun `before the check has run the saved choice is shown as it is`() {
        assertEquals(Dv7HandlingMode.NATIVE_FEL, dv7ModeShown(Dv7HandlingMode.NATIVE_FEL, null))
    }

    @Test
    fun `other saved choices are never changed`() {
        for (mode in Dv7HandlingMode.entries.filter { it != Dv7HandlingMode.NATIVE_FEL }) {
            assertEquals(mode, dv7ModeShown(mode, false))
            assertEquals(mode, dv7ModeShown(mode, true))
        }
    }
}
