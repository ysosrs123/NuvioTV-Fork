package com.nuvio.tv.ui.screens.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import com.nuvio.tv.R
import com.nuvio.tv.core.util.findCause
import java.util.Locale

/** Problems that Arc can show come from outside Arc, with the key the report and the inbox use. */
enum class PlayerErrorCause(val key: String) {
    ServerSilent("server_silent"),
    ServerRefused("server_refused"),
    Limit("limit"),
    ServerUnreachable("server_unreachable"),
    ProviderNotice("provider_notice"),
    NotVideo("not_video"),
    BoxCannotDecode("box_cannot_decode"),
    PictureUnreadable("picture_unreadable"),
    SourceSlow("source_slow"),
}

/**
 * What the error card shows for a known cause. [message] is also the player's error text, so the card and the
 * report check only trust a card whose message still matches the error on screen.
 */
data class PlayerErrorCard(
    val cause: PlayerErrorCause,
    val title: String,
    val message: String,
    val facts: String?,
    val fromWatchdog: Boolean = false,
    val sendAllowed: Boolean = true,
)

/**
 * Failures Arc has proven come from the source get no report: a report can't fix them. Once the same failure has
 * followed the viewer across [SEND_AFTER_SOURCES] different sources in a row, sending is allowed again.
 */
internal class OutsideFailureStreak {
    private var cause: PlayerErrorCause? = null
    private val sources = LinkedHashSet<String>()

    @Synchronized
    fun record(cause: PlayerErrorCause, sourceKey: String?): Boolean {
        if (cause != this.cause) {
            this.cause = cause
            sources.clear()
        }
        sources.add(sourceKey.orEmpty())
        return sources.size >= SEND_AFTER_SOURCES
    }

    @Synchronized
    fun reset() {
        cause = null
        sources.clear()
    }

    companion object {
        const val SEND_AFTER_SOURCES = 3
        val shared = OutsideFailureStreak()

        fun blocksSend(cause: PlayerErrorCause): Boolean = when (cause) {
            PlayerErrorCause.ProviderNotice,
            PlayerErrorCause.ServerRefused,
            PlayerErrorCause.Limit,
            PlayerErrorCause.ServerUnreachable -> true
            else -> false
        }
    }
}

internal fun PlayerErrorCard.applySendRule(sourceKey: String?, streak: OutsideFailureStreak): PlayerErrorCard =
    if (!OutsideFailureStreak.blocksSend(cause)) this else copy(sendAllowed = streak.record(cause, sourceKey))

/**
 * The card a player error would have shown, held while Arc looks for another source. The error screen that search
 * ends on shows it only when it is still about the same link and the same error.
 */
internal data class HeldFailoverCard(val sourceKey: String, val detail: String, val card: PlayerErrorCard) {
    fun forEnd(sourceKey: String, detail: String, streak: OutsideFailureStreak): PlayerErrorCard? =
        if (sourceKey != this.sourceKey || detail != this.detail) null else card.applySendRule(sourceKey, streak)

    /** The same card under the text the screen will show instead, as long as it is still about [from]. */
    fun shownAs(from: String, to: String): HeldFailoverCard? =
        takeIf { it.detail == from }?.copy(detail = to, card = card.copy(message = to))

    companion object {
        /** Each source that fails on the way counts towards bringing Send back, not only the last one. */
        fun hold(sourceKey: String, detail: String, card: PlayerErrorCard, streak: OutsideFailureStreak): HeldFailoverCard {
            if (OutsideFailureStreak.blocksSend(card.cause)) streak.record(card.cause, sourceKey)
            return HeldFailoverCard(sourceKey, detail, card)
        }
    }
}

internal fun PlayerErrorCard?.matching(error: String?): PlayerErrorCard? = this?.takeIf { error != null && it.message == error }

internal object PlayerErrorCauses {
    /** Only answers that are the server's own decision; a 400 or 416 can come from Arc's own request. */
    fun forHttpStatus(code: Int): PlayerErrorCause? = when (code) {
        429 -> PlayerErrorCause.Limit
        401, 403, 404, 410, 451 -> PlayerErrorCause.ServerRefused
        in 500..599 -> PlayerErrorCause.ServerRefused
        else -> null
    }

    /**
     * Unreachable only when not a single byte came from the source, so the server cannot have been half working.
     * Silent also after a little: a server that sends the start of an answer and then stops still sent no video.
     */
    fun forLoadFailure(exceptionClasses: List<String>, bytesLoaded: Long): PlayerErrorCause? {
        val timedOut = exceptionClasses.any { it == "java.net.SocketTimeoutException" }
        if (bytesLoaded > 0L) return PlayerErrorCause.ServerSilent.takeIf { timedOut && bytesLoaded <= SILENT_MAX_BYTES }
        return when {
            exceptionClasses.any { it in UNREACHABLE } -> PlayerErrorCause.ServerUnreachable
            timedOut -> PlayerErrorCause.ServerSilent
            else -> null
        }
    }

    /** More than a stalled start, but over the whole start-up wait less than the film itself needs to keep playing. */
    fun isSlowSource(bytesLoaded: Long, elapsedMs: Long, filmBitsPerSecond: Long?): Boolean {
        if (filmBitsPerSecond == null || filmBitsPerSecond <= 0L) return false
        if (elapsedMs < SLOW_MIN_ELAPSED_MS || bytesLoaded <= SILENT_MAX_BYTES) return false
        return bitsPerSecond(bytesLoaded, elapsedMs) < filmBitsPerSecond
    }

    fun bitsPerSecond(bytes: Long, elapsedMs: Long): Long = if (elapsedMs <= 0L) 0L else bytes * 8L * 1_000L / elapsedMs

    /** A file's average rate from its size and the title's running time; null when either is missing or too short. */
    fun filmBitsPerSecond(sizeBytes: Long?, runtimeMinutes: Int?): Long? {
        if (sizeBytes == null || sizeBytes <= 0L || runtimeMinutes == null || runtimeMinutes < MIN_RUNTIME_MINUTES) return null
        return sizeBytes * 8L / (runtimeMinutes * 60L)
    }

    /** An MPV start that failed on the connection itself, read from its log: the server refused, closed or can't be found. */
    fun forMpvLoadFailure(logLine: String?): PlayerErrorCause? {
        val log = logLine?.lowercase() ?: return null
        return PlayerErrorCause.ServerUnreachable.takeIf { MPV_CONNECTION_FAILURE.containsMatchIn(log) }
    }

    /** "25 KB", "8 MB", "1.2 GB": numbers and units only, so it needs no translation. */
    fun sizeText(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "${(bytes + 500_000L) / 1_000_000L} MB"
        else -> "${((bytes + 999L) / 1_000L).coerceAtLeast(1L)} KB"
    }

    /** "6 Mbit/s", "0.8 Mbit/s". */
    fun rateText(bitsPerSecond: Long): String {
        val mbit = bitsPerSecond / 1_000_000.0
        return if (mbit >= 10.0) "${Math.round(mbit)} Mbit/s" else String.format(Locale.ROOT, "%.1f Mbit/s", mbit)
    }

    const val SILENT_MAX_BYTES = 256L * 1024L
    private const val SLOW_MIN_ELAPSED_MS = 5_000L
    private const val MIN_RUNTIME_MINUTES = 20

    private val MPV_CONNECTION_FAILURE = Regex(
        "connection refused|connection reset|connection closed|broken pipe|failed to resolve|name or service not known|" +
            "no address associated|network is unreachable|tls: |ssl: |handshake"
    )

    /** "3840 × 2160 · HEVC · 59.94 fps": only numbers and codec names, so it needs no translation. */
    fun formatSummary(width: Int, height: Int, sampleMimeType: String?, frameRate: Float): String? {
        val parts = buildList {
            if (width > 0 && height > 0) add("$width × $height")
            codecName(sampleMimeType)?.let(::add)
            if (frameRate > 0f) {
                val rounded = Math.round(frameRate * 100f) / 100f
                val text = if (rounded % 1f == 0f) rounded.toInt().toString() else String.format(Locale.ROOT, "%.2f", rounded).trimEnd('0')
                add("$text fps")
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    fun codecName(sampleMimeType: String?): String? = when (sampleMimeType?.lowercase(Locale.ROOT)) {
        null -> null
        "video/hevc" -> "HEVC"
        "video/avc" -> "H.264"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/dolby-vision" -> "Dolby Vision"
        "video/mpeg2" -> "MPEG-2"
        "video/wvc1", "video/vc1" -> "VC-1"
        "video/mp4v-es" -> "MPEG-4"
        "video/divx" -> "DivX"
        else -> null
    }

    fun factsLine(vararg parts: String?): String? =
        parts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

    fun causeClasses(error: Throwable): List<String> =
        generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(8).map { it.javaClass.name }.toList()

    private val UNREACHABLE = setOf(
        "java.net.ConnectException",
        "java.net.UnknownHostException",
        "java.net.NoRouteToHostException",
    )
}

internal fun httpErrorCard(context: Context, code: Int, source: String?, fromWatchdog: Boolean = false): PlayerErrorCard? {
    val cause = PlayerErrorCauses.forHttpStatus(code) ?: return null
    val (title, message) = when {
        code == 429 -> R.string.player_error_card_limit_title to context.getString(R.string.player_error_card_limit)
        code == 401 -> R.string.player_error_card_expired_title to context.getString(R.string.player_error_card_expired, code)
        code == 404 || code == 410 -> R.string.player_error_card_gone_title to context.getString(R.string.player_error_card_gone, code)
        code >= 500 -> R.string.player_error_card_fault_title to context.getString(R.string.player_error_card_fault, code)
        else -> R.string.player_error_card_blocked_title to context.getString(R.string.player_error_card_blocked, code)
    }
    return PlayerErrorCard(cause, context.getString(title), message, PlayerErrorCauses.factsLine(source, "HTTP $code"), fromWatchdog)
}

internal fun silentServerCard(
    context: Context,
    seconds: Int,
    source: String?,
    fromWatchdog: Boolean,
    bytesLoaded: Long = 0L,
): PlayerErrorCard =
    PlayerErrorCard(
        PlayerErrorCause.ServerSilent,
        context.getString(R.string.player_error_card_silent_title),
        context.getString(R.string.player_error_card_silent, seconds),
        PlayerErrorCauses.factsLine(
            source,
            bytesLoaded.takeIf { it > 0L }?.let(PlayerErrorCauses::sizeText),
            context.getString(R.string.player_error_facts_no_answer),
            context.getString(R.string.player_error_facts_seconds, seconds)
        ),
        fromWatchdog
    )

internal fun slowSourceCard(
    context: Context,
    bytesLoaded: Long,
    elapsedMs: Long,
    filmBitsPerSecond: Long,
    source: String?,
): PlayerErrorCard {
    val seconds = (elapsedMs / 1_000L).toInt()
    val got = PlayerErrorCauses.rateText(PlayerErrorCauses.bitsPerSecond(bytesLoaded, elapsedMs))
    val needs = PlayerErrorCauses.rateText(filmBitsPerSecond)
    return PlayerErrorCard(
        PlayerErrorCause.SourceSlow,
        context.getString(R.string.player_error_card_slow_title),
        context.getString(R.string.player_error_card_slow, PlayerErrorCauses.sizeText(bytesLoaded), seconds, got, needs),
        PlayerErrorCauses.factsLine(source, got, context.getString(R.string.player_error_facts_seconds, seconds)),
        fromWatchdog = true
    )
}

internal fun unreachableServerCard(context: Context, source: String?, fromWatchdog: Boolean): PlayerErrorCard =
    PlayerErrorCard(
        PlayerErrorCause.ServerUnreachable,
        context.getString(R.string.player_error_card_unreachable_title),
        context.getString(R.string.player_error_card_unreachable),
        PlayerErrorCauses.factsLine(source, context.getString(R.string.player_error_facts_no_connection)),
        fromWatchdog
    )

internal fun boxCannotDecodeCard(context: Context, format: Format?, source: String?, fromWatchdog: Boolean): PlayerErrorCard {
    val summary = format?.let { PlayerErrorCauses.formatSummary(it.width, it.height, it.sampleMimeType, it.frameRate) }
    return PlayerErrorCard(
        PlayerErrorCause.BoxCannotDecode,
        context.getString(R.string.player_error_card_box_title),
        if (summary != null) context.getString(R.string.player_error_card_box, summary)
        else context.getString(R.string.player_error_card_box_no_detail),
        PlayerErrorCauses.factsLine(source, summary),
        fromWatchdog
    )
}

internal fun pictureUnreadableCard(context: Context, source: String?): PlayerErrorCard =
    PlayerErrorCard(
        PlayerErrorCause.PictureUnreadable,
        context.getString(R.string.player_error_card_unreadable_title),
        context.getString(R.string.player_error_startup_video_track_unreadable),
        PlayerErrorCauses.factsLine(source),
        fromWatchdog = true
    )

internal fun providerNoticeCard(context: Context, source: String?): PlayerErrorCard =
    PlayerErrorCard(
        PlayerErrorCause.ProviderNotice,
        context.getString(R.string.player_error_card_notice_title),
        context.getString(R.string.player_error_card_notice),
        PlayerErrorCauses.factsLine(source)
    )

/** The card for a fatal player error, or null when Arc cannot prove where the problem comes from. */
@OptIn(UnstableApi::class)
internal fun PlaybackException.errorCard(
    context: Context,
    source: String?,
    lastLoadErrorClasses: List<String>,
    lastLoadErrorMs: Long?,
    bytesLoaded: Long,
): PlayerErrorCard? {
    findInvalidResponseCodeException()?.let { return httpErrorCard(context, it.responseCode, source) }
    if (findCause<androidx.media3.exoplayer.source.UnrecognizedInputFormatException>() != null) {
        return PlayerErrorCard(
            PlayerErrorCause.NotVideo,
            context.getString(R.string.player_error_card_not_video_title),
            context.getString(R.string.player_error_card_not_video),
            PlayerErrorCauses.factsLine(source)
        )
    }
    val renderer = this as? ExoPlaybackException
    val videoRenderer = renderer?.type == ExoPlaybackException.TYPE_RENDERER &&
        MimeTypes.isVideo(renderer.rendererFormat?.sampleMimeType)
    val boxSaidNo = errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
        errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
        (videoRenderer && renderer?.rendererFormatSupport != C.FORMAT_HANDLED)
    if (boxSaidNo && (videoRenderer || renderer?.rendererFormat == null)) {
        return boxCannotDecodeCard(context, renderer?.rendererFormat?.takeIf { videoRenderer }, source, fromWatchdog = false)
    }
    val classes = PlayerErrorCauses.causeClasses(this) + lastLoadErrorClasses
    return when (PlayerErrorCauses.forLoadFailure(classes, bytesLoaded)) {
        PlayerErrorCause.ServerUnreachable -> unreachableServerCard(context, source, fromWatchdog = false)
        PlayerErrorCause.ServerSilent -> lastLoadErrorMs?.takeIf { it >= 1_000L }
            ?.let { silentServerCard(context, (it / 1_000L).toInt(), source, fromWatchdog = false, bytesLoaded = bytesLoaded) }
        else -> null
    }
}
