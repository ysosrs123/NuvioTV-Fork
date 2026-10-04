package com.nuvio.tv.ui.v2.profile

import android.content.Context
import androidx.core.content.edit
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.nuvio.tv.ui.v2.quality.LocalVisualQuality
import com.nuvio.tv.ui.v2.quality.VisualQualityTier
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import org.json.JSONArray

/** A bounded device-local artwork snapshot. No account state and no additional network fetches. */
object ProfilePosterWall {
    private const val File = "v2_profile_poster_wall"
    fun rememberPosters(context: Context, urls: List<String>, profileId: Int? = null) {
        val bounded = urls.filter(String::isNotBlank).distinct().take(24)
        if (bounded.isEmpty()) return
        val encoded = JSONArray(bounded).toString()
        val prefs = context.getSharedPreferences(File, Context.MODE_PRIVATE)
        val key = profileId?.let { "posters_$it" } ?: "posters"
        if (prefs.getString(key, null) != encoded) prefs.edit { putString(key, encoded) }
    }
    fun clear(context: Context, profileId: Int? = null) {
        context.getSharedPreferences(File, Context.MODE_PRIVATE).edit {
            if (profileId == null) clear() else remove("posters_$profileId")
        }
    }
    fun posters(context: Context, profileId: Int? = null): List<String> = runCatching {
        val list = JSONArray(context.getSharedPreferences(File, Context.MODE_PRIVATE).getString(profileId?.let { "posters_$it" } ?: "posters", "[]"))
        (0 until minOf(list.length(), 24)).map { list.getString(it) }
    }.getOrDefault(emptyList())
}

@Composable
fun ProfilePosterWallBackground(pageBackground: Boolean = false, profilePosters: List<String>? = null) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val decodeLimit = when (LocalVisualQuality.current.tier) {
        VisualQualityTier.PERFORMANCE -> 180
        VisualQualityTier.ENHANCED -> 270
        VisualQualityTier.MAXIMUM -> 360
    }
    val posters = profilePosters ?: remember(context) { ProfilePosterWall.posters(context) }
    // The rotated, overscanned tiles must not escape the shaded canvas when the
    // page slides aside for navigation. Otherwise a bright strip leaks beside it.
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().background(Color(0xFF040B14))) {
        val tileWidth = maxWidth / 7
        val decodeWidth = with(density) { (tileWidth.toPx() * 1.2f).toInt().coerceIn(120, decodeLimit) }
        Row(Modifier.fillMaxSize().graphicsLayer { rotationZ = -7f; scaleX = 1.2f; scaleY = 1.2f; alpha = if (pageBackground) .60f else .72f },
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(8) { column ->
                Column(Modifier.width(tileWidth).offset(y = if (column % 2 == 0) (-75).dp else (-15).dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    repeat(3) { row ->
                        val url = posters.getOrNull((column * 3 + row) % posters.size.coerceAtLeast(1))
                        val request = remember(context, url, decodeWidth) { url?.let {
                            ImageRequest.Builder(context).data(it).size(decodeWidth, decodeWidth * 3 / 2)
                                .apply { if (profilePosters != null) diskCacheKey(it) }
                                .memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.READ_ONLY)
                                .networkCachePolicy(CachePolicy.DISABLED).build()
                        } }
                        AsyncImage(request, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.width(tileWidth).height(tileWidth * 1.5f)
                                .clip(RoundedCornerShape(9.dp)).background(Color(0xFF142332)))
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().drawWithCache {
            val center = Brush.radialGradient(if (pageBackground)
                listOf(Color(0x88040B14), Color(0x70040B14), Color(0x98040B14))
                else listOf(Color(0xE0040B14), Color(0xA0040B14), Color(0x40040B14)),
                center = Offset(size.width * .5f, size.height * .48f), radius = size.width * .65f)
            val edges = Brush.verticalGradient(if (pageBackground)
                listOf(Color(0x60040B14), Color.Transparent, Color(0xA0040B14))
                else listOf(Color(0x60040B14), Color.Transparent, Color(0xBF040B14)))
            onDrawBehind { drawRect(center); drawRect(edges) }
        })
    }
}
