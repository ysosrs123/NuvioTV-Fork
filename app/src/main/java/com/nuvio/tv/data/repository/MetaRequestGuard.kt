package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.retryAfterDelayMillis
import java.net.URI
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Names the feature asking for meta, for the request summary. Untagged requests count as "other". */
class MetaCaller(val name: String) : AbstractCoroutineContextElement(MetaCaller) {
    companion object Key : CoroutineContext.Key<MetaCaller>
}

/** A title page the viewer opened: it asks even an addon that is resting or a host that is paused, and leaves the rest as it was. */
internal const val TITLE_PAGE_META_CALLER = "title-page"

/** Continue Watching lookups, which can wait while a film plays. */
internal fun isBackgroundMetaCaller(caller: String): Boolean = caller == "cw" || caller.startsWith("cw-")

internal fun addonHost(url: String): String =
    runCatching { URI(url).host }.getOrNull()?.lowercase()?.takeIf { it.isNotBlank() } ?: url

/**
 * Pauses meta requests to a host after it answers 429. Several installed addons can live on one host
 * (two AIOMetadata configs, for example), so the pause is per host rather than per addon. Honours
 * Retry-After; without one the pause starts at [defaultPauseMs] and doubles while 429s keep coming.
 * An addon error item gives a fixed [errorItemPauseMs] pause instead, which never counts as a 429.
 */
internal class AddonRateLimits(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val defaultPauseMs: Long = 30_000L,
    private val maxPauseMs: Long = 10 * 60_000L,
    private val errorItemPauseMs: Long = 30_000L
) {
    private class State(val pausedUntilMs: Long, val streak: Int, val rateLimited: Boolean)

    private val states = HashMap<String, State>()

    @Synchronized
    fun isPaused(host: String): Boolean {
        val state = states[host] ?: return false
        return nowMs() < state.pausedUntilMs
    }

    /** Returns the pause in ms that now applies to [host]. */
    @Synchronized
    fun recordRateLimited(host: String, retryAfterHeader: String?): Long {
        val now = nowMs()
        val previous = states[host]
        val streak = (previous?.streak ?: 0) + 1
        val fallback = (defaultPauseMs shl (streak - 1).coerceIn(0, 5)).coerceAtMost(maxPauseMs)
        val pause = retryAfterDelayMillis(retryAfterHeader, now, fallback).coerceIn(1_000L, maxPauseMs)
        val until = maxOf(previous?.pausedUntilMs ?: 0L, now + pause)
        states[host] = State(until, streak, rateLimited = true)
        return until - now
    }

    /** True while the pause on [host] comes from a real 429 answer. */
    @Synchronized
    fun isRateLimited(host: String): Boolean {
        val state = states[host] ?: return false
        return state.rateLimited && nowMs() < state.pausedUntilMs
    }

    /** Returns the pause in ms that now applies to [host]. */
    @Synchronized
    fun recordErrorItem(host: String): Long {
        val now = nowMs()
        val previous = states[host]?.takeIf { now < it.pausedUntilMs }
        val until = maxOf(previous?.pausedUntilMs ?: 0L, now + errorItemPauseMs)
        states[host] = State(until, states[host]?.streak ?: 0, rateLimited = previous?.rateLimited == true)
        return until - now
    }

    @Synchronized
    fun recordSuccess(host: String) {
        val state = states[host] ?: return
        if (nowMs() >= state.pausedUntilMs) states.remove(host)
    }

    @Synchronized
    fun clear() = states.clear()
}

/**
 * Counts meta requests so a start-up can be summed up in one log line instead of one per request.
 * The first window covers the first minute after the first request; later windows are longer.
 */
internal class MetaRequestStats(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val firstWindowMs: Long = 60_000L,
    private val laterWindowMs: Long = 10 * 60_000L
) {
    private val requestsByCaller = LinkedHashMap<String, Int>()
    private val requestsByAddon = LinkedHashMap<String, Int>()
    private val rateLimitedByAddon = LinkedHashMap<String, Int>()
    private val skippedByAddon = LinkedHashMap<String, Int>()
    private var windowStartMs = 0L
    private var windows = 0

    /** Returns true when this is the first event of a new window, so the caller can schedule [drain]. */
    @Synchronized
    fun request(caller: String, addon: String): Boolean = event {
        requestsByCaller.bump(caller)
        requestsByAddon.bump(addon)
    }

    @Synchronized
    fun rateLimited(addon: String) {
        rateLimitedByAddon.bump(addon)
    }

    @Synchronized
    fun skipped(caller: String, addon: String): Boolean = event {
        requestsByCaller.bump("$caller(paused)")
        skippedByAddon.bump(addon)
    }

    /** Length of the window that the last [request] or [skipped] opened. */
    @Synchronized
    fun currentWindowMs(): Long = if (windows <= 1) firstWindowMs else laterWindowMs

    @Synchronized
    fun drain(): String? {
        if (windowStartMs == 0L) return null
        val seconds = (nowMs() - windowStartMs) / 1000
        val total = requestsByAddon.values.sum()
        val line = "Meta requests in ${seconds}s (window $windows): total=$total " +
            "429=${rateLimitedByAddon.values.sum()} paused=${skippedByAddon.values.sum()} " +
            "callers=${requestsByCaller.summary()} addons=${requestsByAddon.summary()} " +
            "429s=${rateLimitedByAddon.summary()} pausedSkips=${skippedByAddon.summary()}"
        requestsByCaller.clear()
        requestsByAddon.clear()
        rateLimitedByAddon.clear()
        skippedByAddon.clear()
        windowStartMs = 0L
        return line
    }

    private inline fun event(block: () -> Unit): Boolean {
        val opened = windowStartMs == 0L
        if (opened) {
            windowStartMs = nowMs()
            windows++
        }
        block()
        return opened
    }

    private fun MutableMap<String, Int>.bump(key: String) {
        this[key] = (this[key] ?: 0) + 1
    }

    private fun Map<String, Int>.summary(): String =
        if (isEmpty()) "{}" else entries.sortedByDescending { it.value }.joinToString(",", "{", "}") { "${it.key}=${it.value}" }
}

/** AIOStreams answers some failures, its rate limit among them, with an item whose id carries the error instead of a title. */
internal fun isAddonErrorMetaId(id: String?): Boolean = id?.startsWith("aiostreamserror.", ignoreCase = true) == true

/**
 * Stops asking an addon for meta for a while once it has answered "not found" for [missesToRest] different titles in a
 * row. An AIOStreams setup without a meta addon declares meta yet answers 404 to everything, and a Home start then sent
 * it dozens of requests at once. Any title found clears the count.
 */
internal class AddonMetaMisses(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val missesToRest: Int = 8,
    private val restMs: Long = 10 * 60_000L
) {
    private class State(val misses: Int, val restingUntilMs: Long)

    private val states = HashMap<String, State>()

    @Synchronized
    fun isResting(addonKey: String): Boolean {
        val state = states[addonKey] ?: return false
        if (state.restingUntilMs == 0L) return false
        if (nowMs() < state.restingUntilMs) return true
        states.remove(addonKey)
        return false
    }

    /** Returns true when this miss starts a rest. */
    @Synchronized
    fun recordMiss(addonKey: String): Boolean {
        val misses = (states[addonKey]?.misses ?: 0) + 1
        val rest = misses >= missesToRest
        states[addonKey] = State(if (rest) 0 else misses, if (rest) nowMs() + restMs else 0L)
        return rest
    }

    @Synchronized
    fun recordFound(addonKey: String) {
        states.remove(addonKey)
    }

    @Synchronized
    fun clear() = states.clear()
}
