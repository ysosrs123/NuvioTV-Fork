package com.nuvio.tv.ui.screens.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities

/**
 * Bitstream output after the HDMI audio device went away and came back, for example when the TV
 * switches to another input and HDMI-CEC puts the box to sleep. On TVs the sink's capabilities
 * are read once when the player is built, so a player built while HDMI was gone decodes to PCM
 * for the rest of the title.
 */
@OptIn(UnstableApi::class)
internal object PassthroughOutputReturn {
    const val POLL_MS = 500L
    const val MAX_RETRY_WAIT_MS = 10 * 60_000L
    const val SECOND_CHECK_DELAY_MS = 3_000L
    const val MAX_REBUILDS_PER_STREAM = 3

    private val ENCODINGS = intArrayOf(
        C.ENCODING_AC3,
        C.ENCODING_E_AC3,
        C.ENCODING_E_AC3_JOC,
        C.ENCODING_AC4,
        C.ENCODING_DTS,
        C.ENCODING_DTS_HD,
        C.ENCODING_DTS_UHD_P2,
        C.ENCODING_DOLBY_TRUEHD
    )

    private val movieAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    fun capabilities(context: Context): AudioCapabilities =
        AudioCapabilities.getCapabilities(context, movieAttributes, null)

    fun encodingsOf(capabilities: AudioCapabilities): Set<Int> =
        ENCODINGS.filterTo(mutableSetOf()) { capabilities.supportsEncoding(it) }

    fun liveEncodings(context: Context): Set<Int> =
        runCatching { encodingsOf(capabilities(context)) }.getOrDefault(emptySet())

    fun encodingOf(format: Format?): Int? {
        val mime = format?.sampleMimeType ?: return null
        if (mime == MimeTypes.AUDIO_RAW) return null
        return MimeTypes.getEncoding(mime, format.codecs).takeIf { it in ENCODINGS }
    }

    /** Same fallbacks the platform sink uses: E-AC-3 JOC as E-AC-3, DTS-HD as its DTS core. */
    fun canPass(encodings: Set<Int>, encoding: Int): Boolean = encoding in encodings ||
        (encoding == C.ENCODING_E_AC3_JOC && C.ENCODING_E_AC3 in encodings) ||
        (encoding == C.ENCODING_DTS_HD && C.ENCODING_DTS in encodings)

    /**
     * Whether a refused bitstream open may be retried now: the output takes a bitstream format
     * again (a refusal there is not about a missing output), or media is routed to HDMI again.
     */
    fun outputReadyForRetry(live: Set<Int>, routeIsHdmi: Boolean): Boolean =
        live.isNotEmpty() || routeIsHdmi

    fun retryMayProceed(outputReady: Boolean, waitedMs: Long): Boolean =
        outputReady || waitedMs >= MAX_RETRY_WAIT_MS

    /**
     * Whether the player should be rebuilt so the current track goes out as bitstream again:
     * the output takes it now, but the player was built when it did not.
     */
    fun shouldRebuild(
        encoding: Int?,
        pinned: Set<Int>?,
        live: Set<Int>,
        alreadyBitstream: Boolean,
        claimedByIec: Boolean,
        policyDenies: Boolean,
        forcedPcm: Boolean,
        rebuildsSoFar: Int
    ): Boolean {
        if (encoding == null || pinned == null) return false
        if (alreadyBitstream || claimedByIec || policyDenies || forcedPcm) return false
        if (rebuildsSoFar >= MAX_REBUILDS_PER_STREAM) return false
        return !canPass(pinned, encoding) && canPass(live, encoding)
    }

    fun isHdmiRoute(routeKey: String?): Boolean = routeKey?.startsWith("type:hdmi") == true
}
