package com.nuvio.tv.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kodi-model downmix gate: effectiveDownmixEnabled arms whenever the FFmpeg
 * renderer exists (decoderPriority != 0), not only under Prefer (== 2).
 */
class PlayerSettingsDownmixGateTest {

    @Test
    fun `disabled toggle is never effective regardless of priority`() {
        for (priority in 0..2) {
            assertFalse(
                "priority=$priority",
                PlayerSettings(downmixEnabled = false, decoderPriority = priority)
                    .effectiveDownmixEnabled
            )
        }
    }

    @Test
    fun `device only priority never arms downmix`() {
        assertFalse(
            PlayerSettings(downmixEnabled = true, decoderPriority = 0)
                .effectiveDownmixEnabled
        )
    }

    @Test
    fun `extension on arms downmix`() {
        assertTrue(
            PlayerSettings(downmixEnabled = true, decoderPriority = 1)
                .effectiveDownmixEnabled
        )
    }

    @Test
    fun `prefer app decoders arms downmix`() {
        assertTrue(
            PlayerSettings(downmixEnabled = true, decoderPriority = 2)
                .effectiveDownmixEnabled
        )
    }
}
