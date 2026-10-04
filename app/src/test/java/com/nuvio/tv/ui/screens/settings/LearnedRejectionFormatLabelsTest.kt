package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class LearnedRejectionFormatLabelsTest {
    @Test fun `formats are named once each in settings order whatever the route`() {
        val entries = setOf("type:hdmi::DTS_HD", "type:hdmi_arc::TRUEHD", "type:hdmi::TRUEHD", "type:hdmi::AC3")
        assertEquals("AC-3, TrueHD, DTS-HD", learnedRejectionFormatLabels(entries))
    }

    @Test fun `nothing learned and malformed entries give an empty label`() {
        assertEquals("", learnedRejectionFormatLabels(emptySet()))
        assertEquals("", learnedRejectionFormatLabels(setOf("DTS", "route::", "route::UNKNOWN")))
    }
}
