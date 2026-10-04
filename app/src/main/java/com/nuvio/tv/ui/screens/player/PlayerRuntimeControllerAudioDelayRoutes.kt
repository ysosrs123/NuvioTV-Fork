package com.nuvio.tv.ui.screens.player

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.nuvio.tv.data.local.AudioOutputChannels
import com.nuvio.tv.ui.screens.player.iec.PlatformIecAudioTrackFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Debounce window so flapping add/remove events coalesce into one route decision. */
private const val AUDIO_ROUTE_CHANGE_DEBOUNCE_MS = 700L

internal enum class BluetoothRoutePlaybackAction {
    NONE,
    UPDATE_SINK_IN_PLACE,
    UPDATE_MPV_IN_PLACE,
}

/**
 * Bluetooth connect/disconnect must never rebuild the player: that restarts video from a
 * seek point and shows the loading overlay. Update PCM/passthrough (Exo) or stereo/delay
 * (MPV) on the live engine instead.
 */
internal fun decideBluetoothRoutePlaybackAction(
    wasBluetooth: Boolean,
    isBluetooth: Boolean,
    usingMpv: Boolean,
    oldRouteKey: String? = null,
    newRouteKey: String? = null
): BluetoothRoutePlaybackAction {
    val bluetoothStateChanged = wasBluetooth != isBluetooth
    val bluetoothDeviceChanged = isBluetooth &&
        !oldRouteKey.isNullOrBlank() &&
        !newRouteKey.isNullOrBlank() &&
        oldRouteKey != newRouteKey
    if (!bluetoothStateChanged && !bluetoothDeviceChanged) {
        return BluetoothRoutePlaybackAction.NONE
    }
    return if (usingMpv) {
        BluetoothRoutePlaybackAction.UPDATE_MPV_IN_PLACE
    } else {
        BluetoothRoutePlaybackAction.UPDATE_SINK_IN_PLACE
    }
}

internal object MpvBluetoothAudioPolicy {
    const val STEREO_CHANNELS = "stereo"
    const val AUTO_CHANNELS = "auto"

    fun audioChannels(isBluetooth: Boolean): String {
        return if (isBluetooth) STEREO_CHANNELS else AUTO_CHANNELS
    }

    fun shouldClearAudioSpdif(isBluetooth: Boolean): Boolean = isBluetooth
}

internal fun audioDelayMsToSeconds(delayMs: Int): Double {
    return delayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS) / 1000.0
}

internal suspend fun PlayerRuntimeController.applyStoredAudioDelayForCurrentRouteIfEnabled() {
    if (!rememberAudioDelayPerDeviceEnabled) return

    val route = AudioOutputRouteDetector.detect(context) ?: return
    currentAudioOutputRoute = route

    val storedDelayMs = audioDelayRouteDataStore.loadDelayMs(route.key) ?: 0
    applyAudioDelay(storedDelayMs, persistForCurrentRoute = false)
    Log.d(
        PlayerRuntimeController.TAG,
        "Applied audio delay ${storedDelayMs}ms for route=${route.key}"
    )
}

internal fun PlayerRuntimeController.persistAudioDelayForCurrentRoute(delayMs: Int) {
    if (!rememberAudioDelayPerDeviceEnabled) return

    val route = AudioOutputRouteDetector.detect(context) ?: currentAudioOutputRoute ?: return
    currentAudioOutputRoute = route
    scope.launch {
        audioDelayRouteDataStore.saveDelayMs(route.key, delayMs)
    }
}

internal fun PlayerRuntimeController.registerAudioDelayRouteCallback() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || audioOutputRouteCallback != null) return

    val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
    val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            onAudioOutputRouteMaybeChanged("added", addedDevices)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            onAudioOutputRouteMaybeChanged("removed", removedDevices)
        }
    }

    runCatching {
        audioManager.registerAudioDeviceCallback(callback, null)
        audioOutputRouteCallback = callback
        Log.d(PlayerRuntimeController.TAG, "Registered audio output route callback")
    }.onFailure {
        Log.w(PlayerRuntimeController.TAG, "Failed to register audio route callback", it)
    }
}

internal fun PlayerRuntimeController.unregisterAudioDelayRouteCallback() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val callback = audioOutputRouteCallback ?: return
    val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
    runCatching {
        audioManager.unregisterAudioDeviceCallback(callback)
    }.onFailure {
        Log.w(PlayerRuntimeController.TAG, "Failed to unregister audio route callback", it)
    }
    audioOutputRouteCallback = null
}

private fun PlayerRuntimeController.onAudioOutputRouteMaybeChanged(
    reason: String,
    devices: Array<out AudioDeviceInfo>
) {
    if (isReleasingPlayer) return
    if (_exoPlayer == null && !isUsingMpvEngine()) return

    Log.d(
        PlayerRuntimeController.TAG,
        "Audio device $reason (count=${devices.size}); scheduling route re-probe"
    )

    audioRouteChangeJob?.cancel()
    audioRouteChangeJob = scope.launch {
        delay(AUDIO_ROUTE_CHANGE_DEBOUNCE_MS)
        if (isReleasingPlayer) return@launch

        val oldRoute = currentAudioOutputRoute
        val newRoute = AudioOutputRouteDetector.detect(context)
        if (newRoute != null) {
            currentAudioOutputRoute = newRoute
        }
        AudioRejectionReverifier.ledger.invalidate()
        saveAudioRefusalEvidence()
        AudioChainProbe.invalidate()
        PlatformIecAudioTrackFactory.invalidateIec61937ProbeMemo()
        applySurroundResolutionInPlace(reason)

        if (rememberAudioDelayPerDeviceEnabled) {
            applyStoredAudioDelayForCurrentRouteIfEnabled()
        }

        val wasBluetooth = oldRoute?.isBluetooth == true
        val isBluetooth = (newRoute ?: currentAudioOutputRoute)?.isBluetooth == true
        when (
            decideBluetoothRoutePlaybackAction(
                wasBluetooth = wasBluetooth,
                isBluetooth = isBluetooth,
                usingMpv = isUsingMpvEngine(),
                oldRouteKey = oldRoute?.key,
                newRouteKey = (newRoute ?: currentAudioOutputRoute)?.key
            )
        ) {
            BluetoothRoutePlaybackAction.NONE -> {
                Log.d(
                    PlayerRuntimeController.TAG,
                    "Audio route after device $reason: bluetooth=$isBluetooth (was=$wasBluetooth); player stays running"
                )
                if (reason == "added" && !isBluetooth) schedulePassthroughReturnCheck("device added")
            }
            BluetoothRoutePlaybackAction.UPDATE_SINK_IN_PLACE -> {
                if (_exoPlayer == null) return@launch
                Log.i(
                    PlayerRuntimeController.TAG,
                    "Bluetooth media route changed $wasBluetooth → $isBluetooth after device $reason; " +
                        "updating PCM policy in place (player stays running)"
                )
                applyBluetoothAudioRouteInPlace(isBluetooth)
            }
            BluetoothRoutePlaybackAction.UPDATE_MPV_IN_PLACE -> {
                Log.i(
                    PlayerRuntimeController.TAG,
                    "Bluetooth media route changed $wasBluetooth → $isBluetooth after device $reason; " +
                        "updating MPV stereo/delay in place (player stays running)"
                )
                applyMpvBluetoothAudioRouteInPlace(isBluetooth)
            }
        }
    }
}

/**
 * Rebuilds the player when the HDMI output is back and takes the current track as bitstream,
 * but the player was built while it did not (TV on another input, box asleep). Runs again a
 * little later because a receiver can report its formats a moment after the device appears.
 */
internal fun PlayerRuntimeController.schedulePassthroughReturnCheck(reason: String) {
    if (_exoPlayer == null || isUsingMpvEngine()) return
    passthroughReturnCheckJob?.cancel()
    passthroughReturnCheckJob = scope.launch {
        if (rebuildForReturnedPassthroughIfNeeded(reason)) return@launch
        delay(PassthroughOutputReturn.SECOND_CHECK_DELAY_MS)
        rebuildForReturnedPassthroughIfNeeded("$reason, second check")
    }
}

private fun PlayerRuntimeController.rebuildForReturnedPassthroughIfNeeded(reason: String): Boolean {
    if (isReleasingPlayer || isInBackground || playbackRecoveryJob?.isActive == true) return false
    if (isUsingMpvEngine()) return false
    val player = _exoPlayer ?: return false
    val sink = playbackSpeedAwareAudioSink ?: return false
    val format = player.audioFormat ?: return false
    val encoding = PassthroughOutputReturn.encodingOf(format) ?: return false
    if (AudioOutputRouteDetector.isBluetoothMediaOutput(context)) return false
    val live = PassthroughOutputReturn.liveEncodings(context)
    val rebuildsSoFar = passthroughReturnRebuilds[currentStreamUrl] ?: 0
    val rebuild = PassthroughOutputReturn.shouldRebuild(
        encoding = encoding,
        pinned = sink.pinnedOutputEncodings,
        live = live,
        alreadyBitstream = sink.isDirectPlaybackActive() || sink.isIecHbrActive(),
        claimedByIec = sink.demandsNonTunnelledVideo(format),
        policyDenies = currentAudioPassthroughPolicy?.deniesPassthrough(format.sampleMimeType) == true,
        forcedPcm = hasTriedAudioPcmFallback ||
            _uiState.value.playbackSpeed != 1f ||
            currentStreamUrl in preferFfmpegAudioStreamUrls,
        rebuildsSoFar = rebuildsSoFar
    )
    if (!rebuild) return false
    passthroughReturnRebuilds[currentStreamUrl] = rebuildsSoFar + 1
    Log.i(
        PlayerRuntimeController.TAG,
        "PASSTHROUGH_RETURN rebuild reason=$reason encoding=$encoding " +
            "pinned=${sink.pinnedOutputEncodings} live=$live route=${currentAudioOutputRoute?.key}"
    )
    scheduleOwnedPlaybackRecovery(player.currentPosition, 0L)
    return true
}

internal fun PlayerRuntimeController.applyBluetoothAudioRouteInPlace(isBluetooth: Boolean) {
    val wasPlaying = hasActivePlayIntent() && !userPausedManually
    val sink = playbackSpeedAwareAudioSink
    if (sink != null && sink.isBluetoothForcePcm() == isBluetooth) {
        Log.d(
            PlayerRuntimeController.TAG,
            "Bluetooth PCM policy already $isBluetooth; " +
                if (wasPlaying) "player keeps running" else "player stays paused"
        )
        if (!wasPlaying || userPausedManually) {
            _exoPlayer?.let { player ->
                player.playWhenReady = false
                player.pause()
            }
        }
        return
    }

    sink?.setBluetoothForcePcm(isBluetooth)

    val settings = currentPlayerSettingsForReport
    val forceOptical = !isBluetooth &&
        settings.forceOpticalPassthrough &&
        settings.decoderPriority != 0
    val downmixEnabled = settings.effectiveDownmixEnabled || isBluetooth
    val outputChannels = if (isBluetooth) {
        AudioOutputChannels.CHANNELS_2_0
    } else {
        settings.audioOutputChannels
    }
    ffmpegAudioRenderer?.setForceOpticalPassthrough(forceOptical)
    if (downmixEnabled) {
        ffmpegAudioRenderer?.setAudioOutputChannels(
            outputChannels.ffmpegLayoutName,
            outputChannels.channelCount
        )
        ffmpegAudioRenderer?.setDownmixNormalizationEnabled(!settings.maintainOriginalAudioOnDownmix)
    } else {
        ffmpegAudioRenderer?.setAudioOutputChannels(null, 0)
        ffmpegAudioRenderer?.setDownmixNormalizationEnabled(false)
    }

    sink?.notifyAudioProcessingRequirementChanged()
    _exoPlayer?.let { player ->
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().build()
        if (!wasPlaying || userPausedManually) {
            player.playWhenReady = false
            player.pause()
        }
    }
}

internal fun PlayerRuntimeController.applyMpvBluetoothAudioRouteInPlace(isBluetooth: Boolean) {
    val view = mpvView ?: return
    val wasPlaying = hasActivePlayIntent() && !userPausedManually
    view.applyBluetoothAudioRoute(isBluetooth, reloadOutput = true)
    // ao-reload can drop live properties; re-pin the current per-route delay.
    view.setAudioDelayMs(_uiState.value.audioDelayMs)
    view.applyAudioAmplificationDb(_uiState.value.audioAmplificationDb)
    if (!wasPlaying || userPausedManually) {
        view.setPaused(true)
    }
}
