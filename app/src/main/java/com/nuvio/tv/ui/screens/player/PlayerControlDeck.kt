@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.Layout

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.tv.ui.v2.components.nuvioGlass
import com.nuvio.tv.ui.v2.components.GlassRole
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.nuvio.tv.R
import com.nuvio.tv.data.local.*
import com.nuvio.tv.domain.model.PlayerChromeStyle
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.player.V2StyledTransportButton
import com.nuvio.tv.ui.v2.player.V2StyledUtilityButton
import kotlinx.coroutines.launch

/** Stable global action keys: moving, hiding and styling never reparents the focused button. */
@Composable
internal fun PlayerControlDeck(
    layout: PlayerControlLayout,
    available: Set<PlayerControlAction>,
    targets: (PlayerControlAction) -> FocusRequester,
    playing: Boolean = false,
    preview: Boolean = false,
    enabled: Boolean = true,
    moving: PlayerControlAction? = null,
    moreExpanded: Boolean = false,
    popupSelected: PlayerControlAction? = null,
    onMoreDismiss: () -> Unit = {},
    upFocus: FocusRequester? = null,
    onBottom: () -> Unit = {},
    onClick: (PlayerControlAction) -> Unit,
    onFocused: (PlayerControlAction) -> Unit,
    onFocusLost: (PlayerControlAction) -> Unit = {},
    onSelectKey: ((PlayerControlAction, KeyEvent) -> Boolean)? = null,
    onPlan: (PlayerControlDeckPlan) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var plan by remember { mutableStateOf(PlayerControlDeckPlan(emptyList(), 0, 0, 0, null)) }

    val v2 = LocalV2Appearance.current != null
    val deckAvailable = playerControlDeckAvailable(layout, available, false)
    val include = if (preview) PlayerControlAction.entries.toSet() else layout.focusOrder(deckAvailable).toSet()
    val actions = PlayerControlAction.entries.filter { it in include }
    val ghostLabel = stringResource(R.string.player_layout_hidden_buttons)
    val menuLabel = stringResource(R.string.player_layout_more_buttons)
    val gap = if (layout.style == PlayerControlButtonStyle.PREVIOUS && v2) 12.dp else 8.dp
    Layout(modifier = modifier, content = {
        // Constant enum order is deliberately independent of group/order/visibility/style.
        for (action in actions) key(action.id) {
            val entry = layout.entries.single { it.action == action }
            val ghost = preview && playerControlPreviewFaded(entry, available)
            var focused by remember { mutableStateOf(false) }
            val bring = remember { BringIntoViewRequester() }
            val label = playerControlLabel(action, playing)
            val buttonModifier = Modifier.focusRequester(targets(action)).bringIntoViewRequester(bring)
                .onFocusChanged {
                    focused = it.hasFocus
                    if (it.hasFocus) onFocused(action)
                    else onFocusLost(action)
                }
                .focusProperties {
                    canFocus = enabled
                    left = plan.neighbour(action, PlayerControlDirection.LEFT)?.let(targets) ?: FocusRequester.Cancel
                    right = plan.neighbour(action, PlayerControlDirection.RIGHT)?.let(targets) ?: FocusRequester.Cancel
                    up = plan.neighbour(action, PlayerControlDirection.UP)?.let(targets) ?: upFocus ?: FocusRequester.Cancel
                    down = plan.neighbour(action, PlayerControlDirection.DOWN)?.let(targets) ?: FocusRequester.Cancel
                }.onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    when (native.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> onSelectKey?.invoke(action, native) ?: false
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (native.action == KeyEvent.ACTION_DOWN && plan.neighbour(action, PlayerControlDirection.DOWN) == null) { onBottom(); true } else false
                        }
                        else -> false
                    }
                }
            // One cancellable request per focus or deck move; parent scrolling must not trigger a new request.
            val cell = plan.cells.firstOrNull { it.action == action }
            LaunchedEffect(focused, cell?.x, cell?.y) { if (focused) bring.bringIntoView() }
            // The focus outline is outside the faded content, so hidden state and selection both remain clear.
            Box(buttonModifier.border(if (moving == action) 2.dp else 1.dp,
                if (moving == action) Color.Cyan else if (ghost && focused) Color.White else Color.Transparent, RoundedCornerShape(50))) {
                PlayerStyledControlButton(action, layout.style, playing, label, { onClick(action) }, Modifier.alpha(if (ghost) .4f else 1f))
                if (action == PlayerControlAction.MORE && moreExpanded && entry.visible && action in available) {
                    PlayerControlMorePopup(layout, available, targets, playing, preview, popupSelected, onClick, onFocused, onMoreDismiss)
                }
            }
        }
        if (preview) {
            Text(ghostLabel, color = Color.White.copy(alpha = .65f), style = MaterialTheme.typography.labelSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(menuLabel, color = Color.White.copy(alpha = .65f), style = MaterialTheme.typography.labelSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }) { measurables, constraints ->
        val width = constraints.maxWidth.takeUnless { it == Constraints.Infinity } ?: constraints.minWidth
        val gapPx = gap.roundToPx(); val region = ((width - gapPx * 2).coerceAtLeast(0) / 3)
        val placeables = actions.indices.associate { index -> actions[index] to measurables[index].measure(Constraints(maxWidth = region)) }
        val sectionWidth = (width - gapPx.coerceAtMost(width / 2)).coerceAtLeast(0) / 2
        val label = if (preview) measurables[actions.size].measure(Constraints(maxWidth = sectionWidth)) else null
        val menuLabelPlaceable = if (preview) measurables[actions.size + 1].measure(Constraints(maxWidth = sectionWidth)) else null
        val labelHeight = maxOf(label?.height ?: 0, menuLabelPlaceable?.height ?: 0) + gapPx
        val next = playerControlPrimaryDeckPlan(layout, available, placeables.mapValues { PlayerControlDeckSize(it.value.width, it.value.height) }, width, gapPx, preview, labelHeight)
        if (plan != next) { plan = next; onPlan(next) }
        this.layout(width, constraints.constrainHeight(next.height)) {
            for (cell in next.cells) placeables.getValue(cell.action).place(cell.x, cell.y)
            if (next.hiddenLabelY != null) label?.place(0, next.hiddenLabelY)
            if (next.moreLabelY != null) menuLabelPlaceable?.place(width - menuLabelPlaceable.width, next.moreLabelY)
        }
    }
}

@Composable
internal fun PlayerControlChrome(v2: Boolean, modifier: Modifier = Modifier, rightInset: Dp = 0.dp,
    gap: Dp = 8.dp, title: @Composable () -> Unit, timeline: @Composable () -> Unit,
    time: @Composable () -> Unit, deck: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().padding(start = if (v2) 32.dp else 48.dp,
        end = (if (v2) 32.dp else 48.dp) + rightInset, bottom = if (v2) 24.dp else 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(if (v2) Modifier.padding(horizontal = 12.dp) else Modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) { title() }
        val appearance = LocalV2Appearance.current
        val panel = playerControlChromePolicy(v2, appearance?.playerChromeStyle == PlayerChromeStyle.CONTROL_DECK,
            appearance?.visualStyle == com.nuvio.tv.domain.model.VisualStyle.CINEMATIC_GLASS).showDeckPanel
        Column(Modifier.fillMaxWidth()
            .then(if (panel) Modifier.nuvioGlass(GlassRole.CONTROL, shape = RoundedCornerShape(12.dp)) else Modifier)
            .then(if (v2) Modifier.padding(horizontal = 12.dp, vertical = if (panel) 14.dp else 8.dp) else Modifier),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) { timeline(); time() }
            Spacer(Modifier.height(gap)); deck()
        }
    }
}

@Composable
private fun PlayerStyledControlButton(action: PlayerControlAction, style: PlayerControlButtonStyle, playing: Boolean, label: String, onClick: () -> Unit, modifier: Modifier) {
    val v2 = LocalV2Appearance.current != null
    val transport = action in setOf(PlayerControlAction.PLAY_PAUSE, PlayerControlAction.RESTART, PlayerControlAction.NEXT_EPISODE) || (!v2 && action == PlayerControlAction.EPISODES)
    val icon = playerControlIcon(action, playing)
    if (style == PlayerControlButtonStyle.LABELLED) {
        PillControlButton(icon, playerControlPainter(action, playing), label, onClick,
            modifier = modifier.widthIn(max = 160.dp), labelMaxLines = 1)
    } else if (v2 && transport) {
        V2StyledTransportButton(icon, label, null, null,
            false,
            onClick, {}, null, modifier)
    } else if (v2) {
        V2StyledUtilityButton(icon, label, true, onClick, {}, modifier)
    } else {
        val legacyIcon = when (action) {
            PlayerControlAction.STATS -> Icons.Default.Info
            PlayerControlAction.SUBTITLES -> Icons.Default.Chat
            PlayerControlAction.MORE -> Icons.Default.MoreHoriz
            else -> playerControlIcon(action, playing)
        }
        ControlButton(legacyIcon, if (action == PlayerControlAction.ASPECT) playerControlPainter(action, playing) else null,
            label, onClick, modifier = modifier)
    }
}

/** Show the lower player chrome at the actual activity width, uniformly scaled to the editor. */
@Composable
internal fun ScaledPlayerControlPreview(referenceWidthPx: Int, modifier: Modifier = Modifier, popupBounds: IntRect? = null, content: @Composable () -> Unit) {
    var previewScale by remember { mutableFloatStateOf(1f) }
    Layout(content = { CompositionLocalProvider(LocalPlayerControlPopupPreview provides PlayerControlPopupPreview(popupBounds, previewScale)) { content() } }, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val virtualWidth = referenceWidthPx.coerceAtLeast(width).coerceAtLeast(1)
        val scale = width.toFloat() / virtualWidth
        if (previewScale != scale) previewScale = scale
        val child = measurables.single().measure(Constraints.fixedWidth(virtualWidth))
        val height = (child.height * scale).toInt()
        layout(width, constraints.constrainHeight(height)) {
            child.placeWithLayer(0, 0) { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f) }
        }
    }
}
