package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

data class IptvLiveState(val sources: List<IptvSource> = emptyList(), val source: IptvSourceRef? = null,
    val channels: List<IptvListedChannel> = emptyList(), val next: IptvBrowseCursor? = null,
    val categories: List<IptvCategory> = emptyList(), val category: String? = null, val favourites: Boolean = false, val search: String = "", val airingSearch: Boolean = false,
    val guide: Map<String, GuideGridRow> = emptyMap(), val window: GuideGridWindow? = null,
    val focused: IptvListedChannel? = null, val playingId: String? = null, val previousId: String? = null,
    val recent: List<String> = emptyList(),
    val playback: IptvLivePlayback? = null,
    val player: ExoPlayer? = null, val playingTitle: String? = null, val playing: Boolean = false, val reconnecting: Boolean = false, val playingRow: IptvListedChannel? = null, val catchup: GuideProgramme? = null,
    val loading: Boolean = false, val loaded: Boolean = false, val tuning: Boolean = false, val message: Int? = null, val updating: Int? = null,
    val guidePicker: IptvGuidePicker? = null, val refresh: Map<String, IptvRefreshStatus> = emptyMap(),
    val controlLayout: com.nuvio.tv.data.local.PlayerControlLayout? = null, val shortGuide: Map<String, List<GuideProgramme>> = emptyMap(),
    val hiddenCategories: Set<String> = emptySet(), val multiview: List<IptvTile>? = null, val tileFocus: Int = 0,
    val recordings: List<IptvRecording> = emptyList(), val maxTiles: Int = 1)

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
    private val device: IptvDeviceProfile) : ViewModel() {
    private val mutable = MutableStateFlow(IptvLiveState(maxTiles = device.maxTiles))
    val state = mutable.asStateFlow()
    private val owner = UUID.randomUUID().toString()
    private val browse = IptvBrowseRepository(catalogue, guides)
    private val stalker = IptvStalkerClient()
    private var session: IptvProfileAccess.Session? = null
    private var profileRevision = -1L
    private var foreground = false
    private var pageJob: Job? = null
    private var searchJob: Job? = null
    private var channelSearch: Job? = null
    private var shortGuideJob: Job? = null
    private val tileRuntimes = List(MAX_TILES) { LivePlaybackRuntime(admission) }
    private val tileJobs = arrayOfNulls<Job>(MAX_TILES)
    private val tileVersions = LongArray(MAX_TILES)
    private var multiviewJob: Job? = null
    private var multiviewGeneration = 0L
    private var restored: IptvSourceRef? = null
    private val preferences = context.getSharedPreferences("iptv-live", Context.MODE_PRIVATE)
    private fun prefix(ref: IptvSourceRef) = "${ref.profileId}:${ref.sourceId}:"
    private fun remember(state: IptvLiveState) {
        val ref = state.source ?: return
        preferences.edit().putString(prefix(ref) + "category", if (state.favourites) FAVOURITES_KEY else state.category ?: ALL_KEY).apply()
    }
    private var tuneJob: Job? = null
    private var tuneVersion = 0L
    private var pageVersion = 0L

    init {
        viewModelScope.launch { state.map { it.player != null || it.multiview?.any { tile -> tile.player != null } == true }.distinctUntilChanged().collect(screensaver::setPlaybackActive) }
        viewModelScope.launch { liveLaunch.source.collect { ref -> if (ref != null && session != null) { liveLaunch.source.value = null; showSource(ref) } } }
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
                if (landed && foreground && pageJob?.isActive != true) load(background = true)
            }
        }
        viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                delay(60_000)
                val window = mutable.value.window
                if (foreground && pageJob?.isActive != true && window != null && System.currentTimeMillis() > window.startMillis + WINDOW_SHIFT) load(background = true)
                if (foreground && ++ticks % 60 == 0) session?.let { refresher.refreshStale(it) }
            }
        }
    }
    fun foreground(active: Boolean) {
        foreground = active
        if (active) load(background = mutable.value.channels.isNotEmpty()) else { exitMultiview(); stop() }
    }
    fun loadMore() { if (mutable.value.next != null) load(append = true) }
    fun showFavourites() { mutable.update { it.copy(favourites = true, category = null, focused = null) }; remember(mutable.value); load() }
    fun showCategory(name: String?) { mutable.update { it.copy(favourites = false, category = name, focused = null) }; remember(mutable.value); load() }
    fun toggleHidden(name: String) {
        val ref = mutable.value.source ?: return
        val hidden = mutable.value.hiddenCategories.let { if (name in it) it - name else it + name }
        preferences.edit().putStringSet(prefix(ref) + "hidden", hidden).apply()
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
        val ref = mutable.value.source ?: return
        val row = mutable.value.focused ?: return
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
        if (!state.favourites || state.search.isNotBlank()) return
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
        val ref = mutable.value.source ?: return
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
        val ref = mutable.value.source ?: return
        val picker = mutable.value.guidePicker ?: return
        val key = externalId?.let { GuideKey(requireNotNull(picker.feed).feedId, it) }
        mutable.update { it.copy(guidePicker = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { access.use(current) {
                    val latest = requireNotNull(catalogue.playbackItem(ref, picker.row.item.channel.id))
                    catalogue.setOverlay(ref, latest.channel.id, latest.overlay.copy(manualGuide = key))
                } }
                if (session === current) load(background = true)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (session === current) mutable.update { it.copy(message = R.string.iptv_setup_failed) } }
        }
    }
    fun closeGuidePicker() { mutable.update { it.copy(guidePicker = null) } }
    fun setStreamFormat(row: IptvListedChannel, format: IptvStreamFormat) {
        val current = session ?: return
        val ref = mutable.value.source ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { access.use(current) {
                    val latest = requireNotNull(catalogue.playbackItem(ref, row.item.channel.id))
                    catalogue.setOverlay(ref, latest.channel.id, latest.overlay.copy(streamFormat = format))
                } }
                if (session === current && mutable.value.source == ref) {
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
    fun record(row: IptvListedChannel, programme: GuideProgramme?) {
        val current = session ?: return
        val ref = mutable.value.source ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val result = try {
                if (programme != null && programme.start.epochMillis > now) recorder.schedule(current, ref, row.item.channel.id, programme)
                else recorder.recordNow(current, ref, row.item.channel.id, programme?.takeIf { airing(it, now) })
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { IptvLog.failure("record", error); null }
            val message = when (result) {
                is com.nuvio.tv.core.recording.IptvRecordResult.Accepted ->
                    if (result.recording.status == RecordingStatus.SCHEDULED) R.string.iptv_recording_scheduled else R.string.iptv_recording_started
                is com.nuvio.tv.core.recording.IptvRecordResult.Refused -> iptvRecordRefusalMessage(result.reason)
                null -> R.string.iptv_setup_failed
            }
            if (session === current) mutable.update { it.copy(message = message) }
        }
    }
    fun cancelRecording(id: String) {
        viewModelScope.launch { runCatching { recorder.cancel(id) }.onFailure { if (it is CancellationException) throw it } }
    }
    fun clearMessage() { mutable.update { it.copy(message = null) } }
    fun showSource(ref: IptvSourceRef) {
        if (ref == mutable.value.source) return
        mutable.update { it.copy(source = ref, focused = null, category = null, favourites = false, channels = emptyList(), next = null, guide = emptyMap(), loading = true) }
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
        mutable.update { it.copy(source = current.sources[(index + 1) % current.sources.size].ref, focused = null, category = null, favourites = false) }
        load()
    }
    private fun load(append: Boolean = false, background: Boolean = false) {
        val current = session ?: return
        if (append && pageJob?.isActive == true) return
        pageJob?.cancel()
        val request = ++pageVersion
        pageJob = viewModelScope.launch {
            if (!background && !append) mutable.update { it.copy(loading = true) }
            try {
                val sources = withContext(Dispatchers.IO) { access.use(current) { catalogue.sources(current.profileId) } }
                val ref = mutable.value.source?.takeIf { chosen -> sources.any { it.ref == chosen } }
                    ?: sources.firstOrNull { it.playbackEligible }?.ref ?: sources.firstOrNull()?.ref
                if (ref != null && ref != restored && !append) {
                    restored = ref
                    val saved = preferences.getString(prefix(ref) + "category", null)
                    val hidden = preferences.getStringSet(prefix(ref) + "hidden", null).orEmpty().toSet()
                    mutable.update { it.copy(hiddenCategories = hidden, favourites = saved == FAVOURITES_KEY,
                        category = saved?.takeIf { value -> value != FAVOURITES_KEY && value != ALL_KEY }) }
                }
                val state = mutable.value
                val query = IptvBrowseQuery(search = state.search.trim().take(256), favouritesOnly = state.favourites, category = state.category.takeUnless { state.favourites },
                    excludedCategories = if (state.favourites || state.category != null) emptySet() else state.hiddenCategories.take(500).toSet())
                val cursor = state.next?.takeIf { append && it.revision.ref == ref && it.query == query }
                if (append && cursor == null) return@launch
                val wanted = if (background && !append) state.channels.size.coerceIn(PAGE, device.backgroundRows) else PAGE
                val airing = if (ref != null && !append && state.airingSearch && query.search.isNotBlank())
                    browse.searchAiring(ref, query.search, System.currentTimeMillis(), AIRING_RESULTS,
                        state.hiddenCategories.take(500).toSet()) else null
                var page = if (ref == null || airing != null) null else try { browse.page(ref, query, cursor, wanted.coerceAtMost(200)) }
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
                    else withContext(Dispatchers.IO) { access.use(current) { catalogue.categories(ref) } }
                if (!append && state.category != null && state.search.isBlank() && categories.none { it.name == state.category }) {
                    mutable.update { it.copy(category = null) }
                    remember(mutable.value)
                    load(background = background)
                    return@launch
                }
                val now = System.currentTimeMillis()
                val window = state.window?.takeIf { append || now <= it.startMillis + WINDOW_SHIFT } ?: guideWindow(now)
                val loaded = airing?.map { it.channel } ?: (page?.channels.orEmpty() + extra)
                val rows = if (ref == null || loaded.isEmpty()) emptyMap() else loaded.chunked(200).fold(emptyMap<String, GuideGridRow>()) { acc, part -> acc + browse.guideRows(current.profileId, part, window) }
                if (session === current && request == pageVersion) {
                    val channels = if (append) state.channels + loaded else loaded
                    val focusedId = mutable.value.focused?.item?.channel?.id
                    val focused = channels.firstOrNull { it.item.channel.id == focusedId }
                        ?: channels.firstOrNull { it.item.channel.id == state.playingId } ?: channels.firstOrNull()
                    mutable.update { it.copy(sources = sources, source = ref, channels = channels, next = page?.catalogue?.next,
                        categories = categories, guide = if (append) it.guide + rows else rows, window = window, focused = focused) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { IptvLog.failure("live load", error); if (session === current) mutable.update { it.copy(message = R.string.iptv_setup_failed) } }
            finally { if (session === current && request == pageVersion) mutable.update { it.copy(loading = false, loaded = true) } }
        }
    }
    fun focus(row: IptvListedChannel) {
        if (session != null) mutable.update { it.copy(focused = row) }
        loadShortGuide(row)
    }
    private fun loadShortGuide(row: IptvListedChannel) {
        val current = session ?: return
        val state = mutable.value
        val ref = state.source ?: return
        val id = row.item.channel.id
        val now = System.currentTimeMillis()
        if (state.guide[id]?.cells?.any { it is GuideProgrammeCell } == true ||
            state.shortGuide[id]?.any { it.start.epochMillis <= now && (it.stop?.epochMillis ?: Long.MAX_VALUE) > now } == true) return
        if (state.sources.firstOrNull { it.ref == ref }?.kind != IptvSourceKind.XTREAM) return
        shortGuideJob?.cancel()
        shortGuideJob = viewModelScope.launch {
            delay(400)
            val programmes = runCatching { withContext(Dispatchers.IO) { access.use(current) { } }; shortGuides.nowNext(ref, id) }
                .getOrElse { if (it is CancellationException) throw it; emptyList() }
            if (session === current && mutable.value.source == ref) mutable.update { it.copy(shortGuide = (it.shortGuide + (id to programmes)).entries.toList().takeLast(SHORT_GUIDE_CACHE).associate { e -> e.key to e.value }) }
        }
    }
    fun watch(row: IptvListedChannel, catchup: GuideProgramme? = null) {
        val current = session ?: return
        val ref = mutable.value.source ?: return
        if (!foreground) return
        tuneJob?.cancel()
        loadShortGuide(row)
        val request = ++tuneVersion
        tuneJob = viewModelScope.launch {
            mutable.update { it.copy(tuning = true, playback = null, player = null, playingTitle = null, playing = false, reconnecting = false, catchup = catchup, message = null, playingId = row.item.channel.id, playingRow = row,
                previousId = it.playingId?.takeIf { id -> id != row.item.channel.id } ?: it.previousId,
                recent = (listOf(row.item.channel.id) + it.recent.filter { id -> id != row.item.channel.id }).take(RECENT)) }
            try {
                val source = withContext(Dispatchers.IO) { access.use(current) { catalogue.sources(current.profileId).single { it.ref == ref } } }
                val stored = withContext(Dispatchers.IO) { access.use(current) { requireNotNull(catalogue.playbackItem(ref, row.item.channel.id)) } }
                if (catchup != null && !IptvCatchup.reaches(source.kind, stored.attributes, catchup.start.epochMillis, System.currentTimeMillis()))
                    throw CatchupUnavailableException()
                val overlayFormat = stored.overlay.streamFormat
                val streamFormat = if (catchup != null && source.kind == IptvSourceKind.XTREAM) IptvStreamFormat.MPEG_TS else overlayFormat
                val streams = withContext(Dispatchers.IO) { access.use(current) {
                    catalogue.accounts(current.profileId).firstOrNull { it.id == source.accountId }?.maxStreams ?: 1
                } }
                val variant = if (catchup == null) "main:" + streamFormat.name else "catchup:${catchup.start.epochMillis}:" + streamFormat.name
                val key = AcquisitionKey(admissionAccount(current.profileId, source.accountId), row.item.channel.id, variant, source.activeGeneration ?: 0)
                val result = runtime.open(key, 16L * 1024 * 1024, 96L * 1024 * 1024, owner, streams) { purpose ->
                    val item = withContext(Dispatchers.IO) { access.use(current) {
                        val latest = catalogue.sources(current.profileId).single { it.ref == ref }
                        check(latest.configurationVersion == source.configurationVersion && latest.accountId == source.accountId && latest.activeGeneration == source.activeGeneration)
                        requireNotNull(catalogue.playbackItem(ref, row.item.channel.id)).also { check(it.overlay.streamFormat == overlayFormat) }
                    } }
                    currentCoroutineContext().ensureActive()
                    check(session === current && foreground && request == tuneVersion && profiles.activeProfileId.value == current.profileId && profiles.profileSelectionRevision.value == profileRevision)
                    val locator = if (catchup != null) {
                        val connection = if (source.kind == IptvSourceKind.XTREAM) withContext(Dispatchers.IO) { access.use(current) { catalogue.connection(ref) } } else null
                        IptvCatchup.locator(source.kind, connection, item, catchup.start.epochMillis,
                            catchup.stop?.epochMillis ?: (catchup.start.epochMillis + CATCHUP_FALLBACK), System.currentTimeMillis())
                            ?: throw CatchupUnavailableException()
                    } else liveLocator(current, ref, source, item)
                    IptvLivePlayback(context, locator, purpose, streamFormat,
                        onPlaying = { playing -> if (request == tuneVersion) mutable.update { it.copy(playing = playing) } },
                        onError = { if (request == tuneVersion) { stop(); mutable.update { it.copy(message = R.string.iptv_live_failed) } } },
                        onReconnecting = { active -> if (request == tuneVersion) mutable.update { it.copy(reconnecting = active) } },
                        isLive = catchup == null, onEnded = { if (request == tuneVersion) watch(row) })
                        .also { mutable.update { state -> state.copy(playback = it, player = it.player, playingTitle = item.overlay.customName ?: item.channel.data.name) } }
                }
                if (request == tuneVersion && result != LiveOpenResult.OPENED) mutable.update { it.copy(playback = null, player = null, playingTitle = null,
                    message = if (result == LiveOpenResult.CLOSE_UNCONFIRMED) R.string.iptv_live_closing else if (result == LiveOpenResult.CAPACITY || result == LiveOpenResult.SHARING_UNAVAILABLE) R.string.iptv_live_capacity else R.string.iptv_live_failed) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (request == tuneVersion) mutable.update { it.copy(catchup = null,
                message = if (error is CatchupUnavailableException) R.string.iptv_live_catchup_unavailable else R.string.iptv_live_failed) } }
            finally { if (request == tuneVersion) mutable.update { it.copy(tuning = false) } }
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
    fun startMultiview(rows: List<IptvListedChannel>) {
        val tiles = rows.distinctBy { it.item.channel.id }.take(device.maxTiles)
        if (device.maxTiles < 2 || tiles.isEmpty() || session == null || !foreground) return
        ++tuneVersion; tuneJob?.cancel()
        mutable.update { it.copy(playback = null, player = null, playingTitle = null, playing = false, reconnecting = false, catchup = null, tuning = false,
            playingId = null, previousId = it.playingId ?: it.previousId, multiview = tiles.map { row -> IptvTile(row) }, tileFocus = 0) }
        val previous = multiviewJob
        val generation = ++multiviewGeneration
        multiviewJob = viewModelScope.launch {
            previous?.join()
            if (!runtime.stop(owner)) mutable.update { it.copy(message = R.string.iptv_live_closing) }
            if (generation == multiviewGeneration) tiles.indices.forEach(::openTile)
        }
    }
    fun addToMultiview(row: IptvListedChannel) {
        val tiles = mutable.value.multiview
        if (tiles == null) {
            val playing = mutable.value.playingRow?.takeIf { it.item.channel.id == mutable.value.playingId && mutable.value.player != null }
            startMultiview(listOfNotNull(playing, row))
            return
        }
        if (tiles.size >= device.maxTiles || tiles.any { it.row.item.channel.id == row.item.channel.id }) return
        mutable.update { it.copy(multiview = tiles + IptvTile(row), tileFocus = tiles.size) }
        afterMultiviewJob { openTile(tiles.size) }
    }
    fun replaceTile(index: Int, row: IptvListedChannel) {
        val tiles = mutable.value.multiview ?: return
        if (index !in tiles.indices || tiles.any { it.row.item.channel.id == row.item.channel.id }) return
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
        mutable.update { it.copy(multiview = remaining, tileFocus = it.tileFocus.coerceAtMost(remaining.lastIndex)) }
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
    fun focusTile(index: Int) {
        val tiles = mutable.value.multiview ?: return
        if (index !in tiles.indices) return
        mutable.update { it.copy(tileFocus = index) }
        tiles.forEachIndexed { i, tile -> tile.player?.volume = if (i == index) 1f else 0f }
    }
    fun exitMultiview(continueWith: IptvListedChannel? = null) {
        val tiles = mutable.value.multiview ?: return
        tileJobs.forEach { it?.cancel() }
        tileVersions.indices.forEach { tileVersions[it]++ }
        mutable.update { it.copy(multiview = null) }
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
        val ref = mutable.value.source ?: return
        val tile = mutable.value.multiview?.getOrNull(index) ?: return
        val row = tile.row
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
                val format = item.overlay.streamFormat
                val key = AcquisitionKey(admissionAccount(current.profileId, source.accountId), row.item.channel.id, "tile:" + format.name, source.activeGeneration ?: 0)
                val result = tileRuntimes[index].open(key, device.tileBufferBytes.toLong(), 4L * device.tileBufferBytes, owner, streams) { purpose ->
                    currentCoroutineContext().ensureActive()
                    check(session === current && foreground && tileVersions[index] == version)
                    IptvLivePlayback(context, liveLocator(current, ref, source, item), purpose, format,
                        onPlaying = { playing -> patch { it.copy(playing = playing) } },
                        onError = {
                            patch { it.copy(failure = R.string.iptv_live_failed, playing = false, player = null, playback = null) }
                            if (tileVersions[index] == version) viewModelScope.launch { tileRuntimes[index].stop(owner) }
                        }, handleAudioFocus = false,
                        maxVideoHeight = device.tileMaxHeight, targetBufferBytes = device.tileBufferBytes)
                        .also { playback ->
                            playback.player.volume = if (mutable.value.tileFocus == index) 1f else 0f
                            patch { it.copy(playback = playback, player = playback.player) }
                        }
                }
                if (result != LiveOpenResult.OPENED) patch { it.copy(playback = null, player = null, failure = when (result) {
                    LiveOpenResult.CAPACITY, LiveOpenResult.SHARING_UNAVAILABLE -> R.string.iptv_multiview_capacity
                    LiveOpenResult.CLOSE_UNCONFIRMED -> R.string.iptv_live_closing
                    else -> R.string.iptv_live_failed
                }) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { patch { it.copy(failure = R.string.iptv_live_failed) } }
        }
    }
    fun stop() {
        ++tuneVersion; tuneJob?.cancel()
        mutable.update { it.copy(playback = null, player = null, playingTitle = null, playing = false, reconnecting = false, catchup = null, tuning = false, playingId = null, previousId = it.playingId ?: it.previousId) }
        viewModelScope.launch { if (!runtime.stop(owner)) mutable.update { it.copy(message = R.string.iptv_live_closing) } }
    }
    private fun guideWindow(now: Long): GuideGridWindow {
        val start = Math.floorDiv(now, GuideGridWindow.SLOT_MILLIS) * GuideGridWindow.SLOT_MILLIS - GuideGridWindow.SLOT_MILLIS
        return GuideGridWindow(start, start + WINDOW_SPAN)
    }
    override fun onCleared() {
        screensaver.setPlaybackActive(false)
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch { runtime.stop(owner); tileRuntimes.forEach { it.stop(owner) } }
        super.onCleared()
    }
    private companion object {
        const val PAGE = 60
        const val MAX_TILES = 4
        const val AIRING_RESULTS = 120
        const val RECENT = 8
        const val WINDOW_SPAN = 12 * 60 * 60 * 1000L
        const val WINDOW_SHIFT = 4 * 60 * 60 * 1000L
        const val CATCHUP_FALLBACK = 60 * 60 * 1000L
        const val SHORT_GUIDE_CACHE = 200
        const val MAX_NUMBER = 60_000
        const val FAVOURITES_KEY = "\u0000favourites"
        const val ALL_KEY = "\u0000all"
    }
    private class CatchupUnavailableException : IllegalStateException()
}
