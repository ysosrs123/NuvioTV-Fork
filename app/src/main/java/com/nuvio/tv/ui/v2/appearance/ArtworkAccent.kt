package com.nuvio.tv.ui.v2.appearance

import android.content.Context
import androidx.core.graphics.get
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.nuvio.tv.domain.model.AccentMode
import com.nuvio.tv.domain.model.GlassTintMode
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.components.V2Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest

data class ArtworkReference(val contentId: String, val imageUrl: String)

@Stable
class ArtworkAccentState(val color: State<Color>) {
    var artwork by mutableStateOf<ArtworkReference?>(null)
        private set
    var imageGeneration by mutableIntStateOf(0)
        private set
    fun select(contentId: String, imageUrl: String?) {
        artwork = imageUrl?.takeIf(String::isNotBlank)?.let { ArtworkReference(contentId, it) }
    }
    fun imageLoaded(imageUrl: String?) {
        if (imageUrl != null && imageUrl == artwork?.imageUrl) imageGeneration++
    }
}

val LocalArtworkAccent = staticCompositionLocalOf<ArtworkAccentState?> { null }

@Composable
fun rememberArtworkAccent(): ArtworkAccentState {
    val context = LocalContext.current
    val fallback = NuvioTheme.colors.Secondary
    val appearance = LocalV2Appearance.current
    val enabled = appearance != null && (appearance.accentMode == AccentMode.ADAPTIVE_ARTWORK ||
        appearance.glassTintMode == GlassTintMode.ARTWORK ||
        (NuvioTheme.currentTheme == com.nuvio.tv.domain.model.AppTheme.GLASS &&
            appearance.focusStyle == com.nuvio.tv.domain.model.FocusStyle.CINEMATIC_FOCUS))
    val color = remember { Animatable(fallback) }
    val state = remember { ArtworkAccentState(color.asState()) }
    val cache = remember { object : LinkedHashMap<ArtworkReference, Color>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ArtworkReference, Color>?): Boolean = size > 256
    } }
    LaunchedEffect(enabled, fallback) {
        // Selection and every animated frame stay outside the Activity's composition reads.
        snapshotFlow { state.artwork to state.imageGeneration }.collectLatest { (artwork, _) ->
            val resolved = if (!enabled || artwork == null) fallback else {
                delay(V2Motion.BackdropDebounceMs)
                cache[artwork] ?: resolveCachedArtworkAccent(context, artwork.imageUrl)?.also { cache[artwork] = it } ?: fallback
            }
            color.animateTo(resolved, tween(V2Motion.BackdropCrossfadeMs))
        }
    }
    return state
}

@Composable
fun ObserveArtworkAccent(contentId: String?, imageUrl: String?) {
    val state = LocalArtworkAccent.current
    LaunchedEffect(contentId, imageUrl, state) {
        state?.select(contentId.orEmpty(), imageUrl)
    }
}

/** A small software decode from Coil's existing caches; never a second artwork network fetch. */
private suspend fun resolveCachedArtworkAccent(context: Context, url: String): Color? = withContext(Dispatchers.IO) {
    val result = try {
        context.imageLoader.execute(ImageRequest.Builder(context).data(url).size(48, 48).allowHardware(false)
            .memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.READ_ONLY)
            .networkCachePolicy(CachePolicy.DISABLED).build())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { null }
    val bitmap = ((result as? SuccessResult)?.image as? BitmapImage)?.bitmap ?: return@withContext null
    // A compatible cache hit can return a larger bitmap than the requested decode.
    // Keep sampling/allocation bounded even then; do not copy a full hero bitmap.
    val width = minOf(48, bitmap.width)
    val height = minOf(48, bitmap.height)
    val pixels = IntArray(width * height) { index ->
        bitmap[(index % width) * bitmap.width / width, (index / width) * bitmap.height / height]
    }
    sampleArtworkAccent(pixels)
}

/** Dominant saturated hue buckets avoid muddy averaging and reject near-black/white artwork. */
internal fun sampleArtworkAccent(pixels: IntArray): Color? {
    val weight = FloatArray(12)
    val red = FloatArray(12)
    val green = FloatArray(12)
    val blue = FloatArray(12)
    for (pixel in pixels) {
        if ((pixel ushr 24) < 128) continue
        val r = ((pixel ushr 16) and 255) / 255f
        val g = ((pixel ushr 8) and 255) / 255f
        val b = (pixel and 255) / 255f
        val high = maxOf(r, g, b)
        val low = minOf(r, g, b)
        val delta = high - low
        if (high < 0.12f || delta < 0.08f || high == 0f) continue
        val saturation = delta / high
        if (saturation < 0.18f) continue
        val hue = ((when (high) {
            r -> (g - b) / delta
            g -> 2f + (b - r) / delta
            else -> 4f + (r - g) / delta
        } * 60f) + 360f) % 360f
        val bucket = (hue / 30f).toInt().coerceIn(0, 11)
        val w = saturation * saturation * high
        weight[bucket] += w
        red[bucket] += r * w; green[bucket] += g * w; blue[bucket] += b * w
    }
    val bucket = weight.indices.maxByOrNull { weight[it] } ?: return null
    if (weight[bucket] == 0f) return null
    var color = Color(red[bucket] / weight[bucket], green[bucket] / weight[bucket], blue[bucket] / weight[bucket])
    while (color.luminance() < 0.24f) color = lerp(color, Color.White, 0.08f)
    while (color.luminance() > 0.65f) color = lerp(color, Color.Black, 0.08f)
    return color
}
