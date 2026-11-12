@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Recommend
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.ThumbsUpDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.domain.model.ScoutRating
import com.nuvio.tv.domain.model.ScoutRatingEntry
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.scout.ScoutRateRow
import com.nuvio.tv.ui.scout.ScoutRatingViewModel
import com.nuvio.tv.ui.scout.labelRes

@Composable
internal fun ScoutSettingsContent(
    initialFocusRequester: FocusRequester?,
    onOpenTaste: () -> Unit = {},
    onOpenRoute: (String) -> Unit = {},
    viewModel: ScoutRatingViewModel = hiltViewModel(),
    askAccess: com.nuvio.tv.ui.screens.scout.ask.AskAccessViewModel = hiltViewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val askHasKey by askAccess.hasKey.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val settings = profile.settings
    var showThumbs by rememberSaveable { mutableStateOf(false) }
    var showLeaveOut by remember { mutableStateOf(false) }
    var genres by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    LaunchedEffect(Unit) { genres = viewModel.genres() }

    BackHandler(enabled = showThumbs) { showThumbs = false }

    if (showLeaveOut) {
        ScoutLeaveOutDialog(
            genres = genres,
            selected = settings.leaveOutGenres,
            onToggle = { id ->
                viewModel.updateSettings {
                    it.copy(leaveOutGenres = if (id in it.leaveOutGenres) it.leaveOutGenres - id else it.leaveOutGenres + id)
                }
            },
            onDismiss = { showLeaveOut = false }
        )
    }

    if (showThumbs) {
        ScoutThumbsPage(
            entries = profile.ratings.values.sortedByDescending { it.ratedAt },
            onRate = { entry, rating -> viewModel.setRating(entry.title, rating) }
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.scout_title),
            subtitle = stringResource(R.string.scout_settings_subtitle)
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionRow(
                title = stringResource(R.string.scout_your_taste),
                leadingIcon = Icons.Default.Insights,
                subtitle = stringResource(R.string.scout_your_taste_sub),
                onClick = onOpenTaste,
                modifier = if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier
            )
            SettingsActionRow(
                title = stringResource(R.string.scout_picks_quick_title),
                leadingIcon = Icons.Default.Favorite,
                subtitle = stringResource(R.string.scout_picks_quick_row_sub),
                onClick = { onOpenRoute(com.nuvio.tv.ui.navigation.Screen.ScoutQuickStart.route) }
            )
            if (askHasKey) {
                SettingsActionRow(
                    title = stringResource(R.string.scout_picks_words_title),
                    leadingIcon = Icons.Default.EditNote,
                    subtitle = stringResource(R.string.scout_picks_words_row_sub),
                    onClick = { onOpenRoute(com.nuvio.tv.ui.navigation.Screen.ScoutWords.route) }
                )
            }
        }
        SettingsGroupCard(title = stringResource(R.string.scout_settings_on_home)) {
            SettingsToggleRow(
                title = stringResource(R.string.scout_row_picked),
                leadingIcon = Icons.Default.Recommend,
                subtitle = stringResource(R.string.scout_row_picked_sub),
                checked = settings.pickedRow,
                onToggle = { viewModel.updateSettings { it.copy(pickedRow = !it.pickedRow) } }
            )
            SettingsToggleRow(
                title = stringResource(R.string.scout_row_genre),
                leadingIcon = Icons.Default.Category,
                subtitle = stringResource(R.string.scout_row_genre_sub),
                checked = settings.genreRows,
                onToggle = { viewModel.updateSettings { it.copy(genreRows = !it.genreRows) } }
            )
            SettingsToggleRow(
                title = stringResource(R.string.scout_row_because),
                leadingIcon = Icons.Default.History,
                subtitle = stringResource(R.string.scout_row_because_sub),
                checked = settings.becauseRow,
                onToggle = { viewModel.updateSettings { it.copy(becauseRow = !it.becauseRow) } }
            )
            SettingsToggleRow(
                title = stringResource(R.string.scout_row_gems),
                leadingIcon = Icons.Default.Diamond,
                subtitle = stringResource(R.string.scout_row_gems_sub),
                checked = settings.gemsRow,
                onToggle = { viewModel.updateSettings { it.copy(gemsRow = !it.gemsRow) } }
            )
            SettingsToggleRow(
                title = stringResource(R.string.scout_row_tiles),
                leadingIcon = Icons.Default.GridView,
                subtitle = stringResource(R.string.scout_row_tiles_sub),
                checked = settings.tilesRow,
                onToggle = { viewModel.updateSettings { it.copy(tilesRow = !it.tilesRow) } }
            )
        }
        SettingsGroupCard(title = stringResource(R.string.scout_settings_tune)) {
            SettingsActionRow(
                title = stringResource(R.string.scout_leave_out),
                leadingIcon = Icons.Default.Block,
                subtitle = stringResource(R.string.scout_leave_out_sub),
                value = settings.leaveOutGenres.mapNotNull(genres::get).sorted().joinToString(", ")
                    .ifBlank { stringResource(R.string.scout_leave_out_none) },
                onClick = { showLeaveOut = true },
                enabled = genres.isNotEmpty()
            )
        }
        SettingsGroupCard(title = stringResource(R.string.scout_question_title)) {
            SettingsActionRow(
                title = stringResource(if (askHasKey) R.string.scout_question_settings_change else R.string.scout_question_settings_add),
                leadingIcon = Icons.Default.QuestionAnswer,
                subtitle = stringResource(R.string.scout_question_settings_sub),
                value = stringResource(if (askHasKey) R.string.scout_question_settings_on else R.string.scout_question_settings_none),
                onClick = {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("nuvio://scout-ask"))
                            .setPackage(context.packageName)
                    )
                }
            )
        }
        SettingsGroupCard(title = stringResource(R.string.scout_settings_ratings)) {
            SettingsToggleRow(
                title = stringResource(R.string.scout_ratings_enabled),
                leadingIcon = Icons.Default.ThumbsUpDown,
                subtitle = stringResource(R.string.scout_ratings_enabled_sub),
                checked = settings.ratingsEnabled,
                onToggle = { viewModel.updateSettings { it.copy(ratingsEnabled = !it.ratingsEnabled) } }
            )
            if (settings.ratingsEnabled) {
                SettingsToggleRow(
                    title = stringResource(R.string.scout_ask_enabled),
                    leadingIcon = Icons.Default.RateReview,
                    subtitle = stringResource(R.string.scout_ask_enabled_sub),
                    checked = settings.askAfterFilm,
                    onToggle = { viewModel.updateSettings { it.copy(askAfterFilm = !it.askAfterFilm) } }
                )
                SettingsActionRow(
                    title = stringResource(R.string.scout_your_thumbs),
                    leadingIcon = Icons.Default.ThumbUp,
                    subtitle = pluralStringResource(R.plurals.scout_your_thumbs_sub, profile.ratings.size, profile.ratings.size),
                    onClick = { showThumbs = true },
                    enabled = profile.ratings.isNotEmpty()
                )
            } else {
                SettingsNote(text = stringResource(R.string.scout_ratings_off_note))
            }
        }
    }
}

@Composable
private fun ScoutLeaveOutDialog(
    genres: Map<Int, String>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.scout_leave_out)) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            genres.entries.sortedBy { it.value }.forEachIndexed { index, (id, name) ->
                SettingsToggleRow(
                    title = name,
                    subtitle = null,
                    checked = id in selected,
                    onToggle = { onToggle(id) },
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier
                )
            }
        }
    }
    LaunchedEffect(Unit) { first.requestFocusAfterFrames() }
}

@Composable
internal fun ScoutThumbsPage(
    entries: List<ScoutRatingEntry>,
    onRate: (ScoutRatingEntry, ScoutRating?) -> Unit
) {
    var editing by remember { mutableStateOf<ScoutRatingEntry?>(null) }
    val first = remember { FocusRequester() }
    val rows = remember { HashMap<String, FocusRequester>() }
    var focusAfterClear by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(entries.size) {
        val target = focusAfterClear ?: return@LaunchedEffect
        focusAfterClear = null
        rows[target]?.requestFocusAfterFrames()
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.scout_your_thumbs),
            subtitle = stringResource(R.string.scout_your_thumbs_page_sub)
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            entries.forEachIndexed { index, entry ->
                SettingsActionRow(
                    title = entry.title.name,
                    subtitle = null,
                    value = stringResource(entry.rating.labelRes()),
                    onClick = { editing = entry },
                    modifier = Modifier
                        .focusRequester(rows.getOrPut(entry.title.id) { FocusRequester() })
                        .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                )
            }
        }
    }
    LaunchedEffect(Unit) { first.requestFocusAfterFrames() }
    editing?.let { entry ->
        val focus = remember(entry) { FocusRequester() }
        NuvioDialog(onDismiss = { editing = null }, title = entry.title.name) {
            ScoutRateRow(
                current = entry.rating,
                onRate = {
                    if (it == null) {
                        val at = entries.indexOf(entry)
                        focusAfterClear = (entries.getOrNull(at + 1) ?: entries.getOrNull(at - 1))?.title?.id
                    }
                    onRate(entry, it)
                    editing = null
                },
                firstFocus = focus
            )
        }
        LaunchedEffect(entry) { focus.requestFocusAfterFrames() }
    }
}
