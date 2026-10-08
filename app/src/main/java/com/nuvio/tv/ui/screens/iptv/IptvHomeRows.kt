package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.FixtureTeam
import com.nuvio.tv.core.iptv.HomeRowKind
import com.nuvio.tv.core.iptv.HomeRowSettings
import com.nuvio.tv.core.iptv.HomeRows
import com.nuvio.tv.core.iptv.SportsFavourites
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsLeagues
import com.nuvio.tv.core.iptv.SportsTeams
import com.nuvio.tv.core.iptv.VodArt
import com.nuvio.tv.core.iptv.VodDetailTarget
import com.nuvio.tv.core.iptv.VodKind
import com.nuvio.tv.core.iptv.VodRef
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.recording.IptvRecorder
import com.nuvio.tv.data.iptv.IptvCatalogueStore
import com.nuvio.tv.data.iptv.IptvFixtureLink
import com.nuvio.tv.data.iptv.IptvGuideStore
import com.nuvio.tv.data.iptv.IptvHomeChannel
import com.nuvio.tv.data.iptv.IptvHomePreferences
import com.nuvio.tv.data.iptv.IptvHomeRowsLoader
import com.nuvio.tv.data.iptv.IptvListedChannel
import com.nuvio.tv.data.iptv.IptvLivePreferences
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.data.iptv.IptvProfileAccess
import com.nuvio.tv.data.iptv.IptvRecording
import com.nuvio.tv.data.iptv.IptvSource
import com.nuvio.tv.data.iptv.IptvSportsFixturesRepository
import com.nuvio.tv.data.iptv.IptvSportsPreferences
import com.nuvio.tv.data.iptv.IptvVodArtwork
import com.nuvio.tv.data.iptv.IptvVodArtworkMode
import com.nuvio.tv.data.iptv.IptvVodArtworkPreferences
import com.nuvio.tv.data.iptv.IptvVodRepository
import com.nuvio.tv.data.iptv.IptvVodStreams
import com.nuvio.tv.data.iptv.IptvVodTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IptvHomeTune(val row: IptvListedChannel, val profileId: Int, val favourites: Boolean, val sport: Boolean)

const val IPTV_HOME_ROW_PREFIX = "iptv_home_"

@Immutable
sealed interface IptvHomeRow {
    val kind: HomeRowKind
    val key: String get() = IPTV_HOME_ROW_PREFIX + kind.key

    @Immutable
    data class Channels(override val kind: HomeRowKind, val items: List<IptvHomeChannel>) : IptvHomeRow

    @Immutable
    data class Titles(override val kind: HomeRowKind, val items: List<IptvVodTitle>, val art: Map<VodRef, VodArt> = emptyMap()) : IptvHomeRow

    @Immutable
    data class Recordings(val items: List<IptvRecording>, val logos: Map<Pair<String, String>, String> = emptyMap()) : IptvHomeRow {
        override val kind: HomeRowKind get() = HomeRowKind.RECORDINGS
    }

    @Immutable
    data class LiveSport(val items: List<IptvHomeSport>) : IptvHomeRow {
        override val kind: HomeRowKind get() = HomeRowKind.SPORT
        override val key: String get() = IPTV_HOME_ROW_PREFIX + "live_sport"
    }

    @Immutable
    data class Teams(val items: List<IptvHomeTeam>, val days: Int) : IptvHomeRow {
        override val kind: HomeRowKind get() = HomeRowKind.SPORT
        override val key: String get() = IPTV_HOME_ROW_PREFIX + "teams"
    }
}

@Immutable
data class IptvHomeSport(val fixture: SportsFixture, val link: IptvFixtureLink, val hidden: Boolean, val close: Boolean)

@Immutable
data class IptvHomeTeam(val key: String, val name: String, val league: String, val team: FixtureTeam?, val fixture: SportsFixture?, val home: Boolean?,
    val hidden: Boolean, val recording: IptvTeamRecording, val reminder: Boolean)

sealed interface IptvHomeEvent {
    data class Detail(val target: VodDetailTarget) : IptvHomeEvent
    data class Title(val ref: VodRef) : IptvHomeEvent
}

@HiltViewModel
class IptvHomeViewModel @Inject constructor(@ApplicationContext context: Context, private val catalogue: IptvCatalogueStore, guides: IptvGuideStore,
    vod: IptvVodRepository, vodStreams: IptvVodStreams, private val access: IptvProfileAccess, private val profiles: ProfileManager,
    private val livePreferences: IptvLivePreferences, private val artwork: IptvVodArtwork, private val artworkPreferences: IptvVodArtworkPreferences,
    private val opener: IptvVodOpener, private val recorder: IptvRecorder, private val liveLaunch: IptvLiveLaunch,
    private val sportsPreferences: IptvSportsPreferences, private val sportsRepository: IptvSportsFixturesRepository, private val sports: IptvSportsNuvio) : ViewModel() {
    private data class Request(val profileId: Int, val revision: Long, val settings: HomeRowSettings, val sport: Boolean, val nuvio: Boolean,
        val fixtures: Boolean, val favourites: Set<String>, val rules: Set<String>)

    private val preferences = IptvHomePreferences(context)
    private val loader = IptvHomeRowsLoader(catalogue, guides, vod, vodStreams) { ref ->
        livePreferences.preferences.getStringSet(livePreferences.key(ref, "hidden"), null).orEmpty().toSet()
    }
    private val mutable = MutableStateFlow<List<IptvHomeRow>>(emptyList())
    val rows = mutable.asStateFlow()
    private val navigation = Channel<IptvHomeEvent>(Channel.BUFFERED)
    val events = navigation.receiveAsFlow()
    private var loadJob: Job? = null
    private var artJob: Job? = null
    private var loaded: Request? = null
    private var requested: Request? = null
    private var loadedAt: Long? = null
    private var active = false
    private var opening = false

    init {
        viewModelScope.launch {
            combine(profiles.activeProfileId, profiles.profileSelectionRevision) { id, revision -> id to revision }.distinctUntilChanged().collect { (id, revision) ->
                val previous = loaded ?: requested
                if (previous != null && (previous.profileId != id || previous.revision != revision)) {
                    loadJob?.cancel(); artJob?.cancel()
                    mutable.value = emptyList()
                    loaded = null; requested = null; loadedAt = null
                }
                refresh()
            }
        }
    }

    fun start() { active = true; refresh() }

    fun refresh() {
        if (!active) return
        val request = Request(profiles.activeProfileId.value, profiles.profileSelectionRevision.value, preferences.settings, livePreferences.sport,
            artworkPreferences.mode == IptvVodArtworkMode.NUVIO, BuildConfig.FEATURE_IPTV_ENABLED && sportsPreferences.enabled,
            sportsPreferences.favouriteTeams, sports.rules.value)
        if (request == loaded && HomeRows.fresh(loadedAt, System.currentTimeMillis())) return
        if (loadJob?.isActive == true && request == requested) return
        requested = request
        loadJob?.cancel(); artJob?.cancel()
        loadJob = viewModelScope.launch { load(request) }
    }

    fun tune(row: IptvHomeRow.Channels, item: IptvHomeChannel) {
        liveLaunch.channel.value = IptvHomeTune(item.channel, profiles.activeProfileId.value, row.kind == HomeRowKind.FAVOURITES, row.kind == HomeRowKind.SPORT)
    }

    fun tune(item: IptvHomeSport) {
        liveLaunch.channel.value = IptvHomeTune(item.link.row, profiles.activeProfileId.value, favourites = false, sport = true)
    }

    fun openTeam(item: IptvHomeTeam) = sports.openTeam(item.key)

    fun open(row: IptvHomeRow.Titles, title: IptvVodTitle) {
        if (opening) return
        opening = true
        viewModelScope.launch {
            try {
                val art = row.art[title.ref] ?: if (artworkPreferences.mode == IptvVodArtworkMode.NUVIO)
                    io<VodArt?>(null) { artwork.resolve(listOf(title), opener.language())[title.ref] } else null
                val target = opener.target(title.ref.kind, title.tmdbId ?: art?.tmdbId, title.imdbId ?: art?.imdbId)
                navigation.send(if (target != null) IptvHomeEvent.Detail(target) else IptvHomeEvent.Title(title.ref))
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { IptvLog.failure("home vod open", error); navigation.send(IptvHomeEvent.Title(title.ref)) }
            finally { opening = false }
        }
    }

    private suspend fun load(request: Request) {
        val profileId = request.profileId
        val now = System.currentTimeMillis()
        val kinds = HomeRows.order(request.settings, request.sport)
        val sources = if (kinds.none { it == HomeRowKind.FAVOURITES || it == HomeRowKind.SPORT }) emptyList()
            else io(emptyList<IptvSource>()) { access.use(access.open(profileId)) { catalogue.sources(profileId) }.filter { it.playbackEligible } }
        val result = kinds.flatMap { kind ->
            when (kind) {
                HomeRowKind.FAVOURITES -> listOfNotNull(io(emptyList()) { loader.favourites(profileId, sources, now) }.takeIf { it.isNotEmpty() }
                    ?.let { IptvHomeRow.Channels(kind, it) })
                HomeRowKind.SPORT -> sportRows(request, sources, now) {
                    io(emptyList()) { loader.sport(sources, now) }.takeIf { it.isNotEmpty() }?.let { IptvHomeRow.Channels(kind, it) }
                }
                HomeRowKind.MOVIES -> listOfNotNull(titles(profileId, VodKind.MOVIE, kind, request.nuvio))
                HomeRowKind.SERIES -> listOfNotNull(titles(profileId, VodKind.SERIES, kind, request.nuvio))
                HomeRowKind.RECORDINGS -> listOfNotNull(recordings(profileId))
            }
        }
        if (profiles.activeProfileId.value != profileId || requested != request) return
        mutable.value = result
        loaded = request
        loadedAt = System.currentTimeMillis()
        if (request.nuvio) resolveArt(result)
    }

    private suspend fun sportRows(request: Request, sources: List<IptvSource>, now: Long, guide: suspend () -> IptvHomeRow?): List<IptvHomeRow> {
        if (!request.fixtures) return listOfNotNull(guide())
        val favourites = request.favourites
        val fixtures = io(emptyList()) { sportsRepository.load(now, ZoneId.systemDefault(), true, favourites).takeIf { it.enabled }?.fixtures.orEmpty() }
        val teams = favourites.sortedBy { it.lowercase() }.mapNotNull { SportsTeams.games(it, fixtures, now) }
        val soon = fixtures.filter { it.status == FixtureStatus.LIVE || (it.status == FixtureStatus.SCHEDULED && it.startMillis <= now + SOON_MILLIS &&
            it.startMillis + SOON_MILLIS > now) }
        val links = io(emptyMap()) { sports.link(sources, (soon + teams.mapNotNull { it.current }).distinctBy { it.key }.take(MAX_LINKED), now) }
        val spoilers = io(emptySet()) { sports.spoilersFor(fixtures, request.profileId) }
        val showScores = sportsPreferences.showScores
        val live = soon.mapNotNull { fixture -> links[fixture.key]?.firstOrNull()?.let {
            IptvHomeSport(fixture, it, !showScores || fixture.key in spoilers, showScores && fixture.key !in spoilers && com.nuvio.tv.core.iptv.SportsFixtureSections.close(fixture))
        } }.sortedWith(compareBy<IptvHomeSport>({ !SportsFavourites.has(favourites, it.fixture) }, { if (it.fixture.status == FixtureStatus.LIVE) 0 else 1 },
            { it.fixture.startMillis })).take(HomeRows.SPORT)
        val reminders = sports.reminders.value
        val items = teams.map { games ->
            val fixture = games.current ?: games.last
            IptvHomeTeam(games.key, games.team?.name ?: games.name, SportsLeagues.byId(games.league)?.name ?: games.league, games.team, fixture,
                fixture?.let { SportsTeams.home(games, it) }, fixture != null && (!showScores || fixture.key in spoilers), sports.recording(games.current, games.key),
                games.current?.key?.let { it in reminders } == true)
        }
        return listOfNotNull(if (live.isNotEmpty()) IptvHomeRow.LiveSport(live) else guide(),
            items.takeIf { it.isNotEmpty() }?.let { IptvHomeRow.Teams(it, sports.guideDays) })
    }

    private suspend fun titles(profileId: Int, vodKind: VodKind, kind: HomeRowKind, nuvio: Boolean): IptvHomeRow? {
        val items = io(emptyList()) { loader.recentTitles(profileId, vodKind) }
        if (items.isEmpty()) return null
        val language = opener.language()
        val art = if (nuvio) items.mapNotNull { title -> artwork.cached(title, language)?.let { title.ref to it } }.toMap() else emptyMap()
        return IptvHomeRow.Titles(kind, items, art)
    }

    private suspend fun recordings(profileId: Int): IptvHomeRow? {
        val items = HomeRows.recentRecordings(recorder.all.value.filter { it.profileId == profileId }, { it.status },
            { it.finishedAtMillis ?: it.stopMillis }, HomeRows.RECORDINGS)
        if (items.isEmpty()) return null
        val logos = io(emptyMap()) { loader.logos(profileId, items.map { it.sourceId to it.channelId }) }
        return IptvHomeRow.Recordings(items, logos)
    }

    private fun resolveArt(result: List<IptvHomeRow>) {
        val wanted = result.filterIsInstance<IptvHomeRow.Titles>().flatMap { row -> row.items.filter { it.ref !in row.art } }
        if (wanted.isEmpty()) return
        artJob = viewModelScope.launch {
            val found = io(emptyMap()) { artwork.resolve(wanted, opener.language()) }
            if (found.isNotEmpty()) mutable.update { list ->
                list.map { row -> if (row is IptvHomeRow.Titles) row.copy(art = row.art + row.items.mapNotNull { title -> found[title.ref]?.let { title.ref to it } }) else row }
            }
        }
    }

    private suspend fun <T> io(fallback: T, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { IptvLog.failure("home rows", error); fallback }
    }
}

@Stable
class IptvHomeHost internal constructor(val rows: State<List<IptvHomeRow>>, val now: State<Long>, private val viewModel: IptvHomeViewModel,
    private val onLive: State<() -> Unit>, private val onRecordings: State<() -> Unit>) {
    fun openChannel(row: IptvHomeRow.Channels, item: IptvHomeChannel) { viewModel.tune(row, item); onLive.value() }
    fun openSport(item: IptvHomeSport) { viewModel.tune(item); onLive.value() }
    fun openTeam(item: IptvHomeTeam) = viewModel.openTeam(item)
    fun openLive() = onLive.value()
    fun openTitle(row: IptvHomeRow.Titles, title: IptvVodTitle) = viewModel.open(row, title)
    fun openRecordings() = onRecordings.value()
}

val LocalIptvHome = staticCompositionLocalOf<IptvHomeHost?> { null }

@Composable
fun IptvHomeRowsProvider(onOpenLive: () -> Unit, onOpenRecordings: () -> Unit, onOpenTitle: (String) -> Unit,
    onOpenDetail: (String, String) -> Unit, content: @Composable () -> Unit) {
    if (!BuildConfig.FEATURE_IPTV_ENABLED) { content(); return }
    val viewModel: IptvHomeViewModel = hiltViewModel()
    val rows = viewModel.rows.collectAsStateWithLifecycle()
    val now = produceState(System.currentTimeMillis()) { while (true) { delay(NOW_TICK); value = System.currentTimeMillis() } }
    val live = rememberUpdatedState(onOpenLive)
    val recordings = rememberUpdatedState(onOpenRecordings)
    val latestTitle by rememberUpdatedState(onOpenTitle)
    val latestDetail by rememberUpdatedState(onOpenDetail)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel) { delay(START_DELAY); viewModel.start() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is IptvHomeEvent.Detail -> latestDetail(event.target.itemId, event.target.itemType)
                is IptvHomeEvent.Title -> latestTitle(event.ref.format())
            }
        }
    }
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val host = remember(viewModel) { IptvHomeHost(rows, now, viewModel, live, recordings) }
    CompositionLocalProvider(LocalIptvHome provides host) { content() }
}

private const val START_DELAY = 800L
private const val NOW_TICK = 30_000L
private const val SOON_MILLIS = 30L * 60 * 1000
private const val MAX_LINKED = 60
