package com.nuvio.tv.ui.screens.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.player.dvmkv.DtsStreamType
import com.nuvio.tv.core.player.dvmkv.TrueHdAtmos

data class PlayerMediaInfo(
    val videoCodec: String? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val audioCodec: String? = null,
    val audioProfiles: List<String> = emptyList(),
    val audioChannels: Int? = null,
    /** The playing audio track, as [TrackInfo.trackId] names it, and its language. */
    val audioTrackId: String? = null,
    val audioLanguage: String? = null,
    val hdr: List<String> = emptyList(),
    /** False until the player knows the transfer, as mpv only does once it decodes a frame; until then no SDR. */
    val hdrKnown: Boolean = false,
    val bitDepth: Int? = null
) {
    /** Everything the row shows is in: picture size and the audio format with its channels. */
    val isComplete: Boolean
        get() = videoWidth != null && audioCodec != null && audioChannels != null
}

internal fun PlayerRuntimeController.playerMediaInfo(): PlayerMediaInfo =
    if (isUsingMpvEngine()) {
        mpvView?.readPlayerMediaInfo() ?: PlayerMediaInfo()
    } else {
        val player = _exoPlayer
        // The decoders' formats arrive with the first data; the selected tracks are known before that.
        exoMediaInfo(
            player?.videoFormat ?: player?.selectedTrackFormat(C.TRACK_TYPE_VIDEO),
            player?.audioFormat ?: player?.selectedTrackFormat(C.TRACK_TYPE_AUDIO)
        )
    }

/** The one selected track of [type]; null for an adaptive group, where the variant playing is not known yet. */
private fun Player.selectedTrackFormat(type: Int): Format? {
    val group = currentTracks.groups.firstOrNull { it.type == type && it.isSelected } ?: return null
    val selected = (0 until group.length).filter(group::isTrackSelected)
    return selected.singleOrNull()?.let(group::getTrackFormat)
}

/** The file name the stats HUD shows: the add-on's file name, else the link's last part, else the stream name. */
internal fun PlayerRuntimeController.playingFileName(): String? =
    listOfNotNull(currentFilename, urlFileName(getCurrentStreamUrl()), _uiState.value.currentStreamName)
        .firstOrNull { it.isNotBlank() }

internal fun urlFileName(url: String?): String? = url
    ?.let { runCatching { java.net.URI(it).path }.getOrNull() }
    ?.substringAfterLast('/')
    ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() ?: it }
    ?.takeIf { it.isNotBlank() && it.contains('.') }

/** File name, stream name and description of what is playing, for badges the player cannot read. */
internal fun PlayerRuntimeController.playingReleaseText(): String =
    listOfNotNull(currentFilename, _uiState.value.currentStreamName, currentStreamDescription)
        .joinToString(" ")

internal fun mpvMediaInfo(property: (String) -> String?): PlayerMediaInfo {
    val gamma = property("video-params/gamma")
    return PlayerMediaInfo(
        videoCodec = property("current-tracks/video/codec")?.let(::mpvCodecLabel),
        // Decoded values once there are frames; the file's own track values before that.
        videoWidth = mpvInt(property, "video-params/w") ?: mpvInt(property, "current-tracks/video/demux-w"),
        videoHeight = mpvInt(property, "video-params/h") ?: mpvInt(property, "current-tracks/video/demux-h"),
        audioCodec = property("current-tracks/audio/codec")?.let(::mpvCodecLabel),
        audioProfiles = listOfNotNull(property("current-tracks/audio/codec-profile")),
        audioChannels = mpvInt(property, "audio-params/channel-count")
            ?: mpvInt(property, "current-tracks/audio/demux-channel-count"),
        audioTrackId = property("current-tracks/audio/id"),
        audioLanguage = property("current-tracks/audio/lang"),
        hdr = buildList {
            if (!property("current-tracks/video/dolby-vision-profile").isNullOrBlank()) add("DV")
            when (gamma) {
                "pq" -> add("HDR10")
                "hlg" -> add("HLG")
            }
        },
        hdrKnown = gamma != null,
        bitDepth = property("video-params/pixelformat")?.let(::mpvBitDepth)
    )
}

private fun mpvInt(property: (String) -> String?, name: String): Int? =
    property(name)?.toIntOrNull()?.takeIf { it > 0 }

private fun exoMediaInfo(video: Format?, audio: Format?): PlayerMediaInfo =
    PlayerMediaInfo(
        videoCodec = video?.let(::exoVideoCodec),
        videoWidth = video?.width?.takeIf { it > 0 },
        videoHeight = video?.height?.takeIf { it > 0 },
        audioCodec = audio?.sampleMimeType?.let(::exoCodecLabel),
        audioProfiles = exoAudioProfiles(audio),
        audioChannels = audio?.channelCount?.takeIf { it > 0 },
        audioTrackId = audio?.id,
        audioLanguage = audio?.language,
        hdr = video?.hdrFormats().orEmpty(),
        hdrKnown = video != null,
        bitDepth = video?.colorInfo?.lumaBitdepth?.takeIf { it > 0 } ?: video?.codecs?.let(::codecsBitDepth)
    )

/** A Dolby Vision track names its base layer in the codecs string: dvav/dva1 AVC, dav1 AV1, dvhe/dvh1 HEVC. */
private fun exoVideoCodec(video: Format): String? {
    val mime = video.sampleMimeType ?: return null
    if (mime != MimeTypes.VIDEO_DOLBY_VISION) return exoCodecLabel(mime)
    val codecs = video.codecs.orEmpty().lowercase()
    return when {
        codecs.startsWith("dvav") || codecs.startsWith("dva1") -> "AVC"
        codecs.startsWith("dav1") -> "AV1"
        else -> "HEVC"
    }
}

@OptIn(UnstableApi::class)
private fun exoCodecLabel(mime: String): String? =
    CustomDefaultTrackNameProvider.formatNameFromMime(mime) ?: when (mime) {
        MimeTypes.AUDIO_DTS_X -> "DTS:X"
        MimeTypes.VIDEO_VC1 -> "VC-1"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        MimeTypes.VIDEO_MP4V -> "MPEG-4"
        else -> null
    }

/**
 * Bit depth from the RFC 6381 codecs string, for containers that leave colorInfo empty:
 * HEVC Main 10 (hvc1.2…), AVC High 10 (avc1.6E…), AV1 and VP9 (av01.0.08M.10, vp09.02.10.10).
 * Dolby Vision is always at least 10-bit.
 */
internal fun codecsBitDepth(codecs: String): Int? {
    val parts = codecs.substringBefore(',').trim().lowercase().split('.')
    return when (parts.firstOrNull()) {
        "hvc1", "hev1" -> parts.getOrNull(1)?.trimStart('a', 'b', 'c')?.toIntOrNull()?.let { if (it == 2) 10 else null }
        "avc1", "avc3" -> parts.getOrNull(1)?.take(2)?.toIntOrNull(16)?.let { if (it == 110) 10 else null }
        "av01", "vp09" -> parts.getOrNull(3)?.toIntOrNull()?.takeIf { it > 8 }
        "dvhe", "dvh1", "dvav", "dva1", "dav1" -> 10
        else -> null
    }
}

private val DTS_HD_MA_LABEL = Regex("""\b(ma|master audio)\b""")

@OptIn(UnstableApi::class)
internal fun exoAudioProfiles(audio: Format?): List<String> {
    if (audio == null) return emptyList()
    val mime = audio.sampleMimeType.orEmpty().lowercase()
    val codecs = audio.codecs.orEmpty().lowercase()
    val label = audio.label.orEmpty().lowercase()
    val combined = "$codecs $label"
    val dtsType = DtsStreamType.of(audio)
    return buildList {
        if (mime == MimeTypes.AUDIO_E_AC3_JOC || TrueHdAtmos.isPresent(audio) || combined.contains("atmos")) add("Atmos")
        if (dtsType?.isDtsX == true || combined.contains("dts:x") || combined.contains("dtsx")
            || mime == MimeTypes.AUDIO_DTS_X) add("DTS:X")
        val isDtsHd = combined.contains("dts-hd") || combined.contains("dtshd")
            || codecs.startsWith("dtsh") || codecs.startsWith("dtsl")
        if (dtsType?.isLossless == true || codecs.startsWith("dtsl")
            || (dtsType == null && isDtsHd && DTS_HD_MA_LABEL.containsMatchIn(combined))) add("DTS-HD MA")
        else if (isDtsHd || dtsType != null) add("DTS-HD")
    }
}

/** Shared by the badges and the stats HDR row; the transfer falls back to the HEVC SPS. */
internal fun Format.hdrFormats(): List<String> = buildList {
    if (sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) add("DV")
    when (PlaybackVideoColorTransfer.of(this@hdrFormats)) {
        C.COLOR_TRANSFER_ST2084 -> add("HDR10")
        C.COLOR_TRANSFER_HLG -> add("HLG")
    }
}

private fun mpvBitDepth(pixelFormat: String): Int? = when {
    pixelFormat.contains("12") -> 12
    pixelFormat.contains("10") -> 10
    else -> null
}

private fun mpvCodecLabel(codec: String): String? =
    when (val c = codec.trim().lowercase()) {
        "" -> null
        "h264" -> "AVC"
        "ac3" -> "AC-3"
        "eac3" -> "E-AC-3"
        "truehd" -> "TrueHD"
        "opus" -> "Opus"
        "vorbis" -> "Vorbis"
        "dts" -> "DTS"
        else -> when {
            c.startsWith("pcm") -> "PCM"
            else -> codec.trim().uppercase()
        }
    }
