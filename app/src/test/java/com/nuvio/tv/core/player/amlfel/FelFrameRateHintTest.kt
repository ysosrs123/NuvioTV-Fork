package com.nuvio.tv.core.player.amlfel

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class FelFrameRateHintTest {
    private val calls = mutableListOf<Pair<String, Float>>()
    private val hint = FelFrameRateHint<String> { output, rate -> calls += output to rate }

    private fun playing(strategy: Int = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS) {
        hint.onStrategy(strategy)
        hint.onOutput("surface")
        hint.onFrameRate(23.976f)
        hint.onStarted()
    }

    @Test fun `seamless strategy hints the film rate while playing`() {
        playing()
        assertEquals(listOf("surface" to 23.976f), calls)
    }

    @Test fun `strategy off never hints, Arc's own matching is doing the switch`() {
        playing(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        assertEquals(emptyList<Pair<String, Float>>(), calls)
    }

    @Test fun `pause clears the hint and play sets it again`() {
        playing()
        hint.onStopped()
        hint.onStarted()
        assertEquals(listOf("surface" to 23.976f, "surface" to 0f, "surface" to 23.976f), calls)
    }

    @Test fun `a new surface clears the old one and gets the hint`() {
        playing()
        hint.onOutput("other")
        assertEquals(listOf("surface" to 23.976f, "surface" to 0f, "other" to 23.976f), calls)
    }

    @Test fun `no surface or unknown rate sends nothing`() {
        hint.onStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
        hint.onFrameRate(23.976f)
        hint.onStarted()
        hint.onOutput("surface")
        hint.onFrameRate(Float.NaN)
        hint.onFrameRate(-1f)
        assertEquals(listOf("surface" to 23.976f, "surface" to 0f), calls)
    }

    @Test fun `switching the strategy off mid-play clears the hint`() {
        playing()
        hint.onStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        hint.onOutput(null)
        assertEquals(listOf("surface" to 23.976f, "surface" to 0f), calls)
    }
}
