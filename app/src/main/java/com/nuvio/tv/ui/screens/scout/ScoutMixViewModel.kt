package com.nuvio.tv.ui.screens.scout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.player.ScoutMixReturn
import com.nuvio.tv.core.player.ScoutMixSession
import com.nuvio.tv.core.player.ScoutMixUpNext
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.scout.ScoutMixLibrary
import com.nuvio.tv.core.scout.ScoutMixSuggestions
import com.nuvio.tv.core.scout.ScoutReadyMixBuilder
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.ScoutMixStore
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.ScoutMix
import com.nuvio.tv.domain.model.ScoutMixEpisodeKey
import com.nuvio.tv.domain.model.ScoutMixPick
import com.nuvio.tv.domain.model.ScoutMixShow
import com.nuvio.tv.domain.model.ScoutMixEpisodes
import com.nuvio.tv.domain.model.ScoutMixes
import com.nuvio.tv.domain.model.keptTo
import com.nuvio.tv.domain.model.needsLogoLookup
import com.nuvio.tv.domain.model.pickScoutMixEpisode
import com.nuvio.tv.domain.model.withFoundLogos
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val COUNTDOWN_SECONDS = 8
private const val COMING_SHOWN = 6
private const val TAB_SETTLE_MS = 300L
/** Episodes in a row without a link before the mix stops trying by itself. */
private const val NO_LINK_TRIES = 3

/** The queue without the episode that is up next: the row shows that one first by itself. */
internal fun comingWithout(coming: List<ScoutMixPick.Episode>, pick: ScoutMixPick?): List<ScoutMixPick.Episode> {
    val key = (pick as? ScoutMixPick.Episode)?.key ?: return coming
    return coming.filter { it.key != key }
}

/** Up next first, then the queue, each episode once. */
internal fun mixQueueTiles(pick: ScoutMixPick.Episode?, coming: List<ScoutMixPick.Episode>): List<ScoutMixPick.Episode> =
    (listOfNotNull(pick) + coming).distinctBy { it.key }

data class ScoutMixUiState(
    val loading: Boolean = true,
    val pick: ScoutMixPick? = null,
    val countdown: Int? = null,
    val offers: List<MetaPreview> = emptyList(),
    val coming: List<ScoutMixPick.Episode> = emptyList(),
    val tabShow: String? = null,
    val showEpisodes: List<ScoutMixPick.Episode> = emptyList(),
    /** The episode just passed over because no source had a link for it. */
    val noLink: ScoutMixPick.Episode? = null,
    /** Several in a row had no link, so the mix waits for the viewer. */
    val noLinkStopped: Boolean = false
)

@HiltViewModel
class ScoutMixViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val store: ScoutMixStore,
    private val library: ScoutMixLibrary,
    private val suggestions: ScoutMixSuggestions,
    private val session: ScoutMixSession,
    private val profiles: ProfileManager,
    private val playerSettings: PlayerSettingsDataStore,
    private val ready: ScoutReadyMixBuilder,
    layoutPreferences: LayoutPreferenceDataStore
) : ViewModel() {
    private val mixId: String = savedStateHandle["mixId"] ?: ""
    private var playOnOpen: Boolean = savedStateHandle.get<String>("play") == "1"
    private val played = mutableSetOf<ScoutMixEpisodeKey>()
    private var lastPlayed: ScoutMixEpisodeKey? = null
    private var lastShowId: String? = null
    private var inARow = 0
    private var noLinkRow = 0
    /** Episodes without a link this week, left out like the ones already played. */
    private var noLinks: Set<ScoutMixEpisodeKey> = emptySet()
    private var pickJob: Job? = null
    private var countdownJob: Job? = null
    private var aheadJob: Job? = null
    private var comingJob: Job? = null
    private var tabJob: Job? = null
    private val byShow = HashMap<String, List<ScoutMixPick.Episode>>()
    private var ahead: ScoutMixPick.Episode? = null
    private var showing = true
    private val profileId = profiles.activeProfileId.value

    private val allMixes = store.observe(profileId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val pickSources = store.observePickSources(profileId).stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val mix: StateFlow<ScoutMix?> = store.observeAny(profileId).map { mixes -> mixes.firstOrNull { it.id == mixId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val posterWidthDp = layoutPreferences.posterCardWidthDp.stateIn(viewModelScope, SharingStarted.Eagerly, 126)
    val posterCornerDp = layoutPreferences.posterCardCornerRadiusDp.stateIn(viewModelScope, SharingStarted.Eagerly, 12)

    private val _state = MutableStateFlow(ScoutMixUiState())
    val state: StateFlow<ScoutMixUiState> = _state.asStateFlow()

    private val _savedAs = MutableStateFlow<String?>(null)
    /** The id of the viewer's own copy, once a ready-made mix has been saved. */
    val savedAs: StateFlow<String?> = _savedAs.asStateFlow()

    private val _gone = MutableStateFlow(false)
    val gone: StateFlow<Boolean> = _gone.asStateFlow()

    private val _start = Channel<ScoutMixPick.Episode>(Channel.CONFLATED)
    val start = _start.receiveAsFlow()

    private var logosLookedUp = false
    private var pickedFrom: List<String> = emptyList()
    private val seenLogos = java.util.concurrent.ConcurrentHashMap<String, String>()

    init {
        viewModelScope.launch { store.observeNoSources(profileId).collect { noLinks = it } }
        if (mixId.startsWith(ScoutMixes.READY_PREFIX)) {
            _state.update { it.copy(loading = true) }
            viewModelScope.launch {
                runCatching { ready.ensure(mixId) }
                pickNext()
            }
        } else {
            pickNext()
        }
    }

    /** Keeps a ready-made mix as one of the viewer's own, where it stops changing and can be edited. */
    fun saveToMine() {
        val current = mix.value ?: return
        if (!current.readyMade || _savedAs.value != null) return
        viewModelScope.launch { _savedAs.value = store.saveCopy(current).id }
    }

    /** Only the chosen episodes of each show, for a mix that keeps to a list. */
    private suspend fun offerOf(saved: ScoutMix, show: ScoutMixShow): ScoutMixEpisodes? {
        val allowed = if (saved.only.isEmpty()) null else saved.only[show.id].orEmpty()
        return library.episodes(profileId, show)?.keptTo(allowed)
    }

    /** The page is showing again: back from the player or the sources page, or opened for the first time. */
    fun onShown() {
        showing = true
        val outcome = session.finish()
        if (outcome == ScoutMixReturn.NO_SOURCES) {
            passOver()
            return
        }
        noLinkRow = 0
        _state.update { it.copy(noLink = null, noLinkStopped = false) }
        if (outcome == ScoutMixReturn.LEFT) {
            pickAgainIfShowsChanged()
            return
        }
        (_state.value.pick as? ScoutMixPick.Episode)?.let { seen ->
            played += seen.key
            lastPlayed = seen.key
            lastShowId = seen.show.id
        }
        if (outcome == ScoutMixReturn.NEXT || outcome == ScoutMixReturn.NEXT_PRESSED) {
            inARow = if (outcome == ScoutMixReturn.NEXT_PRESSED) 0 else inARow + 1
            val next = ahead
            if (next == null) {
                pickNext()
                return
            }
            pickJob?.cancel()
            _state.update { it.copy(loading = false, pick = next, countdown = null, coming = comingWithout(it.coming, next)) }
            play()?.let { picked ->
                _start.trySend(picked)
                mix.value?.let { fillComing(it, picked) }
            }
            return
        }
        if (outcome == ScoutMixReturn.WATCHED) {
            inARow = 0
            pickNext()
            return
        }
        inARow += 1
        viewModelScope.launch {
            val settings = playerSettings.playerSettings.first()
            val keepGoing = mix.value?.keepPlaying == true &&
                !(settings.stillWatchingEnabled && inARow >= settings.stillWatchingEpisodeThreshold)
            if (!keepGoing) inARow = 0
            pickNext(andCountDown = keepGoing)
        }
    }

    /** No source had a link for the pick: leave that episode out for a week and play another, a few times at most. */
    private fun passOver() {
        val failed = _state.value.pick as? ScoutMixPick.Episode ?: return
        noLinks = noLinks + failed.key
        played += failed.key
        viewModelScope.launch { store.markNoSources(failed.key.first, failed.key.second) }
        noLinkRow += 1
        val stop = noLinkRow >= NO_LINK_TRIES
        if (stop) noLinkRow = 0
        _state.update { it.copy(noLink = failed, noLinkStopped = stop, coming = it.coming.filter { queued -> queued.key != failed.key }) }
        playOnOpen = !stop
        pickNext()
    }

    /** A show added from its long-press menu while the page was out of sight gives an empty mix something to play. */
    private fun pickAgainIfShowsChanged() {
        val now = _state.value
        val shows = mix.value?.shows?.map { it.id } ?: return
        if (!now.loading && now.pick !is ScoutMixPick.Episode && shows != pickedFrom) pickNext()
    }

    fun onHidden() {
        showing = false
        stopCountdown()
    }

    fun remix() {
        stopCountdown()
        inARow = 0
        noLinkRow = 0
        _state.update { it.copy(noLink = null, noLinkStopped = false) }
        _state.update { it.copy(coming = emptyList()) }
        pickNext()
    }

    fun stop() {
        stopCountdown()
        inARow = 0
    }

    /** Marks the pick as this mix's and hands it to the sources page. */
    fun play(): ScoutMixPick.Episode? {
        stopCountdown()
        val pick = _state.value.pick as? ScoutMixPick.Episode ?: return null
        session.start(profileId, mixId, pick.meta.id, pick.video.id)
        chooseAhead(pick)
        return pick
    }

    /** Plays an episode from the row. The rest of the queue stays as it was. */
    fun playFrom(episode: ScoutMixPick.Episode): ScoutMixPick.Episode? {
        stopCountdown()
        _state.update { it.copy(pick = episode, coming = it.coming.filter { queued -> queued.key != episode.key }) }
        return play()?.also { picked -> mix.value?.let { fillComing(it, picked) } }
    }

    /** A show's tab lists episodes of that show once the remote has settled on it; null is the queue. */
    fun focusTab(showId: String?) {
        tabJob?.cancel()
        _state.update { it.copy(tabShow = showId, showEpisodes = showId?.let(byShow::get).orEmpty()) }
        if (showId == null || showId in byShow) return
        tabJob = viewModelScope.launch {
            delay(TAB_SETTLE_MS)
            val saved = mix.value ?: return@launch
            val show = saved.shows.firstOrNull { it.id == showId } ?: return@launch
            val found = chainFrom(saved, listOf(show), null, played.toMutableSet(), COMING_SHOWN)
            byShow[showId] = found
            _state.update { if (it.tabShow == showId) it.copy(showEpisodes = found) else it }
        }
    }

    private suspend fun chainFrom(
        saved: ScoutMix,
        shows: List<ScoutMixShow>,
        start: ScoutMixPick.Episode?,
        taken: MutableSet<ScoutMixEpisodeKey>,
        wanted: Int
    ): List<ScoutMixPick.Episode> {
        val looked = HashMap<String, ScoutMixEpisodes?>()
        val out = ArrayList<ScoutMixPick.Episode>()
        var last = start
        while (out.size < wanted) {
            val next = pickScoutMixEpisode(
                shows = shows,
                unwatchedOnly = saved.unwatchedOnly,
                played = taken + noLinks,
                lastPlayed = last?.key,
                current = last?.key,
                lastShowId = last?.show?.id,
                episodes = { show -> looked.getOrPut(show.id) { offerOf(saved, show) } }
            ) as? ScoutMixPick.Episode ?: break
            if (next.key in taken) break
            out += next
            taken += next.key
            last = next
        }
        return out
    }

    /** What the mix will play after [pick], kept so the row can promise it and the mix can keep to it. */
    private fun fillComing(saved: ScoutMix, pick: ScoutMixPick.Episode) {
        comingJob?.cancel()
        comingJob = viewModelScope.launch {
            val kept = _state.value.coming.filter { it.key != pick.key && it.key !in played && saved.shows.any { show -> show.sameShow(it.show) } }
            val taken = (played + pick.key + kept.map { it.key }).toMutableSet()
            val more = chainFrom(saved, saved.shows, kept.lastOrNull() ?: pick, taken, COMING_SHOWN - kept.size)
            _state.update { it.copy(coming = kept + more) }
        }
    }

    /** Picks what follows [current] while it plays, so the player can offer it at the credits. */
    private fun chooseAhead(current: ScoutMixPick.Episode) {
        aheadJob?.cancel()
        ahead = null
        aheadJob = viewModelScope.launch {
            val saved = store.observeAny(profileId).first().firstOrNull { it.id == mixId } ?: return@launch
            val next = _state.value.coming.firstOrNull { it.key != current.key && it.key !in played } ?: pickScoutMixEpisode(
                shows = saved.shows,
                unwatchedOnly = saved.unwatchedOnly,
                played = played + current.key + noLinks,
                lastPlayed = current.key,
                current = current.key,
                lastShowId = current.show.id,
                episodes = { show -> offerOf(saved, show) }
            ) as? ScoutMixPick.Episode ?: return@launch
            val settings = playerSettings.playerSettings.first()
            val startsByItself = saved.keepPlaying &&
                !(settings.stillWatchingEnabled && inARow + 1 >= settings.stillWatchingEpisodeThreshold)
            ahead = next
            session.offerNext(
                current.video.id,
                ScoutMixUpNext(
                    mixName = saved.name,
                    showName = next.meta.name.ifBlank { next.show.name },
                    contentId = next.meta.id,
                    videoId = next.video.id,
                    season = next.video.season,
                    episode = next.video.episode,
                    title = next.video.title,
                    thumbnail = next.video.thumbnail ?: next.meta.background ?: next.show.backdrop,
                    startsByItself = startsByItself
                )
            )
        }
    }

    fun setUnwatchedOnly(on: Boolean) {
        viewModelScope.launch {
            store.update(mixId) { it.copy(unwatchedOnly = on) }
            stopCountdown()
            byShow.clear()
            _state.update { it.copy(coming = emptyList(), showEpisodes = emptyList()) }
            pickNext(with = mix.value?.copy(unwatchedOnly = on))
        }
    }

    fun setPickSources(on: Boolean) {
        viewModelScope.launch { store.setPickSources(on) }
    }

    fun setKeepPlaying(on: Boolean) {
        viewModelScope.launch { store.update(mixId) { it.copy(keepPlaying = on) } }
    }

    fun add(item: MetaPreview) {
        viewModelScope.launch {
            val show = library.showFrom(item)
            store.update(mixId) { library.withSuggestedName(it.adding(show), allMixes.value) }
            stopCountdown()
            _state.update { it.copy(coming = emptyList()) }
            pickNext(with = mix.value?.adding(show))
        }
    }

    fun rename(name: String) {
        val cleaned = name.trim().take(ScoutMixes.NAME_LIMIT)
        if (cleaned.isBlank()) return
        viewModelScope.launch { store.update(mixId) { it.copy(name = cleaned, autoName = false) } }
    }

    fun remove(show: ScoutMixShow) {
        viewModelScope.launch {
            store.update(mixId) { library.withSuggestedName(it.removing(show), allMixes.value) }
            val remaining = mix.value?.removing(show)
            val current = _state.value.pick as? ScoutMixPick.Episode
            if (current == null || current.show.sameShow(show)) {
                stopCountdown()
                _state.update { it.copy(coming = emptyList()) }
                pickNext(with = remaining)
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            store.delete(mixId)
            _gone.value = true
        }
    }

    private fun pickNext(andCountDown: Boolean = false, with: ScoutMix? = null) {
        pickJob?.cancel()
        val current = (_state.value.pick as? ScoutMixPick.Episode)?.key
        _state.update { it.copy(loading = true, countdown = null) }
        pickJob = viewModelScope.launch {
            val saved = with ?: store.observeAny(profileId).first().firstOrNull { it.id == mixId }
            if (saved == null) {
                _gone.value = true
                return@launch
            }
            val queued = _state.value.coming.firstOrNull { it.key !in played && it.key !in noLinks && it.key != current }
            val resume = if (queued == null && played.isEmpty() && current == null) {
                runCatching { library.startedEpisode(saved.shows) }.getOrNull()
            } else null
            val pick = queued ?: pickScoutMixEpisode(
                shows = saved.shows,
                unwatchedOnly = saved.unwatchedOnly,
                played = played + noLinks,
                lastPlayed = lastPlayed,
                current = current,
                lastShowId = lastShowId,
                resume = resume,
                episodes = { show -> episodesOf(saved, show) }
            )
            val offers = if (pick is ScoutMixPick.AllSeen) suggestions.forMix(saved) else emptyList()
            pickedFrom = saved.shows.map { it.id }
            _state.update { it.copy(loading = false, pick = pick, offers = offers, coming = comingWithout(it.coming, pick)) }
            if (pick is ScoutMixPick.Episode) {
                fillFromMeta(saved, pick)
                fillComing(saved, pick)
                if (playOnOpen) {
                    playOnOpen = false
                    play()?.let { _start.trySend(it) }
                } else if (andCountDown) countDown()
            }
            saveLogos()
            if (!logosLookedUp) {
                logosLookedUp = true
                viewModelScope.launch {
                    saved.shows.filter { it.needsLogoLookup() && !seenLogos.containsKey(it.id) }.forEach { episodesOf(saved, it) }
                    saveLogos()
                }
            }
        }
    }

    private suspend fun episodesOf(saved: ScoutMix, show: ScoutMixShow) = offerOf(saved, show)?.also { found ->
        if (show.needsLogoLookup() && !seenLogos.containsKey(show.id)) {
            seenLogos[show.id] = library.bestLogo(show) ?: found.meta.logo.orEmpty()
        }
    }

    /** Mixes made before logos were kept pick them up as their shows are looked up, for the Home hero. */
    private suspend fun saveLogos() {
        val saved = mix.value ?: return
        if (saved.shows.none { it.needsLogoLookup() && seenLogos.containsKey(it.id) }) return
        store.update(mixId) { current -> current.copy(shows = withFoundLogos(current.shows, seenLogos)) }
    }

    /** Catalog posters do not always carry a backdrop or genres; the tile and name improve once the show's own are known. */
    private suspend fun fillFromMeta(saved: ScoutMix, pick: ScoutMixPick.Episode) {
        val saving = saved.shows.firstOrNull { it.sameShow(pick.show) } ?: return
        val poster = saving.poster ?: pick.meta.poster
        val backdrop = saving.backdrop ?: pick.meta.background
        val genres = saving.genres.ifEmpty { pick.meta.genres }
        if (poster == saving.poster && backdrop == saving.backdrop && genres == saving.genres) return
        store.update(mixId) { mix ->
            library.withSuggestedName(
                mix.copy(shows = mix.shows.map { if (it.sameShow(saving)) it.copy(poster = poster, backdrop = backdrop, genres = genres) else it }),
                allMixes.value
            )
        }
    }

    private fun countDown() {
        countdownJob?.cancel()
        if (!showing) return
        countdownJob = viewModelScope.launch {
            for (left in COUNTDOWN_SECONDS downTo 1) {
                _state.update { it.copy(countdown = left) }
                delay(1_000)
            }
            play()?.let { _start.trySend(it) }
        }
    }

    private fun stopCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        _state.update { it.copy(countdown = null) }
    }

    override fun onCleared() {
        session.finish()
    }
}
