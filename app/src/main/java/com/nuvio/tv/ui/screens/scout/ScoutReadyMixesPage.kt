package com.nuvio.tv.ui.screens.scout

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.scout.ScoutMixDecade
import com.nuvio.tv.core.scout.ScoutReadyMix
import com.nuvio.tv.core.scout.ScoutReadyMixBuilder
import com.nuvio.tv.core.scout.ScoutReadyMixes
import com.nuvio.tv.core.scout.ScoutReadyTab
import com.nuvio.tv.core.scout.ScoutTasteRepository
import com.nuvio.tv.core.scout.suitsFamily
import com.nuvio.tv.data.local.ScoutMixStore
import com.nuvio.tv.ui.components.NuvioScrollDefaults
import com.nuvio.tv.ui.screens.calendar.calendarNameList
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

private const val NAMES_SHOWN = 4
private const val TILE_NAMES = 2
private const val READY_TILE_PREFIX = "ready_tile_"
private const val READY_TAB_PREFIX = "ready_tab_"
internal const val READY_FOCUS_PLAY = "ready_play"
private const val READY_FOCUS_SAVE = "ready_save"
private const val READY_FOCUS_ADJUST = "ready_adjust"
internal const val READY_FOCUS_OWN = "ready_own"

/** What a tile shows before its mix is made: the first shows Scout would put in, and their artwork. */
data class ScoutReadyTileState(
    val mix: ScoutReadyMix,
    val art: String? = null,
    val names: List<String> = emptyList(),
    val loaded: Boolean = false,
    val hidden: Boolean = false,
    /** Every picture the tile could use, best first, so tiles side by side can each show a different one. */
    val arts: List<String> = emptyList()
)

data class ScoutReadyState(
    val tab: ScoutReadyTab = ScoutReadyTab.DECADES,
    val tiles: List<ScoutReadyTileState> = emptyList(),
    val focused: String? = null,
    val savedIds: Set<String> = emptySet()
) {
    val focusedTile: ScoutReadyTileState? get() = tiles.firstOrNull { it.mix.id == focused } ?: tiles.firstOrNull()
}

@HiltViewModel
class ScoutReadyMixesViewModel @Inject constructor(
    private val builder: ScoutReadyMixBuilder,
    private val store: ScoutMixStore,
    private val tasteRepository: ScoutTasteRepository
) : ViewModel() {
    private val _state = MutableStateFlow(ScoutReadyState())
    val state: StateFlow<ScoutReadyState> = _state.asStateFlow()
    private var family = false
    private var loadJob: Job? = null
    private val loaded = HashMap<String, ScoutReadyTileState>()
    private var taste: Map<String, Double> = emptyMap()

    init {
        showTab(ScoutReadyTab.DECADES)
        viewModelScope.launch { taste = runCatching { tasteRepository.countryWeights() }.getOrDefault(emptyMap()) }
        viewModelScope.launch {
            store.mixes.collect { mixes -> _state.update { it.copy(savedIds = mixes.mapNotNull { mix -> mix.savedFrom }.toSet()) } }
        }
    }

    fun setFamily(on: Boolean) {
        if (on == family) return
        family = on
        loaded.clear()
        showTab(_state.value.tab)
    }

    fun focusTab(tab: ScoutReadyTab) {
        if (tab != _state.value.tab) showTab(tab)
    }

    fun focusMix(id: String) {
        _state.update { it.copy(focused = id) }
    }

    fun save(id: String) {
        if (id in _state.value.savedIds) return
        viewModelScope.launch {
            val mix = builder.ensure(id, family) ?: return@launch
            store.saveCopy(mix)
            _state.update { it.copy(savedIds = it.savedIds + id) }
        }
    }

    /** With Family night on, the mix is made to suit everyone before it opens; otherwise it opens at once. */
    fun prepare(id: String, go: () -> Unit) {
        if (!family) {
            go()
            return
        }
        viewModelScope.launch {
            runCatching { builder.ensure(id, true) }
            go()
        }
    }

    private fun showTab(tab: ScoutReadyTab) {
        val mixes = ScoutReadyMixes.tab(tab, family, LocalDate.now(), Locale.getDefault().country, taste)
        _state.update { now ->
            ScoutReadyState(
                tab = tab,
                tiles = scoutDistinctArt(mixes.map { loaded[it.id] ?: ScoutReadyTileState(it) }.filterNot { it.hidden }),
                focused = mixes.firstOrNull()?.id,
                savedIds = now.savedIds
            )
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            coroutineScope {
                mixes.filter { it.id !in loaded }.map { mix -> async { mix to runCatching { tileFor(mix) }.getOrNull() } }.awaitAll()
            }.forEach { (mix, tile) ->
                if (tile != null) loaded[mix.id] = tile
            }
            _state.update { now -> if (now.tab != tab) now else now.copy(tiles = scoutDistinctArt(now.tiles.map { loaded[it.mix.id] ?: it.copy(loaded = true) }.filterNot { it.hidden })) }
        }
    }

    private suspend fun tileFor(mix: ScoutReadyMix): ScoutReadyTileState {
        val occasion = mix.occasion
        if (occasion != null) {
            val episodes = builder.occasionEpisodes(occasion).filter { !family || it.suitsFamily() }
            if (episodes.size < com.nuvio.tv.core.scout.SCOUT_OCCASION_FEWEST) return ScoutReadyTileState(mix, loaded = true, hidden = true)
            val chosen = com.nuvio.tv.core.scout.scoutOccasionChoice(episodes, family, com.nuvio.tv.core.scout.scoutReadyWeekSeed())
            val first = chosen.values.map { it.first() }
            val arts = first.mapNotNull { it.backdrop }.distinct()
            return ScoutReadyTileState(mix, arts.firstOrNull(), scoutDistinctNames(first.map { it.name }).take(NAMES_SHOWN), true, arts = arts)
        }
        val recipe = mix.recipe ?: return ScoutReadyTileState(mix, loaded = true)
        val preview = builder.preview(recipe.copy(family = family), mix.years)
        val arts = preview.picks.mapNotNull { it.backdrop }.distinct()
        return ScoutReadyTileState(mix, arts.firstOrNull(), scoutDistinctNames(preview.picks.map { it.name }).take(NAMES_SHOWN), true, arts = arts)
    }
}

/** Tiles side by side each take the first picture no tile before them shows, so one show doesn't front them all. */
internal fun scoutDistinctArt(tiles: List<ScoutReadyTileState>): List<ScoutReadyTileState> {
    val used = HashSet<String>()
    return tiles.map { tile ->
        val art = tile.arts.firstOrNull { it !in used } ?: tile.art
        art?.let(used::add)
        if (art == tile.art) tile else tile.copy(art = art)
    }
}

/** Two shows can share a name (a remake, a revival); the line names each name once. */
internal fun scoutDistinctNames(names: List<String>): List<String> = names.distinctBy { it.trim().lowercase() }

@Composable
internal fun readyMixName(mix: ScoutReadyMix): String = stringResource(mix.nameRes)

@Composable
private fun readyWhy(mix: ScoutReadyMix): String {
    val decade = mix.recipe?.decade?.takeIf { it != ScoutMixDecade.ANY && mix.tab == ScoutReadyTab.DECADES }
    return if (decade != null) {
        stringResource(mix.whyRes, decade.from ?: 0, decade.to ?: LocalDate.now().year)
    } else stringResource(mix.whyRes)
}

@Composable
private fun namesLine(names: List<String>): String {
    val locale = LocalConfiguration.current.locales[0]
    return calendarNameList(names, locale) { it.joinToString(", ") }
}

@Composable
private fun readyTileLine(tile: ScoutReadyTileState): String? {
    val occasion = tile.mix.occasion
    val today = LocalDate.now()
    if (occasion != null) {
        if (occasion.inSeason(today)) return stringResource(R.string.scout_premix_in_season)
        val month = occasion.nextStart(today).month.getDisplayName(TextStyle.FULL_STANDALONE, LocalConfiguration.current.locales[0])
        return stringResource(R.string.scout_premix_best_in, month)
    }
    if (tile.names.isEmpty()) return null
    return stringResource(R.string.scout_premix_and_more, namesLine(tile.names.take(TILE_NAMES)))
}

/** The hero for the focused ready-made mix, in place of the picks hero. */
@Composable
internal fun ScoutReadyHero(
    state: ScoutReadyState,
    focus: (String) -> Modifier,
    onPlay: (String) -> Unit,
    onSave: (String) -> Unit,
    onAdjust: (String) -> Unit
) {
    val tile = state.focusedTile ?: return
    val mix = tile.mix
    val saved = mix.id in state.savedIds
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
            Icon(Icons.Default.Shuffle, null, tint = NuvioTheme.colors.Secondary, modifier = Modifier.size(NuvioTheme.spacing.lg))
            Text(stringResource(R.string.scout_premix_kind), style = MaterialTheme.typography.titleSmall, color = NuvioTheme.colors.TextSecondary, maxLines = 1)
        }
        Text(
            text = readyMixName(mix),
            style = MaterialTheme.typography.headlineLarge,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(.62f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm), modifier = Modifier.focusGroup()) {
            ScoutChip(stringResource(R.string.hero_play), onClick = { onPlay(mix.id) }, modifier = focus(READY_FOCUS_PLAY), icon = {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
            })
            ScoutChip(
                stringResource(if (saved) R.string.scout_premix_saved else R.string.scout_premix_save),
                onClick = { onSave(mix.id) },
                enabled = !saved,
                modifier = focus(READY_FOCUS_SAVE),
                icon = { Icon(if (saved) Icons.Default.Check else Icons.Default.Add, null, Modifier.size(18.dp)) }
            )
            mix.recipe?.let { recipe ->
                ScoutChip(stringResource(R.string.scout_premix_adjust), onClick = { onAdjust(recipe.encode()) }, modifier = focus(READY_FOCUS_ADJUST), icon = {
                    Icon(Icons.Default.Tune, null, Modifier.size(18.dp))
                })
            }
        }
        Text(
            text = readyWhy(mix),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(.62f)
        )
        if (tile.names.isNotEmpty()) {
            Text(
                text = stringResource(
                    if (mix.occasion != null) R.string.scout_premix_desc_occasion else R.string.scout_premix_desc,
                    namesLine(tile.names)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(.5f)
            )
        }
        Text(
            text = when {
                mix.occasion == null -> stringResource(R.string.scout_premix_meta_weekly)
                mix.occasion.inSeason(LocalDate.now()) -> stringResource(R.string.scout_premix_meta_in_season)
                else -> stringResource(R.string.scout_premix_meta_any_time)
            },
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 1
        )
    }
}

/** The line, tabs and tiles under the hero. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ColumnScope.ScoutReadyRows(
    state: ScoutReadyState,
    tileHeight: Dp,
    focus: (String) -> Modifier,
    entry: (String) -> androidx.compose.ui.focus.FocusRequester,
    onTab: (ScoutReadyTab) -> Unit,
    onFocusMix: (String) -> Unit,
    onOpen: (String) -> Unit,
    onOwn: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = ScoutFocusRoom),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        Text(stringResource(R.string.scout_premix_pick), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
        ScoutChip(stringResource(R.string.scout_premix_own), onClick = onOwn, modifier = focus(READY_FOCUS_OWN), icon = {
            Icon(Icons.Default.Tune, null, Modifier.size(18.dp))
        })
    }
    val tabs = ScoutReadyTab.entries.map { ScoutTabItem(READY_TAB_PREFIX + it.name, stringResource(it.labelRes), it == state.tab) }
    ScoutTabs(
        items = tabs,
        selected = entry(READY_TAB_PREFIX + state.tab.name),
        entry = { entry(it.key) },
        focus = focus,
        onFocused = { item -> ScoutReadyTab.entries.firstOrNull { READY_TAB_PREFIX + it.name == item.key }?.let(onTab) },
        onClick = { }
    )
    CompositionLocalProvider(LocalBringIntoViewSpec provides NuvioScrollDefaults.smoothScrollSpec) {
        val list = key(state.tab) { rememberLazyListState() }
        LazyRow(
            state = list,
            modifier = Modifier.fillMaxWidth().height(tileHeight + NuvioTheme.spacing.md).scoutRowBounds(),
            contentPadding = PaddingValues(horizontal = ScoutFocusRoom, vertical = NuvioTheme.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)
        ) {
            items(state.tiles, key = { it.mix.id }) { tile ->
                ScoutWideTile(
                    picture = tile.art,
                    title = readyMixName(tile.mix),
                    line = readyTileLine(tile),
                    height = tileHeight,
                    onClick = { onOpen(tile.mix.id) },
                    onFocused = { onFocusMix(tile.mix.id) },
                    modifier = focus(READY_TILE_PREFIX + tile.mix.id)
                )
            }
        }
    }
}

/** Focus lands on the first tile when the page opens, as it does on the picks. */
@Composable
internal fun ScoutReadyFirstFocus(state: ScoutReadyState, entry: (String) -> androidx.compose.ui.focus.FocusRequester) {
    val first = state.tiles.firstOrNull()?.mix?.id ?: return
    LaunchedEffect(Unit) { entry(READY_TILE_PREFIX + first).requestFocusAfterFrames() }
}

