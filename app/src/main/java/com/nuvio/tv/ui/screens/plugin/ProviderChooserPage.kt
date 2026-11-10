package com.nuvio.tv.ui.screens.plugin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.ui.components.SourceStatusFilterChip
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionButton
import com.nuvio.tv.ui.screens.settings.isV2Settings
import com.nuvio.tv.ui.screens.settings.settingsItemColor
import com.nuvio.tv.ui.screens.settings.settingsItemShape
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.Locale

@Composable
private fun languageName(code: String): String {
    val appLocale = LocalConfiguration.current.locales[0]
    val name = Locale.forLanguageTag(code).getDisplayLanguage(appLocale)
    return if (name.isBlank() || name.equals(code, ignoreCase = true)) code.uppercase(appLocale)
    else name.replaceFirstChar { it.titlecase(appLocale) }
}

@Composable
private fun typeLabel(type: ProviderTypeFilter): String = stringResource(
    when (type) {
        ProviderTypeFilter.ALL -> R.string.plugin_choose_type_all
        ProviderTypeFilter.FILMS -> R.string.plugin_choose_type_films
        ProviderTypeFilter.SERIES -> R.string.plugin_choose_type_series
        ProviderTypeFilter.ANIME -> R.string.plugin_choose_type_anime
        ProviderTypeFilter.LIVE -> R.string.plugin_choose_type_live
    }
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProviderChooserPage(
    state: ProviderChooserState,
    onEvent: (PluginUiEvent) -> Unit
) {
    val firstRowFocus = remember { FocusRequester() }
    BackHandler { onEvent(PluginUiEvent.ChooserDismiss) }
    LaunchedEffect(state.repoId) { firstRowFocus.requestFocusAfterFrames() }
    val shown = state.shown

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)
        ) {
            Column(
                modifier = Modifier
                    .weight(0.38f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                Text(
                    text = stringResource(R.string.plugin_choose_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary
                )
                Text(
                    text = stringResource(R.string.plugin_choose_subtitle, state.repoName, state.providers.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
                if (state.types.isNotEmpty()) {
                    FilterHeading(stringResource(R.string.plugin_choose_type_heading))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                    ) {
                        state.types.forEach { type ->
                            SourceStatusFilterChip(
                                name = typeLabel(type),
                                isSelected = state.type == type,
                                onClick = { onEvent(PluginUiEvent.ChooserType(type)) }
                            )
                        }
                    }
                }
                if (state.languages.isNotEmpty()) {
                    FilterHeading(stringResource(R.string.plugin_choose_language_heading))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                    ) {
                        SourceStatusFilterChip(
                            name = stringResource(R.string.plugin_choose_type_all),
                            isSelected = state.language == null,
                            onClick = { onEvent(PluginUiEvent.ChooserLanguage(null)) }
                        )
                        state.languages.forEach { code ->
                            SourceStatusFilterChip(
                                name = languageName(code),
                                isSelected = state.language == code,
                                onClick = { onEvent(PluginUiEvent.ChooserLanguage(code)) }
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    SettingsDialogActionButton(
                        text = stringResource(R.string.plugin_choose_select_shown),
                        onClick = { onEvent(PluginUiEvent.ChooserSelectShown) }
                    )
                    SettingsDialogActionButton(
                        text = stringResource(R.string.plugin_choose_clear),
                        onClick = { onEvent(PluginUiEvent.ChooserClear) }
                    )
                }
                Text(
                    text = stringResource(R.string.plugin_choose_selected_count, state.selected.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    modifier = Modifier.padding(top = NuvioTheme.spacing.lg)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    SettingsDialogActionButton(
                        text = stringResource(R.string.plugin_choose_keep_off),
                        onClick = { onEvent(PluginUiEvent.ChooserKeepAllOff) }
                    )
                    SettingsDialogActionButton(
                        text = stringResource(R.string.plugin_choose_turn_on, state.selected.size),
                        onClick = { onEvent(PluginUiEvent.ChooserApply) },
                        primary = true,
                        enabled = state.selected.isNotEmpty()
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(0.62f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xs)
            ) {
                itemsIndexed(shown, key = { _, item -> item.id }) { index, provider ->
                    ProviderChoiceRow(
                        provider = provider,
                        checked = provider.id in state.selected,
                        onToggle = { onEvent(PluginUiEvent.ChooserToggle(provider.id)) },
                        modifier = if (index == 0) Modifier.focusRequester(firstRowFocus) else Modifier
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = NuvioTheme.colors.TextSecondary,
        modifier = Modifier.padding(top = NuvioTheme.spacing.sm)
    )
}

@Composable
private fun ProviderChoiceRow(
    provider: ScraperInfo,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onToggle,
        modifier = modifier
            .fillMaxWidth()
            .pluginFocus()
            .semantics {
                role = Role.Checkbox
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = settingsItemColor(NuvioTheme.colors.BackgroundCard),
            focusedContainerColor = pluginFocusedFill()
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = pluginFocusedBorder(settingsItemShape(NuvioTheme.radii.md))
        ),
        shape = ClickableSurfaceDefaults.shape(settingsItemShape(NuvioTheme.radii.md)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (isV2Settings()) 1f else 1.01f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(
                        if (checked) NuvioTheme.colors.Secondary else NuvioTheme.colors.Surface,
                        settingsItemShape(NuvioTheme.radii.xs)
                    )
                    .border(
                        NuvioTheme.spacing.hairline,
                        if (checked) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextTertiary,
                        settingsItemShape(NuvioTheme.radii.xs)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (checked) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = NuvioTheme.colors.OnSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                text = provider.name,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            provider.supportedTypes.distinct().forEach { TypeBadge(type = it) }
            provider.contentLanguage.firstOrNull()?.let { code ->
                Text(
                    text = languageName(code),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1
                )
            }
        }
    }
}
