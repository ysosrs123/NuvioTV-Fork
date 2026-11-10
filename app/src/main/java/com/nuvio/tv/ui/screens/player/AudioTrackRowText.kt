package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes

internal data class AudioTrackRowText(val title: String, val subtitle: String?)

/**
 * Text for one row of the Audio list. The main line is the file's own track name, or the language when the file
 * has none, plus the role when the name does not already say it. Codec, channels and sample rate go on the line
 * below, with the language when the main line does not already show it.
 */
internal fun audioTrackRowText(
    track: TrackInfo,
    languageName: String?,
    fallbackTitle: String,
    commentaryLabel: String,
    audioDescriptionLabel: String
): AudioTrackRowText {
    val fileTitle = track.title?.trim()?.takeIf { it.isNotEmpty() }
    val hasStreamDetails = track.codec != null || track.channelCount != null || track.sampleRate != null
    val base = when {
        fileTitle != null -> fileTitle
        !hasStreamDetails -> track.name
        else -> languageName ?: fallbackTitle
    }

    val roleLabel = when (track.role) {
        TrackRole.COMMENTARY -> commentaryLabel
        TrackRole.AUDIO_DESCRIPTION -> audioDescriptionLabel
        else -> null
    }
    val roleHints = when (track.role) {
        TrackRole.COMMENTARY -> COMMENTARY_HINTS
        TrackRole.AUDIO_DESCRIPTION -> AUDIO_DESCRIPTION_HINTS
        else -> emptyList()
    }
    val roleAlreadyNamed = roleLabel != null &&
        (base.contains(roleLabel, ignoreCase = true) || roleHints.any { base.contains(it, ignoreCase = true) })
    val title = if (roleLabel != null && !roleAlreadyNamed) "$base · $roleLabel" else base

    val language = languageName?.takeIf { it.isNotBlank() && !base.contains(it, ignoreCase = true) }
    val subtitle = listOfNotNull(
        language,
        track.codecDetail ?: audioCodecDisplayName(track.codec),
        track.channelCount?.let { CustomDefaultTrackNameProvider.getChannelLayoutName(it) },
        track.sampleRate?.let { sampleRateLabel(it) }
    ).joinToString(" · ").ifBlank { null }

    return AudioTrackRowText(title = title, subtitle = subtitle)
}

/** ExoPlayer codec names pass through; mpv's FFmpeg names get the same spelling ExoPlayer uses. */
internal fun audioCodecDisplayName(codec: String?): String? {
    val raw = codec?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val lower = raw.lowercase()
    if (lower.startsWith("pcm_")) return "PCM"
    return MPV_CODEC_NAMES[lower] ?: raw
}

internal fun sampleRateLabel(sampleRate: Int): String? {
    if (sampleRate <= 0) return null
    val whole = sampleRate / 1000
    val tenth = (sampleRate % 1000) / 100
    return if (tenth == 0) "$whole kHz" else "$whole.$tenth kHz"
}

internal fun trackRoleFromRoleFlags(roleFlags: Int, containerMimeType: String?): TrackRole? = when {
    (roleFlags and C.ROLE_FLAG_COMMENTARY) != 0 -> TrackRole.COMMENTARY
    (roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO) != 0 -> TrackRole.AUDIO_DESCRIPTION
    (roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0 -> TrackRole.HEARING_IMPAIRED
    (roleFlags and C.ROLE_FLAG_MAIN) != 0 &&
        (containerMimeType == MimeTypes.VIDEO_MATROSKA || containerMimeType == MimeTypes.VIDEO_WEBM) ->
        TrackRole.ORIGINAL
    else -> null
}

private val COMMENTARY_HINTS = listOf("comment", "coment", "komment", "koment")
private val AUDIO_DESCRIPTION_HINTS = listOf("descri", "deskript", "narrat")

private val MPV_CODEC_NAMES = mapOf(
    "ac3" to "AC-3",
    "eac3" to "E-AC-3",
    "truehd" to "TrueHD",
    "mlp" to "MLP",
    "dts" to "DTS",
    "aac" to "AAC",
    "flac" to "FLAC",
    "alac" to "ALAC",
    "opus" to "Opus",
    "vorbis" to "Vorbis",
    "mp3" to "MP3",
    "mp2" to "MP2"
)
