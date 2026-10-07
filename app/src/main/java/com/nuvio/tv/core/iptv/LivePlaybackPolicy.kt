package com.nuvio.tv.core.iptv

import androidx.media3.common.PlaybackException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

enum class LiveFailure { DENIED, MISSING, UNSUPPORTED, DECODER, EXHAUSTED }

sealed interface LiveRetryDecision {
    data class Retry(val delayMs: Long) : LiveRetryDecision
    data class Fail(val failure: LiveFailure) : LiveRetryDecision
}

object LiveRetry {
    private val LIVE_DELAYS_MS = longArrayOf(1_000, 2_000, 3_000, 5_000)
    private val CATCHUP_DELAYS_MS = longArrayOf(1_000, 2_000, 3_000, 5_000, 5_000, 5_000)
    const val STEADY_DELAY_MS = 10_000L
    const val MAX_RETRY_AFTER_MS = 60_000L

    fun delay(live: Boolean, attempt: Int): Long? = when {
        attempt < 0 -> null
        live -> LIVE_DELAYS_MS.getOrNull(attempt) ?: STEADY_DELAY_MS
        else -> CATCHUP_DELAYS_MS.getOrNull(attempt)
    }

    fun failure(errorCode: Int?, httpStatus: Int?): LiveFailure? = when {
        httpStatus == 401 || httpStatus == 403 -> LiveFailure.DENIED
        httpStatus == 404 || httpStatus == 410 -> LiveFailure.MISSING
        errorCode == null -> null
        errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION || errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> LiveFailure.DENIED
        errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> LiveFailure.MISSING
        errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED || errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
            errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED || errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
            errorCode in 6000..6999 -> LiveFailure.UNSUPPORTED
        errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED || errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> LiveFailure.DECODER
        else -> null
    }

    fun retryAfterMs(value: String?, nowMillis: Long): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return null
        val millis = text.toLongOrNull()?.takeIf { it >= 0 }?.let { if (it > MAX_RETRY_AFTER_MS / 1000) MAX_RETRY_AFTER_MS else it * 1000 }
            ?: runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis }.getOrNull()
            ?: return null
        return millis.coerceIn(0, MAX_RETRY_AFTER_MS)
    }

    fun decide(live: Boolean, attempt: Int, errorCode: Int?, httpStatus: Int?, retryAfter: String?, nowMillis: Long): LiveRetryDecision {
        failure(errorCode, httpStatus)?.let { return LiveRetryDecision.Fail(it) }
        val base = delay(live, attempt) ?: return LiveRetryDecision.Fail(LiveFailure.EXHAUSTED)
        val wait = if (httpStatus == 429 || httpStatus == 503) retryAfterMs(retryAfter, nowMillis) else null
        return LiveRetryDecision.Retry(maxOf(base, wait ?: 0L))
    }
}

class FrozenVideoWatch(private val limitMs: Long = 8_000) {
    private var progress: Long? = null
    private var since = 0L

    fun reset() { progress = null }

    fun frozen(nowMs: Long, active: Boolean, value: Long): Boolean {
        if (!active) { progress = null; return false }
        if (progress != value) { progress = value; since = nowMs; return false }
        return nowMs - since >= limitMs
    }
}

object LiveFrameRate {
    private val RATES = floatArrayOf(24000f / 1001f, 25f, 30000f / 1001f, 50f, 60000f / 1001f)

    fun estimate(frames: Long, mediaMs: Long): Float? {
        if (frames < 60 || mediaMs < 3_000) return null
        val rate = frames * 1000f / mediaMs
        return RATES.minByOrNull { abs(it - rate) }?.takeIf { abs(it - rate) <= it * 0.03f }
    }
}
