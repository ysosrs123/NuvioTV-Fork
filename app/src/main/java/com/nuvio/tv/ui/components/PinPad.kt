@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.nuvioGlass
import com.nuvio.tv.ui.v2.components.nuvioV2Focus

internal const val PIN_PAD_CLEAR = 'c'
internal const val PIN_PAD_DELETE = 'd'
internal const val PIN_PAD_START = '5'
internal val PIN_PAD_ROWS = listOf("123", "456", "789", "${PIN_PAD_CLEAR}0$PIN_PAD_DELETE")

/** What a pad key does to the digits typed so far. */
internal fun pinAfterKey(pin: String, key: Char, length: Int): String = when {
    key == PIN_PAD_CLEAR -> ""
    key == PIN_PAD_DELETE -> pin.dropLast(1)
    key.isDigit() && pin.length < length -> pin + key
    else -> pin
}

/** Numbers on a pad read left to right in every language, so the arrows match what is on screen. */
@Composable
internal fun PinPad(
    enabled: Boolean,
    onKey: (Char) -> Unit,
    startFocus: FocusRequester,
    modifier: Modifier = Modifier,
    doneLabel: String? = null,
    onDone: (() -> Unit)? = null,
    doneEnabled: Boolean = true
) {
    val hasDone = doneLabel != null && onDone != null
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier = modifier.alpha(if (enabled) 1f else .45f),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            PIN_PAD_ROWS.forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    row.forEachIndexed { columnIndex, key ->
                        PinPadKey(
                            key = key,
                            onClick = { if (enabled) onKey(key) },
                            modifier = Modifier
                                .focusProperties {
                                    if (rowIndex == 0) up = FocusRequester.Cancel
                                    if (rowIndex == PIN_PAD_ROWS.lastIndex && !hasDone) down = FocusRequester.Cancel
                                    if (columnIndex == 0) left = FocusRequester.Cancel
                                    if (columnIndex == row.lastIndex) right = FocusRequester.Cancel
                                }
                                .then(if (key == PIN_PAD_START) Modifier.focusRequester(startFocus) else Modifier)
                        )
                    }
                }
            }
            if (hasDone) {
                PinPadWideKey(
                    label = doneLabel.orEmpty(),
                    onClick = { if (enabled && doneEnabled) onDone?.invoke() },
                    enabled = doneEnabled,
                    modifier = Modifier.focusProperties {
                        down = FocusRequester.Cancel
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                    }
                )
            }
        }
    }
}

@Composable
private fun PinPadKey(key: Char, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val v2 = LocalV2Appearance.current != null
    val shape = RoundedCornerShape(if (v2) NuvioTheme.radii.md else NuvioTheme.radii.full)
    val secondary = NuvioTheme.colors.Secondary
    Button(
        onClick = onClick,
        modifier = modifier
            .size(width = if (v2) 62.dp else 68.dp, height = if (v2) 50.dp else 56.dp)
            .onFocusChanged { focused = it.isFocused }
            .then(if (v2) Modifier.nuvioV2Focus(focused, shape).nuvioGlass(GlassRole.CONTROL, focused, shape) else Modifier),
        shape = ButtonDefaults.shape(shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = if (v2) Color.Transparent else NuvioTheme.colors.TextPrimary.copy(alpha = .08f),
            focusedContainerColor = if (v2) secondary.copy(alpha = .24f) else secondary,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContentColor = if (v2) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.OnSecondary
        ),
        border = ButtonDefaults.border(
            focusedBorder = if (v2) Border.None else Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
        ),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (key) {
                PIN_PAD_CLEAR -> Text(
                    text = stringResource(R.string.action_clear),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                PIN_PAD_DELETE -> Icon(
                    Icons.AutoMirrored.Filled.Backspace,
                    stringResource(R.string.action_delete),
                    Modifier.size(24.dp)
                )
                else -> Text(key.toString(), style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

/** A key as wide as a row of the pad, for the action that ends the entry. */
@Composable
private fun PinPadWideKey(label: String, onClick: () -> Unit, enabled: Boolean, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val v2 = LocalV2Appearance.current != null
    val shape = RoundedCornerShape(if (v2) 12.dp else 999.dp)
    val secondary = NuvioTheme.colors.Secondary
    val keyWidth = if (v2) 62.dp else 68.dp
    Button(
        onClick = onClick,
        modifier = modifier
            .size(width = keyWidth * 3 + NuvioTheme.spacing.sm * 2, height = if (v2) 50.dp else 56.dp)
            .alpha(if (enabled) 1f else .45f)
            .onFocusChanged { focused = it.isFocused }
            .then(if (v2) Modifier.nuvioV2Focus(focused, shape).nuvioGlass(GlassRole.CONTROL, focused, shape) else Modifier),
        shape = ButtonDefaults.shape(shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = if (v2) Color.Transparent else Color.White.copy(alpha = .08f),
            focusedContainerColor = if (v2) secondary.copy(alpha = .24f) else secondary,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContentColor = if (v2) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.OnSecondary
        ),
        border = ButtonDefaults.border(
            focusedBorder = if (v2) Border.None else Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
        ),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
