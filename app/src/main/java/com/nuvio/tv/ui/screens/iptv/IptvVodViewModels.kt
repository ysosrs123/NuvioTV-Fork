package com.nuvio.tv.ui.screens.iptv

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.VodArt
import com.nuvio.tv.core.iptv.VodArtwork
import com.nuvio.tv.core.iptv.VodDetailTarget
import com.nuvio.tv.core.iptv.VodKind
import com.nuvio.tv.core.iptv.VodRef
import com.nuvio.tv.core.iptv.VodStreams
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.iptv.IptvCatalogueStore
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.data.iptv.IptvSource
import com.nuvio.tv.data.iptv.IptvSourceRef
import com.nuvio.tv.data.iptv.IptvVodArtwork
import com.nuvio.tv.data.iptv.IptvVodArtworkMode
import com.nuvio.tv.data.iptv.IptvVodArtworkPreferences
import com.nuvio.tv.data.iptv.IptvVodCategory
import com.nuvio.tv.data.iptv.IptvVodEpisode
import com.nuvio.tv.data.iptv.IptvVodRepository
import com.nuvio.tv.data.iptv.IptvVodResolution
import com.nuvio.tv.data.iptv.IptvVodResolver
import com.nuvio.tv.data.iptv.IptvVodResume
import com.nuvio.tv.data.iptv.IptvVodStreams
import com.nuvio.tv.data.iptv.IptvVodTitle
import com.nuvio.tv.data.iptvvod.IptvVodStreamSources
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class IptvVodAvailability(val movies: Boolean = false, val series: Boolean = false)

@HiltViewModel
class IptvVodMenuViewModel @Inject constructor(private val streams: IptvVodStreams, private val profiles: ProfileManager) : ViewModel() {
    private val mutable = MutableStateFlow(IptvVodAvailability())
    val state = mutable.asStateFlow()

    fun reload() {
        viewModelScope.launch {
            val profileId = profiles.activeProfileId.value
            mutable.value = withContext(Dispatchers.IO) {
                try { IptvVodAvailability(streams.sources(profileId, VodKind.MOVIE).isNotEmpty(), streams.sources(profileId, VodKind.SERIES).isNotEmpty()) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { IptvLog.failure("vod menu", error); IptvVodAvailability() }
            }
        }
    }
}

@Singleton
class IptvVodOpener @Inject constructor(private val tmdb: TmdbService, private val streams: IptvVodStreamSources,
    private val tmdbSettings: TmdbSettingsDataStore) {
    fun language(): String = tmdbSettings.settings.value.language.ifBlank { "en" }

    suspend fun target(kind: VodKind, tmdbId: String?, imdbId: String?): VodDetailTarget? {
        if (kind == VodKind.EPISODE || (tmdbId == null && imdbId == null)) return null
        val type = if (kind == VodKind.SERIES) "series" else "movie"
        val tmdbValue = tmdbId ?: imdbId?.let { id -> quiet { tmdb.imdbToTmdb(id, type)?.toString() } }
        val imdbValue = imdbId ?: tmdbValue?.toIntOrNull()?.let { id -> quiet { tmdb.tmdbToImdb(id, type) } }
        streams.allow(kind, tmdbValue, imdbValue)
        return VodArtwork.target(kind, tmdbValue, imdbValue)
    }

    private suspend fun <T> quiet(block: suspend () -> T?): T? = try { withTimeoutOrNull(LOOKUP_TIMEOUT) { block() } }
        catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }

    private companion object { const val LOOKUP_TIMEOUT = 6_000L }
}

sealed interface IptvVodNavigation {
    data class Detail(val target: VodDetailTarget) : IptvVodNavigation
    data class Title(val ref: VodRef) : IptvVodNavigation
}

enum class IptvVodShelf { RECENT, ALL, CATEGORY, SEARCH }

data class IptvVodBrowseState(
    val kind: VodKind = VodKind.MOVIE, val ready: Boolean = false, val sources: List<IptvSource> = emptyList(), val source: IptvSourceRef? = null,
    val categories: List<IptvVodCategory> = emptyList(), val shelf: IptvVodShelf = IptvVodShelf.RECENT, val category: String? = null,
    val items: List<IptvVodTitle> = emptyList(), val nextOffset: Int? = null, val loading: Boolean = false, val search: String = "",
    val art: Map<VodRef, VodArt> = emptyMap(), val nuvio: Boolean = false, val opening: VodRef? = null, val revision: Int = 0,
)

@HiltViewModel
class IptvVodBrowseViewModel @Inject constructor(savedState: SavedStateHandle, private val repository: IptvVodRepository,
    private val streams: IptvVodStreams, private val artwork: IptvVodArtwork, private val preferences: IptvVodArtworkPreferences,
    private val opener: IptvVodOpener, private val profiles: ProfileManager) : ViewModel() {
    private val kind = VodKind.of(savedState.get<String>("kind").orEmpty())?.takeIf { it != VodKind.EPISODE } ?: VodKind.MOVIE
    private val mutable = MutableStateFlow(IptvVodBrowseState(kind = kind))
    val state = mutable.asStateFlow()
    private val navigation = Channel<IptvVodNavigation>(Channel.BUFFERED)
    val events = navigation.receiveAsFlow()
    private var listJob: Job? = null
    private var artJob: Job? = null
    private var searchJob: Job? = null

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            val profileId = profiles.activeProfileId.value
            val sources = io(emptyList()) { streams.sources(profileId, kind) }
            val current = mutable.value
            val source = current.source?.takeIf { ref -> sources.any { it.ref == ref } } ?: sources.firstOrNull()?.ref
            val changed = source != current.source || !current.ready
            val nuvio = preferences.mode == IptvVodArtworkMode.NUVIO
            mutable.update { it.copy(ready = true, sources = sources, source = source, nuvio = nuvio, art = if (nuvio) it.art else emptyMap()) }
            if (changed) selectSource(source) else if (source != null) loadCategories(source)
        }
    }

    fun showSource(ref: IptvSourceRef) { if (ref != mutable.value.source || mutable.value.shelf == IptvVodShelf.SEARCH) selectSource(ref) }

    fun showRecent() = show(IptvVodShelf.RECENT, null)
    fun showAll() = show(IptvVodShelf.ALL, null)
    fun showCategory(id: String) = show(IptvVodShelf.CATEGORY, id)

    fun search(text: String) {
        val clean = text.take(100)
        mutable.update { it.copy(search = clean) }
        searchJob?.cancel()
        if (clean.isBlank()) { if (mutable.value.shelf == IptvVodShelf.SEARCH) show(IptvVodShelf.RECENT, null); return }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DELAY)
            listJob?.cancel()
            val profileId = profiles.activeProfileId.value
            val source = mutable.value.source
            mutable.update { it.copy(shelf = IptvVodShelf.SEARCH, category = null, loading = true) }
            val found = io(emptyList()) { repository.search(profileId, kind, clean, SEARCH_LIMIT).filter { source == null || it.ref.sourceId == source.sourceId } }
            mutable.update { it.copy(items = found, nextOffset = null, loading = false, revision = it.revision + 1, art = trimmed(it.art, found)) }
        }
    }

    fun loadMore() {
        val current = mutable.value
        val offset = current.nextOffset ?: return
        if (current.loading || current.shelf == IptvVodShelf.SEARCH) return
        load(current.source ?: return, current.shelf, current.category, offset)
    }

    fun visible(refs: List<VodRef>) {
        val current = mutable.value
        if (!current.nuvio) return
        val wanted = current.items.filter { it.ref in refs && it.ref !in current.art }
        if (wanted.isEmpty()) return
        artJob?.cancel()
        artJob = viewModelScope.launch {
            val found = try { artwork.resolve(wanted, opener.language()) } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { IptvLog.failure("vod artwork batch", error); emptyMap() }
            if (found.isNotEmpty()) mutable.update { it.copy(art = it.art + found) }
        }
    }

    fun open(title: IptvVodTitle) {
        if (mutable.value.opening != null) return
        viewModelScope.launch {
            mutable.update { it.copy(opening = title.ref) }
            try {
                val art = if (mutable.value.nuvio) mutable.value.art[title.ref] ?: artwork.resolve(listOf(title), opener.language())[title.ref] else null
                val target = opener.target(title.ref.kind, title.tmdbId ?: art?.tmdbId, title.imdbId ?: art?.imdbId)
                navigation.send(if (target != null) IptvVodNavigation.Detail(target) else IptvVodNavigation.Title(title.ref))
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { IptvLog.failure("vod open", error); navigation.send(IptvVodNavigation.Title(title.ref)) }
            finally { mutable.update { it.copy(opening = null) } }
        }
    }

    private fun selectSource(ref: IptvSourceRef?) {
        searchJob?.cancel()
        mutable.update { it.copy(source = ref, categories = emptyList(), search = "", items = emptyList(), nextOffset = null) }
        if (ref == null) return
        loadCategories(ref)
        load(ref, IptvVodShelf.RECENT, null, 0)
    }

    private fun loadCategories(ref: IptvSourceRef) {
        viewModelScope.launch {
            val categories = io(emptyList()) { repository.categories(ref, kind) }
            if (mutable.value.source == ref) mutable.update { it.copy(categories = categories) }
        }
    }

    private fun show(shelf: IptvVodShelf, category: String?) {
        searchJob?.cancel()
        val source = mutable.value.source ?: return
        mutable.update { it.copy(search = "") }
        load(source, shelf, category, 0)
    }

    private fun load(source: IptvSourceRef, shelf: IptvVodShelf, category: String?, offset: Int) {
        listJob?.cancel()
        mutable.update { it.copy(shelf = shelf, category = category, loading = true) }
        listJob = viewModelScope.launch {
            val page = io(null) { repository.page(source, kind, category, offset, PAGE, recent = shelf == IptvVodShelf.RECENT) }
            mutable.update { state ->
                if (state.source != source) return@update state
                val items = if (offset == 0) page?.items.orEmpty() else (state.items + page?.items.orEmpty()).distinctBy { it.ref }
                val next = page?.nextOffset?.takeIf { shelf != IptvVodShelf.RECENT || it < RECENT_LIMIT }
                val cached = if (state.nuvio) page?.items.orEmpty().mapNotNull { title -> artwork.cached(title, opener.language())?.let { title.ref to it } }.toMap() else emptyMap()
                state.copy(items = items, nextOffset = next, loading = false, revision = if (offset == 0) state.revision + 1 else state.revision,
                    art = trimmed(state.art, items) + cached)
            }
        }
    }

    private fun trimmed(art: Map<VodRef, VodArt>, items: List<IptvVodTitle>): Map<VodRef, VodArt> =
        if (art.size <= MAX_ART) art else items.mapNotNull { title -> art[title.ref]?.let { title.ref to it } }.toMap()

    private suspend fun <T> io(fallback: T, block: () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { IptvLog.failure("vod browse", error); fallback }
    }

    private companion object {
        const val PAGE = 120
        const val RECENT_LIMIT = 240
        const val SEARCH_LIMIT = 150
        const val SEARCH_DELAY = 350L
        const val MAX_ART = 600
    }
}

data class IptvVodPlay(val ref: VodRef, val title: String, val year: Int?, val poster: String?, val backdrop: String?, val source: String?, val fromStart: Boolean)

data class IptvVodTitleState(
    val ref: VodRef? = null, val loaded: Boolean = false, val title: IptvVodTitle? = null, val source: String? = null, val art: VodArt? = null,
    val plot: String? = null, val durationSeconds: Int? = null, val tmdbId: String? = null, val imdbId: String? = null,
    val resume: IptvVodResume? = null, val episodes: List<IptvVodEpisode> = emptyList(), val episodesLoading: Boolean = false,
    val episodesFailed: Boolean = false, val season: Int? = null, val resumes: Map<VodRef, IptvVodResume> = emptyMap(),
    val checking: VodRef? = null, val message: String? = null, val opening: Boolean = false,
) {
    val seasons: List<Int> get() = episodes.map { it.season }.distinct().sorted()
    val latest: IptvVodResume? get() = resumes.values.maxByOrNull { it.updatedAtMillis }
}

sealed interface IptvVodTitleEvent {
    data class Play(val play: IptvVodPlay) : IptvVodTitleEvent
    data class Detail(val target: VodDetailTarget) : IptvVodTitleEvent
}

@HiltViewModel
class IptvVodTitleViewModel @Inject constructor(savedState: SavedStateHandle, @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val repository: IptvVodRepository, private val resolver: IptvVodResolver, private val catalogue: IptvCatalogueStore,
    private val artwork: IptvVodArtwork, private val preferences: IptvVodArtworkPreferences, private val opener: IptvVodOpener) : ViewModel() {
    private val ref = VodRef.parse(savedState.get<String>("ref"))?.takeIf { it.kind != VodKind.EPISODE }
    private val mutable = MutableStateFlow(IptvVodTitleState(ref = ref))
    val state = mutable.asStateFlow()
    private val channel = Channel<IptvVodTitleEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    private var episodesJob: Job? = null

    init { load() }

    private fun load() {
        val ref = ref ?: run { mutable.update { it.copy(loaded = true) }; return }
        viewModelScope.launch {
            val title = io(null) { repository.title(ref) }
            val source = io(null) { catalogue.sources(ref.profileId).firstOrNull { it.ref.sourceId == ref.sourceId }?.label }
            mutable.update { it.copy(loaded = true, title = title, source = source, tmdbId = title?.tmdbId, imdbId = title?.imdbId,
                episodesLoading = title != null && ref.kind == VodKind.SERIES) }
            title ?: return@launch
            refreshResume()
            if (preferences.mode == IptvVodArtworkMode.NUVIO) launch {
                val art = try { artwork.resolve(listOf(title), opener.language())[ref] } catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) { IptvLog.failure("vod title artwork", error); null }
                if (art != null) mutable.update { it.copy(art = art, tmdbId = it.tmdbId ?: art.tmdbId, imdbId = it.imdbId ?: art.imdbId) }
            }
            if (ref.kind == VodKind.MOVIE) launch {
                val info = try { repository.movieInfo(ref) } catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) { IptvLog.failure("vod title info", error); null }
                if (info != null) mutable.update { it.copy(plot = info.plot, durationSeconds = info.durationSeconds, tmdbId = it.tmdbId ?: info.tmdbId,
                    imdbId = it.imdbId ?: info.imdbId) }
            } else loadEpisodes()
        }
    }

    fun loadEpisodes() {
        val ref = ref ?: return
        if (ref.kind != VodKind.SERIES || episodesJob?.isActive == true) return
        episodesJob = viewModelScope.launch {
            mutable.update { it.copy(episodesLoading = true, episodesFailed = false) }
            val episodes = try { repository.episodes(ref) } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { IptvLog.failure("vod title episodes", error); null }
            mutable.update { state ->
                val list = episodes.orEmpty()
                val latest = state.latest?.ref?.let { r -> list.firstOrNull { it.ref == r }?.season }
                state.copy(episodes = list, episodesLoading = false, episodesFailed = episodes == null,
                    season = state.season?.takeIf { s -> list.any { it.season == s } } ?: latest ?: list.firstOrNull { it.season > 0 }?.season ?: list.firstOrNull()?.season)
            }
        }
    }

    fun refreshResume() {
        val ref = ref ?: return
        viewModelScope.launch {
            if (ref.kind == VodKind.SERIES) {
                val resumes = io(emptyList()) { repository.resumes(ref) }.associateBy { it.ref }
                mutable.update { it.copy(resumes = resumes) }
            } else {
                val resume = io(null) { repository.resume(ref) }
                mutable.update { it.copy(resume = resume) }
            }
        }
    }

    fun selectSeason(season: Int) = mutable.update { it.copy(season = season) }

    fun dismissMessage() = mutable.update { it.copy(message = null) }

    fun play(target: VodRef, fromStart: Boolean) {
        val state = mutable.value
        val title = state.title ?: return
        if (state.checking != null) return
        viewModelScope.launch {
            mutable.update { it.copy(checking = target, message = null) }
            val message = when (val result = resolver.resolve(target)) {
                is IptvVodResolution.Ready -> {
                    val episode = if (target.kind == VodKind.EPISODE) mutable.value.episodes.firstOrNull { it.ref == target } else null
                    val name = if (episode == null) title.title else VodStreams.episode(title.title, episode.season, episode.episode, episode.title, null).title
                    val art = mutable.value.art
                    channel.send(IptvVodTitleEvent.Play(IptvVodPlay(target, name, title.year ?: art?.year, art?.poster ?: title.artwork, art?.backdrop,
                        mutable.value.source, fromStart)))
                    null
                }
                is IptvVodResolution.Busy -> context.getString(R.string.iptv_vod_connections_busy, result.sourceLabel)
                IptvVodResolution.Unavailable -> context.getString(R.string.iptv_vod_unavailable)
            }
            mutable.update { it.copy(checking = null, message = message) }
        }
    }

    fun openDetails() {
        val state = mutable.value
        val ref = ref ?: return
        if (state.opening) return
        viewModelScope.launch {
            mutable.update { it.copy(opening = true) }
            try { opener.target(ref.kind, state.tmdbId, state.imdbId)?.let { channel.send(IptvVodTitleEvent.Detail(it)) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { IptvLog.failure("vod title details", error) }
            finally { mutable.update { it.copy(opening = false) } }
        }
    }

    private suspend fun <T> io(fallback: T, block: () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { IptvLog.failure("vod title", error); fallback }
    }
}
