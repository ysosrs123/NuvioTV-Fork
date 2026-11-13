package com.nuvio.tv.ui.screens.scout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.scout.SCOUT_READY_SHOWS
import com.nuvio.tv.core.scout.ScoutMixBlend
import com.nuvio.tv.core.scout.ScoutMixDecade
import com.nuvio.tv.core.scout.ScoutMixFeel
import com.nuvio.tv.core.scout.ScoutMixFrom
import com.nuvio.tv.core.scout.ScoutMixKind
import com.nuvio.tv.core.scout.ScoutMixPreview
import com.nuvio.tv.core.scout.ScoutMixRecipe
import com.nuvio.tv.core.scout.ScoutReadyMixBuilder
import com.nuvio.tv.core.scout.ScoutWidened
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val SETTLE_MS = 500L
private const val LINE_PREFIX = "own_"
private const val FOCUS_MIX_IT = "own_mix_it"
private val LABEL_WIDTH = 180.dp
private val POSTER_HEIGHT = 186.dp

data class ScoutMixOwnState(
    val recipe: ScoutMixRecipe = ScoutMixRecipe(),
    val preview: ScoutMixPreview? = null,
    val finding: Boolean = true,
    val making: Boolean = false
)

@HiltViewModel
class ScoutMixOwnViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val builder: ScoutReadyMixBuilder
) : ViewModel() {
    private val _state = MutableStateFlow(ScoutMixOwnState(recipe = ScoutMixRecipe.decode(savedStateHandle.get<String>("recipe")) ?: ScoutMixRecipe()))
    val state: StateFlow<ScoutMixOwnState> = _state.asStateFlow()
    private val _made = Channel<String>(Channel.CONFLATED)
    val made = _made.receiveAsFlow()
    private var previewJob: Job? = null

    init {
        findShows(settle = false)
    }

    fun choose(recipe: ScoutMixRecipe) {
        if (recipe == _state.value.recipe) return
        _state.update { it.copy(recipe = recipe) }
        findShows(settle = true)
    }

    fun change(edit: (ScoutMixRecipe) -> ScoutMixRecipe) = choose(edit(_state.value.recipe))

    fun startOver() = choose(ScoutMixRecipe(family = _state.value.recipe.family))

    fun mixIt(name: String) {
        if (_state.value.making) return
        _state.update { it.copy(making = true) }
        viewModelScope.launch {
            val mix = builder.own(_state.value.recipe, name)
            _state.update { it.copy(making = false) }
            if (mix != null) _made.trySend(mix.id)
        }
    }

    private fun findShows(settle: Boolean) {
        previewJob?.cancel()
        _state.update { it.copy(finding = true) }
        previewJob = viewModelScope.launch {
            if (settle) delay(SETTLE_MS)
            val recipe = _state.value.recipe
            val found = runCatching { builder.preview(recipe) }.getOrNull()
            _state.update { if (it.recipe == recipe) it.copy(preview = found, finding = false) else it }
        }
    }
}

private data class OwnLine(val key: String, val label: Int, val options: List<Pair<String, Int>>, val chosen: String, val apply: (ScoutMixRecipe, String) -> ScoutMixRecipe)

private fun ownLines(recipe: ScoutMixRecipe): List<OwnLine> = listOf(
    OwnLine("decade", R.string.scout_premix_own_decade, ScoutMixDecade.entries.map { it.code to it.labelRes }, recipe.decade.code) { r, c ->
        r.copy(decade = ScoutMixDecade.entries.first { it.code == c })
    },
    OwnLine("kind", R.string.scout_premix_own_kind, ScoutMixKind.entries.map { it.code to it.labelRes }, recipe.kind.code) { r, c ->
        r.copy(kind = ScoutMixKind.entries.first { it.code == c })
    },
    OwnLine("mood", R.string.scout_premix_own_mood, ScoutMixFeel.entries.filter { !(recipe.family && it == ScoutMixFeel.DARK) }.map { it.code to it.labelRes }, recipe.feel.code) { r, c ->
        r.copy(feel = ScoutMixFeel.entries.first { it.code == c })
    },
    OwnLine("from", R.string.scout_premix_own_from, ScoutMixFrom.entries.map { it.code to it.labelRes }, recipe.from.code) { r, c ->
        r.copy(from = ScoutMixFrom.entries.first { it.code == c })
    },
    OwnLine("blend", R.string.scout_premix_own_blend, ScoutMixBlend.entries.map { it.code to it.labelRes }, recipe.blend.code) { r, c ->
        r.copy(blend = ScoutMixBlend.entries.first { it.code == c })
    }
)

/** The mix's name: the choices made, or the page's own name when everything is left open. */
@Composable
private fun ownMixName(recipe: ScoutMixRecipe): String {
    val parts = buildList {
        if (recipe.decade != ScoutMixDecade.ANY) add(stringResource(recipe.decade.labelRes))
        if (recipe.kind != ScoutMixKind.ANY) add(stringResource(recipe.kind.labelRes))
        if (recipe.feel != ScoutMixFeel.ANY) add(stringResource(recipe.feel.labelRes))
        if (recipe.from != ScoutMixFrom.ANYWHERE) add(stringResource(recipe.from.labelRes))
    }
    return if (parts.isEmpty()) stringResource(R.string.scout_premix_own) else parts.joinToString(" • ")
}

@Composable
private fun ownNote(state: ScoutMixOwnState): String? {
    if (state.finding) return stringResource(R.string.scout_premix_finding)
    val preview = state.preview ?: return null
    if (preview.picks.isEmpty()) return stringResource(R.string.scout_premix_none)
    val count = preview.picks.size.coerceAtMost(SCOUT_READY_SHOWS)
    val goes = pluralStringResource(R.plurals.scout_premix_goes_in, count, count)
    val why = when {
        preview.tooFew -> stringResource(R.string.scout_premix_too_few)
        preview.widened == ScoutWidened.DECADE -> stringResource(R.string.scout_premix_widened_decade)
        preview.widened == ScoutWidened.VOTES -> stringResource(R.string.scout_premix_widened_votes)
        preview.widened == ScoutWidened.MOOD -> stringResource(R.string.scout_premix_widened_mood)
        else -> null
    }
    return listOfNotNull(goes, why).joinToString(". ")
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ScoutMixOwnScreen(
    onBack: () -> Unit,
    onMade: (mixId: String) -> Unit,
    viewModel: ScoutMixOwnViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    BackHandler { onBack() }
    LaunchedEffect(Unit) { viewModel.made.collect { onMade(it) } }
    LaunchedEffect(Unit) { requester(LINE_PREFIX + "decade_" + state.recipe.decade.code).requestFocusAfterFrames() }

    val recipe = state.recipe
    val picks = state.preview?.picks.orEmpty().take(SCOUT_READY_SHOWS)
    val name = ownMixName(recipe)
    BoxWithConstraints(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        ScoutBackdrop(picks.firstNotNullOfOrNull { it.backdrop }, wide = true)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = NuvioTheme.spacing.screen.overscanHorizontal - ScoutFocusRoom,
                    end = NuvioTheme.spacing.screen.overscanHorizontal - ScoutFocusRoom,
                    top = NuvioTheme.spacing.lg
                ),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
        ) {
            Column(Modifier.padding(horizontal = ScoutFocusRoom)) {
                Text(stringResource(R.string.scout_premix_own), style = MaterialTheme.typography.headlineLarge, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
                Text(stringResource(R.string.scout_premix_own_sub), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 2)
            }
            ownLines(recipe).forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(line.label),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = ScoutFocusRoom).width(LABEL_WIDTH)
                    )
                    val items = line.options.map { (code, label) -> ScoutTabItem(LINE_PREFIX + line.key + "_" + code, stringResource(label), code == line.chosen) }
                    ScoutTabs(
                        items = items,
                        selected = requester(LINE_PREFIX + line.key + "_" + line.chosen),
                        entry = { requester(it.key) },
                        focus = { Modifier.focusRequester(requester(it)) },
                        onFocused = { item -> viewModel.change { line.apply(it, item.key.removePrefix(LINE_PREFIX + line.key + "_")) } },
                        onClick = { }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ScoutFocusRoom),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                    modifier = Modifier.focusGroup().then(if (picks.isNotEmpty()) Modifier.focusRestorer(requester(FOCUS_MIX_IT)) else Modifier)
                ) {
                    ScoutChip(
                        label = stringResource(R.string.scout_premix_mix_it),
                        onClick = { viewModel.mixIt(name) },
                        enabled = picks.isNotEmpty() && !state.making,
                        modifier = Modifier.focusRequester(requester(FOCUS_MIX_IT)),
                        icon = { Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp)) }
                    )
                    ScoutChip(
                        label = stringResource(R.string.scout_premix_start_over),
                        onClick = viewModel::startOver,
                        icon = { Icon(Icons.Default.Replay, null, Modifier.size(18.dp)) }
                    )
                }
                ownNote(state)?.let { note ->
                    Text(note, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(NuvioTheme.spacing.xs))
            LazyRow(
                contentPadding = PaddingValues(horizontal = ScoutFocusRoom),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                userScrollEnabled = false
            ) {
                items(picks, key = { it.key }) { pick ->
                    AsyncImage(
                        model = pick.poster,
                        contentDescription = pick.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .height(POSTER_HEIGHT)
                            .width(POSTER_HEIGHT * 2f / 3f)
                            .clip(RoundedCornerShape(NuvioTheme.radii.md))
                            .background(NuvioTheme.colors.BackgroundCard)
                    )
                }
            }
        }
    }
}
