@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.scout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.home.MODERN_LANDSCAPE_LOGO_GRADIENT
import com.nuvio.tv.core.image.sizedHomeArtworkUrl
import com.nuvio.tv.core.scout.ScoutSliceGroup
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.nuvioV2Focus

private const val TILE_IMAGE_WIDTH = 500

@Composable
fun ScoutPickerScreen(
    onOpenSlice: (sliceId: String) -> Unit,
    onBack: () -> Unit,
    viewModel: ScoutPickerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    val opened = remember { FocusRequester() }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) {
        val landed = viewModel.openedTile != null && opened.requestFocusAfterFrames()
        if (!landed) runCatching { first.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(horizontal = NuvioTheme.spacing.screen.overscanHorizontal, vertical = NuvioTheme.spacing.screen.overscanVertical),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        ScoutPageTitle(stringResource(R.string.scout_picker_title), inset = 0.dp)
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)) {
            Column(
                modifier = Modifier.width(190.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                ScoutSliceGroup.entries.filter { it.inPicker }.forEachIndexed { index, group ->
                    ScoutGroupItem(
                        label = stringResource(group.titleRes),
                        selected = group == state.group,
                        onFocused = { viewModel.select(group) },
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier
                    )
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(190.dp),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(NuvioTheme.spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                items(state.tiles, key = { it.id }) { tile ->
                    ScoutTile(
                        tile,
                        onClick = {
                            viewModel.openedTile = tile.id
                            onOpenSlice(tile.id)
                        },
                        modifier = if (tile.id == viewModel.openedTile) Modifier.focusRequester(opened) else Modifier
                    )
                }
            }
        }
    }
}

@Composable
private fun ScoutGroupItem(label: String, selected: Boolean, onFocused: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val v2 = LocalV2Appearance.current != null
    val shape = RoundedCornerShape(if (v2) 10.dp else NuvioTheme.radii.full)
    val secondary = NuvioTheme.colors.Secondary
    Card(
        onClick = onFocused,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .then(if (v2) Modifier.nuvioV2Focus(focused, shape) else Modifier),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f),
        colors = CardDefaults.colors(
            containerColor = if (selected) secondary.copy(alpha = .2f) else Color.Transparent,
            focusedContainerColor = if (v2) secondary.copy(alpha = .2f) else secondary,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContentColor = if (v2) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.OnSecondary
        ),
        border = CardDefaults.border(
            focusedBorder = if (v2) Border.None else Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun ScoutTile(tile: ScoutPickerTile, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val v2 = LocalV2Appearance.current != null
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .onFocusChanged { focused = it.isFocused }
            .then(if (v2) Modifier.nuvioV2Focus(focused, shape) else Modifier),
        shape = CardDefaults.shape(shape),
        scale = if (v2) CardDefaults.scale(focusedScale = 1f) else CardDefaults.scale(),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.BackgroundCard
        ),
        border = CardDefaults.border(
            focusedBorder = if (v2) Border.None else Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
        )
    ) {
        Box(Modifier.fillMaxSize()) {
            if (tile.cover != null) {
                var failed by remember(tile.cover) { mutableStateOf(false) }
                val picture = tile.fallback.takeIf { failed } ?: tile.cover
                AsyncImage(
                    model = sizedHomeArtworkUrl(picture, TILE_IMAGE_WIDTH),
                    contentDescription = tile.title.takeUnless { tile.showTitle },
                    contentScale = ContentScale.Crop,
                    onError = { failed = true },
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                Modifier.fillMaxSize().background(MODERN_LANDSCAPE_LOGO_GRADIENT)
            )
            if (tile.showTitle) {
                Text(
                    text = tile.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                )
            }
        }
    }
}
