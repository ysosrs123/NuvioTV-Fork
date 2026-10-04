package com.nuvio.tv.ui.screens.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/** Fade late sections in place. No expanding height, scroll movement or delayed focus. */
internal fun LazyListScope.detailSection(
    key: String,
    contentType: String,
    immediate: Boolean,
    content: @Composable () -> Unit
) = item(key = key, contentType = contentType) {
    var appeared by rememberSaveable { mutableStateOf(false) }
    val opacity = animateFloatAsState(
        targetValue = if (appeared || immediate) 1f else 0f,
        animationSpec = tween(if (immediate) 0 else 180),
        label = "detailSectionReveal"
    )
    LaunchedEffect(Unit) { appeared = true }
    Box(Modifier.fillMaxWidth().graphicsLayer {
        compositingStrategy = CompositingStrategy.ModulateAlpha
        alpha = if (immediate) 1f else opacity.value
    }) { content() }
}
