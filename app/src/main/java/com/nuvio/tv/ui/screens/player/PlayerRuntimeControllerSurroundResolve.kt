package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.util.Log
import androidx.media3.common.Player
import com.nuvio.tv.core.player.DeniedTranscodePlanner
import com.nuvio.tv.core.player.SurroundFormatResolver
import com.nuvio.tv.data.local.AudioOutputChannels
import com.nuvio.tv.data.local.DeniedCodecHandling
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.SurroundChannelTarget
import com.nuvio.tv.data.local.SurroundFormatMode
import kotlinx.coroutines.delay

internal data class SurroundResolveInputs(
    val routeKey: String?,
    val isBluetooth: Boolean,
    val softwareDecodersAvailable: Boolean,
    val forceOpticalActive: Boolean,
    val effectiveDownmixEnabled: Boolean,
    val effectiveAudioOutputChannels: AudioOutputChannels
)

internal data class SurroundResolveResult(
    val resolution: SurroundFormatResolver.Resolution,
    val targetChannels: Int?,
    val downmixEnabled: Boolean,
    val audioOutputChannels: AudioOutputChannels,
    val deniedTranscodeMimes: Set<String>
)

internal fun resolveSurroundForRoute(
    context: Context,
    playerSettings: PlayerSettings,
    inputs: SurroundResolveInputs
): SurroundResolveResult {
    val currentRouteKey = inputs.routeKey
    val surroundResolution = if (inputs.isBluetooth) {
        SurroundFormatResolver.Resolution.INERT
    } else {
        val chainSnapshot = AudioChainProbe.snapshot(context, currentRouteKey)
        val learnedDeniedGroups = AudioRejectionReverifier.ledger.learnedFor(
            currentRouteKey,
            playerSettings.audioRejectionsConfirmed
        )
        SurroundFormatResolver.resolve(
            manualMode = playerSettings.surroundFormatMode == SurroundFormatMode.MANUAL,
            allowAc3 = playerSettings.allowAc3Passthrough,
            allowEac3 = playerSettings.allowEac3Passthrough,
            allowTrueHd = playerSettings.allowTruehdPassthrough,
            allowDts = playerSettings.allowDtsPassthrough,
            allowDtsHd = playerSettings.allowDtshdPassthrough,
            manualTranscodePreferred =
                playerSettings.deniedCodecHandling == DeniedCodecHandling.TRANSCODE_AC3,
            manualChannelTargetChannels = when (playerSettings.surroundChannelTarget) {
                SurroundChannelTarget.AUTO -> null
                SurroundChannelTarget.CH_2_0 -> 2
                SurroundChannelTarget.CH_5_1 -> 6
                SurroundChannelTarget.CH_7_1 -> 8
            },
            direct = chainSnapshot.direct,
            rawMaxPcmChannels = chainSnapshot.maxPcmChannels,
            routeIsBluetooth = false,
            routeIsHdmiArc = SurroundFormatResolver.routeKeyIsHdmiArc(currentRouteKey),
            softwareDecodersAvailable = inputs.softwareDecodersAvailable,
            forceOpticalActive = inputs.forceOpticalActive,
            learnedDeniedGroups = learnedDeniedGroups,
            tvArcSoundbar = playerSettings.surroundTvArcSoundbar
        )
    }
    val surroundTargetChannels = surroundResolution.inferredChannelTarget
    val surroundDownmixEnabled = inputs.effectiveDownmixEnabled || surroundTargetChannels != null
    val surroundAudioOutputChannels = when {
        surroundTargetChannels == null -> inputs.effectiveAudioOutputChannels
        inputs.effectiveDownmixEnabled &&
            inputs.effectiveAudioOutputChannels.channelCount <= surroundTargetChannels ->
            inputs.effectiveAudioOutputChannels
        else -> surroundTargetToOutputChannels(surroundTargetChannels, inputs.effectiveAudioOutputChannels)
    }
    val deniedTranscodeMimes = DeniedTranscodePlanner.effectiveTranscodeMimes(
        policy = surroundResolution.policy,
        transcodeDeniedToAc3 = surroundResolution.transcodePreferred,
        forcePassthroughActive = inputs.forceOpticalActive
    )
    return SurroundResolveResult(
        resolution = surroundResolution,
        targetChannels = surroundTargetChannels,
        downmixEnabled = surroundDownmixEnabled,
        audioOutputChannels = surroundAudioOutputChannels,
        deniedTranscodeMimes = deniedTranscodeMimes
    )
}

internal suspend fun PlayerRuntimeController.applySurroundResolutionInPlace(reason: String) {
    if (_exoPlayer == null || isUsingMpvEngine()) return
    val inputs = surroundResolveInputs ?: return
    if (inputs.isBluetooth || currentAudioOutputRoute?.isBluetooth == true) return
    val settings = currentPlayerSettingsForReport
    val player = _exoPlayer
    var surround = readSurroundInPlace(inputs, settings)
    if (settings.surroundFormatMode != SurroundFormatMode.MANUAL &&
        surroundResolveNeedsReselect(lastAppliedSurroundResolve, surround)
    ) {
        val applied = lastAppliedSurroundResolve
        var delayMs = SurroundRecheckGuard.firstConfirmDelayMs(
            appliedWhileUnplugged = lastAppliedSurroundHdmi?.unplugged == true,
            pluggedNow = applied != null && !AudioChainProbe.hdmiPlugSnapshot(context).unplugged,
            onlyAddsFormats = applied != null && SurroundRecheckGuard.onlyAddsFormats(applied, surround)
        )
        Log.d(
            PlayerRuntimeController.TAG,
            "SURROUND_RESOLVE_CONFIRM: reason=$reason formats differ; reading again in $delayMs ms"
        )
        var readings = 1
        val settled = SurroundRecheckGuard.settledReading(
            first = surround,
            read = {
                readings++
                AudioChainProbe.invalidate()
                AudioOutputRouteDetector.detect(context)?.let { currentAudioOutputRoute = it }
                readSurroundInPlace(inputs, settings)
            },
            wait = {
                AudioChainProbe.invalidate()
                delay(delayMs)
                delayMs = SurroundRecheckGuard.CONFIRM_DELAY_MS
            }
        )
        if (isReleasingPlayer || _exoPlayer !== player || currentAudioOutputRoute?.isBluetooth == true) {
            AudioChainProbe.invalidate()
            return
        }
        if (settled == null) {
            AudioChainProbe.invalidate()
            Log.i(
                PlayerRuntimeController.TAG,
                "SURROUND_RESOLVE_UNSETTLED: reason=$reason readings=$readings; formats kept"
            )
            queuePlaybackRawEventLine("surround_resolve_unsettled reason=$reason readings=$readings")
            return
        }
        surround = settled
        if (surroundResolveNeedsReselect(lastAppliedSurroundResolve, surround) &&
            keepFormatsForPlayingBitstream(reason, surround)
        ) {
            AudioChainProbe.invalidate()
            return
        }
    }
    commitSurroundResolution(reason, surround, inputs, settings)
}

private fun PlayerRuntimeController.keepFormatsForPlayingBitstream(
    reason: String,
    next: SurroundResolveResult
): Boolean {
    val sink = playbackSpeedAwareAudioSink ?: return false
    val bitstreamPlaying = sink.isIecHbrActive() || sink.isDirectPlaybackActive()
    if (!bitstreamPlaying) return false
    val hdmi = AudioChainProbe.hdmiPlugSnapshot(context)
    val plugListLost = SurroundRecheckGuard.plugListLostFormats(
        atApply = lastAppliedSurroundHdmi?.encodings,
        now = hdmi.encodings
    )
    val decision = SurroundRecheckGuard.decide(
        current = lastAppliedSurroundResolve,
        next = next,
        bitstreamPlaying = true,
        hdmiUnplugged = hdmi.unplugged,
        plugListLostFormats = plugListLost
    )
    if (plugListLost) {
        Log.i(
            PlayerRuntimeController.TAG,
            "SURROUND_RESOLVE_HDMI_LIST: reason=$reason formats gone from the HDMI plug report " +
                "(${lastAppliedSurroundHdmi?.encodings} -> ${hdmi.encodings}); applying"
        )
    }
    if (decision != SurroundRecheckGuard.Decision.KEEP_FOR_BITSTREAM) return false
    val nextPolicy = next.resolution.policy
    Log.i(
        PlayerRuntimeController.TAG,
        "SURROUND_RESOLVE_KEPT: reason=$reason bitstream playing, HDMI plugged in; " +
            "formats kept for this title, not applied: [ac3=${nextPolicy.allowAc3} eac3=${nextPolicy.allowEac3} " +
            "truehd=${nextPolicy.allowTrueHd} dts=${nextPolicy.allowDts} dtshd=${nextPolicy.allowDtsHd}] " +
            "route=${currentAudioOutputRoute?.key}"
    )
    queuePlaybackRawEventLine(
        "surround_resolve_kept reason=$reason ac3=${nextPolicy.allowAc3} eac3=${nextPolicy.allowEac3} " +
            "truehd=${nextPolicy.allowTrueHd} dts=${nextPolicy.allowDts} dtshd=${nextPolicy.allowDtsHd}"
    )
    return true
}

private fun PlayerRuntimeController.readSurroundInPlace(
    inputs: SurroundResolveInputs,
    settings: PlayerSettings
): SurroundResolveResult = resolveSurroundForRoute(
    context,
    settings,
    inputs.copy(routeKey = currentAudioOutputRoute?.key ?: inputs.routeKey)
)

private fun PlayerRuntimeController.commitSurroundResolution(
    reason: String,
    surround: SurroundResolveResult,
    inputs: SurroundResolveInputs,
    settings: PlayerSettings
) {
    val routeKey = currentAudioOutputRoute?.key ?: inputs.routeKey
    val policy = surround.resolution.policy
    val policyChanged = playbackSpeedAwareAudioSink?.setPassthroughPolicy(policy) == true
    currentAudioPassthroughPolicy = policy
    ffmpegAudioRenderer?.applyDownmixSettings(
        downmixEnabled = surround.downmixEnabled,
        audioOutputChannels = surround.audioOutputChannels,
        downmixNormalizationEnabled = !settings.maintainOriginalAudioOnDownmix,
        forceOpticalPassthrough = inputs.forceOpticalActive,
        deniedTranscodeMimes = surround.deniedTranscodeMimes
    )
    val changed = surroundResolveNeedsReselect(lastAppliedSurroundResolve, surround) || policyChanged
    lastAppliedSurroundResolve = surround
    if (changed) lastAppliedSurroundHdmi = AudioChainProbe.hdmiPlugSnapshot(context)
    Log.i(
        PlayerRuntimeController.TAG,
        "SURROUND_RESOLVE_INPLACE: reason=$reason changed=$changed route=$routeKey " +
            "policy=[ac3=${policy.allowAc3} eac3=${policy.allowEac3} truehd=${policy.allowTrueHd} " +
            "dts=${policy.allowDts} dtshd=${policy.allowDtsHd} learned=${policy.learnedDeniedGroups}] " +
            "transcodePreferred=${surround.resolution.transcodePreferred} " +
            "channelTarget=${surround.targetChannels} tvArcSoundbar=${settings.surroundTvArcSoundbar}"
    )
    queuePlaybackRawEventLine(
        "surround_resolve_inplace reason=$reason changed=$changed " +
            "ac3=${policy.allowAc3} eac3=${policy.allowEac3} truehd=${policy.allowTrueHd} " +
            "dts=${policy.allowDts} dtshd=${policy.allowDtsHd} " +
            "transcodePreferred=${surround.resolution.transcodePreferred} " +
            "channelTarget=${surround.targetChannels} tvArcSoundbar=${settings.surroundTvArcSoundbar}"
    )
    if (SpeakerlessBoxRoute.hdmiSoundOff(routeKey)) logHdmiSoundOff(reason, routeKey)
    if (!changed) return
    val wasPlaying = hasActivePlayIntent() && !userPausedManually
    playbackSpeedAwareAudioSink?.notifyAudioProcessingRequirementChanged()
    _exoPlayer?.let { player ->
        // Before the first tracks, or while the film is stopped for the thumbnails screen, the new policy simply
        // applies to the next selection; a reselect then has no playing period and fails inside the player.
        if (player.currentTracks.isEmpty || player.playbackState == Player.STATE_IDLE) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().build()
        if (!wasPlaying || userPausedManually) {
            player.playWhenReady = false
            player.pause()
        }
    }
}

internal fun surroundResolveNeedsReselect(
    previous: SurroundResolveResult?,
    next: SurroundResolveResult
): Boolean = previous != next

internal fun PlayerRuntimeController.logHdmiSoundOff(reason: String, routeKey: String?) {
    Log.i(
        PlayerRuntimeController.TAG,
        "AUDIO_ROUTE_HDMI_SOUND_OFF: reason=$reason route=$routeKey; no HDMI sound on this box, " +
            "the film holds its place until HDMI sound returns"
    )
    queuePlaybackRawEventLine("audio_route_hdmi_sound_off reason=$reason route=$routeKey")
}
