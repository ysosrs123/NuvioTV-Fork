package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.Player
import com.nuvio.tv.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * After the first own IEC failure a DTS-HD film carries on through the system output, which can
 * limit it to the DTS core or play nothing, depending on the device and audio chain. Once the output
 * has settled the player is rebuilt once at the same position to get the full stream back. If IEC
 * fails again, or the output never settles, the app decodes the stream itself so the sound stays.
 */
internal object IecFailureReturn {
    const val SETTLE_MS = 10_000L
    const val NOTICE_MS = 8_000L
    const val CHECK_MS = 5_000L
    const val MAX_PLAYING_CHECKS = 120
    const val FORGET_FAILURE_AFTER_MS = 10L * 60L * 1_000L

    fun retriesAfter(failures: Int, mimeType: String?): Boolean =
        failures == 1 && decodesAfterFailure(mimeType)

    fun decodesAfterFailure(mimeType: String?): Boolean =
        mimeType?.startsWith("audio/vnd.dts.") == true

    fun mayRebuild(
        outputReady: Boolean,
        rebuildOrSeekPending: Boolean,
        inBackground: Boolean,
        forcedPcm: Boolean,
        policyDenies: Boolean,
        normalSpeed: Boolean
    ): Boolean = outputReady && !rebuildOrSeekPending && !inBackground && !forcedPcm &&
        !policyDenies && normalSpeed

    fun forgetsEarlierFailure(lastFailureMs: Long?, nowMs: Long): Boolean =
        lastFailureMs != null && nowMs - lastFailureMs >= FORGET_FAILURE_AFTER_MS
}

internal fun PlayerRuntimeController.startIecFailureReturnChecks() {
    val url = currentStreamUrl
    iecFailureReturnJob?.cancel()
    iecFailureReturnJob = scope.launch {
        delay(IecFailureReturn.SETTLE_MS)
        var playingChecks = 0
        while (playingChecks < IecFailureReturn.MAX_PLAYING_CHECKS) {
            if (currentStreamUrl != url || url in iecOffStreamUrls) return@launch
            val player = _exoPlayer
            if (player?.playbackState == Player.STATE_READY && player.isPlaying) {
                playingChecks++
                val mimeType = player.audioFormat?.sampleMimeType
                val mayRebuild = IecFailureReturn.mayRebuild(
                    outputReady = PassthroughOutputReturn.outputReadyNow(context),
                    rebuildOrSeekPending = isReleasingPlayer || playbackRecoveryJob?.isActive == true ||
                        _uiState.value.pendingSeekPosition != null,
                    inBackground = isInBackground,
                    forcedPcm = hasTriedAudioPcmFallback,
                    policyDenies = currentAudioPassthroughPolicy?.deniesPassthrough(mimeType) == true,
                    normalSpeed = _uiState.value.playbackSpeed == 1f
                )
                if (mayRebuild) {
                    Log.i(
                        PlayerRuntimeController.TAG,
                        "IEC_FAILURE_RETURN rebuild mime=$mimeType positionMs=${player.currentPosition} " +
                            "route=${currentAudioOutputRoute?.key}"
                    )
                    scheduleOwnedPlaybackRecovery(player.currentPosition, 0L)
                    return@launch
                }
            }
            delay(IecFailureReturn.CHECK_MS)
        }
        Log.i(PlayerRuntimeController.TAG, "IEC_FAILURE_RETURN stopped, output never settled")
        val player = _exoPlayer ?: return@launch
        if (decodeStreamAfterIecFailure(player.audioFormat?.sampleMimeType)) {
            scheduleDeferredPlayerReinitialize(fromPositionMs = player.currentPosition)
        }
    }
}

/** Marks the stream for decoding in the app and tells the viewer. The caller rebuilds the player. */
internal fun PlayerRuntimeController.decodeStreamAfterIecFailure(mimeType: String?): Boolean {
    if (!IecFailureReturn.decodesAfterFailure(mimeType) || !iecDecodeStreamUrls.add(currentStreamUrl)) return false
    Log.i(PlayerRuntimeController.TAG, "IEC_FAILURE_DECODE mime=$mimeType host=${currentStreamUrl.safeHost()}")
    val notice = context.getString(R.string.player_dts_hd_decoding_notice)
    _uiState.update { it.copy(playerNotice = notice) }
    playerNoticeJob?.cancel()
    playerNoticeJob = scope.launch {
        delay(IecFailureReturn.NOTICE_MS)
        _uiState.update { it.copy(playerNotice = null) }
    }
    return true
}
