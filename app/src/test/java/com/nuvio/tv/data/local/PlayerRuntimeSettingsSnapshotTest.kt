package com.nuvio.tv.data.local

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class PlayerRuntimeSettingsSnapshotTest {
    private val initial = PlayerSettings()
    private val custom = PlayerControlLayout.default().withVisibility(PlayerControlAction.PLAY_PAUSE, false)
    private fun snapshot(profile: Int = 1, settings: PlayerSettings = initial) = PlayerRuntimeSettingsSnapshot(profile, settings)
    @Test fun `save reorder hide and reset do not repeat playback side effects`() = runBlocking {
        val observed = flowOf(snapshot(), snapshot(settings = initial.copy(controlLayout = custom)),
            snapshot(settings = initial.copy(controlLayout = custom.withGroup(PlayerControlAction.AUDIO, PlayerControlGroup.CENTRE))),
            snapshot(settings = initial.copy(controlLayout = PlayerControlLayout.default())), snapshot())
            .playbackSettingsChanges().toList()
        assertEquals(listOf(snapshot()), observed)
    }
    @Test fun `identical profile values still initialize after switching away and back`() = runBlocking {
        val observed = flowOf(snapshot(), snapshot(2), snapshot()).playbackSettingsChanges().toList()
        assertEquals(listOf(1, 2, 1), observed.map { it.profileId })
    }
    @Test fun `subtitle and AFR changes survive mixed layout saves`() = runBlocking {
        val subtitle = initial.copy(controlLayout = custom, frameRateMatchingMode = FrameRateMatchingMode.START_STOP, subtitleStyle = initial.subtitleStyle.copy(preferredLanguage = "fr"))
        val afr = subtitle.copy(frameRateMatchingMode = FrameRateMatchingMode.OFF)
        val observed = flowOf(snapshot(), snapshot(settings = subtitle), snapshot(settings = afr),
            snapshot(settings = afr.copy(controlLayout = null))).playbackSettingsChanges().toList()
        assertEquals(listOf(snapshot(), snapshot(settings = subtitle), snapshot(settings = afr)), observed)
    }
    @Test fun `buffer audio engine and pause settings are never ignored as layout changes`() = runBlocking {
        val variants = listOf(initial, initial.copy(rememberAudioDelayPerDevice = !initial.rememberAudioDelayPerDevice),
            initial.copy(pauseOverlayEnabled = !initial.pauseOverlayEnabled), initial.copy(enableBufferLogs = !initial.enableBufferLogs),
            initial.copy(internalPlayerEngine = InternalPlayerEngine.MVP_PLAYER), initial.copy(bufferEngineEnabled = !initial.bufferEngineEnabled))
        val observed = variants.asFlow().map { snapshot(settings = it.copy(controlLayout = custom)) }.playbackSettingsChanges().toList()
        assertEquals(variants, observed.map { it.settings.copy(controlLayout = null) })
    }
    @Test fun `each observer lifetime initializes independently`() = runBlocking {
        val source = flowOf(snapshot(settings = initial.copy(controlLayout = custom)))
        assertEquals(1, source.playbackSettingsChanges().toList().size)
        assertEquals(1, source.playbackSettingsChanges().toList().size)
    }
    @Test fun `all settings emissions remain available for reports before playback filtering`() = runBlocking {
        val reports = mutableListOf<PlayerSettings>()
        val observed = flowOf(snapshot(), snapshot(settings = initial.copy(controlLayout = custom)), snapshot())
            .onEach { reports += it.settings }.playbackSettingsChanges().toList()
        assertEquals(3, reports.size); assertEquals(custom, reports[1].controlLayout); assertEquals(1, observed.size)
    }
}
