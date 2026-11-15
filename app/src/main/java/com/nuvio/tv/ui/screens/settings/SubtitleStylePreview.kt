package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.ui.screens.player.showSubtitlePreview
import com.nuvio.tv.ui.theme.NuvioTheme

/** The subtitle style as the player draws it, over the backdrop of the title watched last. */
@Composable
internal fun SubtitleStylePreview(style: SubtitleStyleSettings, modifier: Modifier = Modifier) {
    val viewModel: SubtitleFontsViewModel = hiltViewModel()
    val backdrop by viewModel.previewBackdrop.collectAsState()
    val sample = stringResource(R.string.sub_preview_text)
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)
            .height(150.dp)
            .clip(shape)
            .background(NuvioTheme.colors.BackgroundCard)
    ) {
        backdrop?.let { url ->
            coil3.compose.AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        AndroidView(
            factory = { context -> SubtitleView(context) },
            update = { view -> view.showSubtitlePreview(style, sample) },
            modifier = Modifier.fillMaxSize()
        )
        Text(
            text = stringResource(R.string.sub_preview),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(NuvioTheme.spacing.sm)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(NuvioTheme.radii.sm))
                .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xxs)
        )
    }
}
