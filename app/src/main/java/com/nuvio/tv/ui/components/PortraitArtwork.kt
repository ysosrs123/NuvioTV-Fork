package com.nuvio.tv.ui.components

import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.tv.core.poster.isPortraitArtwork

private val canBlurArtwork = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
private val portraitBackdropDim = ColorFilter.tint(
    Color.Black.copy(alpha = if (canBlurArtwork) 0.58f else 0.7f),
    BlendMode.SrcAtop
)
private val knownPortraitArtwork = LruCache<String, Boolean>(512)

/** Remembers which artwork turned out to be portrait, so a card coming back into view starts fitted. */
class PortraitArtworkState internal constructor(private val url: String?) {
    var isPortrait by mutableStateOf(url != null && knownPortraitArtwork.get(url) == true)
        private set

    fun onLoaded(width: Int, height: Int) {
        val portrait = isPortraitArtwork(width, height)
        isPortrait = portrait
        if (url == null) return
        if (portrait) knownPortraitArtwork.put(url, true) else knownPortraitArtwork.remove(url)
    }

    fun onFailed() {
        isPortrait = false
    }
}

@Composable
fun rememberPortraitArtworkState(url: String?): PortraitArtworkState =
    remember(url) { PortraitArtworkState(url) }

/** Turns the cropped artwork of a landscape card into the backdrop behind its fitted poster. */
fun Modifier.portraitArtworkBackdrop(enabled: Boolean): Modifier =
    if (enabled && canBlurArtwork) blur(12.dp) else this

fun portraitArtworkBackdropFilter(enabled: Boolean): ColorFilter? =
    if (enabled) portraitBackdropDim else null

@Composable
fun FittedPortraitArtwork(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier.fillMaxSize(),
        contentScale = ContentScale.Fit
    )
}
