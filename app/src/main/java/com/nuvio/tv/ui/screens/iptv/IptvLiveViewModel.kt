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
    val page: IptvBrowsePage? = null, val offset: Int = 0, val favourites: Boolean = false,
    val focused: IptvListedChannel? = null, val programmes: List<GuideProgramme> = emptyList(),
    val playback: IptvLivePlayback? = null,
    val player: ExoPlayer? = null, val playingTitle: String? = null, val playing: Boolean = false,
    val loading: Boolean = false, val tuning: Boolean = false, val message: Int? = null, val updating: Int? = null)

@HiltViewModel
class IptvLiveViewModel @Inject constructor(@ApplicationContext private val context: Context,
    private val catalogue: IptvCatalogueStore, private val guides: IptvGuideStore,
    private val access: IptvProfileAccess, private val runtime: LivePlaybackRuntime,
    private val profiles: ProfileManager,
    private val screensaver: com.nuvio.tv.core.player.ScreensaverController,
    private val refresher: IptvRefreshCoordinator) : ViewModel() {
    private val mutable = MutableStateFlow(IptvLiveState())
    val state = mutable.asStateFlow()
    private val owner = UUID.randomUUID().toString()
    private val browse = IptvBrowseRepository(catalogue, guides)
    private val stalker = IptvStalkerClient()
    private var session: IptvProfileAccess.Session? = null
    private var profileRevision = -1L
    private var foreground = false
    private var pageJob: Job? = null
    private var guideJob: Job? = null
    private var tuneJob: Job? = null
    private var tuneVersion = 0L
    private var pageVersion = 0L

    init {
        viewModelScope.launch { state.map { it.player != null }.distinctUntilChanged().collect(screensaver::setPlaybackActive) }
        viewModelScope.launch {
            combine(profiles.activeProfileId, profiles.activeProfileReady, profiles.profileSelectionRevision) { id, ready, revision -> Triple(id, ready, revision) }
                .collect { (id, ready, revision) ->
                    session = null; profileRevision = revision
                    pageJob?.cancel(); guideJob?.cancel(); stop()
                    mutable.value = IptvLiveState()
                    if (ready) {
                        val current = withContext(Dispatchers.IO) { access.open(id) }
                        session = current; load(null); refresher.refreshStale(current)
                    }
                }
        }
        viewModelScope.launch {
            var previous = emptyMap<String, IptvRefreshStatus>()
            refresher.status.collect { statuses ->
                val profile = session?.profileId
                val mine = statuses.filterKeys { profile != null && it.contains(":$profile:") }
                val running = mine.values.filter { it.running }
                mutable.update { it.copy(updating = when {
                    running.any { it.phase == IptvRefreshPhase.DOWNLOADING || it.phase == IptvRefreshPhase.SAVING } -> R.string.iptv_refresh_downloading
                    running.any { it.phase == IptvRefreshPhase.GUIDE } -> R.string.iptv_refresh_guide
                    else -> null
                }) }
                val landed = mine.any { (key, value) -> previous[key]?.phase != value.phase && value.phase in setOf(IptvRefreshPhase.GUIDE, IptvRefreshPhase.DONE) }
                previous = mine
                if (landed && foreground && pageJob?.isActive != true) load(currentCursor(), background = true)
            }
        }
        viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                delay(30_000)
                if (foreground && pageJob?.isActive != true) load(currentCursor(), background = true)
                if (foreground && ++ticks % 60 == 0) session?.let { refresher.refreshStale(it) }
            }
        }
    }
    fun foreground(active: Boolean) {
        foreground = active
        if (active) load(currentCursor()) else stop()
    }
    private fun currentCursor(): IptvBrowseCursor? = mutable.value.let { value ->
        value.page?.let { IptvBrowseCursor(it.catalogue.revision, IptvBrowseQuery(favouritesOnly = value.favourites), value.offset) }
    }
    fun nextPage() { mutable.value.page?.catalogue?.next?.let(::load) }
    fun previousPage() { currentCursor()?.let { load(it.copy(offset = (it.offset - 24).coerceAtLeast(0))) } }
    fun favourites() { mutable.update { it.copy(favourites = !it.favourites, focused = null, programmes = emptyList()) }; load(null) }
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
                if (session === current) load(currentCursor())
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (session === current) mutable.update { it.copy(message = R.string.iptv_setup_failed) } }
        }
    }
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
                    load(currentCursor())
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (session === current) mutable.update { it.copy(message = R.string.iptv_setup_failed) } }
        }
    }
    fun nextSource() {
        val current = mutable.value
        if (current.sources.isEmpty()) return
        val index = current.sources.indexOfFirst { it.ref == current.source }
        mutable.update { it.copy(source = current.sources[(index + 1) % current.sources.size].ref, focused = null, programmes = emptyList()) }
        load(null)
    }
    private fun load(cursor: IptvBrowseCursor?, background: Boolean = false) {
        val current = session ?: return
        pageJob?.cancel()
        val request = ++pageVersion
        pageJob = viewModelScope.launch {
            if (!background) mutable.update { it.copy(loading = true) }
            try {
                val sources = withContext(Dispatchers.IO) { access.use(current) { catalogue.sources(current.profileId) } }
                val ref = mutable.value.source?.takeIf { chosen -> sources.any { it.ref == chosen } } ?: sources.firstOrNull()?.ref
                val query = IptvBrowseQuery(favouritesOnly = mutable.value.favourites)
                var actualCursor = cursor?.takeIf { it.revision.ref == ref && it.query == query }
                val page = if (ref == null) null else try { browse.page(ref, query, actualCursor, 24) }
                    catch (_: IptvCatalogueChangedException) { actualCursor = null; browse.page(ref, query, null, 24) }
                if (session === current && request == pageVersion) {
                    val focusedId = mutable.value.focused?.item?.channel?.id
                    val focused = page?.channels?.firstOrNull { it.item.channel.id == focusedId } ?: page?.channels?.firstOrNull()
                    val sameFocus = background && focused != null && focused.item.channel.id == focusedId
                    mutable.update { it.copy(sources = sources, source = ref, page = page, offset = actualCursor?.offset ?: 0, focused = focused,
                        programmes = if (sameFocus) it.programmes else emptyList()) }
                    focused?.let { focus(it, keepProgrammes = sameFocus) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (session === current) mutable.update { it.copy(message = R.string.iptv_setup_failed) } }
            finally { if (session === current && request == pageVersion) mutable.update { it.copy(loading = false) } }
        }
    }
    fun focus(row: IptvListedChannel, keepProgrammes: Boolean = false) {
        val current = session ?: return
        guideJob?.cancel()
        mutable.update { it.copy(focused = row, programmes = if (keepProgrammes) it.programmes else emptyList()) }
        val key = row.guide.key ?: return
        guideJob = viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val programmes = withContext(Dispatchers.IO) { access.use(current) {
                    guides.programmes(IptvGuideRef(current.profileId, key.feedId), key.externalId, IptvGuideWindow(now, now + 6 * 60 * 60 * 1000), limit = 4).programmes
                } }
                if (session === current && mutable.value.focused?.item?.channel?.id == row.item.channel.id)
                    mutable.update { it.copy(programmes = programmes) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {}
        }
    }
    fun watch(row: IptvListedChannel) {
        val current = session ?: return
        val ref = mutable.value.source ?: return
        if (!foreground) return
        tuneJob?.cancel()
        val request = ++tuneVersion
        tuneJob = viewModelScope.launch {
            mutable.update { it.copy(tuning = true, playback = null, player = null, playingTitle = null, playing = false, message = null) }
            try {
                val source = withContext(Dispatchers.IO) { access.use(current) { catalogue.sources(current.profileId).single { it.ref == ref } } }
                val streamFormat = withContext(Dispatchers.IO) { access.use(current) {
                    requireNotNull(catalogue.playbackItem(ref, row.item.channel.id)).overlay.streamFormat
                } }
                val streams = withContext(Dispatchers.IO) { access.use(current) {
                    catalogue.accounts(current.profileId).firstOrNull { it.id == source.accountId }?.maxStreams ?: 1
                } }
                val key = AcquisitionKey(source.accountId, row.item.channel.id, "main:" + streamFormat.name, source.activeGeneration ?: 0)
                val result = runtime.open(key, 16L * 1024 * 1024, 96L * 1024 * 1024, owner, streams) { purpose ->
                    val item = withContext(Dispatchers.IO) { access.use(current) {
                        val latest = catalogue.sources(current.profileId).single { it.ref == ref }
                        check(latest.configurationVersion == source.configurationVersion && latest.accountId == source.accountId && latest.activeGeneration == source.activeGeneration)
                        requireNotNull(catalogue.playbackItem(ref, row.item.channel.id)).also { check(it.overlay.streamFormat == streamFormat) }
                    } }
                    currentCoroutineContext().ensureActive()
                    check(session === current && foreground && request == tuneVersion && profiles.activeProfileId.value == current.profileId && profiles.profileSelectionRevision.value == profileRevision)
                    val locator = if (source.kind == IptvSourceKind.STALKER) {
                        val command = requireNotNull(item.attributes[IptvStalkerClient.COMMAND_ATTRIBUTE])
                        val connection = withContext(Dispatchers.IO) { access.use(current) { catalogue.connection(ref) } }
                        stalker.streamUrl(connection, command).also { currentCoroutineContext().ensureActive() }
                    } else if (source.kind == IptvSourceKind.XTREAM) {
                        val connection = withContext(Dispatchers.IO) { access.use(current) { catalogue.connection(ref) } }
                        IptvXtreamClient.streamUrl(connection, item.channel.data.locator)
                    } else item.channel.data.locator
                    IptvLivePlayback(context, locator, purpose, streamFormat,
                        onPlaying = { playing -> if (request == tuneVersion) mutable.update { it.copy(playing = playing) } },
                        onError = { if (request == tuneVersion) { stop(); mutable.update { it.copy(message = R.string.iptv_live_failed) } } })
                        .also { mutable.update { state -> state.copy(playback = it, player = it.player, playingTitle = item.overlay.customName ?: item.channel.data.name) } }
                }
                if (request == tuneVersion && result != LiveOpenResult.OPENED) mutable.update { it.copy(playback = null, player = null, playingTitle = null,
                    message = if (result == LiveOpenResult.CLOSE_UNCONFIRMED) R.string.iptv_live_closing else if (result == LiveOpenResult.CAPACITY || result == LiveOpenResult.SHARING_UNAVAILABLE) R.string.iptv_live_capacity else R.string.iptv_live_failed) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (request == tuneVersion) mutable.update { it.copy(message = R.string.iptv_live_failed) } }
            finally { if (request == tuneVersion) mutable.update { it.copy(tuning = false) } }
        }
    }
    fun stop() {
        ++tuneVersion; tuneJob?.cancel()
        mutable.update { it.copy(playback = null, player = null, playingTitle = null, playing = false, tuning = false) }
        viewModelScope.launch { if (!runtime.stop(owner)) mutable.update { it.copy(message = R.string.iptv_live_closing) } }
    }
    override fun onCleared() {
        screensaver.setPlaybackActive(false)
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch { runtime.stop(owner) }
        super.onCleared()
    }
}
