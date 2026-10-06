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
    val categories: List<IptvCategory> = emptyList(), val category: String? = null, val favourites: Boolean = false,
    val guide: Map<String, GuideGridRow> = emptyMap(), val window: GuideGridWindow? = null,
    val focused: IptvListedChannel? = null, val playingId: String? = null, val previousId: String? = null,
    val recent: List<String> = emptyList(),
    val playback: IptvLivePlayback? = null,
    val player: ExoPlayer? = null, val playingTitle: String? = null, val playing: Boolean = false, val reconnecting: Boolean = false, val catchup: GuideProgramme? = null,
    val loading: Boolean = false, val loaded: Boolean = false, val tuning: Boolean = false, val message: Int? = null, val updating: Int? = null,
    val guidePicker: IptvGuidePicker? = null, val refresh: Map<String, IptvRefreshStatus> = emptyMap(),
    val controlLayout: com.nuvio.tv.data.local.PlayerControlLayout? = null)

data class IptvGuidePicker(val row: IptvListedChannel, val feeds: List<IptvGuideFeed>, val feed: IptvGuideRef? = null,
    val query: String = "", val results: List<GuideChannel> = emptyList())

@HiltViewModel
class IptvLiveViewModel @Inject constructor(@ApplicationContext private val context: Context,
    private val catalogue: IptvCatalogueStore, private val guides: IptvGuideStore,
    private val access: IptvProfileAccess, private val runtime: LivePlaybackRuntime,
    private val profiles: ProfileManager,
    private val screensaver: com.nuvio.tv.core.player.ScreensaverController,
    private val refresher: IptvRefreshCoordinator,
    private val playerSettings: com.nuvio.tv.data.local.PlayerSettingsDataStore) : ViewModel() {
    private val mutable = MutableStateFlow(IptvLiveState())
    val state = mutable.asStateFlow()
    private val owner = UUID.randomUUID().toString()
    private val browse = IptvBrowseRepository(catalogue, guides)
    private val stalker = IptvStalkerClient()
    private var session: IptvProfileAccess.Session? = null
    private var profileRevision = -1L
    private var foreground = false
    private var pageJob: Job? = null
    private var searchJob: Job? = null
    private var tuneJob: Job? = null
    private var tuneVersion = 0L
    private var pageVersion = 0L

    init {
        viewModelScope.launch { state.map { it.player != null }.distinctUntilChanged().collect(screensaver::setPlaybackActive) }
        viewModelScope.launch { playerSettings.controlLayoutSnapshot.collect { snapshot -> mutable.update { it.copy(controlLayout = snapshot.layout) } } }
        viewModelScope.launch {
            combine(profiles.activeProfileId, profiles.activeProfileReady, profiles.profileSelectionRevision) { id, ready, revision -> Triple(id, ready, revision) }
                .collect { (id, ready, revision) ->
                    session = null; profileRevision = revision
                    pageJob?.cancel(); stop()
                    mutable.value = IptvLiveState(controlLayout = mutable.value.controlLayout)
                    if (ready) {
                        val current = withContext(Dispatchers.IO) { access.open(id) }
                        session = current; load(); refresher.refreshStale(current)
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
        if (active) load(background = mutable.value.channels.isNotEmpty()) else stop()
    }
    fun loadMore() { if (mutable.value.next != null) load(append = true) }
    fun showFavourites() { mutable.update { it.copy(favourites = true, category = null, focused = null) }; load() }
    fun showCategory(name: String?) { mutable.update { it.copy(favourites = false, category = name, focused = null) }; load() }
    fun lastChannel() {
        val current = mutable.value
        current.channels.firstOrNull { it.item.channel.id == current.previousId }?.let { mutable.update { state -> state.copy(focused = it) }; watch(it) }
    }
    fun watchNumber(number: Int) {
        mutable.value.channels.getOrNull(number - 1)?.let { mutable.update { state -> state.copy(focused = it) }; watch(it) }
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
                    catalogue.setOverlay(ref, latest.channel.id, latest.overlay.copy(favouriteRank = if (latest.overlay.favouriteRank == null) 0 else null))
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
                val state = mutable.value
                val ref = state.source?.takeIf { chosen -> sources.any { it.ref == chosen } }
                    ?: sources.firstOrNull { it.playbackEligible }?.ref ?: sources.firstOrNull()?.ref
                val query = IptvBrowseQuery(favouritesOnly = state.favourites, category = state.category.takeUnless { state.favourites })
                val cursor = state.next?.takeIf { append && it.revision.ref == ref && it.query == query }
                if (append && cursor == null) return@launch
                val limit = if (background) state.channels.size.coerceIn(PAGE, 200) else PAGE
                val page = if (ref == null) null else try { browse.page(ref, query, cursor, limit) }
                    catch (_: IptvCatalogueChangedException) { if (append) return@launch else browse.page(ref, query, null, limit) }
                val categories = if (ref == null) emptyList() else if (append) state.categories
                    else withContext(Dispatchers.IO) { access.use(current) { catalogue.categories(ref) } }
                val now = System.currentTimeMillis()
                val window = state.window?.takeIf { append || now <= it.startMillis + WINDOW_SHIFT } ?: guideWindow(now)
                val rows = page?.let { browse.guideRows(current.profileId, it.channels, window) }.orEmpty()
                if (session === current && request == pageVersion) {
                    val channels = if (append) state.channels + page?.channels.orEmpty() else page?.channels.orEmpty()
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
    }
    fun watch(row: IptvListedChannel, catchup: GuideProgramme? = null) {
        val current = session ?: return
        val ref = mutable.value.source ?: return
        if (!foreground) return
        tuneJob?.cancel()
        val request = ++tuneVersion
        tuneJob = viewModelScope.launch {
            mutable.update { it.copy(tuning = true, playback = null, player = null, playingTitle = null, playing = false, reconnecting = false, catchup = catchup, message = null, playingId = row.item.channel.id,
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
                val key = AcquisitionKey(source.accountId, row.item.channel.id, variant, source.activeGeneration ?: 0)
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
                    } else if (source.kind == IptvSourceKind.STALKER) {
                        val command = requireNotNull(item.attributes[IptvStalkerClient.COMMAND_ATTRIBUTE])
                        val connection = withContext(Dispatchers.IO) { access.use(current) { catalogue.connection(ref) } }
                        stalker.streamUrl(connection, command).also { currentCoroutineContext().ensureActive() }
                    } else if (source.kind == IptvSourceKind.XTREAM) {
                        val connection = withContext(Dispatchers.IO) { access.use(current) { catalogue.connection(ref) } }
                        IptvXtreamClient.streamUrl(connection, item.channel.data.locator)
                    } else item.channel.data.locator
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
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch { runtime.stop(owner) }
        super.onCleared()
    }
    private companion object {
        const val PAGE = 60
        const val RECENT = 8
        const val WINDOW_SPAN = 12 * 60 * 60 * 1000L
        const val WINDOW_SHIFT = 4 * 60 * 60 * 1000L
        const val CATCHUP_FALLBACK = 60 * 60 * 1000L
    }
    private class CatchupUnavailableException : IllegalStateException()
}
