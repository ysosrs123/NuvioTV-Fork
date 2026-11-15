package com.nuvio.tv.ui.screens.player

import android.content.Context
import androidx.media3.common.MimeTypes
import com.nuvio.tv.core.player.AudioPassthroughPolicy
import com.nuvio.tv.core.player.SurroundFormatResolver.DirectSupport
import com.nuvio.tv.data.local.NightModeLevel
import com.nuvio.tv.data.local.PlayerSettings

internal enum class MpvSpdifCodec(val mpvName: String, val mimeType: String) {
    AC3("ac3", MimeTypes.AUDIO_AC3),
    EAC3("eac3", MimeTypes.AUDIO_E_AC3),
    DTS("dts", MimeTypes.AUDIO_DTS),
    DTS_HD("dts-hd", MimeTypes.AUDIO_DTS_HD),
    TRUEHD("truehd", MimeTypes.AUDIO_TRUEHD)
}

/**
 * The codecs mpv may send as a bitstream on this output: the ones the device plays directly and Arc's
 * passthrough settings allow. Force optical adds AC-3 and DTS, as it does on the ExoPlayer path.
 */
internal fun mpvAllowedSpdifCodecs(
    policy: AudioPassthroughPolicy,
    direct: DirectSupport?,
    forceOptical: Boolean
): String = MpvSpdifCodec.entries
    .filter { codec ->
        !policy.deniesPassthrough(codec.mimeType) &&
            (direct.supports(codec) || (forceOptical && codec.isOpticalCodec()))
    }
    .joinToString(",") { it.mpvName }

/** Night mode, volume and dialogue boost and other speeds need decoded sound, so on mpv they win over the bitstream. */
internal fun mpvSpdifCodecsNow(
    allowed: String,
    isBluetooth: Boolean,
    gainDb: Int,
    nightMode: NightModeLevel,
    dialogueBoostDb: Int,
    playbackSpeed: Float
): String = when {
    MpvBluetoothAudioPolicy.shouldClearAudioSpdif(isBluetooth) -> ""
    gainDb != 0 || nightMode != NightModeLevel.OFF || dialogueBoostDb != 0 -> ""
    playbackSpeed != 1f -> ""
    else -> allowed
}

/**
 * mpv's deinterlacer for software decoding only; hardware decoders hand over finished frames. Null while no video
 * decoder is running yet.
 */
internal fun mpvDeinterlaceFor(hwdecCurrent: String?): String? = when {
    hwdecCurrent.isNullOrBlank() -> null
    hwdecCurrent == MPV_HWDEC_SOFTWARE -> MPV_DEINTERLACE_AUTO
    else -> MPV_DEINTERLACE_OFF
}

internal fun PlayerRuntimeController.mpvAllowedSpdifCodecsForRoute(context: Context, settings: PlayerSettings): String {
    val route = currentAudioOutputRoute
    if (route?.isBluetooth == true) return ""
    val forceOptical = settings.forceOpticalPassthrough && settings.decoderPriority != 0
    val surround = resolveSurroundForRoute(
        context,
        settings,
        SurroundResolveInputs(
            routeKey = route?.key,
            isBluetooth = false,
            softwareDecodersAvailable = true,
            forceOpticalActive = forceOptical,
            effectiveDownmixEnabled = settings.effectiveDownmixEnabled,
            effectiveAudioOutputChannels = settings.audioOutputChannels
        )
    )
    return mpvAllowedSpdifCodecs(
        policy = surround.resolution.policy,
        direct = AudioChainProbe.snapshot(context, route?.key).direct,
        forceOptical = forceOptical
    )
}

internal fun PlayerRuntimeController.applyMpvPassthroughCodecs(view: NuvioMpvSurfaceView) {
    val codecs = runCatching { mpvAllowedSpdifCodecsForRoute(context, currentPlayerSettingsForReport) }
        .getOrDefault("")
    view.setAllowedSpdifCodecs(codecs)
}

private fun DirectSupport?.supports(codec: MpvSpdifCodec): Boolean {
    val d = this ?: return false
    return when (codec) {
        MpvSpdifCodec.AC3 -> d.ac3
        MpvSpdifCodec.EAC3 -> d.eac3
        MpvSpdifCodec.DTS -> d.dts
        MpvSpdifCodec.DTS_HD -> d.dtsHd
        MpvSpdifCodec.TRUEHD -> d.trueHd
    }
}

private fun MpvSpdifCodec.isOpticalCodec(): Boolean = this == MpvSpdifCodec.AC3 || this == MpvSpdifCodec.DTS

internal const val MPV_HWDEC_CURRENT_PROPERTY = "hwdec-current"
private const val MPV_HWDEC_SOFTWARE = "no"
private const val MPV_DEINTERLACE_AUTO = "auto"
private const val MPV_DEINTERLACE_OFF = "no"
