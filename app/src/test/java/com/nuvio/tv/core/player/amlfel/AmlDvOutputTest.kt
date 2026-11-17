package com.nuvio.tv.core.player.amlfel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AmlDvOutputTest {

    @Test
    fun `the row shows only when the switch exists and the TV takes Dolby Vision`() {
        assertTrue(AmlDvOutput.rowShown(available = true, tvTakesDv = true))
        assertFalse(AmlDvOutput.rowShown(available = true, tvTakesDv = false))
        assertFalse(AmlDvOutput.rowShown(available = false, tvTakesDv = true))
    }

    @Test
    fun `nothing shows before the checks have answered`() {
        assertFalse(AmlDvOutput.rowShown(available = null, tvTakesDv = null))
        assertFalse(AmlDvOutput.rowShown(available = true, tvTakesDv = null))
    }

    @Test
    fun `the kernel gets Y for the box and N for the TV`() {
        assertEquals("Y", AmlDvOutput.paramValue(boxHdr10 = true))
        assertEquals("N", AmlDvOutput.paramValue(boxHdr10 = false))
    }

    @Test
    fun `off the FEL kernel the switch is never available`() {
        val param = File.createTempFile("am9_dv_out_hdr10", null).apply { deleteOnExit() }
        assertFalse(AmlDvOutput.probe(param))
    }

    @Test
    fun `apply does nothing where the switch is missing`() {
        AmlDvOutput.apply(boxHdr10 = true)
        assertFalse(AmlDvOutput.isAvailable())
    }
}
