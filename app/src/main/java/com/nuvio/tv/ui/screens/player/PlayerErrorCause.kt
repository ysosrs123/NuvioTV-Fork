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

internal fun PlayerErrorCard?.matching(error: String?): PlayerErrorCard? = this?.takeIf { error != null && it.message == error }

internal object PlayerErrorCauses {
    /** Only answers that are the server's own decision; a 400 or 416 can come from Arc's own request. */
    fun forHttpStatus(code: Int): PlayerErrorCause? = when (code) {
        429 -> PlayerErrorCause.Limit
        401, 403, 404, 410, 451 -> PlayerErrorCause.ServerRefused
        in 500..599 -> PlayerErrorCause.ServerRefused
        else -> null
    }

    /** Only when not a single byte came from the source, so the server cannot have been half working. */
    fun forLoadFailure(exceptionClasses: List<String>, bytesLoaded: Long): PlayerErrorCause? {
        if (bytesLoaded > 0L) return null
        return when {
            exceptionClasses.any { it in UNREACHABLE } -> PlayerErrorCause.ServerUnreachable
            exceptionClasses.any { it == "java.net.SocketTimeoutException" } -> PlayerErrorCause.ServerSilent
            else -> null
        }
    }

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

internal fun silentServerCard(context: Context, seconds: Int, source: String?, fromWatchdog: Boolean): PlayerErrorCard =
    PlayerErrorCard(
        PlayerErrorCause.ServerSilent,
        context.getString(R.string.player_error_card_silent_title),
        context.getString(R.string.player_error_card_silent, seconds),
        PlayerErrorCauses.factsLine(
            source,
            context.getString(R.string.player_error_facts_no_answer),
            context.getString(R.string.player_error_facts_seconds, seconds)
        ),
        fromWatchdog
    )

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
    lastLoadErrorClass: String?,
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
    val classes = generateSequence<Throwable>(this) { it.cause }.take(8).map { it.javaClass.name }.toList() +
        listOfNotNull(lastLoadErrorClass)
    return when (PlayerErrorCauses.forLoadFailure(classes, bytesLoaded)) {
        PlayerErrorCause.ServerUnreachable -> unreachableServerCard(context, source, fromWatchdog = false)
        PlayerErrorCause.ServerSilent -> lastLoadErrorMs?.takeIf { it >= 1_000L }
            ?.let { silentServerCard(context, (it / 1_000L).toInt(), source, fromWatchdog = false) }
        else -> null
    }
}
