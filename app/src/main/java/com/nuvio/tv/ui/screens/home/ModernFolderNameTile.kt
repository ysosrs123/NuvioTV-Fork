package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.contentTextDirection

/** A folder without a cover: its name over its own picture, so folders in one row tell apart. */
@Composable
internal fun ModernFolderNameTile(
    title: String,
    pictureUrl: String?,
    requestWidthPx: Int,
    requestHeightPx: Int
) {
    val context = LocalContext.current
    var pictureShown by remember(pictureUrl) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.BackgroundCard),
        contentAlignment = Alignment.Center
    ) {
        if (!pictureUrl.isNullOrBlank()) {
            val model = remember(context, pictureUrl, requestWidthPx, requestHeightPx) {
                ImageRequest.Builder(context)
                    .data(pictureUrl)
                    .size(width = requestWidthPx, height = requestHeightPx)
                    .build()
            }
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onSuccess = { pictureShown = true }
            )
            if (pictureShown) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(textDirection = title.contentTextDirection()),
            color = if (pictureShown) Color.White else NuvioTheme.colors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(NuvioTheme.spacing.sm)
        )
    }
}
