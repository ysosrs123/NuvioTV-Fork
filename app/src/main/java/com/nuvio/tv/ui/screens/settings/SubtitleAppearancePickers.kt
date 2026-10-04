@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.data.local.SubtitleEdgeStyle
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.ui.theme.getFontFamily

@Composable
internal fun subtitleFontName(font: AppFont?): String = font?.displayName ?: stringResource(R.string.subtitle_font_renderer_default)

@Composable
internal fun subtitleEdgeName(edge: SubtitleEdgeStyle): String = stringResource(when (edge) {
    SubtitleEdgeStyle.NONE -> R.string.action_none
    SubtitleEdgeStyle.OUTLINE -> R.string.subtitle_style_outline
    SubtitleEdgeStyle.DROP_SHADOW -> R.string.subtitle_edge_drop_shadow
})

@Composable
internal fun SubtitleFontDialog(selected: AppFont?, onSelected: (AppFont?) -> Unit, onDismiss: () -> Unit) {
    SettingsSingleChoiceDialog<AppFont?>(
        title = stringResource(R.string.subtitle_font_family),
        options = listOf(SettingsPickerOption<AppFont?>(null, subtitleFontName(null))) +
            AppFont.entries.map { SettingsPickerOption<AppFont?>(it, it.displayName, titleFontFamily = getFontFamily(it)) },
        selectedValue = selected,
        onOptionSelected = { onSelected(it); onDismiss() }, onDismiss = onDismiss
    )
}

@Composable
internal fun SubtitleEdgeDialog(selected: SubtitleEdgeStyle, onSelected: (SubtitleEdgeStyle) -> Unit, onDismiss: () -> Unit) {
    SettingsSingleChoiceDialog(
        title = stringResource(R.string.subtitle_edge_style),
        options = SubtitleEdgeStyle.entries.map { SettingsPickerOption(it, subtitleEdgeName(it),
            description = if (it == SubtitleEdgeStyle.DROP_SHADOW) stringResource(R.string.subtitle_shadow_note) else null) },
        selectedValue = selected,
        onOptionSelected = { onSelected(it); onDismiss() }, onDismiss = onDismiss
    )
}
