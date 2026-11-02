package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink

/** What the sound path looked like when an audio track opened. */
internal data class AudioStartState(
    val routeKey: String?,
    val sessionId: Int,
    val effectControlSessionId: Int?,
    val output: String,
    val tunnelled: Boolean,
    val tunnellingAsked: Boolean,
    val offload: Boolean,
    val outputTakes: Set<Int>?,
    val mediaVolume: Int?,
    val mediaVolumeMax: Int?,
    val mediaMuted: Boolean?,
    val nightMode: String,
    val dialogueBoost: String,
    val gainDb: Int
)

private val ENCODING_NAMES = linkedMapOf(
    C.ENCODING_AC3 to "AC3",
    C.ENCODING_E_AC3 to "EAC3",
    C.ENCODING_E_AC3_JOC to "EAC3-JOC",
    C.ENCODING_AC4 to "AC4",
    C.ENCODING_DOLBY_TRUEHD to "TrueHD",
    C.ENCODING_DTS to "DTS",
    C.ENCODING_DTS_HD to "DTS-HD",
    C.ENCODING_DTS_UHD_P2 to "DTS-UHD"
)

internal fun effectControlText(sessionId: Int, effectControlSessionId: Int?): String = when (effectControlSessionId) {
    null -> "none"
    sessionId -> "open"
    else -> "other-session($effectControlSessionId)"
}

internal fun audioStartLine(state: AudioStartState): String = buildString {
    append("AUDIO_START: route=").append(state.routeKey ?: "?")
    append(" session=").append(state.sessionId)
    append(" output=").append(state.output)
    append(" tunnel=").append(if (state.tunnelled) "on" else "off")
    append(" tunnelAsked=").append(if (state.tunnellingAsked) "on" else "off")
    append(" offload=").append(if (state.offload) "on" else "off")
    append(" outputTakes=")
    val takes = state.outputTakes
    if (takes == null) append("?") else append(ENCODING_NAMES.filterKeys { it in takes }.values.joinToString(",", "[", "]"))
    append(" mediaVolume=")
    if (state.mediaVolume == null) append("?") else append(state.mediaVolume).append('/').append(state.mediaVolumeMax ?: "?")
    append(" muted=").append(state.mediaMuted?.let { if (it) "yes" else "no" } ?: "?")
    append(" effectControl=").append(effectControlText(state.sessionId, state.effectControlSessionId))
    append(" appEffects=none")
    append(" night=").append(state.nightMode)
    append(" dialogue=").append(state.dialogueBoost)
    append(" gainDb=").append(state.gainDb)
}

internal fun PlayerRuntimeController.logAudioStart(
    player: ExoPlayer,
    config: AudioSink.AudioTrackConfig,
    output: String
) = logAudioStart(player, config.tunneling, config.offload, output)

internal fun PlayerRuntimeController.logAudioStart(
    player: ExoPlayer,
    tunnelled: Boolean,
    offload: Boolean,
    output: String
) {
    runCatching {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ui = _uiState.value
        val line = audioStartLine(
            AudioStartState(
                routeKey = currentAudioOutputRoute?.key,
                sessionId = player.audioSessionId,
                effectControlSessionId = effectControlSessionId,
                output = output,
                tunnelled = tunnelled,
                tunnellingAsked = isTunnelingActiveForCurrentPlayback,
                offload = offload,
                outputTakes = runCatching { PassthroughOutputReturn.liveEncodings(context) }.getOrNull(),
                mediaVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC),
                mediaVolumeMax = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                mediaMuted = audioManager?.let { runCatching { it.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrNull() },
                nightMode = ui.nightMode.name,
                dialogueBoost = ui.dialogueBoostState.name,
                gainDb = ui.audioAmplificationDb
            )
        )
        Log.i(PlayerRuntimeController.TAG, line)
        playbackAnalyticsDiagnostics.recordRawEventLine(line)
    }
}
