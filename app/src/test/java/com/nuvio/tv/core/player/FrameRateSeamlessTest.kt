package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.FrameRateUtils.DisplayModeSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameRateSeamlessTest {

    private val film = 24000f / 1001f

    private val modes = listOf(
        mode(1, 3840, 2160, 60f),
        mode(2, 3840, 2160, 59.94f),
        mode(3, 3840, 2160, 50f),
        mode(4, 3840, 2160, 48f),
        mode(5, 3840, 2160, 47.952f),
        mode(6, 3840, 2160, 30f),
        mode(7, 3840, 2160, 25f),
        mode(8, 3840, 2160, 24f),
        mode(9, 3840, 2160, 23.976f),
        mode(10, 1920, 1080, 60f),
        mode(11, 1920, 1080, 50f),
        mode(12, 1920, 1080, 48f),
        mode(13, 1920, 1080, 24f),
        mode(14, 1920, 1080, 23.976f)
    )

    private val qms4k60 = modes[0].copy(alternativeRefreshRates = listOf(48f, 47.952f, 50f, 59.94f))
    private val plain4k60 = modes[0]

    @Test
    fun `qms display picks 48 Hz for a 24 fps film in 4K`() {
        assertEquals(48f, pick(qms4k60, 24f), 0.01f)
        assertEquals(47.952f, pick(qms4k60, film), 0.01f)
    }

    @Test
    fun `qms display picks 48 Hz with resolution matching at the active size`() {
        assertEquals(48f, pick(qms4k60, 24f, 3840, 2160, resolutionMatching = true), 0.01f)
    }

    @Test
    fun `without alternative rates a 24 fps film still switches to 24 Hz`() {
        assertEquals(24f, pick(plain4k60, 24f), 0.01f)
        assertEquals(23.976f, pick(plain4k60, film), 0.01f)
        assertEquals(24f, pick(plain4k60, 24f, 3840, 2160, resolutionMatching = true), 0.01f)
    }

    @Test
    fun `25 fps takes 50 Hz with qms and without`() {
        assertEquals(50f, pick(qms4k60, 25f), 0.01f)
        assertEquals(50f, pick(plain4k60, 25f), 0.01f)
    }

    @Test
    fun `resolution matching to another size is unchanged`() {
        val modeId = FrameRateUtils.selectDisplayMode(modes, qms4k60, 24f, 1920, 1080, true)?.modeId
        assertEquals(13, modeId)
    }

    @Test
    fun `seamless list without a clean cadence keeps the hard switch`() {
        val active = modes[0].copy(alternativeRefreshRates = listOf(59.94f))
        assertEquals(24f, pick(active, 24f), 0.01f)
    }

    private fun pick(
        active: DisplayModeSpec,
        frameRate: Float,
        videoWidth: Int? = null,
        videoHeight: Int? = null,
        resolutionMatching: Boolean = false
    ): Float {
        val table = modes.map { if (it.modeId == active.modeId) active else it }
        return FrameRateUtils.selectDisplayMode(table, active, frameRate, videoWidth, videoHeight, resolutionMatching)!!
            .refreshRate
    }

    private fun mode(id: Int, width: Int, height: Int, refreshRate: Float) =
        DisplayModeSpec(id, width, height, refreshRate)
}
