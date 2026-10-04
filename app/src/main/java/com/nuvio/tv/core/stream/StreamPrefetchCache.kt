package com.nuvio.tv.core.stream

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.PrefetchedSelection
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.repository.StreamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/** Per-profile, opt-in source listing cache. Provider resolution and media access are playback-only.
 * One cancellable search runs at a time; two completed metadata entries live for at most five minutes.
 * Explicit Play reuses metadata where possible and falls back to its normal repository search.
 */
object StreamPrefetchCache {

    private const val TAG = "StreamPrefetch"
    private const val TTL_MS = 5L * 60L * 1000L
    private const val MAX_ENTRIES = 2
    private const val JOIN_TIMEOUT_MS = 20_000L

    /** Outlives any ViewModel: the details screen may be gone before this finishes. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Entry(
        val streams: List<AddonStreams>,
        val atMs: Long,
        /** Winner ranked at prefetch time, null when no ranker was supplied. */
        val selection: PrefetchedSelection?,
        /**
         * True when this pool was cut short by the prefetch completion cap
         * (see collectFinal). Remaining speculative source children are cancelled.
         * Explicit Play re-collects normally instead of treating a subset as final.
         */
        val capHit: Boolean = false
    )

    private val lock = Any()
    private var policyProfile: Int? = null
    private var policyEnabled = false
    private val allowed = MutableStateFlow(false)
    val speculationAllowed = allowed.asStateFlow()
    private var policyGeneration = 0L
    private var currentProfile: () -> Int? = { null }

    fun bindProfile(reader: () -> Int?) = synchronized(lock) { currentProfile = reader }

    /** No speculative work until the current profile's saved preference is loaded. */
    fun updatePolicy(profile: Int, enabled: Boolean) = synchronized(lock) {
        if (policyProfile == profile && policyEnabled == enabled) return@synchronized
        if (policyProfile != profile) completed.clear()
        policyProfile = profile
        policyEnabled = enabled
        cancelPendingLocked()
        allowed.value = permittedLocked()
    }

    fun cancelPending() = synchronized(lock) { cancelPendingLocked() }

    private fun cancelPendingLocked() {
        policyGeneration++
        scope.coroutineContext.cancelChildren()
        inFlightJob = null
        inFlightKey = null
    }

    private fun permittedLocked() = policyEnabled && policyProfile == currentProfile()

    private val completed = LinkedHashMap<String, Entry>(4, 0.75f, true)
    private var inFlightKey: String? = null
    private var inFlightJob: Deferred<List<AddonStreams>>? = null

    /**
     * True when the in-flight job was started by a background warmer.
     * Consulted only while [inFlightJob] is active, so a stale value after the
     * job clears itself is harmless. Guarded by [lock] like its siblings.
     */
    private var inFlightBackground: Boolean = false

    fun keyOf(type: String, videoId: String, season: Int?, episode: Int?): String {
        return (currentProfile()?.toString() ?: "unbound") + "|" + type + "|" + videoId + "|" + (season ?: -1) + "|" + (episode ?: -1)
    }

    /** Caller must hold [lock]. */
    private fun freshLocked(key: String): List<AddonStreams>? {
        if (policyProfile != currentProfile()) return null
        val entry = completed[key] ?: return null
        if (SystemClock.elapsedRealtime() - entry.atMs > TTL_MS) {
            completed.remove(key)
            return null
        }
        return entry.streams
    }

    /** Caller must hold [lock]. */
    private fun putLocked(
        key: String,
        streams: List<AddonStreams>,
        selection: PrefetchedSelection?,
        capHit: Boolean
    ) {
        completed[key] = Entry(streams, SystemClock.elapsedRealtime(), selection, capHit)
        while (completed.size > MAX_ENTRIES) {
            val eldest = completed.keys.firstOrNull() ?: break
            completed.remove(eldest)
        }
    }

    /**
     * Start (or keep) a prefetch for this target. Cheap and idempotent: a fresh
     * completed entry or an identical in-flight job is a no-op.
     */
    fun prefetch(
        repository: StreamRepository,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        /** Which producer started this prefetch, for log attribution. */
        source: String,
        /**
         * A background warmer (cw, cw_focus, binge_lookahead) must never cancel
         * a ui-owned scrape. The details page's hero/episode prefetches drive the
         * visible source line; a Continue Watching reshuffle (e.g. a progress sync
         * landing while the details page is open on the back stack) would cancel
         * that scrape before its ranker ran, so the uiSignal never publishes and
         * the line sits on SEARCHING with nothing behind it. A background caller
         * finding a ui-owned job in flight for a DIFFERENT key yields (no-op)
         * instead of cancelling; background-vs-background keeps
         * cancel-and-replace so scanning the CW row stays single-flight.
         * Ui-owned callers (default) cancel anything.
         */
        background: Boolean = false,
        /**
         * Completion cap. When non-null, the scrape collection stops waiting
         * after this many ms and ranks on whatever arrived, rather
         * than blocking on the slowest source. Null waits for full completion. Local ranking is unchanged;
         * only the size of the pool they see can be smaller under the cap.
         */
        capMs: Long? = null,
        /**
         * Optional ranker, invoked on the prefetch's own IO coroutine
         * once the scrape completes. Null skips ranking. The cache stays ignorant of settings storage: it invokes an opaque
         * supplier and stores an opaque result.
         */
        rank: (suspend (List<AddonStreams>) -> PrefetchedSelection?)? = null,
        /**
         * Opt-in: re-run [rank] to publish THIS caller's uiSignal when the
         * prefetch is deduped (fresh cache hit or in-flight). Only callers that
         * own a hero uiKey (details_hero) need it; background warmers such as
         * binge_lookahead and cw call prefetch() per tick and must leave this
         * false so their repeat calls stay dedup no-ops (no republish storm,
         * and a no-uiKey warmer can never clobber the hero signal).
         */
        republishOnDedup: Boolean = false
    ) {
        if (type.isBlank() || videoId.isBlank()) return
        val key = keyOf(type, videoId, season, episode)
        synchronized(lock) {
            if (!permittedLocked()) return
            val generation = policyGeneration
            val cached = freshLocked(key)
            if (cached != null) {
                // A fresh entry already exists (Continue-Watching, or a previous
                // open of this same detail page, pre-warmed this episode within
                // the TTL). The scrape is done, but THIS caller's ranker has not
                // run, so its uiSignal -- keyed on the caller's uiKey -- is never
                // published, and a details_hero caller's hero source line stays
                // stuck in SEARCHING. Re-invoke the supplied ranker on the cached
                // groups (no re-scrape) so this caller's uiSignal is published.
                if (republishOnDedup && rank != null) {
                    // Rank even when the cached result is empty.
                    // rank -> rankForPrefetch publishes a terminal EMPTY
                    // uiSignal for this caller's uiKey when it has nothing to
                    // pick, letting the hero source line hide itself instead
                    // of spinning on SEARCHING forever.
                    scope.async {
                        try {
                            rank(cached)
                            Log.i(TAG, "PREFETCH cache-republish source=$source key=$key groups=${cached.size}")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "PREFETCH cache-republish rank failed: ${e.message}")
                        }
                    }
                }
                return
            }
            val existing = inFlightJob
            if (inFlightKey == key && existing != null && existing.isActive) {
                // A prefetch for this key is already in flight (e.g. binge_lookahead
                // warming the next episode during playback). That job runs ITS OWN
                // ranker/uiKey (binge passes none), so THIS caller's uiSignal is
                // never published -- a details_hero caller returning to the detail
                // page while the lookahead is still scraping would sit in SEARCHING.
                // Chain this caller's ranker onto the in-flight result so its
                // uiSignal is published once that scrape completes (no re-scrape).
                if (republishOnDedup && rank != null) {
                    scope.async {
                        val result = try {
                            existing.await()
                        } catch (e: CancellationException) {
                            // A cancelled AWAITED scrape must still
                            // yield a terminal signal, or the hero line spins
                            // on SEARCHING forever. Rethrow only when this
                            // republish coroutine itself was cancelled rather
                            // than the scrape it awaited.
                            if (existing.isCancelled) emptyList() else throw e
                        } catch (e: Exception) {
                            emptyList()
                        }
                        // Rank even when the result is empty - rank
                        // publishes a terminal EMPTY uiSignal for this caller's
                        // uiKey, hiding the line instead of leaving it in
                        // SEARCHING with no signal ever arriving.
                        try {
                            rank(result)
                            Log.i(TAG, "PREFETCH inflight-republish source=$source key=$key groups=${result.size}")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "PREFETCH inflight-republish rank failed: ${e.message}")
                        }
                    }
                }
                return
            }
            if (background && existing != null && existing.isActive && !inFlightBackground) {
                // Never cancel a ui-owned scrape from a background warmer: the
                // ui caller's ranker/uiSignal would be lost and its source line
                // stuck. The warm is best-effort; skipping it is the safe side.
                Log.i(
                    TAG,
                    "PREFETCH yield source=$source key=$key: " +
                        "ui-owned prefetch in flight key=$inFlightKey"
                )
                return
            }
            existing?.cancel()
            inFlightKey = key
            inFlightBackground = background
            inFlightJob = scope.async {
                val (result, capHit) = collectFinal(repository, type, videoId, season, episode, capMs)
                val rankT0 = SystemClock.elapsedRealtime()
                val selection = if (rank != null) {
                    try {
                        rank(result)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "PREFETCH rank failed: ${e.message}")
                        null
                    }
                } else {
                    null
                }
                if (rank != null && result.isNotEmpty()) {
                    val winnerLabel = if (selection != null) "yes" else "none"
                    Log.i(
                        TAG,
                        // total_ms brackets the SUPPLIED ranker, which for the
                        // details and Continue Watching callers is rankForPrefetch -- so it
                        // measures local ranking only; provider/media work waits for Play.
                        "PREFETCH rank groups=${result.size} " +
                            "winner=$winnerLabel " +
                            "total_ms=${SystemClock.elapsedRealtime() - rankT0}"
                    )
                }
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (generation != policyGeneration || !permittedLocked()) return@async emptyList()
                    if (result.isNotEmpty()) putLocked(key, result, selection, capHit)
                    if (inFlightKey == key) {
                        inFlightKey = null
                        inFlightJob = null
                    }
                }
                result
            }
        }
        Log.i(TAG, "PREFETCH start source=$source key=$key")
    }

    private data class CollectFinalResult(
        val streams: List<AddonStreams>,
        val capHit: Boolean
    )

    private suspend fun collectFinal(
        repository: StreamRepository,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        capMs: Long?
    ): CollectFinalResult {
        var last: List<AddonStreams> = emptyList()
        var capHit = false
        // Each Success emission from the repository carries the FULL accumulated
        // pool, so `last` after the collect (or after the cap cancels it) always
        // holds the most complete set seen. Under the cap, withTimeoutOrNull
        // cancels this speculative search and all its source children. A cap-hit
        // remains partial: explicit Play can collect the normal complete search.
        val collectBlock: suspend () -> Unit = {
            repository.getStreamsForPrefetch(type, videoId, season, episode).collect { result ->
                if (result is NetworkResult.Success) last = result.data
            }
        }
        try {
            if (capMs != null) {
                if (withTimeoutOrNull(capMs) { collectBlock() } == null) {
                    capHit = true
                    Log.i(TAG, "PREFETCH cap-hit ms=$capMs groups=${last.size}")
                }
            } else {
                collectBlock()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "PREFETCH failed: ${e.message}")
            return CollectFinalResult(emptyList(), false)
        }
        Log.i(TAG, "PREFETCH done groups=${last.size}")
        return CollectFinalResult(last, capHit)
    }

    /**
     * The winner ranked during the prefetch, or null.
     *
     * Read at presentation time rather than at [streamsFor] time, so a prefetch
     * that completes during the join is still usable. A join that falls through
     * to the live flow returns null here and the caller ranks live.
     */
    fun selectionFor(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?
    ): PrefetchedSelection? {
        val key = keyOf(type, videoId, season, episode)
        synchronized(lock) {
            if (policyProfile != currentProfile()) return null
            val entry = completed[key] ?: return null
            if (SystemClock.elapsedRealtime() - entry.atMs > TTL_MS) {
                completed.remove(key)
                return null
            }
            return entry.selection
        }
    }

    /**
     * True when the completed prefetch for this target was cut short by the
     * completion cap. Read at presentation time by the ViewModel to decide
     * whether still-loading source chips are genuinely dead or merely late
     * (alive in the session and re-collectable). No TTL prune here; an evicted
     * entry simply yields a conservative false.
     */
    fun capHitFor(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?
    ): Boolean {
        val key = keyOf(type, videoId, season, episode)
        synchronized(lock) {
            return policyProfile == currentProfile() && (completed[key]?.capHit ?: false)
        }
    }

    /**
     * The stream list for this target: a completed prefetch, a join onto one in
     * flight, or the live repository flow. Drop-in for the repository call.
     */
    fun streamsFor(
        repository: StreamRepository,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean = false
    ): Flow<NetworkResult<List<AddonStreams>>> {
        // A user refresh must bypass BOTH caches -- this
        // prefetch layer (hit/join below) AND the repository session cache
        // (forwarded via getStreamsFromAllAddons). Skipping the hit/join here
        // means a refresh always reaches a live scrape rather than replaying a
        // warmed or in-flight prefetch.
        if (forceRefresh) {
            return repository.getStreamsFromAllAddons(type, videoId, season, episode, forceRefresh = true)
        }
        val requestedProfile = currentProfile()
        val key = keyOf(type, videoId, season, episode)
        var hit: List<AddonStreams>? = null
        var join: Deferred<List<AddonStreams>>? = null
        synchronized(lock) {
            hit = freshLocked(key)
            if (policyProfile == currentProfile() && hit == null && inFlightKey == key) {
                val running = inFlightJob
                if (running != null && running.isActive) join = running
            }
        }

        val hitData = hit
        if (hitData != null) {
            Log.i(TAG, "PREFETCH hit key=$key groups=${hitData.size}")
            return flow {
                if (currentProfile() != requestedProfile) {
                    emitAll(repository.getStreamsFromAllAddons(type, videoId, season, episode))
                    return@flow
                }
                emit(NetworkResult.Loading)
                emit(NetworkResult.Success(hitData))
            }
        }

        val joinJob = join
        if (joinJob != null) {
            Log.i(TAG, "PREFETCH join key=$key")
            return flow {
                emit(NetworkResult.Loading)
                val joined = try {
                    withTimeoutOrNull(JOIN_TIMEOUT_MS) { joinJob.await() }
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    null // OFF/navigation cancelled the speculative job; Play still searches.
                } catch (e: Exception) {
                    null
                }
                if (currentProfile() == requestedProfile && joined != null && joined.isNotEmpty()) {
                    emit(NetworkResult.Success(joined))
                } else {
                    Log.i(TAG, "PREFETCH join empty; falling back to live scrape")
                    emitAll(repository.getStreamsFromAllAddons(type, videoId, season, episode))
                }
            }
        }

        Log.i(TAG, "PREFETCH miss key=$key")
        return repository.getStreamsFromAllAddons(type, videoId, season, episode)
    }
}
