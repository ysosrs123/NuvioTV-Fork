@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.ImportedSubtitleFont
import com.nuvio.tv.core.player.ImportedSubtitleFontStore
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.domain.model.SubtitleFont
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme

/** The Font row's value: the imported font's name, or the bundled choice. */
@Composable
internal fun subtitleFontLabel(style: SubtitleStyleSettings): String {
    val context = LocalContext.current
    val store = remember(context) { ImportedSubtitleFontStore.get(context) }
    val fonts by store.fonts.collectAsState()
    return fonts.firstOrNull { it.id == style.importedFontId }?.name ?: subtitleFontName(style.font)
}

/** True when the chosen imported font's file is gone, so the player draws the bundled choice instead. */
@Composable
internal fun subtitleImportedFontMissing(style: SubtitleStyleSettings): Boolean {
    val id = style.importedFontId ?: return false
    val context = LocalContext.current
    val store = remember(context) { ImportedSubtitleFontStore.get(context) }
    val fonts by store.fonts.collectAsState()
    return fonts.none { it.id == id }
}

private sealed interface FontChoice {
    data class Bundled(val font: SubtitleFont?) : FontChoice
    data class Imported(val font: ImportedSubtitleFont) : FontChoice
}

@Composable
internal fun SubtitleFontDialog(
    selected: SubtitleFont?,
    selectedImportedId: String?,
    onSelected: (SubtitleFont?) -> Unit,
    onSelectedImported: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val viewModel: SubtitleFontsViewModel = hiltViewModel()
    val imported by viewModel.fonts.collectAsState()
    val importState by viewModel.importState.collectAsState()
    var removing by remember { mutableStateOf<ImportedSubtitleFont?>(null) }
    val selectedChoice: FontChoice = imported.firstOrNull { it.id == selectedImportedId }
        ?.let { FontChoice.Imported(it) } ?: FontChoice.Bundled(selected)
    val initialFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { initialFocus.requestFocusAfterFrames() }
    DisposableEffect(viewModel) { onDispose { viewModel.stopImport() } }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.subtitle_font_family),
        width = 640.dp,
        suppressFirstKeyUp = false
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            fullWidth("default") {
                val choice = FontChoice.Bundled(null)
                FontOptionCard(
                    title = subtitleFontName(null),
                    selected = selectedChoice == choice,
                    focusRequester = initialFocus.takeIf { selectedChoice == choice },
                    onClick = { onSelected(null); onDismiss() }
                )
            }
            groupLabel("arc", R.string.subtitle_font_group_arc)
            SubtitleFont.entries.forEach { font ->
                item(key = font.name) {
                    val choice = FontChoice.Bundled(font)
                    FontOptionCard(
                        title = font.displayName,
                        fontFamily = subtitleFontFamily(font),
                        selected = selectedChoice == choice,
                        focusRequester = initialFocus.takeIf { selectedChoice == choice },
                        onClick = { onSelected(font); onDismiss() }
                    )
                }
            }
            if (imported.isNotEmpty()) {
                groupLabel("yours", R.string.subtitle_font_group_yours)
                imported.forEach { font ->
                    item(key = font.id) {
                        val choice = FontChoice.Imported(font)
                        FontOptionCard(
                            title = font.name,
                            description = stringResource(R.string.subtitle_font_hold_to_remove),
                            fontFamily = importedFontFamily(font),
                            selected = selectedChoice == choice,
                            focusRequester = initialFocus.takeIf { selectedChoice == choice },
                            onClick = { onSelectedImported(font.id); onDismiss() },
                            onLongClick = { removing = font }
                        )
                    }
                }
            }
            fullWidth("import") {
                FontOptionCard(
                    title = stringResource(R.string.sub_font_import),
                    description = stringResource(R.string.subtitle_font_import_short),
                    leadingPhone = true,
                    selected = false,
                    onClick = viewModel::startImport
                )
            }
        }
    }

    removing?.let { font ->
        RemoveImportedFontDialog(
            font = font,
            onConfirm = { viewModel.remove(font) },
            onDismiss = { removing = null }
        )
    }
    if (importState.isActive) {
        SubtitleFontImportDialog(state = importState, onClose = viewModel::stopImport)
    }
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

private fun LazyGridScope.groupLabel(key: String, text: Int) {
    item(key = "label-$key", span = { GridItemSpan(maxLineSpan) }) {
        Text(
            text = stringResource(text).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(start = NuvioTheme.spacing.md, top = NuvioTheme.spacing.sm)
        )
    }
}

@Composable
private fun importedFontFamily(font: ImportedSubtitleFont): FontFamily? {
    val context = LocalContext.current
    return remember(font.id) {
        ImportedSubtitleFontStore.get(context).typeface(font.id)?.let { FontFamily(it) }
    }
}

@Composable
private fun FontOptionCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    description: String? = null,
    fontFamily: FontFamily? = null,
    leadingPhone: Boolean = false,
    focusRequester: FocusRequester? = null,
    onLongClick: (() -> Unit)? = null
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            },
        colors = CardDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.Secondary.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.5f),
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                shape = RoundedCornerShape(10.dp)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NuvioTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingPhone) {
                Icon(
                    imageVector = Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (selected) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextPrimary,
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                    fontFamily = fontFamily,
                    maxLines = 1
                )
                if (!description.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                    Text(
                        text = description,
                        color = NuvioTheme.colors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoveImportedFontDialog(font: ImportedSubtitleFont, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocus.requestFocusAfterFrames() }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.sub_font_remove, font.name),
        suppressFirstKeyUp = false
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(cancelFocus),
                colors = ButtonDefaults.colors(
                    containerColor = Color.Black.copy(alpha = 0.85f),
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.colors(
                    containerColor = Color.Black.copy(alpha = 0.85f),
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.subtitle_font_remove_action))
            }
        }
    }
}

@Composable
private fun SubtitleFontImportDialog(state: SubtitleFontImportState, onClose: () -> Unit) {
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { closeFocus.requestFocusAfterFrames() }
    NuvioDialog(
        onDismiss = onClose,
        title = stringResource(R.string.sub_font_import),
        width = 640.dp,
        suppressFirstKeyUp = false
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
            verticalAlignment = Alignment.CenterVertically
        ) {
            state.qrCodeBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(190.dp)
                        .background(Color.White, RoundedCornerShape(NuvioTheme.radii.md))
                        .padding(NuvioTheme.spacing.md)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                val added = state.addedName
                when {
                    state.error != null -> Text(state.error, color = NuvioTheme.colors.Warning, style = MaterialTheme.typography.bodyLarge)
                    added != null -> Text(stringResource(R.string.subtitle_font_added, added), color = NuvioTheme.colors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
                    else -> {
                        Text(stringResource(R.string.sub_font_import_qr), color = NuvioTheme.colors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.sub_font_import_desc), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        state.serverUrl?.let { url ->
                            Text(
                                url,
                                color = NuvioTheme.colors.Secondary,
                                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr)
                            )
                        }
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = onClose,
                modifier = Modifier.focusRequester(closeFocus),
                colors = ButtonDefaults.colors(
                    containerColor = Color.Black.copy(alpha = 0.85f),
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_close))
            }
        }
    }
}
