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
    val player: ExoPlayer? = null, val playingTitle: String? = null, val playing: Boolean = false,
    val loading: Boolean = false, val tuning: Boolean = false, val message: Int? = null)

@HiltViewModel
class IptvLiveViewModel @Inject constructor(@ApplicationContext private val context: Context,
    private val catalogue: IptvCatalogueStore, private val guides: IptvGuideStore,
    private val access: IptvProfileAccess, private val runtime: LivePlaybackRuntime,
    private val profiles: ProfileManager,
    private val screensaver: com.nuvio.tv.core.player.ScreensaverController) : ViewModel() {
    private val mutable = MutableStateFlow(IptvLiveState())
    val state = mutable.asStateFlow()
    private val owner = UUID.randomUUID().toString()
    private val browse = IptvBrowseRepository(catalogue, guides)
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
                    if (ready) { session = access.open(id); load(null) }
                }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                if (foreground && pageJob?.isActive != true) load(currentCursor())
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
    fun nextSource() {
        val current = mutable.value
        if (current.sources.isEmpty()) return
        val index = current.sources.indexOfFirst { it.ref == current.source }
        mutable.update { it.copy(source = current.sources[(index + 1) % current.sources.size].ref, focused = null, programmes = emptyList()) }
        load(null)
    }
    private fun load(cursor: IptvBrowseCursor?) {
        val current = session ?: return
        pageJob?.cancel()
        val request = ++pageVersion
        pageJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true) }
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
                    mutable.update { it.copy(sources = sources, source = ref, page = page, offset = actualCursor?.offset ?: 0, focused = focused, programmes = emptyList()) }
                    focused?.let(::focus)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (session === current) mutable.update { it.copy(message = R.string.iptv_setup_failed) } }
            finally { if (session === current && request == pageVersion) mutable.update { it.copy(loading = false) } }
        }
    }
    fun focus(row: IptvListedChannel) {
        val current = session ?: return
        guideJob?.cancel()
        mutable.update { it.copy(focused = row, programmes = emptyList()) }
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
            catch (_: Exception) { /* A missing/changed feed leaves programme information unavailable. */ }
        }
    }
    fun watch(row: IptvListedChannel) {
        val current = session ?: return
        val ref = mutable.value.source ?: return
        if (!foreground) return
        tuneJob?.cancel()
        val request = ++tuneVersion
        tuneJob = viewModelScope.launch {
            mutable.update { it.copy(tuning = true, player = null, playingTitle = null, playing = false, message = null) }
            try {
                val source = withContext(Dispatchers.IO) { access.use(current) { catalogue.sources(current.profileId).single { it.ref == ref } } }
                val key = AcquisitionKey(source.accountId, row.item.channel.id, "main", source.activeGeneration ?: 0)
                val result = runtime.open(key, 16L * 1024 * 1024, 96L * 1024 * 1024, owner) { purpose ->
                    val item = withContext(Dispatchers.IO) { access.use(current) {
                        val latest = catalogue.sources(current.profileId).single { it.ref == ref }
                        check(latest.configurationVersion == source.configurationVersion && latest.accountId == source.accountId && latest.activeGeneration == source.activeGeneration)
                        requireNotNull(catalogue.playbackItem(ref, row.item.channel.id))
                    } }
                    currentCoroutineContext().ensureActive()
                    check(session === current && foreground && request == tuneVersion && profiles.activeProfileId.value == current.profileId && profiles.profileSelectionRevision.value == profileRevision)
                    IptvLivePlayback(context, item.channel.data.locator, purpose,
                        onPlaying = { playing -> if (request == tuneVersion) mutable.update { it.copy(playing = playing) } },
                        onError = { if (request == tuneVersion) { stop(); mutable.update { it.copy(message = R.string.iptv_live_failed) } } })
                        .also { mutable.update { state -> state.copy(player = it.player, playingTitle = item.overlay.customName ?: item.channel.data.name) } }
                }
                if (request == tuneVersion && result != LiveOpenResult.OPENED) mutable.update { it.copy(player = null, playingTitle = null,
                    message = if (result == LiveOpenResult.CLOSE_UNCONFIRMED) R.string.iptv_live_closing else if (result == LiveOpenResult.CAPACITY || result == LiveOpenResult.SHARING_UNAVAILABLE) R.string.iptv_live_capacity else R.string.iptv_live_failed) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (request == tuneVersion) mutable.update { it.copy(message = R.string.iptv_live_failed) } }
            finally { if (request == tuneVersion) mutable.update { it.copy(tuning = false) } }
        }
    }
    fun stop() {
        ++tuneVersion; tuneJob?.cancel()
        mutable.update { it.copy(player = null, playingTitle = null, playing = false, tuning = false) }
        viewModelScope.launch { if (!runtime.stop(owner)) mutable.update { it.copy(message = R.string.iptv_live_closing) } }
    }
    override fun onCleared() {
        screensaver.setPlaybackActive(false)
        // viewModelScope is already cancelled; cleanup must finish outside it.
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch { runtime.stop(owner) }
        super.onCleared()
    }
}
