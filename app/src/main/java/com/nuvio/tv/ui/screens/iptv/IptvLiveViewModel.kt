package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.*
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.iptv.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.roundToInt

data class IptvLiveState(val sources: List<IptvSource> = emptyList(), val source: IptvSourceRef? = null,
    val channels: List<IptvListedChannel> = emptyList(), val next: IptvBrowseCursor? = null,
    val categories: List<IptvCategory> = emptyList(), val category: String? = null, val favourites: Boolean = false, val sports: Boolean = false, val search: String = "", val airingSearch: Boolean = false,
    val guide: Map<String, GuideGridRow> = emptyMap(), val window: GuideGridWindow? = null,
    val focused: IptvListedChannel? = null, val playingId: String? = null, val previousId: String? = null,
    val recent: List<String> = emptyList(),
    val playback: IptvLivePlayback? = null,
    val player: ExoPlayer? = null, val playingTitle: String? = null, val playing: Boolean = false, val reconnecting: Boolean = false, val playingRow: IptvListedChannel? = null, val catchup: GuideProgramme? = null,
    val sportEnabled: Boolean = true, val timeshiftEnabled: Boolean = true, val catchupFrom: Long? = null, val paused: Boolean = false, val pausedAt: Long? = null, val pausedProgramme: GuideProgramme? = null,
    val loading: Boolean = false, val loaded: Boolean = false, val tuning: Boolean = false, val message: Int? = null, val updating: Int? = null,
    val guidePicker: IptvGuidePicker? = null, val refresh: Map<String, IptvRefreshStatus> = emptyMap(),
    val controlLayout: com.nuvio.tv.data.local.PlayerControlLayout? = null, val shortGuide: Map<String, List<GuideProgramme>> = emptyMap(),
    val hiddenCategories: Set<String> = emptySet(), val multiview: List<IptvTile>? = null, val tileFocus: Int = 0,
    val recordings: List<IptvRecording> = emptyList(), val maxTiles: Int = 1,
    val alarmPrompt: Boolean = false, val multiviewLayout: MultiviewLayout = MultiviewLayout.GRID,
    val multiviewQuality: MultiviewQuality = MultiviewQuality.AUTO, val mainTile: Int = 0, val panelHeight: Int = 1080,
    val tileHeights: List<Int> = emptyList(), val density: GuideDensity = GuideDensity.COMPACT,
    val allSources: Boolean = false, val sourceCategories: List<IptvSourceCategories> = emptyList(),
    val extraGuide: Map<String, GuideGridRow> = emptyMap(), val scrubProgrammes: List<GuideProgramme> = emptyList(),
    val catchupUntil: Long? = null, val scrubTarget: Long? = null, val inset: IptvTile? = null, val picker: IptvPicker? = null,
    val localTimeshift: Boolean = false, val localBehind: Boolean = false) {
    val mergedFavourites: Boolean get() = allSources && favourites && search.isBlank()
    val canReorder: Boolean get() = source != null && search.isBlank() && !sports && !mergedFavourites
}

data class IptvSourceCategories(val source: IptvSource, val categories: List<IptvCategory>)

data class IptvPicker(val ref: IptvSourceRef, val channels: List<IptvListedChannel> = emptyList(), val next: IptvBrowseCursor? = null,
    val loading: Boolean = true, val category: String? = null, val favourites: Boolean = false, val categories: List<IptvCategory>? = null,
    val hidden: Set<String> = emptySet())

data class IptvTile(val row: IptvListedChannel, val playback: IptvLivePlayback? = null, val player: ExoPlayer? = null,
    val playing: Boolean = false, val failure: Int? = null)

data class IptvGuidePicker(val row: IptvListedChannel, val feeds: List<IptvGuideFeed>, val feed: IptvGuideRef? = null,
    val query: String = "", val results: List<GuideChannel> = emptyList())

@HiltViewModel
class IptvLiveViewModel @Inject constructor(@ApplicationContext private val context: Context,
    private val catalogue: IptvCatalogueStore, private val guides: IptvGuideStore,
    private val access: IptvProfileAccess, private val runtime: LivePlaybackRuntime,
    private val profiles: ProfileManager,
    private val screensaver: com.nuvio.tv.core.player.ScreensaverController,
    private val refresher: IptvRefreshCoordinator,
    private val playerSettings: com.nuvio.tv.data.local.PlayerSettingsDataStore,
    private val shortGuides: IptvShortGuideRepository,
    private val liveLaunch: IptvLiveLaunch,
    private val admission: LiveSessionAdmission,
    private val recorder: com.nuvio.tv.core.recording.IptvRecorder,
    private val device: IptvDeviceProfile,
    private val livePreferences: IptvLivePreferences,
    private val recordingTargets: com.nuvio.tv.core.recording.IptvRecordingTargets,
    private val sportsFixtures: IptvSportsFixturesRepository) : ViewModel() {
    private val mutable = MutableStateFlow(IptvLiveState(maxTiles = device.maxTiles))
    val state = mutable.asStateFlow()
    private val fullscreenRequested = MutableStateFlow(false)
    val fullscreenRequest = fullscreenRequested.asStateFlow()
    private val owner = UUID.randomUUID().toString()
    private val browse = IptvBrowseRepository(catalogue, guides)
    private val stalker = IptvStalkerClient()
    private var session: IptvProfileAccess.Session? = null
    private var profileRevision = -1L
    private var foreground = false
    private var pageJob: Job? = null
    private var guideReloadPending = false
    private var searchJob: Job? = null
    private var channelSearch: Job? = null
    private var shortGuideJob: Job? = null
    private val tileRuntimes = List(MAX_TILES) { LivePlaybackRuntime(admission) }
    private val tileJobs = arrayOfNulls<Job>(MAX_TILES)
    private val tileVersions = LongArray(MAX_TILES)
    private var multiviewJob: Job? = null
    private var tileSizes: List<Int> = emptyList()
    private var multiviewGeneration = 0L
    private var restored: IptvSourceRef? = null
    private val preferences = livePreferences.preferences
    @OptIn(ExperimentalCoroutinesApi::class)
    val expiryWarning: StateFlow<ExpiryWarning?> = profiles.activeProfileId.flatMapLatest { IptvSourceConnections(catalogue, livePreferences).expiryWarning(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val insetRuntime = LivePlaybackRuntime(admission)
    private var insetJob: Job? = null
    private var insetVersion = 0L
    private var scrubJob: Job? = null
    private var scrubLoad: Job? = null
    private var catchupWatch: Job? = null
    private var moveJob: Job? = null
    private var categoriesJob: Job? = null
    private var pickerJob: Job? = null
    private var extraGuideJob: Job? = null
    private val timeshiftPreferences = IptvTimeshiftPreferences(context)
    private var fullscreen = false
    private fun refOf(row: IptvListedChannel): IptvSourceRef? = session?.let { IptvSourceRef(it.profileId, row.item.channel.sourceId) }
    private fun hiddenOf(ref: IptvSourceRef): Set<String> = preferences.getStringSet(livePreferences.key(ref, "hidden"), null).orEmpty().toSet()
    private fun ordered(ref: IptvSourceRef, categories: List<IptvCategory>): List<IptvCategory> {
        val byName = categories.associateBy { it.name }
        return CategoryOrder.apply(categories.map { it.name }, livePreferences.categoryOrder(ref)).mapNotNull(byName::get)
    }
    private fun remember(state: IptvLiveState) {
        val ref = state.source ?: return
        preferences.edit().putString(livePreferences.key(ref, "category"), when {
            state.favourites -> FAVOURITES_KEY
            state.sports -> SPORTS_KEY
            else -> state.category ?: ALL_KEY
        }).apply()
    }
    private var tuneJob: Job? = null
    private var warmJob: Job? = null
    private var backupJob: Job? = null
    private val backupArm = MutableStateFlow<Pair<String, IptvListedChannel>?>(null)
    val backup: StateFlow<Pair<String, IptvListedChannel>?> = backupArm.asStateFlow()
    private var twinJob: Job? = null
    private var twinFor: String? = null
    private val twinState = MutableStateFlow<Pair<String, List<IptvListedChannel>>?>(null)
    val twins: StateFlow<Pair<String, List<IptvListedChannel>>?> = twinState.asStateFlow()
    private var tuneVersion = 0L
    private var pageVersion = 0L

    init {
        viewModelScope.launch(Dispatchers.IO) { runCatching { IptvLocalTimeshiftPlaces.sweepOnce(timeshiftPreferences, recordingTargets) } }
        viewModelScope.launch { state.map { it.player != null || it.multiview?.any { tile -> tile.player != null } == true }.distinctUntilChanged().collect(screensaver::setPlaybackActive) }
        viewModelScope.launch { liveLaunch.source.collect { ref -> if (ref != null && session != null) { liveLaunch.source.value = null; showSource(ref) } } }
        viewModelScope.launch { liveLaunch.channel.collect { if (it != null) tuneLaunched() } }
        viewModelScope.launch {
            recorder.all.collect { all -> val profile = session?.profileId; mutable.update { it.copy(recordings = all.filter { r -> r.profileId == profile }) } }
        }
        viewModelScope.launch { playerSettings.controlLayoutSnapshot.collect { snapshot -> mutable.update { it.copy(controlLayout = snapshot.layout) } } }
        viewModelScope.launch {
            combine(profiles.activeProfileId, profiles.activeProfileReady, profiles.profileSelectionRevision) { id, ready, revision -> Triple(id, ready, revision) }
                .collect { (id, ready, revision) ->
                    session = null; profileRevision = revision; restored = null
                    pageJob?.cancel(); stop()
                    mutable.value = IptvLiveState(controlLayout = mutable.value.controlLayout, maxTiles = device.maxTiles)
                    if (ready) {
                        val current = withContext(Dispatchers.IO) { access.open(id) }
                        session = current
                        val requested = liveLaunch.source.value?.takeIf { it.profileId == current.profileId }
                        liveLaunch.source.value = null
                        if (requested != null) mutable.update { it.copy(source = requested) }
                        mutable.update { it.copy(recordings = recorder.all.value.filter { r -> r.profileId == current.profileId }) }
                        load(); refresher.refreshStale(current)
                        tuneLaunched()
                    }
                }
        }
        viewModelScope.launch {
            var previous = emptyMap<String, IptvRefreshStatus>()
            refresher.status.collect { statuses ->
                val profile = session?.profileId
                val mine = statuses.filterKeys { profile != null && it.contains(":$profile:") }
                val running = mine.values.filter { it.running }
                mutable.update { it.copy(refresh = mine, updating = when {
                    running.any { it.phase == IptvRefreshPhase.DOWNLOADING || it.phase == IptvRefreshPhase.SAVING } -> R.string.iptv_refresh_downloading
                    running.any { it.phase == IptvRefreshPhase.GUIDE } -> R.string.iptv_refresh_guide
                    else -> null
                }) }
                val landed = mine.any { (key, value) -> previous[key]?.phase != value.phase && value.phase in setOf(IptvRefreshPhase.GUIDE, IptvRefreshPhase.DONE) }
                previous = mine
                if (landed && foreground) { if (pageJob?.isActive == true) guideReloadPending = true else load(background = true) }
            }
        }
        viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                delay(60_000)
                val window = mutable.value.window
                if (foreground && pageJob?.isActive != true && window != null && windowFor(mutable.value, System.currentTimeMillis()) != window) load(background = true)
                if (foreground && ++ticks % 60 == 0) session?.let { refresher.refreshStale(it) }
                else if (foreground && ticks % 5 == 0 && mutable.value.sports && mutable.value.search.isBlank() && pageJob?.isActive != true) load(background = true)
            }
        }
    }
    fun foreground(active: Boolean) {
        foreground = active
        if (active) load(background = mutable.value.channels.isNotEmpty()) else { exitMultiview(); stop() }
        if (active) tuneLaunched()
    }
    fun fullscreenShown() { fullscreenRequested.value = false }
    private fun tuneLaunched() {
        val current = session ?: return
        if (!foreground) return
        val tune = liveLaunch.channel.value ?: return
        liveLaunch.channel.value = null
        if (tune.profileId != current.profileId) return
        val row = tune.row
        val ref = refOf(row) ?: return
        val state = mutable.value
        restored = ref
        mutable.update { it.copy(source = ref, focused = row, category = null, favourites = tune.favourites, sports = tune.sport && it.sportEnabled,
            search = "", hiddenCategories = hiddenOf(ref), channels = if (it.source == ref) it.channels else emptyList(), next = null,
            guide = if (it.source == ref) it.guide else emptyMap()) }
        load()
        if (state.multiview != null) exitMultiview(row)
        else if (state.playingId != row.item.channel.id || state.player == null || state.catchup != null) watch(row)
        fullscreenRequested.value = true
    }
    fun loadMore() { if (mutable.value.next != null) load(append = true) }
    fun showFavourites() { mutable.update { it.copy(favourites = true, sports = false, category = null, focused = null) }; remember(mutable.value); load() }
    private var beforeSports: Pair<Boolean, String?> = false to null
    fun showSports() {
        mutable.value.takeIf { !it.sports }?.let { beforeSports = it.favourites to it.category }
        mutable.update { it.copy(favourites = false, sports = true, category = null, focused = null) }; remember(mutable.value); load()
    }
    fun leaveSports() { val (favourites, category) = beforeSports; if (favourites) showFavourites() else showCategory(category) }
    fun showCategory(name: String?) { mutable.update { it.copy(favourites = false, sports = false, category = name, focused = null) }; remember(mutable.value); load() }
    fun toggleHidden(name: String) {
        val ref = mutable.value.source ?: return
        val hidden = mutable.value.hiddenCategories.let { if (name in it) it - name else it + name }
        preferences.edit().putStringSet(livePreferences.key(ref, "hidden"), hidden).apply()
        mutable.update { it.copy(hiddenCategories = hidden) }
        if (mutable.value.category == null && !mutable.value.favourites) load()
    }
    fun lastChannel() {
        val current = mutable.value
        current.channels.firstOrNull { it.item.channel.id == current.previousId }?.let { mutable.update { state -> state.copy(focused = it) }; watch(it) }
    }
    fun watchNumber(number: Int) {
        val state = mutable.value
        state.channels.getOrNull(number - 1)?.let { mutable.update { current -> current.copy(focused = it) }; watch(it); return }
        val ref = state.source ?: return
        val next = state.next ?: return
        if (number < 1 || number > MAX_NUMBER) return
        viewModelScope.launch {
            val row = runCatching { browse.page(ref, next.query, next.copy(offset = number - 1), 1).channels.firstOrNull() }
                .getOrElse { if (it is CancellationException) throw it; null }
            if (row == null) mutable.update { it.copy(message = R.string.iptv_live_number_missing) }
            else if (mutable.value.source == ref) {
                if (mutable.value.guide[row.item.channel.id] == null) mutable.value.window?.let { window ->
                    val rows = runCatching { browse.guideRows(ref.profileId, listOf(row), window) }.getOrElse { if (it is CancellationException) throw it; emptyMap() }
                    mutable.update { it.copy(guide = it.guide + rows) }
                }
                watch(row)
            }
        }
    }
    fun zap(delta: Int) {
        val current = mutable.value
        if (current.channels.isEmpty()) return
        val index = current.channels.indexOfFirst { it.item.channel.id == (current.playingId ?: current.focused?.item?.channel?.id) }
        val target = current.channels[Math.floorMod((if (index < 0) 0 else index) + delta, current.channels.size)]
        mutable.update { it.copy(focused = target) }
        watch(target)
    }
    fun toggleFavourite() {
        val current = session ?: return
        val row = mutable.value.focused ?: return
        val ref = refOf(row) ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { access.use(current) {
                    val latest = requireNotNull(catalogue.playbackItem(ref, row.item.channel.id))
                    val next = catalogue.page(ref, IptvBrowseQuery(favouritesOnly = true), null, 200).items
                        .mapNotNull { it.overlay.favouriteRank }.maxOrNull()?.plus(1) ?: 0
                    catalogue.setOverlay(ref, latest.channel.id, latest.overlay.copy(favouriteRank = if (latest.overlay.favouriteRank == null) next else null))
                } }
                if (session === current) load(background = true)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (session === current) mutable.update { it.copy(message = R.string.iptv_setup_failed) } }
        }
    }
    fun moveFavourite(row: IptvListedChannel, delta: Int) {
        val current = session ?: return
        val state = mutable.value
        val ref = state.source ?: return
        if (!state.favourites || state.search.isNotBlank() || state.mergedFavourites) return
        val order = state.channels.map { it.item.channel.id }.toMutableList()
        val index = order.indexOf(row.item.channel.id)
        val target = index + delta
        if (index < 0 || target !in order.indices) return
        order.add(target, order.removeAt(index))
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { access.use(current) {
                    order.forEachIndexed { rank, id ->
                        val latest = catalogue.playbackItem(ref, id) ?: return@forEachIndexed
                        if (latest.overlay.favouriteRank != rank) catalogue.setOverlay(ref, id, latest.overlay.copy(favouriteRank = rank))
                    }
                } }
                if (session === current) load(background = true)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (session === current) mutable.update { it.copy(message = R.string.iptv_setup_failed) } }
        }
    }
    fun openGuidePicker(row: IptvListedChannel) {
        val current = session ?: return
        val ref = refOf(row) ?: return
        viewModelScope.launch {
            val feeds = runCatching { withContext(Dispatchers.IO) { access.use(current) {
                val linked = catalogue.guideAssociations(ref)
                (linked.priority + linked.feedIds).distinct().map { guides.feed(IptvGuideRef(current.profileId, it)) }
            } } }.getOrElse { if (it is CancellationException) throw it; emptyList() }
            if (session === current) mutable.update { it.copy(guidePicker = IptvGuidePicker(row, feeds)) }
        }
    }
    fun pickGuideFeed(feed: IptvGuideRef?) {
        mutable.update { state -> state.copy(guidePicker = state.guidePicker?.copy(feed = feed, query = "", results = emptyList())) }
        if (feed != null) searchGuide("")
    }
    fun searchGuide(query: String) {
        val current = session ?: return
        val picker = mutable.value.guidePicker ?: return
        val feed = picker.feed ?: return
        mutable.update { it.copy(guidePicker = picker.copy(query = query)) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(200)
            val results = runCatching { withContext(Dispatchers.IO) { access.use(current) { guides.searchChannels(feed, query.take(256)) } } }
                .getOrElse { if (it is CancellationException) throw it; emptyList() }
            mutable.update { state -> state.copy(guidePicker = state.guidePicker?.takeIf { it.feed == feed && it.query == query }?.copy(results = results) ?: state.guidePicker) }
        }
    }
    fun chooseGuideChannel(externalId: String?) {
        val current = session ?: return
        val picker = mutable.value.guidePicker ?: return
        val ref = refOf(picker.row) ?: return
        val key = externalId?.let { GuideKey(requireNotNull(picker.feed).feedId, it) }
        mutable.update { it.copy(guidePicker = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { access.use(current) {
                    val latest = requireNotNull(catalogue.playbackItem(ref, picker.row.item.channel.id))
                    catalogue.setOverlay(ref, latest.channel.id, latest.overlay.copy(manualGuide = key))
                } }
                key?.let { refresher.refreshIfChanged(current, IptvGuideRef(current.profileId, it.feedId)) }
                if (session === current) load(background = true)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (session === current) mutable.update { it.copy(message = R.string.iptv_setup_failed) } }
        }
    }
    fun closeGuidePicker() { mutable.update { it.copy(guidePicker = null) } }
    fun setStreamFormat(row: IptvListedChannel, format: IptvStreamFormat) {
        val current = session ?: return
        val ref = refOf(row) ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { access.use(current) {
                    val latest = requireNotNull(catalogue.playbackItem(ref, row.item.channel.id))
                    catalogue.setOverlay(ref, latest.channel.id, latest.overlay.copy(streamFormat = format))
                } }
                if (session === current) {
                    mutable.update { it.copy(message = R.string.iptv_live_format_saved) }
                    load(background = true)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (session === current) mutable.update { it.copy(message = R.string.iptv_setup_failed) } }
        }
    }
    fun searchMode(airing: Boolean) {
        if (airing == mutable.value.airingSearch) return
        mutable.update { it.copy(airingSearch = airing) }
        if (mutable.value.search.isNotBlank()) load()
    }
    fun search(text: String) {
        val value = text.take(256)
        if (value == mutable.value.search) return
        mutable.update { it.copy(search = value) }
        channelSearch?.cancel()
        channelSearch = viewModelScope.launch { delay(300); load() }
    }
    fun record(row: IptvListedChannel, programme: GuideProgramme?, location: String? = null) {
        val current = session ?: return
        val ref = refOf(row) ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val result = try {
                if (programme != null && programme.start.epochMillis > now) recorder.schedule(current, ref, row.item.channel.id, programme, location)
                else recorder.recordNow(current, ref, row.item.channel.id, programme?.takeIf { airing(it, now) }, location)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { IptvLog.failure("record", error); null }
            val message = when (result) {
                is com.nuvio.tv.core.recording.IptvRecordResult.Accepted ->
                    if (result.immediate) R.string.iptv_recording_started else R.string.iptv_recording_scheduled
                is com.nuvio.tv.core.recording.IptvRecordResult.Refused -> iptvRecordRefusalMessage(result.reason)
                null -> R.string.iptv_setup_failed
            }
            val alarms = (result as? com.nuvio.tv.core.recording.IptvRecordResult.Refused)?.reason ==
                com.nuvio.tv.core.recording.IptvRecordRefusal.EXACT_ALARMS_DENIED
            if (session === current) mutable.update { it.copy(message = if (alarms) null else message, alarmPrompt = alarms) }
        }
    }
    fun alarmSettings(): android.content.Intent? = recorder.exactAlarmSettings()
    fun dismissAlarmPrompt() { mutable.update { it.copy(alarmPrompt = false) } }
    fun cancelRecording(id: String) {
        viewModelScope.launch { runCatching { recorder.cancel(id) }.onFailure { if (it is CancellationException) throw it } }
    }
    fun clearMessage() { mutable.update { it.copy(message = null) } }
    fun showSource(ref: IptvSourceRef) {
        if (ref == mutable.value.source) return
        mutable.update { it.copy(source = ref, focused = null, category = null, favourites = false, sports = false, channels = emptyList(), next = null, guide = emptyMap(), loading = true) }
        load()
    }
    fun refreshSource() {
        val current = session ?: return
        val ref = mutable.value.source ?: return
        mutable.value.sources.firstOrNull { it.ref == ref }?.let { refresher.refresh(current, it) }
    }
    fun nextSource() {
        val current = mutable.value
        if (current.sources.isEmpty()) return
        val index = current.sources.indexOfFirst { it.ref == current.source }
        mutable.update { it.copy(source = current.sources[(index + 1) % current.sources.size].ref, focused = null, category = null, favourites = false, sports = false) }
        load()
    }
    private fun load(append: Boolean = false, background: Boolean = false) {
        val current = session ?: return
        if (append && pageJob?.isActive == true) return
        pageJob?.cancel()
        val request = ++pageVersion
        if (!append) guideReloadPending = false
        pageJob = viewModelScope.launch {
            if (!background && !append) mutable.update { it.copy(loading = true) }
            try {
                val sources = withContext(Dispatchers.IO) { access.use(current) { catalogue.sources(current.profileId) } }
                val ref = mutable.value.source?.takeIf { chosen -> sources.any { it.ref == chosen } }
                    ?: sources.firstOrNull { it.playbackEligible }?.ref ?: sources.firstOrNull()?.ref
                val sportEnabled = livePreferences.sport
                if (!sportEnabled && mutable.value.sports) mutable.update { it.copy(sports = false) }
                mutable.update { it.copy(sportEnabled = sportEnabled, timeshiftEnabled = livePreferences.timeshift, density = livePreferences.guideDensity) }
                if (ref != null && ref != restored && !append) {
                    restored = ref
                    val saved = when (livePreferences.startView) {
                        IptvStartView.LAST -> preferences.getString(livePreferences.key(ref, "category"), null)
                        IptvStartView.ALL -> ALL_KEY
                        IptvStartView.FAVOURITES -> FAVOURITES_KEY
                        IptvStartView.SPORT -> SPORTS_KEY
                    }?.takeIf { sportEnabled || it != SPORTS_KEY }
                    val hidden = hiddenOf(ref)
                    mutable.update { it.copy(hiddenCategories = hidden, favourites = saved == FAVOURITES_KEY, sports = saved == SPORTS_KEY,
                        category = saved?.takeIf { value -> value != FAVOURITES_KEY && value != ALL_KEY && value != SPORTS_KEY }) }
                }
                val state = mutable.value
                val query = IptvBrowseQuery(search = state.search.trim().take(256), favouritesOnly = state.favourites, category = state.category.takeUnless { state.favourites },
                    excludedCategories = if (state.favourites || state.category != null) emptySet() else state.hiddenCategories.take(500).toSet())
                val cursor = state.next?.takeIf { append && it.revision.ref == ref && it.query == query }
                if (append && cursor == null) return@launch
                val wanted = if (background && !append) state.channels.size.coerceIn(PAGE, device.backgroundRows) else PAGE
                val airing = if (ref != null && !append && state.airingSearch && query.search.isNotBlank())
                    browse.searchAiring(ref, query.search, System.currentTimeMillis(), AIRING_RESULTS,
                        state.hiddenCategories.take(500).toSet())
                else if (ref != null && !append && state.sports && query.search.isBlank())
                    browse.sports(ref, System.currentTimeMillis(), limit = AIRING_RESULTS, excludedCategories = state.hiddenCategories.take(500).toSet())
                else null
                val merged = if (!append && state.mergedFavourites) mergedFavourites(sources) else null
                var page = if (ref == null || airing != null || merged != null) null else try { browse.page(ref, query, cursor, wanted.coerceAtMost(200)) }
                    catch (_: IptvCatalogueChangedException) { if (append) return@launch else browse.page(ref, query, null, wanted.coerceAtMost(200)) }
                var extra = emptyList<IptvListedChannel>()
                while (ref != null && page != null && !append && page.channels.size + extra.size < wanted) {
                    val more = page.catalogue.next ?: break
                    val nextPage = try { browse.page(ref, query, more, (wanted - page.channels.size - extra.size).coerceAtMost(200)) }
                        catch (_: IptvCatalogueChangedException) { break }
                    extra = extra + nextPage.channels
                    page = page.copy(catalogue = page.catalogue.copy(next = nextPage.catalogue.next))
                }
                val categories = if (ref == null) emptyList() else if (append) state.categories
                    else ordered(ref, withContext(Dispatchers.IO) { access.use(current) { catalogue.categories(ref) } })
                if (!append && state.category != null && state.search.isBlank() && categories.none { it.name == state.category }) {
                    mutable.update { it.copy(category = null) }
                    remember(mutable.value)
                    load(background = background)
                    return@launch
                }
                val now = System.currentTimeMillis()
                val window = windowFor(state, now, append)
                val always = if (ref != null && !append && state.sports && query.search.isBlank())
                    runCatching { sportsFixtures.alwaysChannels(ref, state.hiddenCategories) }.getOrElse { if (it is CancellationException) throw it; emptyList() }
                else emptyList()
                val loaded = airing?.map { it.channel }?.let { if (always.isEmpty()) it else (it + always).distinctBy { row -> row.item.channel.id } }
                    ?: merged ?: (page?.channels.orEmpty() + extra)
                val rows = if (ref == null || loaded.isEmpty()) emptyMap() else loaded.chunked(200).fold(emptyMap<String, GuideGridRow>()) { acc, part -> acc + browse.guideRows(current.profileId, part, window) }
                if (session === current && request == pageVersion) {
                    val channels = if (append) state.channels + loaded else loaded
                    val loadedIds = loaded.mapTo(HashSet()) { it.item.channel.id }
                    val focusedId = mutable.value.focused?.item?.channel?.id
                    val focused = channels.firstOrNull { it.item.channel.id == focusedId }
                        ?: channels.firstOrNull { it.item.channel.id == state.playingId } ?: channels.firstOrNull()
                    mutable.update { it.copy(sources = sources, source = ref, channels = channels, next = page?.catalogue?.next,
                        categories = categories, window = window, focused = focused, guide = when {
                        append -> it.guide + rows
                        background && window == it.window -> rows + it.guide.filterKeys { id ->
                            rows[id]?.cells?.none { c -> c is GuideProgrammeCell } != false && id in loadedIds }
                        else -> rows
                    }) }
                    if (!append && mutable.value.allSources) loadSourceCategories(sources)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { IptvLog.failure("live load", error); if (session === current) mutable.update { it.copy(message = R.string.iptv_setup_failed) } }
            finally {
                if (session === current && request == pageVersion) {
                    mutable.update { it.copy(loading = false, loaded = true) }
                    if (guideReloadPending && foreground) { guideReloadPending = false; viewModelScope.launch { load(background = true) } }
                }
            }
        }
    }
    private suspend fun mergedFavourites(sources: List<IptvSource>): List<IptvListedChannel> {
        val result = mutableListOf<IptvListedChannel>()
        for (source in sources.filter { it.playbackEligible }) {
            if (result.size >= MERGED_FAVOURITES) break
            result += runCatching { browse.page(source.ref, IptvBrowseQuery(favouritesOnly = true), null, minOf(200, MERGED_FAVOURITES - result.size)).channels }
                .getOrElse { if (it is CancellationException) throw it; emptyList() }
        }
        return result
    }
    private fun loadSourceCategories(sources: List<IptvSource>) {
        val current = session ?: return
        categoriesJob?.cancel()
        categoriesJob = viewModelScope.launch {
            val grouped = runCatching { withContext(Dispatchers.IO) { sources.filter { it.playbackEligible }.map { source ->
                val hidden = hiddenOf(source.ref)
                IptvSourceCategories(source, ordered(source.ref, access.use(current) { catalogue.categories(source.ref) }).filter { it.name !in hidden })
            } } }.getOrElse { if (it is CancellationException) throw it; emptyList() }
            if (session === current) mutable.update { it.copy(sourceCategories = grouped) }
        }
    }
    fun toggleAllSources() {
        val wasMerged = mutable.value.mergedFavourites
        mutable.update { it.copy(allSources = !it.allSources, sourceCategories = if (it.allSources) emptyList() else it.sourceCategories) }
        val state = mutable.value
        if (state.allSources) loadSourceCategories(state.sources)
        if (wasMerged != state.mergedFavourites) load()
    }
    fun showSourceCategory(ref: IptvSourceRef, name: String?) {
        if (mutable.value.sources.none { it.ref == ref }) return
        restored = ref
        val hidden = hiddenOf(ref)
        mutable.update { it.copy(source = ref, focused = null, category = name, favourites = false, sports = false, hiddenCategories = hidden,
            channels = if (it.source == ref) it.channels else emptyList(), next = null, guide = if (it.source == ref) it.guide else emptyMap(), loading = true) }
        remember(mutable.value)
        load()
    }
    fun moveCategory(name: String, move: ListMove) {
        val state = mutable.value
        val ref = state.source ?: return
        val visible = state.categories.filter { it.name !in state.hiddenCategories }.map { it.name }
        val order = CategoryOrder.move(visible, state.categories.map { it.name }.filter { it in state.hiddenCategories }, name, move) ?: return
        livePreferences.setCategoryOrder(ref, order)
        mutable.update { it.copy(categories = ordered(ref, it.categories)) }
        if (state.allSources) loadSourceCategories(state.sources)
    }
    fun moveChannel(row: IptvListedChannel, move: ListMove) {
        val current = session ?: return
        val state = mutable.value
        val ref = state.source ?: return
        if (!state.canReorder || refOf(row) != ref) return
        val index = state.channels.indexOfFirst { it.item.channel.id == row.item.channel.id }
        val local = if (move == ListMove.BOTTOM && state.next != null && index >= 0) state.channels.filterIndexed { i, _ -> i != index }
            else movedList(state.channels, index, move) ?: return
        mutable.update { it.copy(channels = local, message = if (local.size < state.channels.size) R.string.iptv_move_moved_bottom else it.message) }
        val query = IptvBrowseQuery(category = state.category.takeUnless { state.favourites },
            excludedCategories = if (state.favourites || state.category != null) emptySet() else state.hiddenCategories.take(500).toSet())
        val favourites = state.favourites
        val previous = moveJob
        moveJob = viewModelScope.launch {
            previous?.join()
            try {
                withContext(Dispatchers.IO) { access.use(current) {
                    if (!favourites) catalogue.moveChannel(ref, row.item.channel.id, query, move)
                    else {
                        val all = mutableListOf<IptvCatalogueItem>()
                        var cursor: IptvBrowseCursor? = null
                        do {
                            val page = catalogue.page(ref, IptvBrowseQuery(favouritesOnly = true), cursor, 200)
                            all += page.items; cursor = page.next
                        } while (cursor != null && all.size < MAX_FAVOURITES)
                        val moved = movedList(all, all.indexOfFirst { it.channel.id == row.item.channel.id }, move)
                        moved?.forEachIndexed { rank, item -> if (item.overlay.favouriteRank != rank) catalogue.setOverlay(ref, item.channel.id, item.overlay.copy(favouriteRank = rank)) }
                    }
                } }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { IptvLog.failure("move channel", error); if (session === current) mutable.update { it.copy(message = R.string.iptv_setup_failed) } }
        }
    }
    fun finishMove() {
        val previous = moveJob
        viewModelScope.launch { previous?.join(); load(background = true) }
    }
    fun focus(row: IptvListedChannel) {
        if (session != null) mutable.update { it.copy(focused = row) }
        loadShortGuide(row)
        prewarm(row)
    }
    private fun prewarm(row: IptvListedChannel) {
        warmJob?.cancel()
        IptvLiveNet.drop()
        val current = session ?: return
        val ref = refOf(row) ?: return
        val state = mutable.value
        if (!foreground || state.multiview != null || row.item.channel.id == state.playingId) return
        val kind = state.sources.firstOrNull { it.ref == ref }?.kind ?: return
        if (kind == IptvSourceKind.STALKER) return
        warmJob = viewModelScope.launch {
            delay(WARM_DELAY)
            val url = runCatching { withContext(Dispatchers.IO) { access.use(current) {
                val item = requireNotNull(catalogue.playbackItem(ref, row.item.channel.id))
                if (kind == IptvSourceKind.XTREAM) IptvXtreamClient.streamUrl(catalogue.connection(ref), item.channel.data.locator) else item.channel.data.locator
            } } }.getOrElse { if (it is CancellationException) throw it; null } ?: return@launch
            if (session === current && foreground && mutable.value.focused?.item?.channel?.id == row.item.channel.id)
                IptvLiveNet.warm(url, liveReceiveBytes(liveLowMemory(context)))
        }
    }
    private fun loadShortGuide(row: IptvListedChannel) {
        val current = session ?: return
        val state = mutable.value
        val ref = refOf(row) ?: return
        val id = row.item.channel.id
        val now = System.currentTimeMillis()
        if ((state.guide[id] ?: state.extraGuide[id])?.cells?.any { it is GuideProgrammeCell } == true ||
            state.shortGuide[id]?.any { it.start.epochMillis <= now && (it.stop?.epochMillis ?: Long.MAX_VALUE) > now } == true) return
        if (state.sources.firstOrNull { it.ref == ref }?.kind != IptvSourceKind.XTREAM) return
        shortGuideJob?.cancel()
        shortGuideJob = viewModelScope.launch {
            delay(400)
            val programmes = runCatching { withContext(Dispatchers.IO) { access.use(current) { } }; shortGuides.nowNext(ref, id) }
                .getOrElse { if (it is CancellationException) throw it; emptyList() }
            if (session === current) mutable.update { it.copy(shortGuide = (it.shortGuide + (id to programmes)).entries.toList().takeLast(SHORT_GUIDE_CACHE).associate { e -> e.key to e.value }) }
        }
    }
    fun boost(row: IptvListedChannel): Int = refOf(row)?.let { livePreferences.boost(it, row.item.channel.id) } ?: 0
    fun setBoost(row: IptvListedChannel, db: Int) {
        val ref = refOf(row) ?: return
        livePreferences.setBoost(ref, row.item.channel.id, db)
        if (mutable.value.playingId == row.item.channel.id) mutable.value.playback?.setBoost(db)
    }
    fun togglePause() { if (mutable.value.paused) resume() else pause() }
    fun pause() {
        val state = mutable.value
        val player = state.player ?: return
        if (state.paused) return
        player.playWhenReady = false
        val now = System.currentTimeMillis()
        val programme = if (state.catchup == null) state.playingRow?.let { liveProgramme(state, it.item.channel.id, now) } else null
        mutable.update { it.copy(paused = true, pausedAt = now, pausedProgramme = programme, localBehind = it.localTimeshift) }
    }
    fun resume() {
        val state = mutable.value
        val player = state.player ?: return
        val row = state.playingRow
        val pausedAt = state.pausedAt
        val from = if (state.catchup == null && row != null && pausedAt != null && !state.localTimeshift)
            LiveTimeshift.resumeFrom(pausedAt, System.currentTimeMillis(), state.pausedProgramme, hasArchive(row) && livePreferences.timeshift) else null
        mutable.update { it.copy(paused = false, pausedAt = null, pausedProgramme = null) }
        if (from != null && row != null) watch(row, state.pausedProgramme, from) else player.playWhenReady = true
    }
    fun rewindLive(): Boolean {
        val state = mutable.value
        val row = state.playingRow ?: return false
        if (state.player == null) return false
        val now = System.currentTimeMillis()
        val catchup = state.catchup
        val behind = state.catchupFrom
        if (catchup != null) {
            if (behind == null) return false
            val from = LiveTimeshift.rewindFrom(behind, catchup, hasArchive(row) && livePreferences.timeshift) ?: return false
            if (from >= behind) return false
            watch(row, catchup, from)
            return true
        }
        if (state.localTimeshift && localStep(row, (state.playback?.localPosition() ?: now) - LiveTimeshift.REWIND_MILLIS)) return true
        val programme = liveProgramme(state, row.item.channel.id, now)
        val from = LiveTimeshift.rewindFrom(now, programme, hasArchive(row) && livePreferences.timeshift) ?: return false
        watch(row, programme, from)
        return true
    }
    fun armBackup(playingId: String, backup: IptvListedChannel?) {
        backupArm.value = backup?.takeIf { it.item.channel.id != playingId }?.let { playingId to it }
        if (backup == null) backupJob?.cancel()
    }
    fun findTwins(row: IptvListedChannel?) {
        val current = session ?: return
        if (row == null) return
        val id = row.item.channel.id
        if (twinFor == id && (twinJob?.isActive == true || twinState.value?.first == id)) return
        twinJob?.cancel()
        twinFor = id
        val sources = mutable.value.sources.filter { it.playbackEligible && it.ref.sourceId != row.item.channel.sourceId }.take(MAX_TWIN_SOURCES)
        val term = guideSearchPhrase(row.item.channel.data.name)
        if (sources.isEmpty() || term == null) { twinState.value = id to emptyList(); return }
        twinJob = viewModelScope.launch {
            val found = sources.flatMap { source ->
                runCatching { browse.page(source.ref, IptvBrowseQuery(search = term, excludedCategories = hiddenOf(source.ref).take(500).toSet()), null, TWIN_PAGE).channels }
                    .getOrElse { if (it is CancellationException) throw it; emptyList() }
            }
            val byKey = found.associateBy { it.item.channel.sourceId to it.item.channel.id }
            val picked = ChannelTwins.pick(twin(row), found.map(::twin)).mapNotNull { byKey[it.sourceId to it.id] }
            if (session === current && twinFor == id) twinState.value = id to picked
        }
    }
    private fun twin(row: IptvListedChannel) = TwinChannel(row.item.channel.sourceId, row.item.channel.id, row.item.channel.data.name, row.item.channel.data.guideId, row.guide.key)
    private fun switchToBackup(row: IptvListedChannel): Boolean {
        val (id, backup) = backupArm.value ?: return false
        val state = mutable.value
        if (id != row.item.channel.id || state.playingId != id || state.catchup != null || state.multiview != null) return false
        backupArm.value = null
        backupJob?.cancel()
        IptvLog.info("live backup switch")
        watch(backup, notice = R.string.iptv_sport5p_backup_switched)
        return true
    }
    private fun backupOnStall(row: IptvListedChannel, request: Long, active: Boolean) {
        backupJob?.cancel()
        if (!active || backupArm.value?.first != row.item.channel.id) return
        backupJob = viewModelScope.launch {
            delay(BACKUP_STALL)
            if (request == tuneVersion && mutable.value.reconnecting) switchToBackup(row)
        }
    }
    fun seekTo(millis: Long): Boolean {
        val state = mutable.value
        val row = state.playingRow ?: return false
        val player = state.player ?: return false
        val now = System.currentTimeMillis()
        val target = millis.coerceAtMost(now)
        val catchup = state.catchup
        if (catchup != null) return scrub(target - (state.scrubTarget ?: LiveTimeshift.position(catchup, state.catchupFrom, player.currentPosition)))
        if (state.localTimeshift) return scrub(target - (state.scrubTarget ?: state.playback?.localPosition() ?: now))
        val programme = if (hasArchive(row) && livePreferences.timeshift) CatchupScrub.programmeAt(catchupProgrammes(state, row.item.channel.id), target)
            ?: liveProgramme(state, row.item.channel.id, target) else null
        if (programme == null || !programme.start.precise) { mutable.update { it.copy(message = R.string.iptv_live_catchup_unavailable) }; return false }
        watch(row, programme, target.takeIf { it > programme.start.epochMillis })
        return true
    }
    fun goLive(): Boolean {
        val state = mutable.value
        val row = state.playingRow ?: return false
        val player = state.player ?: return false
        val playback = state.playback ?: return false
        return when (goLiveRoute(state, System.currentTimeMillis())) {
            GoLiveRoute.CUSHION -> playback.goLive()
            GoLiveRoute.LOCAL -> playback.localLive().also { live ->
                if (live) {
                    scrubJob?.cancel()
                    player.playWhenReady = true
                    mutable.update { it.copy(localBehind = false, paused = false, pausedAt = null, pausedProgramme = null, scrubTarget = null) }
                }
            }
            GoLiveRoute.RETUNE -> { watch(row); true }
            GoLiveRoute.NONE -> false
        }
    }
    val showStatsByDefault: Boolean get() = livePreferences.showStats
    private fun playWhenReadyChanged(ready: Boolean) {
        val state = mutable.value
        if (ready && state.paused) mutable.update { it.copy(paused = false, pausedAt = null, pausedProgramme = null) }
        else if (!ready && !state.paused) {
            val now = System.currentTimeMillis()
            val programme = if (state.catchup == null) state.playingRow?.let { liveProgramme(state, it.item.channel.id, now) } else null
            mutable.update { it.copy(paused = true, pausedAt = now, pausedProgramme = programme) }
        }
    }
    fun watch(row: IptvListedChannel, catchup: GuideProgramme? = null, from: Long? = null, notice: Int? = null, auto: Boolean = false) {
        val current = session ?: return
        val ref = refOf(row) ?: return
        if (!foreground) return
        val live = mutable.value
        if (catchup == null && from == null && notice == null && !auto && live.catchup == null && live.multiview == null &&
            live.playingId == row.item.channel.id && tuneJob?.isActive == true) {
            IptvLog.info("tune repeat ignored")
            fullscreenRequested.value = true
            return
        }
        tuneJob?.cancel(); scrubJob?.cancel(); catchupWatch?.cancel(); warmJob?.cancel()
        runtime.interrupt(owner)
        val insetClosing = if (live.inset?.row?.item?.channel?.id == row.item.channel.id) { closeInset(); insetJob } else null
        loadShortGuide(row)
        val request = ++tuneVersion
        val started = android.os.SystemClock.elapsedRealtime()
        val until = catchup?.let { CatchupScrub.streamEnd(it, from, System.currentTimeMillis()) }
        if (catchup != null) { loadScrubProgrammes(row, from ?: catchup.start.epochMillis); watchCatchup(row, request) }
        tuneJob = viewModelScope.launch {
            mutable.update { it.copy(tuning = true, playback = null, player = null, playingTitle = null, playing = false, reconnecting = false, catchup = catchup,
                catchupFrom = from.takeIf { catchup != null }, catchupUntil = until, scrubTarget = null,
                scrubProgrammes = if (it.playingId == row.item.channel.id) it.scrubProgrammes else emptyList(),
                paused = false, pausedAt = null, pausedProgramme = null, message = notice, playingId = row.item.channel.id, playingRow = row,
                localTimeshift = false, localBehind = false,
                previousId = it.playingId?.takeIf { id -> id != row.item.channel.id } ?: it.previousId,
                recent = (listOf(row.item.channel.id) + it.recent.filter { id -> id != row.item.channel.id }).take(RECENT)) }
            try {
                val (source, stored, streams) = withContext(Dispatchers.IO) { access.use(current) {
                    val source = catalogue.sources(current.profileId).single { it.ref == ref }
                    Triple(source, requireNotNull(catalogue.playbackItem(ref, row.item.channel.id)),
                        catalogue.accounts(current.profileId).firstOrNull { it.id == source.accountId }?.maxStreams ?: 1)
                } }
                val catchupStart = from ?: catchup?.start?.epochMillis
                if (catchup != null && !IptvCatchup.reaches(source.kind, stored.attributes, requireNotNull(catchupStart), System.currentTimeMillis()))
                    throw CatchupUnavailableException()
                val overlayFormat = stored.overlay.streamFormat
                val streamFormat = if (catchup != null && source.kind == IptvSourceKind.XTREAM) IptvStreamFormat.MPEG_TS
                    else overlayFormat.takeIf { it != IptvStreamFormat.AUTO } ?: livePreferences.defaultFormat
                val variant = if (catchup == null) "main:" + streamFormat.name else "catchup:$catchupStart:" + streamFormat.name
                val key = AcquisitionKey(admissionAccount(current.profileId, source.accountId), row.item.channel.id, variant, source.activeGeneration ?: 0)
                val local = if (catchup == null) localConfig(row) else null
                var plan = emptyList<IptvCatchupLocator>()
                var catchupConnection: IptvSourceConnection? = null
                val styles = IptvCatchupStyles.shared(context)
                val prepared = if (catchup != null) {
                    catchupConnection = if (source.kind == IptvSourceKind.XTREAM) withContext(Dispatchers.IO) { access.use(current) { catalogue.connection(ref) } } else null
                    plan = IptvCatchup.locators(source.kind, catchupConnection, stored, requireNotNull(catchupStart), requireNotNull(until), System.currentTimeMillis(),
                        remembered = catchupConnection?.let(styles::remembered)).ifEmpty { throw CatchupUnavailableException() }
                    plan.first().url
                } else if (source.kind == IptvSourceKind.STALKER) null else liveLocator(current, ref, source, stored)
                insetClosing?.join()
                val resolved = android.os.SystemClock.elapsedRealtime()
                var closed = resolved
                var logged = false
                val result = runtime.open(key, 16L * 1024 * 1024, 96L * 1024 * 1024, owner, streams) { purpose ->
                    closed = android.os.SystemClock.elapsedRealtime()
                    val item = withContext(Dispatchers.IO) { access.use(current) {
                        val latest = catalogue.sources(current.profileId).single { it.ref == ref }
                        check(latest.configurationVersion == source.configurationVersion && latest.accountId == source.accountId && latest.activeGeneration == source.activeGeneration)
                        requireNotNull(catalogue.playbackItem(ref, row.item.channel.id)).also { check(it.overlay.streamFormat == overlayFormat) }
                    } }
                    currentCoroutineContext().ensureActive()
                    check(session === current && foreground && request == tuneVersion && profiles.activeProfileId.value == current.profileId && profiles.profileSelectionRevision.value == profileRevision)
                    val locator = prepared ?: liveLocator(current, ref, source, item)
                    var failure: LiveFailure? = null
                    lateinit var created: IptvLivePlayback
                    created = IptvLivePlayback(context, locator, purpose, plan.firstOrNull()?.format ?: streamFormat, boostDb = livePreferences.boost(ref, row.item.channel.id),
                        headers = StreamHeaders.requestHeaders(item.attributes), sourceUserAgent = livePreferences.userAgent(ref),
                        alternatives = plan.drop(1).map { it.url to (it.format ?: streamFormat) },
                        onAlternative = { index -> catchupConnection?.let { styles.worked(it, plan.getOrNull(index)?.style) } },
                        onFailure = { failure = it },
                        onPlaying = { playing -> if (request != tuneVersion) handedTile(created, playing = playing) else {
                            mutable.update { it.copy(playing = playing) }
                            if (playing && !logged) {
                                logged = true
                                val now = android.os.SystemClock.elapsedRealtime()
                                IptvLog.info("tune kind=${source.kind} format=$streamFormat catchup=${catchup != null} auto=$auto prepare ms=${resolved - started} " +
                                    "close ms=${closed - resolved} play ms=${now - closed} total ms=${now - started} ${created.startSummary()}")
                            }
                        } },
                        onError = { if (request != tuneVersion) handedTile(created, failed = true) else {
                            if (from != null) watch(row, notice = R.string.iptv_live_timeshift_unavailable)
                            else if (catchup != null || !switchToBackup(row)) { stop(keepChannel = true); if (!auto) mutable.update { it.copy(message = failureMessage(failure)) } }
                        } },
                        onPlayWhenReady = { ready -> if (request == tuneVersion) playWhenReadyChanged(ready) },
                        onReconnecting = { active -> if (request == tuneVersion) {
                            mutable.update { it.copy(reconnecting = active) }
                            if (catchup == null) backupOnStall(row, request, active)
                        } },
                        isLive = catchup == null, onEnded = { if (request == tuneVersion) { if (catchup != null) continueCatchup(row) else watch(row) } },
                        localTimeshift = local, onLocalTimeshift = { active -> if (request == tuneVersion) localChanged(active) })
                    created.also { mutable.update { state -> state.copy(playback = it, player = it.player, playingTitle = item.overlay.customName ?: item.channel.data.name) } }
                }
                if (request == tuneVersion && result != LiveOpenResult.OPENED) mutable.update { it.copy(playback = null, player = null, playingTitle = null,
                    message = if (auto) null else if (result == LiveOpenResult.CLOSE_UNCONFIRMED) R.string.iptv_live_closing else if (result == LiveOpenResult.CAPACITY || result == LiveOpenResult.SHARING_UNAVAILABLE) R.string.iptv_live_capacity else R.string.iptv_live_failed) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (request == tuneVersion && from != null) watch(row, notice = R.string.iptv_live_timeshift_unavailable)
                else if (request == tuneVersion) {
                    mutable.update { it.copy(catchup = null, catchupFrom = null,
                        message = if (auto) null else if (error is CatchupUnavailableException) R.string.iptv_live_catchup_unavailable else R.string.iptv_live_failed) }
                    runtime.stop(owner)
                }
            }
            finally { if (request == tuneVersion) mutable.update { it.copy(tuning = false) } }
        }
    }
    fun setFullscreen(active: Boolean) {
        fullscreen = active
        val state = mutable.value
        val playback = state.playback ?: return
        val row = state.playingRow ?: return
        if (!active || state.catchup != null || playback.localTimeshift) return
        viewModelScope.launch {
            val config = localConfig(row) ?: return@launch
            if (mutable.value.playback === playback && fullscreen) playback.armLocalTimeshift(config)
        }
    }
    private suspend fun localConfig(row: IptvListedChannel): IptvLocalTimeshiftConfig? {
        val state = mutable.value
        val recording = state.recordings.any { it.channelId == row.item.channel.id && it.status == RecordingStatus.RECORDING }
        if (LocalTimeshiftPolicy.block(timeshiftPreferences.enabled, fullscreen, true, true, state.multiview != null, recording, true) != null) return null
        val config = withContext(Dispatchers.IO) { runCatching { IptvLocalTimeshiftPlaces.config(timeshiftPreferences, recordingTargets) }.getOrNull() }
        if (config == null) IptvLog.info("local timeshift unavailable reason=location")
        return config
    }
    private fun localChanged(active: Boolean) = mutable.update {
        it.copy(localTimeshift = active, localBehind = active && it.localBehind,
            message = if (!active && it.localTimeshift) R.string.iptv_timeshift_fallback else it.message)
    }
    private fun localStep(row: IptvListedChannel, target: Long): Boolean {
        val state = mutable.value
        val playback = state.playback?.takeIf { it.localTimeshift } ?: return false
        val now = System.currentTimeMillis()
        return when (val step = LocalTimeshiftPolicy.step(target, now, playback.localOldest(), hasArchive(row) && livePreferences.timeshift)) {
            LocalTimeshiftStep.Live -> { playback.localLive(); mutable.update { it.copy(localBehind = false) }; true }
            is LocalTimeshiftStep.Local -> { playback.localSeek(step.atMillis); mutable.update { it.copy(localBehind = true) }; true }
            LocalTimeshiftStep.Archive -> false
        }
    }
    private fun localScrub(row: IptvListedChannel, step: Long): Boolean {
        val state = mutable.value
        val playback = state.playback?.takeIf { it.localTimeshift } ?: return false
        val now = System.currentTimeMillis()
        val target = ((state.scrubTarget ?: playback.localPosition() ?: now) + step).coerceAtMost(now)
        scrubJob?.cancel()
        mutable.update { it.copy(scrubTarget = target) }
        scrubJob = viewModelScope.launch {
            delay(SCRUB_COMMIT)
            mutable.update { it.copy(scrubTarget = null) }
            if (mutable.value.playingId != row.item.channel.id || localStep(row, target)) return@launch
            val time = System.currentTimeMillis()
            val plan = CatchupScrub.plan(target, CatchupStream(time, null, false), catchupProgrammes(mutable.value, row.item.channel.id), time)
            if (plan is CatchupStep.Tune) tuneCatchup(row, plan, fallbackLive = false)
            else mutable.update { it.copy(message = R.string.iptv_live_catchup_unavailable) }
        }
        return true
    }
    private fun failureMessage(failure: LiveFailure?): Int = when (failure) {
        LiveFailure.DENIED -> R.string.iptv_playback_denied
        LiveFailure.MISSING -> R.string.iptv_playback_missing
        LiveFailure.UNSUPPORTED -> R.string.iptv_playback_unsupported
        LiveFailure.DECODER -> R.string.iptv_playback_decoder
        LiveFailure.EXHAUSTED, null -> R.string.iptv_live_failed
    }
    val displaySettings = playerSettings.playerSettings
    private fun catchupPosition(state: IptvLiveState): Long? {
        val catchup = state.catchup ?: return null
        val player = state.player ?: return null
        return LiveTimeshift.position(catchup, state.catchupFrom, player.currentPosition)
    }
    private fun loadScrubProgrammes(row: IptvListedChannel, around: Long) {
        val current = session ?: return
        val id = row.item.channel.id
        scrubLoad?.cancel()
        scrubLoad = viewModelScope.launch {
            val start = Math.floorDiv(around, GuideGridWindow.SLOT_MILLIS) * GuideGridWindow.SLOT_MILLIS - SCRUB_WINDOW
            val programmes = runCatching { browse.guideRows(current.profileId, listOf(row), GuideGridWindow(start, start + 2 * SCRUB_WINDOW))[id]
                ?.cells?.filterIsInstance<GuideProgrammeCell>()?.map { it.programme }.orEmpty() }
                .getOrElse { if (it is CancellationException) throw it; emptyList() }
            if (session === current && mutable.value.playingId == id) mutable.update { it.copy(scrubProgrammes = programmes) }
        }
    }
    private fun watchCatchup(row: IptvListedChannel, request: Long) {
        catchupWatch = viewModelScope.launch {
            while (isActive && request == tuneVersion) {
                delay(2_000)
                val state = mutable.value
                if (request != tuneVersion || state.catchup == null) break
                val position = catchupPosition(state) ?: continue
                if (!state.paused && state.playing && state.scrubTarget == null && CatchupScrub.nearLive(position, System.currentTimeMillis())) { watch(row); break }
            }
        }
    }
    fun scrub(step: Long): Boolean {
        val state = mutable.value
        val row = state.playingRow ?: return false
        val catchup = state.catchup ?: return localScrub(row, step)
        val player = state.player ?: return false
        val now = System.currentTimeMillis()
        val base = state.scrubTarget ?: LiveTimeshift.position(catchup, state.catchupFrom, player.currentPosition)
        val target = (base + step).coerceAtMost(now)
        val stream = CatchupStream(state.catchupFrom ?: catchup.start.epochMillis, state.catchupUntil, player.isCurrentMediaItemSeekable)
        val plan = CatchupScrub.plan(target, stream, catchupProgrammes(state, row.item.channel.id), now)
        scrubJob?.cancel()
        when (plan) {
            is CatchupStep.Seek -> { player.seekTo(plan.positionMillis); mutable.update { it.copy(scrubTarget = null) } }
            CatchupStep.Stay -> mutable.update { it.copy(scrubTarget = null) }
            else -> {
                mutable.update { it.copy(scrubTarget = target) }
                scrubJob = viewModelScope.launch { delay(SCRUB_COMMIT); commitScrub(row, plan) }
            }
        }
        return true
    }
    private fun commitScrub(row: IptvListedChannel, plan: CatchupStep) {
        mutable.update { it.copy(scrubTarget = null) }
        if (mutable.value.playingId != row.item.channel.id) return
        when (plan) {
            CatchupStep.Live -> watch(row)
            is CatchupStep.Tune -> tuneCatchup(row, plan, fallbackLive = false)
            is CatchupStep.Seek -> mutable.value.player?.seekTo(plan.positionMillis)
            CatchupStep.Stay -> Unit
        }
    }
    private fun tuneCatchup(row: IptvListedChannel, plan: CatchupStep.Tune, fallbackLive: Boolean) {
        val kind = mutable.value.sources.firstOrNull { it.ref.sourceId == row.item.channel.sourceId }?.kind
        val start = plan.fromMillis ?: plan.programme.start.epochMillis
        if (kind != null && IptvCatchup.reaches(kind, row.item.attributes, start, System.currentTimeMillis())) watch(row, plan.programme, plan.fromMillis)
        else if (fallbackLive) watch(row) else mutable.update { it.copy(message = R.string.iptv_live_catchup_unavailable) }
    }
    private fun continueCatchup(row: IptvListedChannel) {
        val state = mutable.value
        val catchup = state.catchup ?: return watch(row)
        val now = System.currentTimeMillis()
        val ended = catchupPosition(state) ?: state.catchupUntil ?: now
        val programmes = catchupProgrammes(state, row.item.channel.id)
        when (val plan = CatchupScrub.afterEnd(CatchupScrub.programmeAt(programmes, ended - 1) ?: catchup, ended, programmes, now)) {
            is CatchupStep.Tune -> tuneCatchup(row, plan, fallbackLive = true)
            else -> watch(row)
        }
    }
    private fun ensureGuide(rows: List<IptvListedChannel>) {
        val current = session ?: return
        val state = mutable.value
        val window = state.window ?: return
        val missing = rows.filter { state.guide[it.item.channel.id] == null && state.extraGuide[it.item.channel.id] == null }.distinctBy { it.item.channel.id }.take(20)
        if (missing.isEmpty()) return
        val previous = extraGuideJob
        extraGuideJob = viewModelScope.launch {
            previous?.join()
            val loaded = runCatching { browse.guideRows(current.profileId, missing, window) }.getOrElse { if (it is CancellationException) throw it; emptyMap() }
            if (session === current) mutable.update { it.copy(extraGuide = (it.extraGuide + loaded).entries.toList().takeLast(EXTRA_GUIDE).associate { e -> e.key to e.value }) }
        }
    }
    fun showInset(row: IptvListedChannel) {
        val state = mutable.value
        if (state.player == null || state.multiview != null || state.playingId == row.item.channel.id || !foreground || session == null) return
        if (device.maxTiles < 2) { mutable.update { it.copy(message = R.string.iptv_inset_unsupported) }; return }
        val main = state.playback?.pixelRate ?: multiviewPixelRate(MULTIVIEW_RUNGS.last())
        if (!multiviewHasRoom(listOf(main), device.decodeBudget)) { mutable.update { it.copy(message = R.string.iptv_inset_decoder) }; return }
        ensureGuide(listOf(row))
        val previous = insetJob
        val version = ++insetVersion
        mutable.update { it.copy(inset = IptvTile(row)) }
        insetJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            insetRuntime.stop(owner)
            if (version == insetVersion) openInset(row, version)
        }
    }
    fun closeInset() {
        if (mutable.value.inset == null && insetJob?.isActive != true) return
        val previous = insetJob
        ++insetVersion
        mutable.update { it.copy(inset = null) }
        insetJob = viewModelScope.launch { previous?.cancelAndJoin(); insetRuntime.stop(owner) }
    }
    fun swapInset() {
        val state = mutable.value
        val inset = state.inset?.row ?: return
        val main = state.playingRow?.takeIf { state.player != null && it.item.channel.id == state.playingId } ?: return
        val previous = insetJob
        val version = ++insetVersion
        mutable.update { it.copy(inset = IptvTile(main)) }
        insetJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            insetRuntime.stop(owner)
            if (version != insetVersion) return@launch
            watch(inset)
            tuneJob?.join()
            val latest = mutable.value
            if (version == insetVersion && latest.player != null && latest.playingId == inset.item.channel.id) openInset(main, version)
            else if (version == insetVersion) mutable.update { it.copy(inset = null) }
        }
    }
    private suspend fun openInset(row: IptvListedChannel, version: Long) {
        val current = session ?: return
        val ref = refOf(row) ?: return
        fun patch(change: (IptvTile) -> IptvTile) = mutable.update { state ->
            val tile = state.inset
            if (version != insetVersion || tile == null || tile.row.item.channel.id != row.item.channel.id) state else state.copy(inset = change(tile))
        }
        try {
            val source = withContext(Dispatchers.IO) { access.use(current) { catalogue.sources(current.profileId).single { it.ref == ref } } }
            val item = withContext(Dispatchers.IO) { access.use(current) { requireNotNull(catalogue.playbackItem(ref, row.item.channel.id)) } }
            val streams = withContext(Dispatchers.IO) { access.use(current) {
                catalogue.accounts(current.profileId).firstOrNull { it.id == source.accountId }?.maxStreams ?: 1
            } }
            val format = item.overlay.streamFormat.takeIf { it != IptvStreamFormat.AUTO } ?: livePreferences.defaultFormat
            val key = AcquisitionKey(admissionAccount(current.profileId, source.accountId), row.item.channel.id, "inset:" + format.name, source.activeGeneration ?: 0)
            val result = insetRuntime.open(key, device.tileBufferBytes.toLong(), 4L * device.tileBufferBytes, owner, streams) { purpose ->
                currentCoroutineContext().ensureActive()
                check(session === current && foreground && version == insetVersion)
                IptvLivePlayback(context, liveLocator(current, ref, source, item), purpose, format, headers = StreamHeaders.requestHeaders(item.attributes),
                    sourceUserAgent = livePreferences.userAgent(ref),
                    onPlaying = { playing -> patch { it.copy(playing = playing) } },
                    onError = {
                        patch { it.copy(failure = R.string.iptv_live_failed, playing = false, player = null, playback = null) }
                        if (version == insetVersion) viewModelScope.launch { insetRuntime.stop(owner) }
                    }, handleAudioFocus = false, maxVideoHeight = MULTIVIEW_RUNGS.first(), targetBufferBytes = device.tileBufferBytes)
                    .also { playback ->
                        playback.player.volume = 0f
                        patch { it.copy(playback = playback, player = playback.player) }
                    }
            }
            if (result != LiveOpenResult.OPENED && version == insetVersion) mutable.update { it.copy(inset = null, message = when (result) {
                LiveOpenResult.CAPACITY, LiveOpenResult.SHARING_UNAVAILABLE -> R.string.iptv_inset_capacity
                LiveOpenResult.CLOSE_UNCONFIRMED -> R.string.iptv_live_closing
                else -> R.string.iptv_live_failed
            }) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { if (version == insetVersion) mutable.update { it.copy(inset = null, message = R.string.iptv_live_failed) } }
    }
    fun pickerSource(ref: IptvSourceRef?) {
        pickerJob?.cancel()
        if (ref == null || (ref == mutable.value.source && !mutable.value.mergedFavourites)) { mutable.update { it.copy(picker = null) }; return }
        mutable.update { it.copy(picker = IptvPicker(ref, hidden = hiddenOf(ref))) }
        loadPicker(ref, null)
    }
    fun pickerCategory(favourites: Boolean, category: String?) {
        val state = mutable.value
        val ref = state.picker?.ref ?: state.source ?: return
        pickerJob?.cancel()
        mutable.update { it.copy(picker = IptvPicker(ref, category = category.takeUnless { favourites }, favourites = favourites,
            categories = it.picker?.takeIf { picker -> picker.ref == ref }?.categories ?: it.categories.takeIf { _ -> ref == it.source }, hidden = hiddenOf(ref))) }
        loadPicker(ref, null)
    }
    fun pickerMore() {
        val picker = mutable.value.picker ?: return
        if (!picker.loading && picker.next != null && picker.channels.size < MAX_PICKER) loadPicker(picker.ref, picker.next)
    }
    private fun loadPicker(ref: IptvSourceRef, cursor: IptvBrowseCursor?) {
        val current = session ?: return
        pickerJob?.cancel()
        mutable.update { it.copy(picker = it.picker?.copy(loading = true)) }
        val chosen = mutable.value.picker?.takeIf { it.ref == ref }
        pickerJob = viewModelScope.launch {
            val favourites = chosen?.favourites == true
            val category = chosen?.category.takeUnless { favourites }
            val query = IptvBrowseQuery(favouritesOnly = favourites, category = category,
                excludedCategories = if (favourites || category != null) emptySet() else hiddenOf(ref).take(500).toSet())
            val categories = chosen?.categories ?: runCatching { withContext(Dispatchers.IO) { access.use(current) { ordered(ref, catalogue.categories(ref)) } } }
                .getOrElse { if (it is CancellationException) throw it; null }
            val page = runCatching { browse.page(ref, query, cursor?.takeIf { it.query == query }, PICKER_PAGE) }
                .getOrElse { if (it is CancellationException) throw it; null }
            if (session === current) mutable.update { state ->
                val picker = state.picker?.takeIf { it.ref == ref && it.favourites == favourites && it.category == category } ?: return@update state
                state.copy(picker = picker.copy(channels = if (cursor == null) page?.channels.orEmpty() else picker.channels + page?.channels.orEmpty(),
                    next = page?.catalogue?.next, loading = false, categories = picker.categories ?: categories))
            }
        }
    }
    private suspend fun liveLocator(current: IptvProfileAccess.Session, ref: IptvSourceRef, source: IptvSource, item: IptvCatalogueItem): String =
        when (source.kind) {
            IptvSourceKind.STALKER -> {
                val command = requireNotNull(item.attributes[IptvStalkerClient.COMMAND_ATTRIBUTE])
                val connection = withContext(Dispatchers.IO) { access.use(current) { catalogue.connection(ref) } }
                stalker.streamUrl(connection, command).also { currentCoroutineContext().ensureActive() }
            }
            IptvSourceKind.XTREAM -> {
                val connection = withContext(Dispatchers.IO) { access.use(current) { catalogue.connection(ref) } }
                IptvXtreamClient.streamUrl(connection, item.channel.data.locator)
            }
            IptvSourceKind.M3U -> item.channel.data.locator
        }
    fun openExternal(row: IptvListedChannel) {
        val current = session ?: return
        val ref = refOf(row) ?: return
        if (!foreground) return
        val live = mutable.value
        if (live.player != null || live.tuning || live.playingId != null) stop()
        viewModelScope.launch {
            try {
                val (source, item) = withContext(Dispatchers.IO) { access.use(current) {
                    catalogue.sources(current.profileId).single { it.ref == ref } to requireNotNull(catalogue.playbackItem(ref, row.item.channel.id))
                } }
                val url = liveLocator(current, ref, source, item)
                runtime.stop(owner)
                if (session !== current || !foreground) return@launch
                com.nuvio.tv.core.player.ExternalPlayerLauncher.launch(context, url, item.overlay.customName ?: item.channel.data.name,
                    LiveExternalPlayer.headers(StreamHeaders.requestHeaders(item.attributes), livePreferences.userAgent(ref)))
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { IptvLog.failure("external player", error); mutable.update { it.copy(message = R.string.iptv_live_failed) } }
        }
    }
    fun startMultiview(rows: List<IptvListedChannel>) {
        val tiles = rows.distinctBy { it.item.channel.id }.take(device.maxTiles)
        if (device.maxTiles < 2) { mutable.update { it.copy(message = R.string.iptv_inset_unsupported) }; return }
        if (tiles.isEmpty() || session == null || !foreground) return
        closeInset()
        ensureGuide(tiles)
        val live = mutable.value
        val kept = live.playback?.takeIf { playback -> live.player === playback.player && live.playingId == tiles.first().item.channel.id && live.playing &&
            !live.tuning && tuneJob?.isActive != true && live.catchup == null && !live.paused && !live.reconnecting && !live.localTimeshift && !playback.localTimeshift && playback.live }
        ++tuneVersion; tuneJob?.cancel(); scrubJob?.cancel(); catchupWatch?.cancel()
        if (kept == null) runtime.interrupt(owner)
        tileJobs.forEach { it?.cancel() }
        val keptVersion = ++tileVersions[0]
        mutable.update { it.copy(playback = null, player = null, playingTitle = null, playing = false, reconnecting = false, catchup = null, catchupFrom = null,
            catchupUntil = null, scrubTarget = null, paused = false, pausedAt = null, pausedProgramme = null, tuning = false,
            playingId = null, previousId = it.playingId ?: it.previousId, multiview = tiles.mapIndexed { i, row ->
                if (i == 0 && kept != null) IptvTile(row, kept, kept.player, playing = true) else IptvTile(row) }, tileFocus = 0, mainTile = 0,
            panelHeight = AndroidDeviceProfile.panelHeight(context), tileHeights = emptyList(),
            multiviewLayout = livePreferences.multiviewLayout, multiviewQuality = livePreferences.multiviewQuality) }
        tileSizes = emptyList()
        val previous = multiviewJob
        val generation = ++multiviewGeneration
        multiviewJob = viewModelScope.launch {
            previous?.join()
            val handed = kept != null && generation == multiviewGeneration && tileVersions[0] == keptVersion &&
                runtime.handOver(owner, tileRuntimes[0], device.tileBufferBytes.toLong(), 4L * device.tileBufferBytes)
            if (kept != null) IptvLog.info("multiview handover kept=$handed")
            if (kept != null && !handed) mutable.update { state -> state.copy(multiview = state.multiview?.mapIndexed { i, tile ->
                if (i == 0 && tile.playback === kept) IptvTile(tile.row) else tile }) }
            if (!runtime.stop(owner) && generation == multiviewGeneration) mutable.update { it.copy(message = R.string.iptv_live_closing) }
            if (generation != multiviewGeneration) return@launch
            if (handed) { kept?.limitHeight(tileHeight(0)); applyTileAudio() }
            tiles.indices.filter { !handed || it > 0 }.forEach(::openTile)
        }
    }
    private fun handedTile(playback: IptvLivePlayback, playing: Boolean? = null, failed: Boolean = false) {
        val index = mutable.value.multiview?.indexOfFirst { it.playback === playback }?.takeIf { it >= 0 } ?: return
        mutable.update { state -> state.copy(multiview = state.multiview?.mapIndexed { i, tile -> if (i != index || tile.playback !== playback) tile
            else if (failed) tile.copy(failure = R.string.iptv_live_failed, playing = false, player = null, playback = null) else tile.copy(playing = playing ?: tile.playing) }) }
        if (failed) { val version = tileVersions[index]; viewModelScope.launch { if (tileVersions[index] == version) tileRuntimes[index].stop(owner) } }
        else if (playing == true) { applyTileAudio(); applyTileHeights("tile $index playing") }
    }
    private fun applyTileAudio() {
        val state = mutable.value
        val tiles = state.multiview ?: return
        val order = tiles.indices.sortedBy { it == state.tileFocus }
        order.forEach { i ->
            val player = tiles[i].player ?: return@forEach
            val audible = i == state.tileFocus
            runCatching {
                player.volume = if (audible) 1f else 0f
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audible).build()
            }
        }
    }
    private fun refusal(tile: LivePlaybackRuntime, result: LiveOpenResult): Int = when (result) {
        LiveOpenResult.CAPACITY -> when (tile.lastDenial) {
            AdmissionDenial.ACCOUNT_LIMIT -> R.string.iptv_multiview_account_full
            AdmissionDenial.DEVICE_DECODERS -> R.string.iptv_multiview_decoder_busy
            AdmissionDenial.DEVICE_MEMORY -> R.string.iptv_multiview_memory_full
            AdmissionDenial.ACQUISITION_CLOSING -> R.string.iptv_live_closing
            else -> R.string.iptv_multiview_capacity
        }
        LiveOpenResult.SHARING_UNAVAILABLE -> R.string.iptv_multiview_capacity
        LiveOpenResult.CLOSE_UNCONFIRMED -> R.string.iptv_live_closing
        else -> R.string.iptv_live_failed
    }
    fun addToMultiview(row: IptvListedChannel) {
        val tiles = mutable.value.multiview
        if (tiles == null) {
            val playing = mutable.value.playingRow?.takeIf { it.item.channel.id == mutable.value.playingId && mutable.value.player != null }
            startMultiview(listOfNotNull(playing, row))
            return
        }
        if (tiles.any { it.row.item.channel.id == row.item.channel.id }) { mutable.update { it.copy(message = R.string.iptv_multiview_already) }; return }
        if (tiles.size >= minOf(device.maxTiles, multiviewMaxTiles(mutable.value.multiviewLayout, device.maxTiles))) {
            mutable.update { it.copy(message = R.string.iptv_multiview_tiles_full) }; return
        }
        val loads = tileLoads(tiles + IptvTile(row))
        if (!multiviewFits(loads, device.decode)) {
            IptvLog.info(multiviewBudgetLine(loads, List(loads.size) { MULTIVIEW_RUNGS.first() }, device.decode, "refused"))
            mutable.update { it.copy(message = R.string.iptv_multiview_decoder_room) }; return
        }
        ensureGuide(listOf(row))
        mutable.update { it.copy(multiview = tiles + IptvTile(row), tileFocus = tiles.size) }
        applyTileHeights("added")
        afterMultiviewJob { openTile(tiles.size) }
    }
    fun replaceTile(index: Int, row: IptvListedChannel) {
        val tiles = mutable.value.multiview ?: return
        if (index !in tiles.indices) return
        if (tiles.any { it.row.item.channel.id == row.item.channel.id }) { mutable.update { it.copy(message = R.string.iptv_multiview_already) }; return }
        ensureGuide(listOf(row))
        mutable.update { it.copy(multiview = tiles.toMutableList().also { list -> list[index] = IptvTile(row) }) }
        afterMultiviewJob { openTile(index) }
    }
    fun removeTile(index: Int) {
        val tiles = mutable.value.multiview ?: return
        if (index !in tiles.indices) return
        if (tiles.size == 1) { exitMultiview(); return }
        tileJobs.forEach { it?.cancel() }
        tileVersions.indices.forEach { tileVersions[it]++ }
        val remaining = tiles.filterIndexed { i, _ -> i != index }.map { IptvTile(it.row) }
        mutable.update { it.copy(multiview = remaining, tileFocus = it.tileFocus.coerceAtMost(remaining.lastIndex),
            mainTile = if (it.mainTile == index) 0 else if (it.mainTile > index) it.mainTile - 1 else it.mainTile) }
        tileSizes = emptyList()
        val previous = multiviewJob
        val generation = ++multiviewGeneration
        multiviewJob = viewModelScope.launch {
            previous?.join()
            tileRuntimes.forEach { it.stop(owner) }
            if (generation == multiviewGeneration && mutable.value.multiview != null) mutable.value.multiview?.indices?.forEach(::openTile)
        }
    }
    private fun afterMultiviewJob(block: () -> Unit) {
        val previous = multiviewJob?.takeIf { it.isActive } ?: return block()
        val generation = multiviewGeneration
        viewModelScope.launch { previous.join(); if (generation == multiviewGeneration) block() }
    }
    fun setTileSizes(physicalHeights: List<Int>) {
        tileSizes = physicalHeights
        applyTileHeights()
    }
    fun setMultiviewLayout(layout: MultiviewLayout) {
        livePreferences.multiviewLayout = layout
        mutable.update { it.copy(multiviewLayout = layout) }
    }
    fun setMultiviewQuality(quality: MultiviewQuality) {
        livePreferences.multiviewQuality = quality
        mutable.update { it.copy(multiviewQuality = quality) }
        applyTileHeights()
    }
    fun showLarge(index: Int) {
        if (mutable.value.multiview?.indices?.contains(index) == true) mutable.update { it.copy(mainTile = index) }
    }
    private fun applyTileHeights(action: String? = null) {
        val state = mutable.value
        val tiles = state.multiview ?: return
        val loads = tileLoads(tiles)
        val heights = multiviewHeights(loads, state.tileFocus, state.multiviewQuality, device.decode)
        if (action != null || heights != state.tileHeights) IptvLog.info(multiviewBudgetLine(loads, heights, device.decode, action ?: "sized"))
        mutable.update { it.copy(tileHeights = heights) }
        tiles.forEachIndexed { i, tile -> tile.playback?.limitHeight(heights[i]) }
    }
    private fun tileLoads(tiles: List<IptvTile>): List<MultiviewTileLoad> = tiles.mapIndexed { i, tile ->
        val physical = tileSizes.getOrNull(i) ?: (mutable.value.panelHeight / 2)
        val player = tile.player
        val format = player?.let { runCatching { it.videoFormat }.getOrNull() }?.takeIf { it.width > 0 && it.height > 0 }
        if (player == null || format == null) return@mapIndexed MultiviewTileLoad(physical)
        val rate = format.frameRate.takeIf { it > 0f }?.roundToInt() ?: MULTIVIEW_FRAME_RATE
        val variants = runCatching { player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.sumOf { it.length } }.getOrDefault(0)
        MultiviewTileLoad(physical, format.width.toLong() * format.height * rate, rate,
            format.sampleMimeType == MimeTypes.VIDEO_H265 || format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION, variants > 1)
    }
    private fun tileHeight(index: Int): Int = mutable.value.tileHeights.getOrNull(index)
        ?: multiviewRung(mutable.value.panelHeight / 2, mutable.value.multiviewQuality)
    fun focusTile(index: Int) {
        val tiles = mutable.value.multiview ?: return
        if (index !in tiles.indices) return
        mutable.update { it.copy(tileFocus = index) }
        applyTileHeights()
        applyTileAudio()
    }
    fun exitMultiview(continueWith: IptvListedChannel? = null) {
        val tiles = mutable.value.multiview ?: return
        tileJobs.forEach { it?.cancel() }
        tileVersions.indices.forEach { tileVersions[it]++ }
        mutable.update { it.copy(multiview = null, picker = null) }
        val previous = multiviewJob
        val generation = ++multiviewGeneration
        multiviewJob = viewModelScope.launch {
            previous?.join()
            tileRuntimes.forEach { it.stop(owner) }
            if (continueWith != null && generation == multiviewGeneration && mutable.value.multiview == null && mutable.value.playingId == null) watch(continueWith)
        }
    }
    private fun openTile(index: Int) {
        val current = session ?: return
        val tile = mutable.value.multiview?.getOrNull(index) ?: return
        val row = tile.row
        val ref = refOf(row) ?: return
        tileJobs[index]?.cancel()
        val version = ++tileVersions[index]
        fun patch(change: (IptvTile) -> IptvTile) = mutable.update { state ->
            val tiles = state.multiview ?: return@update state
            if (tileVersions[index] != version || index !in tiles.indices || tiles[index].row.item.channel.id != row.item.channel.id) state
            else state.copy(multiview = tiles.toMutableList().also { it[index] = change(it[index]) })
        }
        tileJobs[index] = viewModelScope.launch {
            patch { it.copy(playback = null, player = null, playing = false, failure = null) }
            try {
                val source = withContext(Dispatchers.IO) { access.use(current) { catalogue.sources(current.profileId).single { it.ref == ref } } }
                val item = withContext(Dispatchers.IO) { access.use(current) { requireNotNull(catalogue.playbackItem(ref, row.item.channel.id)) } }
                val streams = withContext(Dispatchers.IO) { access.use(current) {
                    catalogue.accounts(current.profileId).firstOrNull { it.id == source.accountId }?.maxStreams ?: 1
                } }
                val format = item.overlay.streamFormat.takeIf { it != IptvStreamFormat.AUTO } ?: livePreferences.defaultFormat
                val key = AcquisitionKey(admissionAccount(current.profileId, source.accountId), row.item.channel.id, "tile:" + format.name, source.activeGeneration ?: 0)
                val result = tileRuntimes[index].open(key, device.tileBufferBytes.toLong(), 4L * device.tileBufferBytes, owner, streams) { purpose ->
                    currentCoroutineContext().ensureActive()
                    check(session === current && foreground && tileVersions[index] == version)
                    IptvLivePlayback(context, liveLocator(current, ref, source, item), purpose, format, headers = StreamHeaders.requestHeaders(item.attributes),
                        sourceUserAgent = livePreferences.userAgent(ref),
                        onPlaying = { playing -> patch { it.copy(playing = playing) }; if (playing) { applyTileAudio(); applyTileHeights("tile $index playing") } },
                        onError = {
                            patch { it.copy(failure = R.string.iptv_live_failed, playing = false, player = null, playback = null) }
                            if (tileVersions[index] == version) viewModelScope.launch { tileRuntimes[index].stop(owner) }
                        }, handleAudioFocus = false,
                        maxVideoHeight = tileHeight(index), targetBufferBytes = device.tileBufferBytes)
                        .also { playback ->
                            val audible = mutable.value.tileFocus == index
                            playback.player.volume = if (audible) 1f else 0f
                            playback.player.trackSelectionParameters = playback.player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audible).build()
                            patch { it.copy(playback = playback, player = playback.player) }
                        }
                }
                if (result != LiveOpenResult.OPENED) {
                    val failure = refusal(tileRuntimes[index], result)
                    IptvLog.info("multiview tile refused result=$result denial=${tileRuntimes[index].lastDenial}")
                    patch { it.copy(playback = null, player = null, failure = failure) }
                    if (tileVersions[index] == version) mutable.update { it.copy(message = failure) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { IptvLog.failure("multiview tile", error); patch { it.copy(failure = R.string.iptv_live_failed) } }
        }
    }
    fun stop(keepChannel: Boolean = false) {
        val version = ++tuneVersion; tuneJob?.cancel(); scrubJob?.cancel(); catchupWatch?.cancel(); warmJob?.cancel()
        runtime.interrupt(owner)
        IptvLiveNet.drop()
        closeInset()
        mutable.update { it.copy(playback = null, player = null, playingTitle = null, playing = false, reconnecting = false, catchup = null, catchupFrom = null,
            catchupUntil = null, scrubTarget = null, localTimeshift = false, localBehind = false,
            paused = false, pausedAt = null, pausedProgramme = null, tuning = false, playingId = if (keepChannel) it.playingId else null,
            previousId = if (keepChannel) it.previousId else it.playingId ?: it.previousId) }
        viewModelScope.launch { if (!runtime.stop(owner) && version == tuneVersion) mutable.update { it.copy(message = R.string.iptv_live_closing) } }
    }
    private fun windowFor(state: IptvLiveState, now: Long, append: Boolean = false): GuideGridWindow =
        if (state.sports && state.search.isBlank()) state.window?.takeIf { append || SportsOnlyWindow.fresh(it, now) } ?: SportsOnlyWindow.guide(now)
        else state.window?.takeIf { append || (it.spanMillis == WINDOW_SPAN && now <= it.startMillis + WINDOW_SHIFT) } ?: guideWindow(now)
    private fun guideWindow(now: Long): GuideGridWindow {
        val start = Math.floorDiv(now, GuideGridWindow.SLOT_MILLIS) * GuideGridWindow.SLOT_MILLIS - GuideGridWindow.SLOT_MILLIS
        return GuideGridWindow(start, start + WINDOW_SPAN)
    }
    override fun onCleared() {
        screensaver.setPlaybackActive(false)
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch { runtime.stop(owner); insetRuntime.stop(owner); tileRuntimes.forEach { it.stop(owner) } }
        super.onCleared()
    }
    private companion object {
        const val PAGE = 60
        const val MAX_TILES = 4
        const val AIRING_RESULTS = 120
        const val RECENT = 8
        const val WINDOW_SPAN = 12 * 60 * 60 * 1000L
        const val WINDOW_SHIFT = 4 * 60 * 60 * 1000L
        const val SHORT_GUIDE_CACHE = 200
        const val MERGED_FAVOURITES = 400
        const val MAX_FAVOURITES = 2000
        const val EXTRA_GUIDE = 120
        const val SCRUB_COMMIT = 700L
        const val BACKUP_STALL = 12_000L
        const val WARM_DELAY = 400L
        const val SCRUB_WINDOW = 12 * 60 * 60 * 1000L
        const val PICKER_PAGE = 100
        const val TWIN_PAGE = 40
        const val MAX_TWIN_SOURCES = 8
        const val MAX_PICKER = 2000
        const val MAX_NUMBER = 60_000
        const val FAVOURITES_KEY = "\u0000favourites"
        const val SPORTS_KEY = "\u0000sports"
        const val ALL_KEY = "\u0000all"
    }
    private class CatchupUnavailableException : IllegalStateException()
}
