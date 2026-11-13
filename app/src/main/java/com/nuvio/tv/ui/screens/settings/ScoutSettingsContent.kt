@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
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
import androidx.compose.material.icons.filled.Translate
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
    val trackerSignedIn by viewModel.trackerSignedIn.collectAsStateWithLifecycle()
    val settings = profile.settings
    var showThumbs by rememberSaveable { mutableStateOf(false) }
    var showLeaveOut by remember { mutableStateOf(false) }
    var showLeaveOutLanguages by remember { mutableStateOf(false) }
    var genres by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    LaunchedEffect(Unit) { genres = viewModel.genres() }

    BackHandler(enabled = showThumbs) { showThumbs = false }

    if (showLeaveOut) {
        ScoutLeaveOutDialog(
            title = stringResource(R.string.scout_leave_out),
            choices = genres.entries.sortedBy { it.value }.map { it.key.toString() to it.value },
            selected = settings.leaveOutGenres.map(Int::toString).toSet(),
            onToggle = { key ->
                val id = key.toInt()
                viewModel.updateSettings {
                    it.copy(leaveOutGenres = if (id in it.leaveOutGenres) it.leaveOutGenres - id else it.leaveOutGenres + id)
                }
            },
            onDismiss = { showLeaveOut = false }
        )
    }
    val languageNames = scoutLeaveOutLanguageNames()
    if (showLeaveOutLanguages) {
        ScoutLeaveOutDialog(
            title = stringResource(R.string.scout_leave_out_languages),
            choices = languageNames,
            selected = settings.leaveOutLanguages,
            onToggle = { id ->
                viewModel.updateSettings {
                    it.copy(leaveOutLanguages = if (id in it.leaveOutLanguages) it.leaveOutLanguages - id else it.leaveOutLanguages + id)
                }
            },
            onDismiss = { showLeaveOutLanguages = false }
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
            SettingsActionRow(
                title = stringResource(R.string.scout_leave_out_languages),
                leadingIcon = Icons.Default.Translate,
                subtitle = stringResource(R.string.scout_leave_out_languages_sub),
                value = languageNames.filter { it.first in settings.leaveOutLanguages }.joinToString(", ") { it.second }
                    .ifBlank { stringResource(R.string.scout_leave_out_none) },
                onClick = { showLeaveOutLanguages = true }
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
                SettingsToggleRow(
                    title = stringResource(R.string.scout_send_ratings),
                    leadingIcon = Icons.Default.CloudUpload,
                    subtitle = stringResource(R.string.scout_send_ratings_sub),
                    checked = settings.sendToTrackers,
                    onToggle = { viewModel.updateSettings { it.copy(sendToTrackers = !it.sendToTrackers) } }
                )
                if (settings.sendToTrackers && !trackerSignedIn) {
                    SettingsNote(text = stringResource(R.string.scout_send_ratings_none))
                }
                SettingsToggleRow(
                    title = stringResource(R.string.scout_use_ratings),
                    leadingIcon = Icons.Default.CloudDownload,
                    subtitle = stringResource(R.string.scout_use_ratings_sub),
                    checked = settings.useTrackerRatings,
                    onToggle = { viewModel.updateSettings { it.copy(useTrackerRatings = !it.useTrackerRatings) } }
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

/** Indian languages first, then the others by their name in the app's language. */
@Composable
private fun scoutLeaveOutLanguageNames(): List<Pair<String, String>> {
    val indian = stringResource(R.string.scout_language_indian)
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    return remember(indian, locale) {
        val others = com.nuvio.tv.core.scout.SCOUT_LEAVE_OUT_LANGUAGES
            .filter { it.id != com.nuvio.tv.core.scout.SCOUT_LANGUAGE_INDIAN }
            .map { choice ->
                choice.id to java.util.Locale(choice.id).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
            }
            .sortedBy { it.second }
        listOf(com.nuvio.tv.core.scout.SCOUT_LANGUAGE_INDIAN to indian) + others
    }
}

@Composable
private fun ScoutLeaveOutDialog(
    title: String,
    choices: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    NuvioDialog(onDismiss = onDismiss, title = title) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            choices.forEachIndexed { index, (id, name) ->
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
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.scout_your_thumbs),
            subtitle = stringResource(R.string.scout_your_thumbs_page_sub)
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(entries, key = { _, entry -> entry.title.id }) { index, entry ->
                    SettingsActionRow(
                        title = entry.title.name,
                        subtitle = entry.source?.let { stringResource(R.string.scout_thumb_from, trackerName(it)) },
                        value = stringResource(entry.rating.labelRes()),
                        onClick = { editing = entry },
                        modifier = Modifier
                            .focusRequester(rows.getOrPut(entry.title.id) { FocusRequester() })
                            .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                    )
                }
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

private fun trackerName(source: String): String =
    when (com.nuvio.tv.core.tracking.TrackingProviderId.fromStorage(source)) {
        com.nuvio.tv.core.tracking.TrackingProviderId.TRAKT -> "Trakt"
        com.nuvio.tv.core.tracking.TrackingProviderId.SIMKL -> "Simkl"
        com.nuvio.tv.core.tracking.TrackingProviderId.MDBLIST -> "MDBList"
        com.nuvio.tv.core.tracking.TrackingProviderId.WETRAKR -> "WeTrakr"
        com.nuvio.tv.core.tracking.TrackingProviderId.FLOPPY -> "Floppy"
        null -> source
    }
