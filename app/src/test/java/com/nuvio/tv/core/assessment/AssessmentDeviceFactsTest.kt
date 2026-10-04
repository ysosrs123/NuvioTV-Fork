package com.nuvio.tv.core.assessment
import android.view.Display
import com.nuvio.tv.core.player.*
import com.nuvio.tv.ui.screens.player.AudioOutputRoute
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class AssessmentDeviceFactsTest {
    private fun mode(id: Int, width: Int = 1920, rate: Float = 60f) = mockk<Display.Mode> {
        every { modeId } returns id; every { physicalWidth } returns width
        every { physicalHeight } returns 1080; every { refreshRate } returns rate
    }
    private fun policy(dv: Boolean = false) = DolbyVisionBaseLayerPolicy.resolveFromCapabilities(
        true, dv, true, false, false, false, false, false, false, false, false, true, 33)
    private fun facts(safe: Int = 250, current: Int = 1, modes: List<Display.Mode> = listOf(mode(1), mode(2, rate = 24f)),
        dv: Boolean = false, route: AudioOutputRoute? = AudioOutputRoute("type:hdmi|name:tv", "TV", false),
        native: Boolean = false, cache: Long = 1_000) = AssessmentDeviceFacts(safe, 500,
            DisplayCapabilities.Snapshot(true,false,modes,current,true), policy(dv), route, native, cache)
    @Test fun `equivalent modes compare by values across new instances and order`() {
        assertTrue(facts().sameAs(facts(modes = listOf(mode(2, rate = 24f), mode(1)))))
    }
    @Test fun `memory suitability must remain current`() { assertFalse(facts().sameAs(facts(safe = 125))) }
    @Test fun `current mode and mode capabilities changes expire facts`() {
        assertFalse(facts().sameAs(facts(current = 2))); assertFalse(facts().sameAs(facts(modes = listOf(mode(1,3840)))))
    }
    @Test fun `HDR capability change expires facts`() { assertFalse(facts().sameAs(facts(dv = true))) }
    @Test fun `audio route change expires facts`() {
        assertFalse(facts().sameAs(facts(route = AudioOutputRoute("type:hdmi_arc|name:tv", "TV", false))))
    }
    @Test fun `unavailable audio route is not substituted with a known route`() {
        assertFalse(facts().sameAs(facts(route = null)))
        assertTrue(facts(route = null).sameAs(facts(route = null)))
    }
    @Test fun `native suitability and useful cache availability change but raw free bytes do not`() {
        assertFalse(facts().sameAs(facts(native = true))); assertFalse(facts().sameAs(facts(cache = 0)))
        assertTrue(facts().sameAs(facts(cache = 2_000)))
    }
}
