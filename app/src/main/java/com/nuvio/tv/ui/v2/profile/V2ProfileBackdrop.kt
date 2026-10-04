package com.nuvio.tv.ui.v2.profile

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.nuvio.tv.domain.model.VisualStyle
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.V2Motion
import com.nuvio.tv.ui.v2.components.v2GlassSource
import kotlinx.coroutines.delay

@Composable
fun V2ProfileBackdrop(focusedColor: Color, profileId: Int?, snapshotVersion: Int = 0) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val snapshots = remember(context, snapshotVersion) { mutableMapOf<Int, List<String>>() }
    val snapshot by androidx.compose.runtime.produceState<Triple<Int?, Int, List<String>>>(Triple(null, -1, emptyList()), profileId, snapshotVersion) {
        val previousProfile = value.first
        value = Triple(profileId, snapshotVersion, emptyList())
        if (profileId != null) {
            // First open and cached returns need no focus debounce. The caller
            // still withholds profileId until the PIN state permits a preview.
            if (previousProfile != null && previousProfile != profileId && profileId !in snapshots) {
                delay(V2Motion.BackdropDebounceMs)
            }
            val posters = snapshots[profileId] ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                readProfilePosters(context, profileId)
            }.also { snapshots[profileId] = it }
            value = Triple(profileId, snapshotVersion, posters)
        }
    }
    val posters = snapshot.third.takeIf { snapshot.first == profileId && snapshot.second == snapshotVersion }.orEmpty()
    var settledColor by remember { mutableStateOf(focusedColor) }
    LaunchedEffect(focusedColor) {
        delay(V2Motion.BackdropDebounceMs)
        settledColor = focusedColor
    }
    val color = animateColorAsState(settledColor, tween(V2Motion.BackdropCrossfadeMs), label = "profileAmbience")
    val pureDark = LocalV2Appearance.current?.visualStyle == VisualStyle.PURE_LIQUID_DARK
    Box(Modifier.fillMaxSize().v2GlassSource()) { ProfilePosterWallBackground(profilePosters = posters) }
    Box(Modifier.fillMaxSize().drawWithCache {
        onDrawBehind {
            if (!pureDark) drawRect(Brush.radialGradient(
                listOf(color.value.copy(alpha = 0.13f), Color.Transparent),
                center = Offset(size.width / 2, size.height * 0.48f), radius = size.width * 0.55f))
        }
    })
}
