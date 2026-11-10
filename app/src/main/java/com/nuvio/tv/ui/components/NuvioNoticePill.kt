package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.nuvioGlass

@Composable
internal fun NuvioNoticePill(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Box(
        modifier = modifier
            .then(
                if (LocalV2Appearance.current != null) {
                    Modifier.nuvioGlass(GlassRole.HUD, shape = shape)
                } else {
                    Modifier
                        .clip(shape)
                        .background(NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.86f))
                }
            )
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
