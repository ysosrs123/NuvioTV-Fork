package com.nuvio.tv.ui.v2.components

import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.Person
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.core.graphics.toColorInt
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.ui.theme.NuvioTheme

@Immutable
data class AvatarModel(val id: String, val name: String, val colorHex: String, val imageUrl: String? = null)

/** Immediate initials underneath a bounded cached image; failed downloads never remove identity. */
@Composable
fun NuvioAvatar(
    avatar: AvatarModel,
    size: Dp,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    selected: Boolean = false,
    portraitPlaceholder: Boolean = false,
    description: String? = avatar.name
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val pixels = with(density) { size.roundToPx() }.coerceIn(32, 1024)
    val color = remember(avatar.id, avatar.colorHex) {
        val fallback = Color.hsv((avatar.id.hashCode().toLong() and 0x7fffffff).rem(360).toFloat(), 0.6f, 0.5f)
        var base = runCatching { Color(avatar.colorHex.toColorInt()) }.getOrDefault(fallback)
        // White initials retain at least 4.5:1 contrast, including custom bright profile colours.
        while (base.luminance() > 0.18f) base = lerp(base, Color.Black, 0.1f)
        base.copy(alpha = 1f)
    }
    val request = remember(context, avatar.imageUrl, pixels) {
        avatar.imageUrl?.takeIf(String::isNotBlank)?.let {
            ImageRequest.Builder(context).data(it).size(pixels, pixels).crossfade(120).build()
        }
    }
    Box(
        modifier.size(size).nuvioV2Focus(focused, CircleShape, if (portraitPlaceholder) NuvioTheme.colors.Secondary else null).clip(CircleShape)
            .background(if (portraitPlaceholder) Color(0xFF0C1A29) else color, CircleShape)
            .border(if (selected) 2.dp else 1.dp,
                if (selected) NuvioTheme.colors.Secondary else Color.White.copy(alpha = 0.18f), CircleShape)
            .semantics { description?.let { contentDescription = it }; this.selected = selected },
        contentAlignment = Alignment.Center
    ) {
        if (portraitPlaceholder) {
            androidx.tv.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Person, null,
                Modifier.size(size * .46f), tint = Color(0xFFB9D4FF))
        } else Text(avatar.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            color = Color.White, fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.SemiBold)
        if (request != null) {
            AsyncImage(request, contentDescription = null, modifier = Modifier.size(size), contentScale = ContentScale.Crop)
        }
    }
}
