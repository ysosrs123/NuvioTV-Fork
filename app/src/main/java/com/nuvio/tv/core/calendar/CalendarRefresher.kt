package com.nuvio.tv.core.calendar

import android.content.res.Resources
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.ScreensaverController
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingProviderRegistry
import com.nuvio.tv.data.repository.MetaCaller
import com.nuvio.tv.data.repository.SimklIdResolver
import com.nuvio.tv.domain.repository.MetaRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CalendarClock

enum class CalendarRefreshReason { HOME_SETTLED, CALENDAR_OPENED, SETTINGS_CHANGED, SOURCES_PENDING }

/** Waits before a refresh is run again because a source was not ready; one per refresh that stays incomplete. */
val CALENDAR_SOURCE_RETRIES: List<Duration> = listOf(Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(120))

data class CalendarState(
    val profileId: Int? = null,
    /** Followed titles with their releases, hidden titles left out. Empty until [loaded]. */
    val titles: List<CalendarTitleRecord> = emptyList(),
    /** The cache and the profile's settings are both read. */
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val remaining: Int = 0,
    val seenReminders: Set<String> = emptySet(),
    /** Watched episodes as last read by a refresh; null until one has run for this profile. */
    val watched: CalendarWatchedIndex? = null
)

/** Whether the app is on screen; the activity sets it. */
@Singleton
class CalendarAppVisibility @Inject constructor() {
    val visible = MutableStateFlow(true)
}

/** What the refresher reads from the rest of the app. */
interface CalendarRefreshInputs {
    val activeProfileId: StateFlow<Int>
    val playbackActive: StateFlow<Boolean>
    val appVisible: StateFlow<Boolean>
    val settings: StateFlow<CalendarSettings?>
    suspend fun settings(profileId: Int): CalendarSettings
    suspend fun followed(profileId: Int, knownIds: Map<String, CalendarIds>): CalendarFollowedSnapshot
    fun traktSignedIn(): Boolean
    suspend fun traktCalendar(today: LocalDate): List<TraktCalendarItem>?
    suspend fun refreshConcurrency(): Int
    fun region(): String
    fun zone(): ZoneId = ZoneId.systemDefault()

    /** A title's backdrop and logo from its details; null when they could not be read. */
    suspend fun fetchArt(type: String, ids: List<String>): CalendarArt? = null

    /** The ids in a title's details from the add-ons; empty when it has none, null when they could not be read. */
    suspend fun lookupIds(type: CalendarTitleType, contentId: String): CalendarIds? = null
}

@Singleton
class DefaultCalendarRefreshInputs @Inject constructor(
    private val settingsStore: CalendarSettingsDataStore,
    private val sourceReader: CalendarFollowedSourceReader,
    private val traktCalendarClient: TraktCalendarClient,
    private val trackingProviders: TrackingProviderRegistry,
    private val deviceTier: CalendarDeviceTier,
    private val zoneProvider: CalendarZoneProvider,
    private val metaRepository: MetaRepository,
    private val simklIdResolver: SimklIdResolver,
    profileManager: ProfileManager,
    screensaverController: ScreensaverController,
    appVisibility: CalendarAppVisibility
) : CalendarRefreshInputs {
    override val activeProfileId = profileManager.activeProfileId
    override val playbackActive = screensaverController.playbackActive
    override val appVisible: StateFlow<Boolean> = appVisibility.visible
    override val settings = settingsStore.settings
    override suspend fun settings(profileId: Int) = settingsStore.current(profileId)
    override suspend fun followed(profileId: Int, knownIds: Map<String, CalendarIds>) =
        sourceReader.read(profileId, settingsStore.current(profileId).followOtherServices, knownIds)
    override fun traktSignedIn() = trackingProviders.isAuthenticated(TrackingProviderId.TRAKT)
    override suspend fun traktCalendar(today: LocalDate) = traktCalendarClient.myShows(today)
    override suspend fun refreshConcurrency() = deviceTier.refreshConcurrency()
    override fun zone(): ZoneId = zoneProvider.zone()

    override suspend fun fetchArt(type: String, ids: List<String>): CalendarArt? {
        for (id in ids) {
            val result = try {
                withTimeoutOrNull(ART_FETCH_MS) {
                    withContext(MetaCaller("calendar-art")) {
                        metaRepository.getMetaFromAllAddons(type, id).first { it !is NetworkResult.Loading }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (result is NetworkResult.Success) return calendarArtOf(result.data)
        }
        return null
    }

    override suspend fun lookupIds(type: CalendarTitleType, contentId: String): CalendarIds? {
        val result = try {
            withTimeoutOrNull(ART_FETCH_MS) {
                withContext(MetaCaller("calendar-ids")) {
                    val kind = if (type == CalendarTitleType.MOVIE) "movie" else "series"
                    metaRepository.getMetaFromAllAddons(kind, contentId).first { it !is NetworkResult.Loading }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val fromAddons = (result as? NetworkResult.Success)?.let { calendarIdsOf(it.data) }
        if (fromAddons != null && !fromAddons.isEmpty) return fromAddons
        val fromSimkl = calendarSimklLookup(contentId)?.let { (source, id) ->
            val resolved = try {
                withTimeoutOrNull(ART_FETCH_MS) { simklIdResolver.resolveIds(source, id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            resolved?.let { calendarIdsFromSimkl(it.imdb, it.tmdb, it.tvdb, it.simklId) }
        }
        return calendarLookedUpIds(fromAddons, fromSimkl)
    }

    /** The box's own region, not the app language, for film release dates. */
    override fun region(): String =
        Resources.getSystem().configuration.locales[0]?.country?.takeIf { it.length == 2 } ?: "US"

    private companion object {
        const val ART_FETCH_MS = 6_000L
    }
}

/**
 * Keeps the Calendar of the active profile. A refresh reads the followed titles from what the app already has, asks
 * the Trakt calendar when signed in, then refreshes only the titles that are due, 2 or 4 at a time, waiting while
 * something plays. Every finished title is kept, so a cancelled refresh carries on where it stopped next time.
 * A request while a refresh runs is kept and runs once after it. When a source was not ready, the titles it gave last
 * time are kept and the refresh runs again after [sourceRetries], at most that many times in a row.
 * Nothing is read before the first call.
 */
@Singleton
class CalendarRefresher internal constructor(
    private val storage: CalendarStorage,
    private val inputs: CalendarRefreshInputs,
    private val remote: CalendarRemoteSources,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val sourceRetries: List<Duration> = CALENDAR_SOURCE_RETRIES
) {
    @Inject
    constructor(
        storage: CalendarStorage,
        inputs: CalendarRefreshInputs,
        remote: CalendarRemoteSources,
        @CalendarClock clock: Clock
    ) : this(storage, inputs, remote, clock, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    /** The profile and storage generation a refresh started with; anything it writes after either changed is dropped. */
    private data class Owner(val profileId: Int, val generation: Int)

    private val started = AtomicBoolean(false)
    private val dataMutex = Mutex()
    private val jobLock = Any()
    private var job: Job? = null
    private var jobTakesRequests = false
    private var runAgain: CalendarRefreshReason? = null
    private var retry: Job? = null
    private var retriesUsed = 0

    private var data = CalendarSnapshot()
    private var owner: Owner? = null
    private var watched: CalendarWatchedIndex? = null

    private val _state = MutableStateFlow(CalendarState())
    val state: StateFlow<CalendarState> = _state.asStateFlow()

    fun requestRefresh(reason: CalendarRefreshReason) {
        ensureStarted()
        synchronized(jobLock) {
            val previous = job
            if (previous?.isActive == true && jobTakesRequests) {
                if (runAgain == null || reason == CalendarRefreshReason.SETTINGS_CHANGED) runAgain = reason
                return
            }
            retry?.cancel()
            retry = null
            jobTakesRequests = true
            job = scope.launch(start = CoroutineStart.LAZY) {
                previous?.join()
                val me = currentCoroutineContext()[Job]
                var next: CalendarRefreshReason? = reason
                while (next != null) {
                    val complete = runRefresh(next)
                    next = synchronized(jobLock) {
                        if (job !== me) return@synchronized null
                        if (complete) retriesUsed = 0
                        runAgain?.also { runAgain = null } ?: run {
                            finishLocked(complete)
                            null
                        }
                    }
                }
            }.also { it.start() }
        }
    }

    private fun finishLocked(complete: Boolean) {
        jobTakesRequests = false
        if (complete) return
        val wait = sourceRetries.getOrNull(retriesUsed)
        if (wait == null) {
            Log.w(TAG, "sources still not ready after ${sourceRetries.size} tries; next refresh when the Calendar opens")
            return
        }
        retriesUsed++
        Log.i(TAG, "refresh again in ${wait.seconds} s ($retriesUsed of ${sourceRetries.size})")
        retry = scope.launch {
            delay(wait.toMillis())
            combine(inputs.appVisible, inputs.playbackActive) { visible, playing -> visible && !playing }.first { it }
            val self = currentCoroutineContext()[Job]
            synchronized(jobLock) {
                if (retry !== self) return@launch
                retry = null
                requestRefresh(CalendarRefreshReason.SOURCES_PENDING)
            }
        }
    }

    /** Reads the cached Calendar without asking for anything new. */
    fun start() = ensureStarted()

    fun cancelRefresh() {
        synchronized(jobLock) {
            runAgain = null
            job?.cancel()
            retry?.cancel()
            retry = null
            retriesUsed = 0
        }
    }

    suspend fun dueReminders(): CalendarDueReminders {
        ensureStarted()
        val current = ensureLoaded(inputs.activeProfileId.value)
        val settings = inputs.settings(current.profileId)
        val index = watchedIndex(current)
        val (titles, seen) = dataMutex.withLock { data.titles.visible(settings) to data.seenReminders.keys }
        return com.nuvio.tv.core.calendar.dueReminders(titles, settings.bellOn, seen, index::isWatched, clock.instant())
    }

    suspend fun markRemindersSeen(episodeKeys: Collection<String>) {
        if (episodeKeys.isEmpty()) return
        ensureStarted()
        val current = ensureLoaded(inputs.activeProfileId.value)
        withOwner(current) {
            data = data.copy(seenReminders = data.seenReminders.withSeen(episodeKeys, clock.instant()))
            publishLocked()
        }
        save(current, force = true)
    }

    private fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            var previous: Pair<Int, Int>? = null
            combine(inputs.activeProfileId, storage.generations) { profileId, generation -> profileId to generation }
                .distinctUntilChanged()
                .collect { key ->
                    if (previous != null) cancelRefresh()
                    previous = key
                    ensureLoaded(key.first)
                }
        }
        scope.launch {
            inputs.settings.collect { dataMutex.withLock { publishLocked() } }
        }
    }

    /** False when a source was not ready, so the refresh should run again once it may be. */
    private suspend fun runRefresh(reason: CalendarRefreshReason): Boolean {
        val me = currentCoroutineContext()[Job]
        val current = ensureLoaded(inputs.activeProfileId.value)
        setRefreshing(current, me, refreshing = true, remaining = 0)
        var complete = true
        try {
            val profileId = current.profileId
            val settings = inputs.settings(profileId)
            val now = clock.instant()
            val savedIds = dataMutex.withLock { data.resolvedIds }
            var sources = inputs.followed(profileId, savedIds.usableIds())
            val looked = lookUpUnreadableIds(sources.unresolved, savedIds, now)
            if (looked.values.any { !it.ids.isEmpty }) {
                sources = inputs.followed(profileId, mergeCalendarResolvedIds(savedIds, emptyMap(), looked, now).usableIds())
            }
            withOwner(current) {
                watched = sources.watched
                publishLocked()
            } ?: return true
            complete = sources.complete

            val traktSignedIn = inputs.traktSignedIn()
            val snapshot = dataMutex.withLock { data }
            val traktDue = now.toEpochMilli() - snapshot.traktCalendarFetchedAtMs >= CALENDAR_TRAKT_REFRESH.toMillis()
            val traktItems = if (traktSignedIn && traktDue && sources.traktWatchedLoaded) {
                awaitNoPlayback()
                inputs.traktCalendar(now.atZone(ZoneOffset.UTC).toLocalDate())
            } else {
                null
            }
            val traktCandidates = when {
                !traktSignedIn || !sources.traktFollowed || !sources.traktWatchedLoaded -> emptyList()
                traktItems != null -> traktCalendarCandidates(traktItems)
                else -> snapshot.titles.filter { CalendarOrigin.TRAKT_CALENDAR in it.origins }.map {
                    CalendarTitleCandidate(it.ids, it.type, CalendarOrigin.TRAKT_CALENDAR, name = it.name)
                }
            }
            val rules = CalendarFollowRules(
                includePersonalLists = settings.includePersonalLists,
                hiddenTitleKeys = settings.hiddenTitleKeys,
                bellOn = settings.bellOn,
                removedFromContinueWatching = sources.removedFromContinueWatching,
                upcomingAliases = sources.addonEntries.flatMapTo(mutableSetOf()) { it.first.aliases() }
            )
            val unread = if (traktItems != null) sources.unreadOrigins - CalendarOrigin.TRAKT_CALENDAR else sources.unreadOrigins
            val carried = carriedCalendarCandidates(snapshot.titles, unread)
            val result = followCalendarTitles(sources.candidates + traktCandidates + carried, rules, now)
            val followed = result.titles.withCachedPosters(sources.cachedPosters)

            val due = withOwner(current) {
                var records = applyAddonEntries(reconcileCalendarRecords(followed, data.titles), sources.addonEntries, now)
                    .withCachedArt(sources.cachedArt)
                records = when {
                    !traktSignedIn -> records.map { it.dueWithoutTraktCalendar() }
                    traktItems != null -> applyTraktCalendar(records, traktItems, now)
                    else -> records
                }
                data = data.copy(
                    titles = records,
                    resolvedIds = mergeCalendarResolvedIds(data.resolvedIds, sources.resolvedIds, looked, now),
                    traktCalendarFetchedAtMs = when {
                        traktItems != null -> now.toEpochMilli()
                        traktSignedIn -> data.traktCalendarFetchedAtMs
                        else -> 0L
                    }
                )
                publishLocked()
                records.filter { it.isDue(now) }
                    .sortedWith(compareByDescending<CalendarTitleRecord> { CalendarOrigin.BELL in it.origins }.thenBy { it.nextRefreshAtMs })
            } ?: return true
            save(current, force = true)
            Log.i(TAG, "refresh reason=$reason titles=${followed.size} due=${due.size} trakt=${traktItems?.size ?: "-"}")
            calendarLeftOutSummary(result.leftOut).forEach { Log.i(TAG, it) }
            calendarUnresolvedSummary(sources.unresolved, sources.unresolvedNames)?.let { Log.i(TAG, it) }
            if (!complete) {
                val notReady = sources.notReady.entries.joinToString(", ") { (name, why) -> "$name $why" }
                val kept = snapshot.titles.count { record -> record.origins.any { it in unread } }
                Log.w(TAG, "sources not ready: $notReady; kept $kept titles they gave last time")
            }
            if (due.isNotEmpty()) {
                setRefreshing(current, me, refreshing = true, remaining = due.size)
                val fetcher = CalendarTitleFetcher(remote, inputs.region())
                val queue = ArrayDeque(due.map { it.key })
                supervisorScope {
                    repeat(inputs.refreshConcurrency().coerceAtLeast(1)) {
                        launch {
                            while (true) {
                                awaitNoPlayback()
                                val key = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                                refreshOne(current, key, fetcher)
                            }
                        }
                    }
                }
            }
            try {
                fetchCalendarArt(current, settings)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Backdrops skipped: ${error.message}")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Calendar refresh stopped: ${error.message}")
            complete = true
        } finally {
            withContext(NonCancellable) {
                save(current, force = true)
                setRefreshing(current, me, refreshing = false, remaining = 0)
            }
        }
        return complete
    }

    private suspend fun refreshOne(current: Owner, key: String, fetcher: CalendarTitleFetcher) {
        val (record, tvMazeIds, traktFetchedAt) = withOwner(current) {
            Triple(data.titles.firstOrNull { it.key == key }, data.tvmazeIds, data.traktCalendarFetchedAtMs)
        } ?: return
        if (record == null) return
        val now = clock.instant()
        val signedIn = inputs.traktSignedIn()
        val outcome = try {
            fetcher.refresh(record, tvMazeIds, signedIn && record.isCoveredByTrakt(traktFetchedAt, now), now)
                .withoutTraktCalendarIfSignedOut(signedIn)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CalendarFetchOutcome(fetcher.failed(record, now))
        }
        withOwner(current) {
            data = data.copy(
                titles = data.titles.map { if (it.key == key) outcome.record else it },
                tvmazeIds = data.tvmazeIds + outcome.tvMazeIds
            )
            publishLocked(remaining = (_state.value.remaining - 1).coerceAtLeast(0))
        }
        save(current, force = false)
    }

    /**
     * One details read at a time for the titles whose art is due. The new backdrops are
     * published together at the end, so the row is rebuilt once instead of once per title.
     */
    private suspend fun fetchCalendarArt(current: Owner, settings: CalendarSettings) {
        val now = clock.instant()
        val wanted = withOwner(current) { calendarBackdropsWanted(data.titles.visible(settings), now) } ?: return
        if (wanted.isEmpty()) return
        var found = 0
        for (title in wanted) {
            awaitNoPlayback()
            val type = if (title.type == CalendarTitleType.MOVIE) "movie" else "series"
            val art = inputs.fetchArt(type, calendarContentIds(title))
            if (!art?.backdrop.isNullOrBlank()) found++
            withOwner(current) {
                data = data.copy(titles = data.titles.map { if (it.key == title.key) it.withFetchedArt(art, now) else it })
            } ?: return
        }
        withOwner(current) { publishLocked() } ?: return
        Log.i(TAG, "backdrops asked=${wanted.size} found=$found")
        save(current, force = false)
    }

    private suspend fun ensureLoaded(profileId: Int): Owner = dataMutex.withLock {
        val wanted = Owner(profileId, storage.generation())
        if (owner != wanted) {
            data = storage.load(profileId)
            owner = wanted
            watched = null
            publishLocked(refreshing = false, remaining = 0)
        }
        wanted
    }

    /** Runs [block] under the lock only while [current] still owns the data; null otherwise. */
    private suspend inline fun <T> withOwner(current: Owner, block: () -> T): T? = dataMutex.withLock {
        currentCoroutineContext().ensureActive()
        if (owner != current) null else block()
    }

    private suspend fun watchedIndex(current: Owner): CalendarWatchedIndex {
        dataMutex.withLock { if (owner == current) watched?.let { return it } }
        return readWatched(current)
    }

    private suspend fun readWatched(current: Owner): CalendarWatchedIndex {
        val known = dataMutex.withLock { data.resolvedIds }.usableIds()
        val index = inputs.followed(current.profileId, known).watched
        withOwner(current) {
            watched = index
            publishLocked()
        }
        return index
    }

    /** Reads what has been watched again, for when watch progress changes outside a refresh. */
    suspend fun reloadWatched() {
        ensureStarted()
        readWatched(ensureLoaded(inputs.activeProfileId.value))
    }

    private suspend fun save(current: Owner, force: Boolean) {
        val snapshot = dataMutex.withLock { if (owner == current) data else null } ?: return
        storage.save(current.profileId, snapshot, current.generation, force)
    }

    /** Only the refresh that is current may change the refreshing flag. */
    private suspend fun setRefreshing(current: Owner, me: Job?, refreshing: Boolean, remaining: Int) {
        if (synchronized(jobLock) { job !== me }) return
        dataMutex.withLock { if (owner == current) publishLocked(refreshing = refreshing, remaining = remaining) }
    }

    private fun List<CalendarTitleRecord>.visible(settings: CalendarSettings): List<CalendarTitleRecord> {
        val hidden = settings.hiddenTitleKeys
        return if (hidden.isEmpty()) this else filterNot { title -> hidden.any(title::matchesKey) }
    }

    private fun publishLocked(
        refreshing: Boolean = _state.value.refreshing,
        remaining: Int = _state.value.remaining
    ) {
        val profileId = owner?.profileId
        val settings = inputs.settings.value?.takeIf { profileId != null && it.profileId == profileId }
        _state.value = CalendarState(
            profileId = profileId,
            titles = settings?.let { data.titles.visible(it) }.orEmpty(),
            loaded = settings != null,
            refreshing = refreshing,
            remaining = remaining,
            seenReminders = data.seenReminders.keys,
            watched = watched
        )
    }

    private suspend fun awaitNoPlayback() {
        inputs.playbackActive.first { active -> !active }
    }

    /** Asks the add-ons for the ids of a few titles saved under ids the Calendar cannot read; kept, so asked once. */
    private suspend fun lookUpUnreadableIds(
        unresolved: List<Pair<CalendarTitleType, String>>,
        saved: Map<String, CalendarResolvedIds>,
        now: Instant
    ): Map<String, CalendarResolvedIds> {
        val wanted = calendarIdsToLookUp(unresolved, saved, now)
        if (wanted.isEmpty()) return emptyMap()
        val found = LinkedHashMap<String, CalendarResolvedIds>()
        wanted.forEach { (type, contentId) ->
            awaitNoPlayback()
            val ids = inputs.lookupIds(type, contentId) ?: return@forEach
            found[contentId] = CalendarResolvedIds(ids, now.toEpochMilli())
        }
        Log.i(TAG, "looked up ids for ${wanted.size} titles, found ${found.values.count { !it.ids.isEmpty }}")
        return found
    }

    private companion object {
        const val TAG = "CalendarRefresher"
    }
}

/**
 * Signed out of Trakt: the times its calendar gave stay on show until TVmaze, TheTVDB or Trakt's public next-episode
 * answer replaces them, so the title is asked again now instead of losing its times for hours.
 */
internal fun CalendarTitleRecord.dueWithoutTraktCalendar(): CalendarTitleRecord =
    if (sources[CalendarSource.TRAKT].isNullOrEmpty() || nextRefreshAtMs == 0L) this else copy(nextRefreshAtMs = 0L)

/** After a good fetch while signed out, the Trakt calendar's own times give way to what was just fetched. */
internal fun CalendarFetchOutcome.withoutTraktCalendarIfSignedOut(signedIn: Boolean): CalendarFetchOutcome =
    if (signedIn || record.failures > 0) this else copy(record = record.withoutSources(CalendarSource.TRAKT))
