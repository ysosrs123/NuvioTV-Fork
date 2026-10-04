package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import com.nuvio.tv.core.player.AudioPassthroughPolicy

/** Main-thread-owned recovery identity. Reconstruction preserves the attempt budget. */
internal class PlaybackRecoveryGate {
    var generation: Long = 0; private set
    private var url: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var audioAttempts = 0
    private val openedAudioGroups = mutableSetOf<AudioPassthroughPolicy.Group>()
    private var lastAudioSinkErrorAtMs = Long.MIN_VALUE
    fun begin(url: String, headers: Map<String, String>): Boolean {
        val changed = this.url != url || this.headers != headers
        generation++
        if (changed) resetPlaybackAudioState()
        this.url = url
        this.headers = headers.toMap()
        return changed
    }
    fun cancel(resetIdentity: Boolean = false) {
        generation++
        if (resetIdentity) {
            url = null
            headers = emptyMap()
            resetPlaybackAudioState()
        }
    }
    fun isCurrent(generation: Long, url: String, headers: Map<String, String>) =
        this.generation == generation && this.url == url && this.headers == headers
    fun canRetryAudio() = audioAttempts < 2
    fun nextAudioDelayMs(): Long? = if (canRetryAudio()) 400L * ++audioAttempts else null

    fun noteAudioOutputOpened(group: AudioPassthroughPolicy.Group?) {
        if (group != null) openedAudioGroups += group
    }

    /** A format that already opened in this playback was not refused by the receiver; a later failure is transient. */
    fun shouldLearnAudioRejection(group: AudioPassthroughPolicy.Group?): Boolean =
        group != null && group !in openedAudioGroups

    fun noteAudioSinkError(atMs: Long) {
        lastAudioSinkErrorAtMs = atMs
    }

    /** Returns true when a spent audio retry allowance was restored after a stretch of clean playback. */
    fun noteStablePlayback(playingSinceMs: Long, nowMs: Long): Boolean {
        if (audioAttempts == 0) return false
        val stableSinceMs = maxOf(playingSinceMs, lastAudioSinkErrorAtMs)
        if (nowMs - stableSinceMs < AUDIO_RETRY_STABLE_RESET_MS) return false
        audioAttempts = 0
        return true
    }

    private fun resetPlaybackAudioState() {
        audioAttempts = 0
        openedAudioGroups.clear()
        lastAudioSinkErrorAtMs = Long.MIN_VALUE
    }

    companion object {
        const val AUDIO_RETRY_STABLE_RESET_MS = 60_000L
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun passthroughGroupOfEncoding(encoding: Int): AudioPassthroughPolicy.Group? = when (encoding) {
    C.ENCODING_AC3 -> AudioPassthroughPolicy.Group.AC3
    C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC -> AudioPassthroughPolicy.Group.EAC3
    C.ENCODING_DOLBY_TRUEHD -> AudioPassthroughPolicy.Group.TRUEHD
    C.ENCODING_DTS -> AudioPassthroughPolicy.Group.DTS
    C.ENCODING_DTS_HD -> AudioPassthroughPolicy.Group.DTS_HD
    else -> null
}
