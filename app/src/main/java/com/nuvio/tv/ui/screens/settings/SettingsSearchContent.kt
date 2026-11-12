package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.scout.ask.MicButton
import com.nuvio.tv.ui.screens.scout.ask.QuestionField
import com.nuvio.tv.ui.screens.scout.ask.rememberAskVoice
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import java.util.Locale

private class SettingsSearchItem(
    val doc: SettingsSearchDoc,
    val entry: SettingsSearchEntry,
    val title: String,
    val explanation: String,
    val path: String
)

private fun Context.searchItems(entries: List<SettingsSearchEntry>): List<SettingsSearchItem> {
    val english = createConfigurationContext(
        Configuration(resources.configuration).apply { setLocale(Locale.ENGLISH) }
    )
    fun Context.text(id: Int): String = if (id == 0) "" else getString(id)
    return entries.mapIndexed { index, entry ->
        val title = entry.literalTitle ?: text(entry.title)
        val explanation = text(entry.explanation)
        val section = when {
            entry.qualityRules -> text(R.string.autoplay_quality_rules_title)
            entry.playback != null -> text(entry.playback.title)
            entry.layout != null -> text(entry.layout.title)
            entry.integration == IntegrationSettingsSection.Debrid -> text(R.string.debrid_title)
            entry.integration == IntegrationSettingsSection.Tmdb -> "TMDB"
            entry.integration == IntegrationSettingsSection.MdbList -> text(R.string.mdblist_title)
            entry.integration == IntegrationSettingsSection.AnimeSkip -> "Anime-Skip"
            else -> null
        }
        val path = listOfNotNull(text(entry.category.searchTitle()), section).joinToString(" › ")
        SettingsSearchItem(
            doc = SettingsSearchDoc(
                key = index,
                title = normalizeSettingsSearchText(title),
                altTitle = normalizeSettingsSearchText(entry.literalTitle ?: english.text(entry.title)),
                explanation = normalizeSettingsSearchText(explanation),
                altExplanation = normalizeSettingsSearchText(english.text(entry.explanation)),
                keywords = normalizeSettingsSearchText(entry.keywords.joinToString(" ")),
                path = normalizeSettingsSearchText(path)
            ),
            entry = entry,
            title = title,
            explanation = explanation,
            path = path
        )
    }
}

private fun underlined(text: String, query: String): AnnotatedString = buildAnnotatedString {
    append(text)
    query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { word ->
        var at = text.indexOf(word, ignoreCase = true)
        while (at >= 0) {
            addStyle(SpanStyle(textDecoration = TextDecoration.Underline), at, at + word.length)
            at = text.indexOf(word, at + word.length, ignoreCase = true)
        }
    }
}

/** Down leaves the search box for the results, whatever sent the key; the text box itself only does that for a real remote. */
internal fun leavesSearchFieldDown(keyCode: Int, action: Int, hasResults: Boolean): Boolean =
    hasResults && action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN

@Composable
internal fun SettingsSearchContent(
    query: String,
    onQueryChange: (String) -> Unit,
    visibleCategories: List<SettingsCategory>,
    isEssentialMode: Boolean,
    listState: LazyListState,
    returnKey: Int?,
    initialFocusRequester: FocusRequester?,
    onOpen: (SettingsSearchEntry, String, Int) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val isV2 = LocalV2Appearance.current != null
    val inReach = remember(visibleCategories, isV2) {
        SETTINGS_SEARCH_ENTRIES.filter { it.category in visibleCategories && it.shownIn(isV2) }
    }
    val items = remember(context, inReach) { context.searchItems(inReach) }
    val found = remember(items, query) {
        val byKey = items.associateBy { it.doc.key }
        searchSettings(items.map { it.doc }, query).mapNotNull { byKey[it.key] }
    }
    val results = found.filter { it.entry.availableIn(isEssentialMode) }
    val hiddenInAdvanced = if (isEssentialMode) found.count { it.entry.modes == SettingsSearchModes.ADVANCED } else 0
    val voice = rememberAskVoice(onHeard = onQueryChange)
    LaunchedEffect(voice.words) {
        if (voice.listening && voice.words.isNotBlank()) onQueryChange(voice.words)
    }
    val returnIndex = results.indexOfFirst { it.doc.key == returnKey }
    LaunchedEffect(returnKey, query) {
        runCatching { listState.scrollToItem(returnIndex.coerceAtLeast(0)) }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        if (query.isBlank()) {
            SettingsDetailHeader(
                title = stringResource(R.string.settings_search_title),
                subtitle = stringResource(R.string.settings_search_subtitle)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            QuestionField(
                value = query,
                onValueChange = onQueryChange,
                onSubmit = { focusManager.moveFocus(FocusDirection.Down) },
                placeholder = stringResource(R.string.settings_search_title),
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (leavesSearchFieldDown(native.keyCode, native.action, results.isNotEmpty())) {
                            keyboard?.hide()
                            focusManager.moveFocus(FocusDirection.Down)
                        } else {
                            false
                        }
                    }
                    .then(
                        if (initialFocusRequester != null && returnIndex < 0) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        }
                    )
            )
            if (voice.available) {
                MicButton(voice.listening, voice.start, Modifier.size(56.dp))
            }
        }
        when {
            query.isBlank() -> Text(
                text = voice.problem ?: stringResource(R.string.settings_search_try),
                style = MaterialTheme.typography.bodyMedium,
                color = if (voice.problem != null) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary
            )
            results.isEmpty() && hiddenInAdvanced == 0 -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.settings_search_none, query.trim()),
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.settings_search_none_tip),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = NuvioTheme.spacing.xxs)
                    )
                }
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                items(results, key = { it.doc.key }) { item ->
                    SettingsActionRow(
                        title = item.title,
                        annotatedTitle = underlined(item.title, query),
                        subtitle = null,
                        subtitleContent = { _, contentAlpha ->
                            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
                                if (item.explanation.isNotBlank()) {
                                    Text(
                                        text = underlined(item.explanation, query),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = NuvioTheme.colors.TextSecondary.copy(alpha = contentAlpha),
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = item.path,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = NuvioTheme.colors.TextTertiary.copy(alpha = contentAlpha),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        onClick = { onOpen(item.entry, item.title, item.doc.key) },
                        jumpAnchor = false,
                        modifier = if (initialFocusRequester != null && item.doc.key == returnKey) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                }
                item {
                    Text(
                        text = if (hiddenInAdvanced > 0) {
                            stringResource(R.string.settings_search_more_advanced, hiddenInAdvanced)
                        } else {
                            stringResource(R.string.settings_search_results, results.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = NuvioTheme.spacing.xs)
                    )
                }
            }
        }
    }
}
