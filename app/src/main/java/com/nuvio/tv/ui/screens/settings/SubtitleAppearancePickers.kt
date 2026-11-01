@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.data.local.SubtitleEdgeStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.nuvio.tv.domain.model.SubtitleFont
import com.nuvio.tv.ui.screens.player.boldFontResource
import com.nuvio.tv.ui.screens.player.fontResource
import com.nuvio.tv.ui.theme.getFontFamily

@Composable
internal fun subtitleFontName(font: SubtitleFont?): String = font?.displayName ?: stringResource(R.string.subtitle_font_renderer_default)

@Composable
internal fun subtitleEdgeName(edge: SubtitleEdgeStyle): String = stringResource(when (edge) {
    SubtitleEdgeStyle.NONE -> R.string.action_none
    SubtitleEdgeStyle.OUTLINE -> R.string.subtitle_style_outline
    SubtitleEdgeStyle.DROP_SHADOW -> R.string.subtitle_edge_drop_shadow
})

@Composable
internal fun SubtitleFontDialog(selected: SubtitleFont?, onSelected: (SubtitleFont?) -> Unit, onDismiss: () -> Unit) {
    SettingsSingleChoiceDialog<SubtitleFont?>(
        title = stringResource(R.string.subtitle_font_family),
        options = listOf(SettingsPickerOption<SubtitleFont?>(null, subtitleFontName(null))) +
            SubtitleFont.entries.map { SettingsPickerOption<SubtitleFont?>(it, it.displayName, titleFontFamily = subtitleFontFamily(it)) },
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

private fun subtitleFontFamily(font: SubtitleFont): FontFamily {
    font.appFont?.let { return getFontFamily(it) }
    val regular = Font(font.fontResource(), if (font == SubtitleFont.NOTO_SANS_SEMIBOLD) FontWeight.SemiBold else FontWeight.Normal)
    return font.boldFontResource()?.let { FontFamily(regular, Font(it, FontWeight.Bold)) } ?: FontFamily(regular)
}
