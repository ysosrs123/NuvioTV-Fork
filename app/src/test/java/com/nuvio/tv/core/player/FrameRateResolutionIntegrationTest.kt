package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameRateResolutionIntegrationTest {
    private val modes = listOf(720 to 480, 1280 to 720, 1920 to 1080, 3840 to 2160)

    @Test fun `4 by 3 full HD stays at full HD rather than downscaling to 720p`() {
        assertEquals(listOf(1920 to 1080), FrameRateUtils.selectResolutionCandidates(modes, 1440, 1080))
    }

    @Test fun `SD content retains the fork 720p output floor`() {
        assertEquals(listOf(1280 to 720), FrameRateUtils.selectResolutionCandidates(modes, 720, 480))
    }

    @Test fun `ultrawide source requires a mode that fits both dimensions`() {
        assertEquals(listOf(3840 to 2160), FrameRateUtils.selectResolutionCandidates(modes, 2560, 1080))
    }

    @Test fun `content larger than display uses largest supported mode`() {
        assertEquals(listOf(3840 to 2160), FrameRateUtils.selectResolutionCandidates(modes, 7680, 4320))
    }

    @Test fun `SD only display keeps its available mode`() {
        assertEquals(listOf(720 to 480), FrameRateUtils.selectResolutionCandidates(listOf(720 to 480), 1920, 1080))
    }

    @Test fun `rotated dimensions select the same resolution family`() {
        assertEquals(listOf(1920 to 1080), FrameRateUtils.selectResolutionCandidates(modes, 1080, 1440))
    }
}
