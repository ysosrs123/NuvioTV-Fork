package com.nuvio.tv.ui.screens.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.mediaserver.ServerSort
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsPickerOption
import com.nuvio.tv.ui.screens.settings.SettingsSingleChoiceDialog
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.NumberFormat

@Composable
fun GenreFoldersContent(
    state: GenreFoldersState,
    onSelectLibrary: (AvailableCatalog) -> Unit,
    onSelectSort: (ServerSort) -> Unit,
    onToggleGenre: (String) -> Unit,
    onCreate: () -> Unit
) {
    var dialog by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val sortNames = mapOf(
        ServerSort.ADDED to stringResource(R.string.server_library_sort_added),
        ServerSort.TITLE to stringResource(R.string.server_library_sort_title),
        ServerSort.RELEASED to stringResource(R.string.server_library_sort_released)
    )

    LaunchedEffect(Unit) {
        runCatching { firstFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = NuvioTheme.spacing.xxxl, start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl)
    ) {
        Text(
            text = stringResource(R.string.collections_editor_genre_folders),
            style = MaterialTheme.typography.headlineMedium.copy(textDirection = TextDirection.Content),
            color = NuvioTheme.colors.TextPrimary
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)
        ) {
            Column(
                modifier = Modifier.weight(0.4f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                SettingsActionRow(
                    title = stringResource(R.string.collections_editor_genre_folders_library),
                    subtitle = null,
                    value = state.library.catalogName,
                    onClick = { dialog = "library" },
                    modifier = Modifier.focusRequester(firstFocus)
                )
                SettingsActionRow(
                    title = stringResource(R.string.collections_editor_genre_folders_order),
                    subtitle = null,
                    value = sortNames.getValue(state.sort),
                    onClick = { dialog = "sort" }
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                val count = state.tickedCount
                NuvioButton(onClick = onCreate, enabled = state.canCreate) {
                    Text(stringResource(R.string.collections_editor_genre_folders_create, count))
                }
                if (count > GENRE_FOLDERS_LONG_ROW) {
                    Text(
                        text = stringResource(R.string.collections_editor_genre_folders_long, count),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.weight(0.6f),
                contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxxl),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                if (state.countsLoading) {
                    item(key = "counting") {
                        Text(
                            text = stringResource(R.string.collections_editor_genre_folders_counting),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextTertiary,
                            modifier = Modifier.padding(bottom = NuvioTheme.spacing.xs)
                        )
                    }
                }
                items(state.genres, key = { it.name }) { genre ->
                    SettingsToggleRow(
                        title = genre.name,
                        subtitle = genre.count?.let(numbers::format),
                        checked = genre.ticked,
                        onToggle = { onToggleGenre(genre.name) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    when (dialog) {
        "library" -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.collections_editor_genre_folders_library),
            options = state.libraries.map { SettingsPickerOption(it, it.catalogName) },
            selectedValue = state.library,
            onOptionSelected = {
                onSelectLibrary(it)
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        "sort" -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.collections_editor_genre_folders_order),
            options = ServerSort.entries.map { SettingsPickerOption(it, sortNames.getValue(it)) },
            selectedValue = state.sort,
            onOptionSelected = {
                onSelectSort(it)
                dialog = null
            },
            onDismiss = { dialog = null }
        )
    }
}
