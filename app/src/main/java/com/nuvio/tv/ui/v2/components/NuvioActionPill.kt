package com.nuvio.tv.ui.v2.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NuvioActionPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    neutralBackdrop: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = remember { RoundedCornerShape(12.dp) }
    val cinematic = com.nuvio.tv.ui.v2.appearance.LocalV2Appearance.current?.visualStyle ==
        com.nuvio.tv.domain.model.VisualStyle.CINEMATIC_GLASS
    val surface = if (cinematic) Modifier.nuvioGlass(GlassRole.CONTROL, focused, shape, neutralBackdrop = neutralBackdrop)
        else Modifier.background(NuvioTheme.colors.BackgroundElevated, shape)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.onFocusChanged { focused = it.isFocused }
            .nuvioV2Focus(focused, shape)
            .then(surface),
        shape = ButtonDefaults.shape(shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = NuvioTheme.colors.Secondary.copy(alpha = if (cinematic) .16f else .24f),
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContentColor = NuvioTheme.colors.TextPrimary
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp, vertical = 14.dp),
        content = content
    )
}
