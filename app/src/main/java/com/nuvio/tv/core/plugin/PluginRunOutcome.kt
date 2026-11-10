package com.nuvio.tv.core.plugin

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

enum class PluginRunState { FOUND, NO_RESULTS, DOWN, CANT_RUN, STOPPED }

enum class PluginRunReason { TIMEOUT, HTTP, UNREACHABLE, ERROR, NEWER_LIBRARY, DID_NOT_LOAD }

data class PluginRunOutcome(
    val scraperId: String,
    val state: PluginRunState,
    val streams: Int = 0,
    val reason: PluginRunReason? = null,
    val httpCode: Int? = null
)

data class PluginSearchSummary(
    val total: Int,
    val outcomes: List<PluginRunOutcome>
) {
    fun count(state: PluginRunState): Int = outcomes.count { it.state == state }

    fun withUnfinishedStopped(scraperIds: List<String>): PluginSearchSummary {
        if (outcomes.size >= total) return this
        val recorded = outcomes.mapTo(HashSet()) { it.scraperId }
        return copy(outcomes = outcomes + scraperIds.filter { it !in recorded }.map { PluginRunOutcome(it, PluginRunState.STOPPED) })
    }
}

fun pluginSearchKey(type: String, videoId: String, season: Int?, episode: Int?): String =
    "${type.lowercase()}:$videoId:${season ?: ""}:${episode ?: ""}"

/** Collects the first problem a running extension hits, so a run with no results can say why. */
class PluginRunProblems(private val linksDueAtMs: Long? = null) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PluginRunProblems>

    @Volatile var reason: PluginRunReason? = null
        private set
    @Volatile var httpCode: Int? = null
        private set

    /** How long an extension may keep collecting links, so what it has found is delivered before the search limit. */
    fun linkWaitMs(capMs: Long, nowMs: Long = System.currentTimeMillis()): Long =
        linksDueAtMs?.let { (it - nowMs).coerceIn(0L, capMs) } ?: capMs

    fun note(reason: PluginRunReason, httpCode: Int? = null) {
        if (this.reason == null || this.reason == PluginRunReason.ERROR) {
            this.reason = reason
            this.httpCode = httpCode
        }
    }

    fun noteFailure(error: Throwable) {
        when {
            error is LinkageError -> note(PluginRunReason.NEWER_LIBRARY)
            else -> {
                val (reason, code) = classifyFailure(error)
                note(reason, code)
            }
        }
    }

    fun outcome(scraperId: String, streams: Int): PluginRunOutcome {
        if (streams > 0) return PluginRunOutcome(scraperId, PluginRunState.FOUND, streams)
        val why = reason ?: return PluginRunOutcome(scraperId, PluginRunState.NO_RESULTS)
        val state = if (why == PluginRunReason.NEWER_LIBRARY || why == PluginRunReason.DID_NOT_LOAD) {
            PluginRunState.CANT_RUN
        } else {
            PluginRunState.DOWN
        }
        return PluginRunOutcome(scraperId, state, 0, why, httpCode)
    }
}

private val HTTP_CODE = Regex("""(?i)\b(?:http|code|status)\D{0,12}([45]\d\d)\b""")

internal fun classifyFailure(error: Throwable): Pair<PluginRunReason, Int?> {
    var current: Throwable? = error
    while (current != null) {
        when (current) {
            is InterruptedIOException -> return PluginRunReason.TIMEOUT to null
            is UnknownHostException, is ConnectException, is SSLException -> return PluginRunReason.UNREACHABLE to null
        }
        HTTP_CODE.find(current.message.orEmpty())?.let { return PluginRunReason.HTTP to it.groupValues[1].toInt() }
        current = current.cause?.takeIf { it !== current }
    }
    return PluginRunReason.ERROR to null
}
