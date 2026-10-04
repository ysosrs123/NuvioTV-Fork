@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import androidx.tv.material3.*
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.nuvioGlass
import com.nuvio.tv.ui.v2.components.nuvioControlSurface
import com.nuvio.tv.data.local.*
import kotlinx.coroutines.launch

internal data class PlayerControlPopupPreview(val bounds: IntRect?, val scale: Float)
internal val LocalPlayerControlPopupPreview = staticCompositionLocalOf<PlayerControlPopupPreview?> { null }

/** Anchored window: no extra rows, spacer or height are added to the underlying player. */
@Composable
internal fun PlayerControlMorePopup(layout: PlayerControlLayout, available: Set<PlayerControlAction>, targets: (PlayerControlAction) -> FocusRequester,
    playing: Boolean, preview: Boolean, selected: PlayerControlAction?, onClick: (PlayerControlAction) -> Unit,
    onFocused: (PlayerControlAction) -> Unit, onDismiss: () -> Unit) {
    val v2 = LocalV2Appearance.current != null
    val context = LocalPlayerControlPopupPreview.current
    val activityDensity = LocalDensity.current
    val density = if (preview && context != null) Density(activityDensity.density * context.scale.coerceAtLeast(.01f), activityDensity.fontScale) else activityDensity
    val window = LocalWindowInfo.current.containerSize
    val bounds = context?.bounds?.takeIf { preview } ?: IntRect(0, 0, window.width.coerceAtLeast(1), window.height.coerceAtLeast(1))
    val items = playerControlMoreEntries(layout, available, preview)
    val scope = rememberCoroutineScope()
    val closeFocus = remember { FocusRequester() }
    var focusedAction by remember { mutableStateOf<PlayerControlAction?>(null) }
    val requesters = remember { PlayerControlAction.entries.associateWith { BringIntoViewRequester() } }
    val provider = remember(bounds, density) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
            val left = bounds.left.coerceIn(0, windowSize.width)
            val top = bounds.top.coerceIn(0, windowSize.height)
            val allowed = PlayerControlPopupBounds(left, top, bounds.right.coerceIn(left, windowSize.width), bounds.bottom.coerceIn(top, windowSize.height))
            val result = playerControlPopupPosition(PlayerControlPopupBounds(anchorBounds.left, anchorBounds.top, anchorBounds.right, anchorBounds.bottom),
                allowed, popupContentSize.width, popupContentSize.height, with(density) { 8.dp.roundToPx() })
            return IntOffset(result.x, result.y)
        }
    } }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = !preview, dismissOnBackPress = !preview, dismissOnClickOutside = !preview)) {
        CompositionLocalProvider(LocalDensity provides density) {
            val maxWidth = minOf(320.dp, with(density) { bounds.width.coerceAtLeast(1).toDp() })
            val maxHeight = minOf(320.dp, with(density) { bounds.height.coerceAtLeast(1).toDp() } * .7f)
            Column(Modifier.width(maxWidth).heightIn(max = maxHeight).then(if (v2) Modifier.nuvioGlass(GlassRole.PANEL, shape = RoundedCornerShape(12.dp))
                    else Modifier.background(NuvioTheme.colors.BackgroundCard.copy(alpha = .96f), RoundedCornerShape(12.dp))
                        .border(1.dp, NuvioTheme.colors.TextPrimary.copy(alpha = .2f), RoundedCornerShape(12.dp))).padding(8.dp)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.player_more_actions_title), Modifier.padding(8.dp), color = Color.White, style = MaterialTheme.typography.titleSmall)
                if (items.isEmpty()) Text(stringResource(R.string.player_layout_more_empty), Modifier.padding(8.dp), color = Color.White)
                for ((index, entry) in items.withIndex()) key(entry.action.id) {
                    val action = entry.action
                    val ghost = !entry.visible || action !in available
                    val rowModifier = Modifier.fillMaxWidth().bringIntoViewRequester(requesters.getValue(action))
                    // The same widget, typography and padding are measured in preview and runtime.
                    val itemModifier = rowModifier.heightIn(min = 42.dp).nuvioControlSurface(RoundedCornerShape(8.dp))
                        .border(1.dp, if (preview && selected == action) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
                        .alpha(if (preview && ghost) .4f else 1f)
                        .then(if (preview) Modifier.focusProperties { canFocus = false } else Modifier
                            .focusRequester(targets(action)).focusProperties {
                                left = FocusRequester.Cancel; right = FocusRequester.Cancel
                                up = items.getOrNull(index - 1)?.action?.let(targets) ?: FocusRequester.Cancel
                                down = items.getOrNull(index + 1)?.action?.let(targets) ?: closeFocus
                            }.onFocusChanged { if (it.hasFocus) {
                                focusedAction = action; onFocused(action); scope.launch { requesters.getValue(action).bringIntoView() }
                            } })
                    Button(onClick = { if (!preview) onClick(action) }, modifier = itemModifier,
                        colors = playerMoreButtonColors(v2), shape = ButtonDefaults.shape(shape = RoundedCornerShape(8.dp)),
                        border = ButtonDefaults.border(focusedBorder = if (v2) Border.None else Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = RoundedCornerShape(8.dp))),
                        scale = ButtonDefaults.scale(focusedScale = 1f), contentPadding = PaddingValues(8.dp)) {
                        Icon(playerControlIcon(action, playing), null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                        Text(playerControlLabel(action, playing), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 2)
                    }
                }
                if (!preview) Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().nuvioControlSurface(RoundedCornerShape(8.dp)).focusRequester(closeFocus).focusProperties {
                    up = items.lastOrNull()?.action?.let(targets) ?: FocusRequester.Cancel
                    down = FocusRequester.Cancel; left = FocusRequester.Cancel; right = FocusRequester.Cancel
                }, colors = playerMoreButtonColors(v2), shape = ButtonDefaults.shape(shape = RoundedCornerShape(8.dp)),
                    border = ButtonDefaults.border(focusedBorder = if (v2) Border.None else Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = RoundedCornerShape(8.dp))),
                    scale = ButtonDefaults.scale(focusedScale = 1f)) { Text(stringResource(R.string.action_close)) }
            }
            LaunchedEffect(items.map { it.action }, selected) {
                withFrameNanos { }; withFrameNanos { }
                if (preview) {
                    if (selected != null && items.any { it.action == selected }) requesters.getValue(selected).bringIntoView()
                } else {
                    val target = selected?.takeIf { a -> items.any { it.action == a } }
                        ?: focusedAction?.takeIf { a -> items.any { it.action == a } } ?: items.firstOrNull()?.action
                    runCatching { (target?.let(targets) ?: closeFocus).requestFocus() }
                }
            }
        }
    }
}

/** Shared theme colors for action and Close rows; V2 glass owns the container/focus decoration. */
@Composable
private fun playerMoreButtonColors(v2: Boolean) = ButtonDefaults.colors(
    containerColor = if (v2) Color.Transparent else NuvioTheme.colors.BackgroundCard,
    focusedContainerColor = if (v2) Color.Transparent else NuvioTheme.colors.Secondary,
    contentColor = NuvioTheme.colors.TextPrimary,
    focusedContentColor = if (v2) Color.White else NuvioTheme.colors.OnSecondary
)
