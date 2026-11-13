package com.nuvio.tv.ui.screens.scout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.nuvio.tv.core.image.sizedHomeArtworkUrl
import com.nuvio.tv.core.party.PartyAvatars
import com.nuvio.tv.core.profile.LocalProfileAvatars
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.remote.supabase.AvatarRepository
import com.nuvio.tv.data.repository.MemberAccessRepository
import com.nuvio.tv.domain.model.CosmeticEntitlement
import com.nuvio.tv.core.scout.SCOUT_QUICK_EPISODE_LIMIT
import com.nuvio.tv.core.scout.ScoutAnswers
import com.nuvio.tv.core.scout.ScoutFolderSupport
import com.nuvio.tv.core.scout.ScoutGroupMember
import com.nuvio.tv.core.scout.ScoutMood
import com.nuvio.tv.core.scout.ScoutPickDetail
import com.nuvio.tv.core.scout.ScoutPicksFinder
import com.nuvio.tv.core.scout.ScoutTime
import com.nuvio.tv.core.scout.ScoutTune
import com.nuvio.tv.core.scout.ScoutWatch
import com.nuvio.tv.core.scout.scoutOrderPicks
import com.nuvio.tv.core.scout.ask.AskFailure
import com.nuvio.tv.core.scout.ask.AskItem
import com.nuvio.tv.core.scout.ask.AskOutcome
import com.nuvio.tv.core.scout.ask.AskRunner
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.core.sync.ProfileSyncService
import com.nuvio.tv.data.local.ProfileLockStateDataStore
import com.nuvio.tv.data.local.ScoutStore
import com.nuvio.tv.domain.model.ScoutPick
import com.nuvio.tv.domain.model.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

data class ScoutShownPick(val pick: ScoutPick, val detail: ScoutPickDetail? = null, val suitsEveryone: Boolean = false, val reason: String? = null)

sealed interface ScoutTab {
    data object ForYou : ScoutTab
    data object Taste : ScoutTab
    data class Feeling(val mood: ScoutMood) : ScoutTab
}

data class ScoutScreenState(
    val answers: ScoutAnswers = ScoutAnswers(),
    val others: Set<Int> = emptySet(),
    val tab: ScoutTab = ScoutTab.ForYou,
    val surprise: Boolean = false,
    val moreLike: String? = null,
    val row: List<ScoutPick> = emptyList(),
    /** Goes up each time the row shows different titles, so the page can start the new row at its first one. */
    val rowTurn: Int = 0,
    val everyone: Set<String> = emptySet(),
    val rowLoading: Boolean = true,
    val nothing: Boolean = false,
    val hero: ScoutShownPick? = null,
    val heroLogo: String? = null,
    val fromCountryOffered: Boolean = false,
    val pinFor: UserProfile? = null,
    val pinWrong: Boolean = false,
    val pinChecking: Boolean = false,
    val asking: Boolean = false,
    val askBusy: Boolean = false,
    val askFailure: AskFailure? = null
) {
    /** Unset answers, a surprise or "more like" draw from elsewhere, which is not a query a row could repeat. */
    val canSave: Boolean get() = !surprise && moreLike == null && !asking && !answers.isUnset
}

internal fun nextRowTurn(shown: List<ScoutPick>, next: List<ScoutPick>, turn: Int): Int =
    if (shown.map { it.key } == next.map { it.key }) turn else turn + 1

const val SCOUT_FOCUS_FOR_YOU = "tab_you"
const val SCOUT_FOCUS_THREE_MORE = "tab_more"
private const val MAX_EPISODE_CHECKS = 15
private const val ROW_SETTLE_MS = 150L
private const val KEPT_ROW_SETTLE_MS = 50L
private val NEAR_MOODS = listOf(1, -1, 2, -2)
private const val SPEED_TAG = "ScoutSpeed"
private const val HERO_SETTLE_MS = 250L
private const val ROW_SIZE = 12
private const val WARM_AFTER_MS = 800L
private const val WARM_AT_ONCE = 2
private const val WARM_TILES = 3
private const val WARM_IMAGE_WIDTH = 96
private const val WARM_IMAGE_HEIGHT = 54

/** TMDB's series length filter lets long shows through, so a short-episode answer is checked against the last episode. */
fun scoutEpisodeFits(detail: ScoutPickDetail?): Boolean = (detail?.minutes ?: 0) <= SCOUT_QUICK_EPISODE_LIMIT

@HiltViewModel
class ScoutViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val finder: ScoutPicksFinder,
    private val asker: AskRunner,
    private val folders: ScoutFolderSupport,
    private val profileManager: ProfileManager,
    private val locks: ProfileLockStateDataStore,
    private val sync: ProfileSyncService,
    store: ScoutStore,
    localAvatars: LocalProfileAvatars,
    private val avatars: AvatarRepository,
    private val memberAccess: MemberAccessRepository
) : ViewModel() {
    private val _state = MutableStateFlow(ScoutScreenState())
    val state: StateFlow<ScoutScreenState> = _state.asStateFlow()

    val savedRows: StateFlow<Set<String>> = store.profile.map { it.settings.savedSlices.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val profiles: StateFlow<List<UserProfile>> = profileManager.profiles
    val activeProfileId: StateFlow<Int> = profileManager.activeProfileId

    private val avatarCatalog = flow {
        val full = memberAccess.access.value.entitlements.includes(CosmeticEntitlement.PROFILE_AVATARS)
        emit(emptyList())
        emit(runCatching { avatars.getAvatarCatalog(full) }.getOrDefault(emptyList()))
    }

    /** The same picture the side menu and Who's watching draw for each profile. */
    val profilePictures: StateFlow<Map<Int, String>> =
        combine(profileManager.profiles, localAvatars.avatars, avatarCatalog) { all, local, catalog ->
            all.mapNotNull { profile ->
                val picture = local[profile.id]?.let(PartyAvatars::imageUri)
                    ?: profile.avatarUrl?.takeIf { it.isNotBlank() }
                    ?: profile.avatarId?.let { avatars.getAvatarImageUrl(it, catalog) }
                picture?.let { profile.id to it }
            }.toMap()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val country: String = Locale.getDefault().country

    /** Where focus was, so a trip to a title page comes back to the same spot. */
    var focusKey: String = SCOUT_FOCUS_FOR_YOU

    /** Where focus has to land after the tabs change, whatever it brushes past on the way. */
    var focusPending: String? = null

    private var job: Job? = null
    private var heroJob: Job? = null
    private var warmJob: Job? = null
    private var nearJob: Job? = null
    private val building = HashMap<String, Deferred<List<ScoutPick>>>()
    private var warmedFor: String? = null
    private val shown = LinkedHashSet<String>()
    private val details = HashMap<String, ScoutPickDetail>()
    private val logos = HashMap<String, String?>()
    private val members = HashMap<Int, ScoutGroupMember>()
    private val unlocked = HashSet<Int>()

    init {
        viewModelScope.launch {
            val saved = finder.pool() != null
            if (!saved) finder.refreshPool()
            loadRow(0)
            if (saved) finder.refreshPool()
        }
        viewModelScope.launch { _state.update { it.copy(fromCountryOffered = finder.fromCountryOffered(country)) } }
    }

    /** Resting on a tab shows it, the way Seasons work on a title's page. */
    fun focusTab(tab: ScoutTab) {
        val now = _state.value
        if (now.tab == tab && now.moreLike == null) return
        _state.update {
            it.copy(tab = tab, moreLike = null, surprise = false, asking = false, askBusy = false, askFailure = null, answers = it.answers.copy(mood = (tab as? ScoutTab.Feeling)?.mood, tunes = emptySet()))
        }
        if (tab != ScoutTab.Taste) {
            loadRow()
            warmNear()
        }
    }

    fun pressWatch(watch: ScoutWatch) = answer { it.pressWatch(watch) }
    fun pressTime(time: ScoutTime) = answer { it.pressTime(time) }
    fun pressFamily() = answer { it.pressFamily(!it.family) }

    private fun answer(change: (ScoutAnswers) -> ScoutAnswers) {
        _state.update { s ->
            val next = change(s.answers)
            s.copy(answers = next, moreLike = null, tab = if (next.mood == null && s.tab is ScoutTab.Feeling) ScoutTab.ForYou else s.tab)
        }
        if (_state.value.tab != ScoutTab.Taste) loadRow()
    }

    /** The tile in focus takes over the page once the remote has settled on it. */
    fun focusPick(pick: ScoutPick) {
        if (_state.value.hero?.pick?.key == pick.key) return
        heroJob?.cancel()
        heroJob = viewModelScope.launch {
            delay(HERO_SETTLE_MS)
            showHero(pick)
        }
    }

    private suspend fun showHero(pick: ScoutPick) {
        val key = pick.key
        val withRating = _state.value.answers.family
        _state.update {
            it.copy(hero = ScoutShownPick(pick, details[key], key in it.everyone, pick.aiReason.takeIf { _ -> it.asking }), heroLogo = logos[key])
        }
        coroutineScope {
            if (key !in details) launch {
                finder.detail(pick, withRating)?.let { found ->
                    details[key] = found
                    _state.update { s -> if (s.hero?.pick?.key == key) s.copy(hero = s.hero?.copy(detail = found)) else s }
                }
            }
            if (key !in logos) launch {
                val found = finder.logo(pick)
                logos[key] = found
                _state.update { s -> if (s.hero?.pick?.key == key) s.copy(heroLogo = found) else s }
            }
        }
    }

    private fun rowKey(s: ScoutScreenState) = s.answers.encode() + "|" + s.others.sorted().joinToString(",") + "|" + s.surprise

    private fun loadRow(settle: Long = ROW_SETTLE_MS) {
        job?.cancel()
        _state.update { it.copy(rowLoading = true, nothing = false, asking = false, askBusy = false, askFailure = null) }
        job = viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            val landed = _state.value
            val keptRow = keptFor(landed)
            val kept = keptRow != null
            delay(if (kept) minOf(settle, KEPT_ROW_SETTLE_MS) else settle)
            val asked = _state.value
            showRow(keptRow?.takeIf { rowKey(asked) == rowKey(landed) } ?: rowFor(asked))
            Log.i(SPEED_TAG, "row ${asked.answers.mood?.code ?: "for_you"} kept=$kept ${SystemClock.elapsedRealtime() - started} ms")
        }
    }

    private suspend fun keptFor(s: ScoutScreenState): List<ScoutPick>? = if (s.surprise) null else finder.keptRow(rowKey(s))

    /** A row already being fetched is waited for, so landing on it never starts the same fetch twice. */
    private suspend fun rowFor(s: ScoutScreenState): List<ScoutPick> {
        keptFor(s)?.let { return it }
        if (s.surprise) return buildRow(s)
        val key = rowKey(s)
        val fetch = building.getOrPut(key) {
            viewModelScope.async {
                buildRow(s).also { if (it.isNotEmpty()) finder.keepRow(key, it) }
            }.also { started -> started.invokeOnCompletion { building.remove(key) } }
        }
        return fetch.await()
    }

    /** The moods either side of where the remote is, ahead of everything else. */
    private fun warmNear() {
        val s = _state.value
        val quick = s.answers.time == ScoutTime.QUICK_EPISODES || s.answers.time == ScoutTime.SHORT
        if (s.surprise || s.moreLike != null || s.asking || quick) return
        val moods = listOf<ScoutMood?>(null) + ScoutMood.choicesFor(s.answers.watch, s.answers.family)
        val at = moods.indexOf((s.tab as? ScoutTab.Feeling)?.mood).coerceAtLeast(0)
        nearJob?.cancel()
        nearJob = viewModelScope.launch {
            NEAR_MOODS.mapNotNull { (at + it).takeIf(moods.indices::contains) }.forEach { index ->
                launch { warm(s.copy(answers = s.answers.copy(mood = moods[index], tunes = emptySet()))) }
            }
        }
    }

    /** Once a row is up, the neighbouring moods are fetched quietly, so resting on one shows it at once. */
    private fun warmMoods() {
        val s = _state.value
        val quick = s.answers.time == ScoutTime.QUICK_EPISODES || s.answers.time == ScoutTime.SHORT
        if (s.surprise || s.moreLike != null || s.asking || quick) return
        val asked = s.answers.copy(mood = null, tunes = emptySet()).encode() + s.others.sorted()
        if (asked == warmedFor) return
        warmedFor = asked
        warmJob?.cancel()
        warmJob = viewModelScope.launch {
            delay(WARM_AFTER_MS)
            val gate = Semaphore(WARM_AT_ONCE)
            val moods = listOf<ScoutMood?>(null) + ScoutMood.choicesFor(s.answers.watch, s.answers.family)
            val from = moods.indexOf((s.tab as? ScoutTab.Feeling)?.mood).coerceAtLeast(0)
            coroutineScope {
                moods.indices.sortedBy { abs(it - from) }.forEach { index ->
                    launch {
                        gate.withPermit { warm(s.copy(answers = s.answers.copy(mood = moods[index], tunes = emptySet()))) }
                    }
                }
            }
            finder.saveKept()
        }
    }

    private suspend fun warm(s: ScoutScreenState) {
        val picks = rowFor(s)
        val top = picks.firstOrNull() ?: return
        coroutineScope {
            launch { finder.detail(top, s.answers.family)?.let { details[top.key] = it } }
            launch { logos[top.key] = finder.logo(top) }
            launch { saveImage(top.backdrop?.let { sizedHomeArtworkUrl(it, SCOUT_BACKDROP_WIDTH) }) }
            picks.take(WARM_TILES).forEach { pick ->
                launch { saveImage((pick.backdrop ?: pick.poster)?.let { sizedHomeArtworkUrl(it, SCOUT_TILE_IMAGE_WIDTH) }) }
            }
        }
    }

    /** To disk only: nothing here takes up picture memory until the page shows it. */
    private suspend fun saveImage(url: String?) {
        if (url == null) return
        context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(url)
                .size(WARM_IMAGE_WIDTH, WARM_IMAGE_HEIGHT)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .build()
        )
    }

    private suspend fun showRow(picks: List<ScoutPick>) {
        val others = group()
        val ownPool = finder.pool()?.picked.orEmpty().map { it.key }.toSet()
        val everyone = if (others.isEmpty()) emptySet() else picks.filter { pick ->
            pick.key in ownPool && others.all { member -> member.pool?.picked.orEmpty().any { it.key == pick.key } }
        }.map { it.key }.toSet()
        _state.update {
            it.copy(row = picks, rowTurn = nextRowTurn(it.row, picks, it.rowTurn), everyone = everyone, rowLoading = false, nothing = picks.isEmpty())
        }
        warmMoods()
        picks.firstOrNull()?.let { showHero(it) }
    }

    private suspend fun buildRow(s: ScoutScreenState): List<ScoutPick> {
        val answers = s.answers
        var fresh = order(
            when {
                s.surprise && answers.isUnset -> finder.surprisePool()
                answers.isUnset -> finder.pool()?.picked.orEmpty()
                else -> emptyList()
            }
        )
        if (fresh.size < ROW_SIZE) fresh = (fresh + order(finder.fetch(answers, 1, relaxed = false))).distinctBy { it.key }
        if (fresh.size < ROW_SIZE / 2) fresh = (fresh + order(finder.fetch(answers, 1, relaxed = true))).distinctBy { it.key }
        if (answers.time != ScoutTime.QUICK_EPISODES && answers.time != ScoutTime.SHORT) return fresh.take(ROW_SIZE)
        val kept = ArrayList<ScoutPick>()
        var checks = 0
        for (pick in fresh) {
            if (kept.size == ROW_SIZE) break
            if (pick.tv && checks < MAX_EPISODE_CHECKS) {
                checks++
                val detail = details[pick.key] ?: finder.detail(pick, answers.family)?.also { details[pick.key] = it }
                if (!scoutEpisodeFits(detail)) continue
            }
            kept += pick
        }
        return kept
    }

    /** A profile with a PIN joins only once the PIN is right, so its taste cannot be read by anyone else on the box. */
    fun pressProfile(profile: UserProfile) {
        val current = _state.value.others
        when {
            profile.id in current -> {
                _state.update { it.copy(others = current - profile.id) }
                othersChanged()
            }
            profile.id !in unlocked -> viewModelScope.launch {
                if (locks.pinEnabled.first()[profile.id] == true) _state.update { it.copy(pinFor = profile, pinWrong = false) }
                else {
                    _state.update { it.copy(others = it.others + profile.id) }
                    othersChanged()
                }
            }
            else -> {
                _state.update { it.copy(others = current + profile.id) }
                othersChanged()
            }
        }
    }

    fun submitPin(pin: String) {
        val profile = _state.value.pinFor ?: return
        _state.update { it.copy(pinChecking = true, pinWrong = false) }
        viewModelScope.launch {
            val ok = sync.verifyProfilePin(profile.id, pin).getOrNull()?.unlocked == true
            if (ok) {
                unlocked += profile.id
                _state.update { it.copy(pinFor = null, pinChecking = false, others = it.others + profile.id) }
                othersChanged()
            } else {
                _state.update { it.copy(pinChecking = false, pinWrong = true) }
            }
        }
    }

    fun dismissPin() = _state.update { it.copy(pinFor = null, pinWrong = false) }

    fun clearPinError() = _state.update { it.copy(pinWrong = false) }

    private fun othersChanged() {
        if (_state.value.tab != ScoutTab.Taste) loadRow()
    }

    fun surprise() {
        focusKey = SCOUT_FOCUS_THREE_MORE
        focusPending = SCOUT_FOCUS_THREE_MORE
        _state.update { it.copy(answers = ScoutAnswers(), surprise = true, moreLike = null, tab = ScoutTab.ForYou) }
        shown.clear()
        loadRow(0)
    }

    fun pressTune(tune: ScoutTune) {
        _state.update { s ->
            val next = s.answers.pressTune(tune)
            s.copy(answers = next, surprise = s.surprise, moreLike = null)
        }
        shown.clear()
        loadRow(0)
    }

    fun threeMore() {
        shown += _state.value.row.map { it.key }
        loadRow(0)
    }

    fun moreLike(pick: ScoutPick) {
        job?.cancel()
        _state.update { it.copy(moreLike = pick.name, rowLoading = true, nothing = false, asking = false, askFailure = null) }
        job = viewModelScope.launch { showRow(order(finder.moreLike(pick)).take(ROW_SIZE)) }
    }

    /** "Or just ask": the input takes the mood line's place and the page stays as it is until there is an answer. */
    fun openAsk() {
        job?.cancel()
        focusPending = null
        _state.update { it.copy(asking = true, askBusy = false, askFailure = null, rowLoading = false) }
    }

    fun ask(question: String) {
        job?.cancel()
        heroJob?.cancel()
        _state.update { it.copy(askBusy = true, askFailure = null, row = emptyList(), rowLoading = true, nothing = false) }
        job = viewModelScope.launch {
            when (val outcome = asker.ask(question)) {
                is AskOutcome.Answer -> {
                    _state.update { it.copy(askBusy = false) }
                    showRow(outcome.items.ifEmpty { outcome.held }.mapNotNull(::askedPick))
                }
                is AskOutcome.Failed -> _state.update { it.copy(askBusy = false, askFailure = outcome.failure, rowLoading = false) }
            }
        }
    }

    private fun askedPick(item: AskItem): ScoutPick? {
        val id = item.meta.id.removePrefix("tmdb:").toIntOrNull() ?: return null
        return ScoutPick(
            tmdbId = id,
            tv = item.meta.type == ContentType.SERIES,
            name = item.meta.name,
            poster = item.meta.poster,
            backdrop = item.meta.background,
            overview = item.meta.description,
            released = item.meta.released,
            rating = item.meta.imdbRating,
            aiReason = item.reason
        )
    }

    fun leaveTaste() {
        focusKey = SCOUT_FOCUS_FOR_YOU
        focusPending = SCOUT_FOCUS_FOR_YOU
        focusTab(ScoutTab.ForYou)
    }

    /** Back from a surprise or "more like this" to the moods. */
    fun backToMoods() {
        focusKey = SCOUT_FOCUS_FOR_YOU
        focusPending = SCOUT_FOCUS_FOR_YOU
        shown.clear()
        _state.update { it.copy(surprise = false, moreLike = null, tab = ScoutTab.ForYou, answers = ScoutAnswers()) }
        loadRow(0)
    }

    fun toggleSaved() {
        val id = _state.value.answers.encode()
        viewModelScope.launch { folders.toggleSaved(id) }
    }

    private suspend fun group(): List<ScoutGroupMember> =
        _state.value.others.map { id -> members.getOrPut(id) { finder.member(id) } }

    private suspend fun order(list: List<ScoutPick>): List<ScoutPick> {
        val exclusions = finder.exclusions()
        val others = group()
        val ordered = withContext(Dispatchers.Default) { scoutOrderPicks(list, finder.pool(), exclusions, System.nanoTime(), others) }
        val kept = ordered.filterNot { it.key in shown }
        val ratingLimits = com.nuvio.tv.core.ratinglimit.ratingLimitGateOrNull(context) ?: return kept
        val limit = withContext(Dispatchers.IO) { ratingLimits.activeLimit() } ?: return kept
        return ratingLimits.keepFitting(limit, kept) { "tmdb:${it.tmdbId}" to if (it.tv) "series" else "movie" }
    }
}
