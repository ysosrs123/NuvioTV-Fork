package com.nuvio.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.ui.v2.components.nuvioGlass
import com.nuvio.tv.ui.v2.components.nuvioV2Focus
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.util.toAbsoluteAlignment
import com.nuvio.tv.ui.util.contentTextDirection
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
internal fun PanelEyebrow(text: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            letterSpacing = 2.2.sp,
            color = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.padding(start = 10.dp, bottom = 10.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.12f))
        )
        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
internal fun RailEyebrow(
    text: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            letterSpacing = 2.2.sp,
            color = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.padding(bottom = 10.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.12f))
        )
    }
}

@Composable
internal fun PanelActionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null
) {
    val contentAlpha = if (enabled) 1f else 0.45f
    val v2 = LocalV2Appearance.current != null
    var focused by remember { mutableStateOf(false) }
    val actionShape = RoundedCornerShape(if (v2) 10.dp else 999.dp)
    Card(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused?.invoke() }
            .then(if (v2) Modifier.nuvioV2Focus(focused, actionShape)
                .nuvioGlass(GlassRole.CONTROL, focused, actionShape) else Modifier),
        colors = CardDefaults.colors(
            containerColor = if (v2) Color.Transparent else Color.White.copy(alpha = 0.08f),
            focusedContainerColor = if (v2) Color.Transparent else Color.White.copy(alpha = 0.08f)
        ),
        shape = CardDefaults.shape(actionShape),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, Color.Transparent), shape = RoundedCornerShape(999.dp)),
            focusedBorder = if (v2) Border.None else Border(BorderStroke(2.dp, Color.White), shape = actionShape)
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = if (v2) 12.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = contentAlpha),
                    modifier = Modifier.width(16.dp).height(16.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Shared two-line selection row for the player panels (audio, sources, subtitles).
 *
 * Row states:
 *  - resting: transparent surface, white title, tonal subtitle;
 *  - selected: white 0.16 fill, trailing tick;
 *  - focused: solid white pill with dark content: title, subtitle and tick all
 *    flip to the panel surface colour so they read on white.
 *
 * Focus drives both the pill fill and the content colour from a single [isFocused]
 * state (the AvatarPickerGrid idiom), so background and text animate together and
 * there is no white-on-white frame during fast D-pad scrolling. [trailing] is an
 * optional slot rendered before the tick: the sources panel puts the addon logo
 * there; audio and subtitles leave it null.
 */
@Composable
internal fun PlayerPanelRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    subtitle: String? = null,
    titleEnd: String? = null,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    trailing: (@Composable (focused: Boolean) -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val v2 = LocalV2Appearance.current != null
    val darkContent = Color.Black
    val bgColor by animateColorAsState(
        targetValue = when {
            isFocused && v2 -> com.nuvio.tv.ui.theme.NuvioTheme.colors.Secondary.copy(alpha = .20f)
            isFocused -> Color.White
            selected -> Color.White.copy(alpha = 0.16f)
            else -> Color.Transparent
        },
        animationSpec = tween(150),
        label = "panelRowBg"
    )
    val titleColor by animateColorAsState(
        targetValue = if (isFocused && !v2) darkContent else Color.White,
        animationSpec = tween(150),
        label = "panelRowTitle"
    )
    val subtitleColor by animateColorAsState(
        targetValue = if (isFocused && !v2) darkContent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.6f),
        animationSpec = tween(150),
        label = "panelRowSubtitle"
    )
    val tickColor = if (isFocused && !v2) darkContent else Color.White

    Row(
        modifier = modifier
            .fillMaxWidth()
            .nuvioV2Focus(isFocused, RoundedCornerShape(10.dp), stationary = true)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.align(title.contentTextDirection().toAbsoluteAlignment()), verticalAlignment = Alignment.Bottom) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = title.contentTextDirection()),
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (!titleEnd.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = titleEnd,
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = titleEnd.contentTextDirection()),
                        color = subtitleColor,
                        maxLines = 1
                    )
                }
            }
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    modifier = Modifier.align(subtitle.contentTextDirection().toAbsoluteAlignment()),
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = subtitle.contentTextDirection()),
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing?.invoke(isFocused)
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = tickColor,
                modifier = Modifier.width(18.dp).height(18.dp)
            )
        }
    }
}
