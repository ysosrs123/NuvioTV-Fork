package com.nuvio.tv.ui.screens.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExoAudioFocusPolicyTest {

    private val folder = File(
        listOf(File("src/main"), File("app/src/main")).first { it.isDirectory },
        "java/com/nuvio/tv/ui/screens/player"
    )

    @Test
    fun `the player is built with audio focus off`() {
        val build = File(folder, "PlayerRuntimeControllerInitialization.kt").readText()
        assertTrue(build.contains("setAudioAttributes(audioAttributes, false)"))
    }

    @Test
    fun `screen pause and resume never touch audio focus`() {
        assertFalse(File(folder, "PlayerRuntimeControllerMpv.kt").readText().contains("setAudioAttributes"))
    }
}
