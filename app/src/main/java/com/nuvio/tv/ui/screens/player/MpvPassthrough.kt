package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.media.AudioFormat
import androidx.media3.common.MimeTypes
import com.nuvio.tv.core.player.AudioPassthroughPolicy
import com.nuvio.tv.core.player.SurroundFormatResolver.DirectSupport
import com.nuvio.tv.data.local.NightModeLevel
import com.nuvio.tv.data.local.PlayerSettings

/**
 * mpv hands every bitstream to Android in a 48 kHz carrier, so only AC-3 and the DTS core fit. E-AC-3, DTS-HD and
 * TrueHD need 192 kHz and are refused by the output; mpv decodes those to multichannel PCM instead. A DTS-HD track
 * with only "dts" allowed sends its DTS core.
 */
internal enum class MpvSpdifCodec(val mpvName: String, val mimeType: String, val sinkEncodings: Set<Int>) {
    AC3("ac3", MimeTypes.AUDIO_AC3, setOf(AudioFormat.ENCODING_AC3)),
    DTS("dts", MimeTypes.AUDIO_DTS, setOf(AudioFormat.ENCODING_DTS, AudioFormat.ENCODING_DTS_HD))
}

/**
 * The codecs mpv may send as a bitstream on this output: the ones the device plays directly, the TV or receiver
 * lists in its HDMI report ([sinkEncodings], null when the box gives none) and Arc's passthrough settings allow.
 * The box may decode AC-3 itself for a TV that takes only PCM, but mpv's bitstream goes to the TV as it is.
 * Force optical adds AC-3 and DTS, as it does on the ExoPlayer path.
 */
internal fun mpvAllowedSpdifCodecs(
    policy: AudioPassthroughPolicy,
    direct: DirectSupport?,
    forceOptical: Boolean,
    sinkEncodings: Set<Int>? = null
): String = MpvSpdifCodec.entries
    .filter { codec ->
        !policy.deniesPassthrough(codec.mimeType) &&
            (forceOptical || (direct.supports(codec) && (sinkEncodings == null || codec.sinkEncodings.any { it in sinkEncodings })))
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
        forceOptical = forceOptical,
        sinkEncodings = if (mpvSinkReportApplies(route?.key)) AudioChainProbe.hdmiPlugSnapshot(context).encodings else null
    )
}

/** The HDMI report describes the TV or receiver only when the sound goes out over HDMI; optical and USB have their own. */
internal fun mpvSinkReportApplies(routeKey: String?): Boolean = routeKey?.startsWith("type:hdmi") == true

internal fun PlayerRuntimeController.applyMpvPassthroughCodecs(view: NuvioMpvSurfaceView) {
    view.setAllowedSpdifCodecs(
        mpvCodecsToApply(currentStreamUrl, mpvDecodedSoundStreamUrls) {
            runCatching { mpvAllowedSpdifCodecsForRoute(context, currentPlayerSettingsForReport) }.getOrDefault("")
        }
    )
}

/** A stream whose start stalled while it sent a bitstream plays its sound decoded from then on. */
internal fun mpvCodecsToApply(streamUrl: String, decodedSoundStreamUrls: Set<String>, allowed: () -> String): String =
    if (streamUrl in decodedSoundStreamUrls) "" else allowed()

/**
 * A start that never shows a frame while mpv sends a bitstream may be a format the output takes but never plays; the
 * retry decodes the sound. A stall with decoded sound already is left to the normal retry.
 */
internal fun mpvRetryDecodesSound(source: String, firstFrameRendered: Boolean, bitstreamActive: Boolean): Boolean =
    bitstreamActive && !firstFrameRendered && (source == "startup_stall" || source == "startup_absolute")

private fun DirectSupport?.supports(codec: MpvSpdifCodec): Boolean {
    val d = this ?: return false
    return when (codec) {
        MpvSpdifCodec.AC3 -> d.ac3
        MpvSpdifCodec.DTS -> d.dts
    }
}

internal const val MPV_HWDEC_CURRENT_PROPERTY = "hwdec-current"
private const val MPV_HWDEC_SOFTWARE = "no"
private const val MPV_DEINTERLACE_AUTO = "auto"
private const val MPV_DEINTERLACE_OFF = "no"
