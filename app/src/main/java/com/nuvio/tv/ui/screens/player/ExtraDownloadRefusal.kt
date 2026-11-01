package com.nuvio.tv.ui.screens.player

import androidx.media3.datasource.HttpDataSource
import java.io.EOFException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * A server that will not take a second download from the same viewer: a piece request refused (401, 403) or reset or
 * cut off once open, while another download to that server keeps delivering. When every download fails together it
 * is an outage or an expired link, and nothing is learnt. "Too many requests" (429, 503) keeps its own rule.
 */
internal object ExtraDownloadRefusal {
    /** Another piece counts as delivering when its bytes arrived this recently. */
    const val DELIVERING_WITHIN_MS = 3_000L

    private val REFUSED_CODES = setOf(401, 403)

    fun isRefusal(error: Throwable): Boolean {
        if (PlaybackNetworkFailure.isConnectFailure(error)) return false
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < 6) {
            when (cause) {
                is HttpDataSource.InvalidResponseCodeException -> return cause.responseCode in REFUSED_CODES
                is EOFException -> return true
                is ProtocolException -> return cause.message.orEmpty().contains("unexpected end of stream", true)
                is SocketException -> {
                    val message = cause.message.orEmpty()
                    return message.contains("reset", true) || message.contains("broken pipe", true)
                }
            }
            cause = cause.cause
            depth++
        }
        return false
    }

    /** True when a piece other than [refused] delivered bytes within [DELIVERING_WITHIN_MS] of [nowMs]. */
    fun othersDelivering(deliveredAtMs: Map<Long, Long>, refused: Long, nowMs: Long): Boolean =
        deliveredAtMs.any { (piece, atMs) -> piece != refused && nowMs - atMs in 0..DELIVERING_WITHIN_MS }

    /** Pieces served within this long count as served side by side. */
    const val SERVED_WITHIN_MS = 30_000L

    /**
     * A failure a server that bans parallel downloads gives: a refusal, a refused connection or a read that timed out.
     * An address that cannot be found or reached at all is the box's own network.
     */
    fun isBlock(error: Throwable): Boolean {
        if (isRefusal(error)) return true
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < 6) {
            when (cause) {
                is ConnectException -> return cause.message.orEmpty().contains("refused", true)
                is SocketTimeoutException -> return !cause.message.orEmpty().contains("connect", true)
            }
            cause = cause.cause
            depth++
        }
        return false
    }

    /** The pieces that delivered bytes within [SERVED_WITHIN_MS] of [nowMs]. */
    fun servedRecently(deliveredAtMs: Map<Long, Long>, nowMs: Long): Int =
        deliveredAtMs.count { (_, atMs) -> nowMs - atMs in 0..SERVED_WITHIN_MS }
}

/** A download that failed while no other to the server delivered, as [ParallelBlockTrial.failed] takes it. */
internal data class AllDownloadsFailed(
    val running: Int,
    val served: Int,
    val ageMs: Long,
    val beside: Int,
    val block: Boolean
)

/**
 * A server that serves several downloads for a while, then blocks them all at once and serves one again: it bans
 * parallel downloads. When every download fails together after [running] ran side by side, playback tries the server
 * on one ([Verdict.TRYING]). One download carrying [confirmBytes] again within [trialMs] proves the ban
 * ([Verdict.LEARNT]); the lone try failing too, or the server serving as many again, is an outage or an expired link
 * ([Verdict.CLEARED]), and nothing is learnt.
 */
internal class ParallelBlockTrial(
    private val trialMs: Long = 90_000L,
    private val confirmBytes: Long = 4L * 1024 * 1024
) {
    enum class Verdict { NONE, TRYING, LEARNT, CLEARED }

    private data class Trial(val atMs: Long, val running: Int, val bytes: Long)

    private var trial: Trial? = null

    val trying: Boolean @Synchronized get() = trial != null

    /** The running parallel downloads when the trial began, 0 without one. */
    val trialRunning: Int @Synchronized get() = trial?.running ?: 0

    /**
     * A download failed while no other delivered. [running] = requests running at the failure, it included; [served] =
     * pieces served recently ([ExtraDownloadRefusal.servedRecently]); [startedAtMs] = when the failed request was sent,
     * [beside] = the requests running when it was sent; [block] = [ExtraDownloadRefusal.isBlock]; [bytes] = what
     * playback received so far.
     */
    @Synchronized
    fun failed(
        running: Int,
        served: Int,
        startedAtMs: Long,
        beside: Int,
        block: Boolean,
        bytes: Long,
        nowMs: Long
    ): Verdict {
        val t = trial
        if (t == null) {
            if (!block || running < 2 || served < 2) return Verdict.NONE
            trial = Trial(nowMs, running, bytes)
            return Verdict.TRYING
        }
        if (nowMs - t.atMs >= trialMs) {
            trial = null
            return Verdict.CLEARED
        }
        // Requests sent before the block belong to it; a retry sent beside others proves nothing.
        if (startedAtMs < t.atMs || beside > 0) return Verdict.NONE
        trial = null
        return Verdict.CLEARED
    }

    /** Playback received [bytes] in all so far, with [running] requests running now. */
    @Synchronized
    fun progress(bytes: Long, running: Int, nowMs: Long): Verdict {
        val t = trial ?: return Verdict.NONE
        if (nowMs - t.atMs >= trialMs) {
            trial = null
            return Verdict.CLEARED
        }
        if (bytes - t.bytes < confirmBytes) return Verdict.NONE
        trial = null
        return if (running < t.running) Verdict.LEARNT else Verdict.CLEARED
    }

    @Synchronized
    fun reset() {
        trial = null
    }
}

/**
 * Strikes per server. The first refused extra download holds the server to one download fewer than were running; a
 * second within [holdMs] holds it to one. Refusals within [burstMs] of a strike (a retry of the same piece, pieces
 * refused together) belong to that strike.
 */
internal class ExtraDownloadStrikes(
    private val holdMs: Long = HostSpeeds.LIMIT_EASE_MS,
    private val burstMs: Long = 10_000L
) {
    private data class Strike(val atMs: Long, val limit: Int)

    private val strikes = ConcurrentHashMap<String, Strike>()

    /** The downloads to hold [key] to after it refused an extra one with [running] requests running (it included). */
    fun limitAfter(key: String, running: Int, nowMs: Long): Int {
        val previous = strikes[key]
        val limit = when {
            previous == null || nowMs - previous.atMs >= holdMs -> (running - 1).coerceAtLeast(1)
            nowMs - previous.atMs < burstMs -> return previous.limit
            else -> 1
        }
        strikes[key] = Strike(nowMs, limit)
        return limit
    }

    fun clear() = strikes.clear()
}
