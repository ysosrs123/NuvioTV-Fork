@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.scout

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.core.image.sizedHomeArtworkUrl
import com.nuvio.tv.ui.components.NuvioScrollDefaults
import com.nuvio.tv.ui.screens.home.MODERN_LANDSCAPE_LOGO_GRADIENT
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.nuvioGlass
import com.nuvio.tv.ui.v2.components.nuvioV2Focus

internal const val SCOUT_BACKDROP_WIDTH = 1920
internal const val SCOUT_TILE_IMAGE_WIDTH = 780

/** The picture behind the whole page, darkened where words sit on it. Wide darkens further across, for charts. */
@Composable
internal fun BoxScope.ScoutBackdrop(picture: String?, wide: Boolean = false) {
    val context = LocalContext.current
    val page = NuvioTheme.colors.Background
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val model = remember(context, picture) {
        picture?.let { ImageRequest.Builder(context).data(sizedHomeArtworkUrl(it, SCOUT_BACKDROP_WIDTH)).crossfade(true).build() }
    }
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = BiasAlignment(0f, -.4f),
        modifier = Modifier.matchParentSize()
    )
    val across = if (wide) {
        listOf(0f to page.copy(alpha = .98f), .56f to page.copy(alpha = .93f), .74f to page.copy(alpha = .55f), .92f to page.copy(alpha = .08f), 1f to page.copy(alpha = .08f))
    } else {
        listOf(0f to page.copy(alpha = .97f), .3f to page.copy(alpha = .8f), .54f to page.copy(alpha = .22f), .68f to Color.Transparent, 1f to Color.Transparent)
    }
    val stops = if (rtl) across.map { (at, colour) -> 1f - at to colour }.reversed() else across
    Box(
        Modifier
            .matchParentSize()
            .background(Brush.horizontalGradient(*stops.toTypedArray()))
            .background(Brush.verticalGradient(0f to page.copy(alpha = .86f), .09f to page.copy(alpha = .5f), .19f to Color.Transparent))
            .background(Brush.verticalGradient(.42f to Color.Transparent, .74f to page.copy(alpha = .9f), 1f to page))
    )
}

/** A word in the row across the top of the page, set straight onto the picture. */
@Composable
internal fun ScoutWord(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    onFocused: () -> Unit = {},
    leading: (@Composable () -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val v2 = LocalV2Appearance.current != null
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Card(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .then(if (v2) Modifier.nuvioV2Focus(focused, shape, stationary = true) else Modifier)
            .semantics { selected = on },
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = if (v2) NuvioTheme.colors.Secondary.copy(alpha = .3f) else NuvioTheme.colors.Secondary
        ),
        border = CardDefaults.border(
            focusedBorder = if (v2) Border.None else Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                leading?.invoke()
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (on || focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                    maxLines = 1
                )
            }
            Box(
                Modifier
                    .padding(top = NuvioTheme.spacing.xxs)
                    .width(NuvioTheme.spacing.lg)
                    .height(NuvioTheme.spacing.xxs)
                    .background(if (on) NuvioTheme.colors.Secondary else Color.Transparent, RoundedCornerShape(NuvioTheme.radii.full))
            )
        }
    }
}

internal data class ScoutTabItem(val key: String, val label: String, val selected: Boolean)

/** The row of tabs, drawn as the details page draws its Seasons. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ScoutTabs(
    items: List<ScoutTabItem>,
    focus: (String) -> Modifier,
    onFocused: (ScoutTabItem) -> Unit,
    onClick: (ScoutTabItem) -> Unit,
    selected: FocusRequester = FocusRequester.Default,
    entry: (ScoutTabItem) -> FocusRequester? = { null }
) {
    val list = rememberLazyListState()
    val landing = if (selected === FocusRequester.Default) items.getOrNull(list.firstVisibleItemIndex)?.let(entry) ?: selected else selected
    CompositionLocalProvider(LocalBringIntoViewSpec provides NuvioScrollDefaults.smoothScrollSpec) {
        LazyRow(
            state = list,
            modifier = Modifier.fillMaxWidth().scoutRowBounds().focusRestorer(landing),
            contentPadding = PaddingValues(horizontal = ScoutFocusRoom, vertical = NuvioTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            items(items, key = { it.key }) { item ->
                ScoutTab(item, focus(item.key), { onFocused(item) }, { onClick(item) })
            }
        }
    }
}

@Composable
private fun ScoutTab(item: ScoutTabItem, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val v2 = LocalV2Appearance.current != null
    val shape = RoundedCornerShape(if (v2) NuvioTheme.radii.sm else NuvioTheme.radii.xxl)
    val accent = NuvioTheme.colors.Secondary
    Card(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .nuvioV2Focus(focused, shape, stationary = true)
            .then(if (v2 && (focused || item.selected)) Modifier.nuvioGlass(GlassRole.CONTROL, focused, shape) else Modifier)
            .then(
                if (v2 && item.selected && !focused) {
                    Modifier.drawWithContent {
                        drawContent()
                        val barWidth = 18.dp.toPx()
                        val barHeight = 2.5.dp.toPx()
                        drawRoundRect(
                            color = accent,
                            topLeft = Offset((size.width - barWidth) / 2f, size.height - 4.dp.toPx() - barHeight),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(barHeight / 2f)
                        )
                    }
                } else {
                    Modifier
                }
            )
            .semantics { selected = item.selected },
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f),
        colors = CardDefaults.colors(
            containerColor = when {
                v2 -> Color.Transparent
                item.selected -> accent.copy(alpha = .34f)
                else -> NuvioTheme.colors.BackgroundCard
            },
            focusedContainerColor = if (v2) Color.Transparent else accent
        ),
        border = CardDefaults.border(
            focusedBorder = if (v2) Border.None else Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
        )
    ) {
        Text(
            text = item.label,
            style = if (v2) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
            color = when {
                !v2 && focused -> NuvioTheme.colors.OnSecondary
                focused || item.selected -> NuvioTheme.colors.TextPrimary
                else -> NuvioTheme.colors.TextSecondary
            },
            maxLines = 1,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = 10.dp)
        )
    }
}

/** A wide tile with the title's picture, its name and a short line under it. */
@Composable
internal fun ScoutWideTile(
    picture: String?,
    title: String,
    line: String?,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
    badge: String? = null,
    corner: (@Composable BoxScope.() -> Unit)? = null
) {
    val v2 = LocalV2Appearance.current != null
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .size(width = height * 16f / 9f, height = height)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .nuvioV2Focus(focused, shape, stationary = true),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f),
        colors = CardDefaults.colors(containerColor = NuvioTheme.colors.BackgroundCard, focusedContainerColor = NuvioTheme.colors.BackgroundCard),
        border = CardDefaults.border(
            focusedBorder = if (v2) Border.None else Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
        )
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = picture?.let { sizedHomeArtworkUrl(it, SCOUT_TILE_IMAGE_WIDTH) },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(Modifier.fillMaxSize().background(MODERN_LANDSCAPE_LOGO_GRADIENT))
            badge?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(NuvioTheme.spacing.xs)
                        .background(NuvioTheme.colors.Background.copy(alpha = .72f), RoundedCornerShape(NuvioTheme.radii.xs))
                        .padding(horizontal = NuvioTheme.spacing.xs, vertical = NuvioTheme.spacing.xxs)
                )
            }
            corner?.invoke(this)
            Column(Modifier.align(Alignment.BottomStart).padding(NuvioTheme.spacing.md)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!line.isNullOrBlank()) {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextPrimary.copy(alpha = .85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** One line in a choosing list: what it is, an optional note, and a tick when it is on. */
@Composable
internal fun ScoutOptionRow(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    leading: (@Composable () -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val v2 = LocalV2Appearance.current != null
    val shape = RoundedCornerShape(if (v2) NuvioTheme.radii.sm else NuvioTheme.radii.full)
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(if (v2) Modifier.nuvioV2Focus(focused, shape).nuvioGlass(GlassRole.CONTROL, focused, shape) else Modifier),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f),
        colors = CardDefaults.colors(
            containerColor = if (v2) Color.Transparent else NuvioTheme.colors.TextPrimary.copy(alpha = .08f),
            focusedContainerColor = if (v2) Color.Transparent else NuvioTheme.colors.Secondary
        ),
        border = CardDefaults.border(
            focusedBorder = if (v2) Border.None else Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            leading?.invoke()
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (!v2 && focused) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (!v2 && focused) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextSecondary, maxLines = 1)
            }
            if (on) {
                Box(Modifier.size(NuvioTheme.spacing.xl).background(NuvioTheme.colors.Secondary, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, null, Modifier.size(NuvioTheme.spacing.lg), tint = NuvioTheme.colors.OnSecondary)
                }
            } else {
                Spacer(Modifier.size(NuvioTheme.spacing.xl))
            }
        }
    }
}
