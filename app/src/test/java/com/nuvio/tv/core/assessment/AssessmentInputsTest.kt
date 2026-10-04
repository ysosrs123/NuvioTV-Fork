package com.nuvio.tv.core.assessment
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.data.local.*
import org.junit.Assert.*
import org.junit.Test

class AssessmentInputsTest {
    private val settings = PlayerSettings()
    private val source = LastPlaybackDiagnostics(timestampMs = 100, streamUrl = "https://example.invalid/private-token", headersJson = "secret", videoBitrate = 40_000_000, durationMs = 120_000)
    @Test fun `equivalent input values match distinct instances`() {
        assertTrue(AssessmentInputs.capture(settings, source).matches(settings.copy(), source.copy()))
    }
    @Test fun `settings that ground recommendations expire previous inputs`() {
        val changes = listOf(settings.copy(enableHttp2 = !settings.enableHttp2),
            settings.copy(internalPlayerEngine = InternalPlayerEngine.MVP_PLAYER),
            settings.copy(parallelConnectionCount = settings.parallelConnectionCount + 1),
            settings.copy(vodCacheSizeMb = settings.vodCacheSizeMb + 1),
            settings.copy(forceOpticalPassthrough = !settings.forceOpticalPassthrough),
            settings.copy(tunnelingEnabled = !settings.tunnelingEnabled))
        val captured = AssessmentInputs.capture(settings, source)
        changes.forEach { assertFalse(captured.matches(it, source)) }
    }
    @Test fun `audio format settings do not expire inputs`() {
        val captured = AssessmentInputs.capture(settings, source)
        listOf(settings.copy(allowTruehdPassthrough = !settings.allowTruehdPassthrough),
            settings.copy(allowDtshdPassthrough = !settings.allowDtshdPassthrough),
            settings.copy(deniedCodecHandling = DeniedCodecHandling.TRANSCODE_AC3),
            settings.copy(surroundFormatMode = SurroundFormatMode.MANUAL))
            .forEach { assertTrue(captured.matches(it, source)) }
    }
    @Test fun `rewind and startup buffer intent changes expire inputs`() {
        val captured = AssessmentInputs.capture(settings, source)
        assertFalse(captured.matches(settings.copy(bufferSettings = settings.bufferSettings.copy(backBufferDurationMs = 19_000)), source))
        assertFalse(captured.matches(settings.copy(bufferSettings = settings.bufferSettings.copy(bufferForPlaybackMs = 17_000)), source))
    }
    @Test fun `source renewal and authorization changes expire inputs`() {
        val captured = AssessmentInputs.capture(settings, source)
        listOf(source.copy(timestampMs = 101), source.copy(streamUrl = "https://example.invalid/new-token"), source.copy(headersJson = "new-secret"))
            .forEach { assertFalse(captured.matches(settings, it)) }
    }
    @Test fun `bitrate duration and format changes expire inputs`() {
        val captured = AssessmentInputs.capture(settings, source)
        listOf(source.copy(videoBitrate = 80_000_000), source.copy(durationMs = 240_000), source.copy(videoHdrType = "Dolby Vision"))
            .forEach { assertFalse(captured.matches(settings, it)) }
    }
    @Test fun `unrelated playback outcome counters do not expire source evidence`() {
        assertTrue(AssessmentInputs.capture(settings, source).matches(settings, source.copy(rebufferCount = 5, result = "Played", resolvedServingHost = "cdn.example.invalid")))
    }
    @Test fun `provenance string never includes private source or headers`() {
        val label = AssessmentInputs.capture(settings, source).toString()
        assertFalse(label.contains("private-token")); assertFalse(label.contains("secret")); assertFalse(label.contains("example.invalid"))
    }
}
