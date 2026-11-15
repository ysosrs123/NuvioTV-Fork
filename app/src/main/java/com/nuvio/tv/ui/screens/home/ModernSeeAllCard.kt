package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CardDepthSurface
import com.nuvio.tv.ui.components.NuvioCard
import com.nuvio.tv.ui.components.nuvioCardDepth
import com.nuvio.tv.ui.components.LocalCardDepthStyle
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.theme.NuvioTheme

/** Last card of a Home row cut short at its title limit: opens the whole catalog. */
@Composable
internal fun ModernSeeAllCard(
    width: Dp,
    height: Dp,
    cornerRadius: Dp,
    focusRequester: FocusRequester,
    onFocusGained: () -> Unit,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val isV2 = LocalV2Appearance.current != null
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val ring = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs)
    val focusedBorder = if (isV2) Border.None else remember(shape, ring) { Border(border = ring, shape = shape) }
    var focused by remember { mutableStateOf(false) }
    NuvioCard(
        onClick = onClick,
        shape = shape,
        light = isV2,
        containerColor = NuvioTheme.colors.BackgroundCard,
        focusedBorder = focusedBorder,
        modifier = Modifier
            .zIndex(if (focused) 1f else 0f)
            .width(width)
            .height(height)
            .rowFocusRing(focused, shape)
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    onFocusGained()
                    onFocused()
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nuvioCardDepth(shape = shape, surface = CardDepthSurface.POSTERS, style = LocalCardDepthStyle.current),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(NuvioTheme.spacing.xxl),
                    tint = NuvioTheme.colors.TextSecondary
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                Text(
                    text = stringResource(R.string.action_see_all),
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioTheme.colors.TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
