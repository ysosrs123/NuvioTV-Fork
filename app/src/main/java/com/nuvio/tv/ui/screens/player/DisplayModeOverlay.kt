@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.keepLtr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import kotlin.math.abs
import kotlin.math.roundToInt

private val ROW_HEIGHT = 18.dp
private val ROW_GAP = NuvioTheme.spacing.xxs

@Composable
fun DisplayModeOverlay(
    info: DisplayModeInfo?,
    isVisible: Boolean,
    onAnimationComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (info == null) return

    val items = buildList {
        add(stringResource(R.string.display_mode_refresh) to formatHz(info.refreshRate))
        info.measuredHz?.let { hz ->
            add(stringResource(R.string.display_mode_measured) to
                String.format(java.util.Locale.US, "%.3f Hz", hz) + if (info.seamless) " · QMS" else "")
        }
        if (info.width > 0 && info.height > 0) {
            add(stringResource(R.string.display_mode_resolution) to "${info.width}x${info.height}")
        }
    }
    val count = items.size
    val totalLineHeight = (ROW_HEIGHT.value * count) + (ROW_GAP.value * (count - 1))

    var frame by remember { mutableStateOf(StaggeredOverlayFrame.hidden(count)) }
    var animating by remember { mutableStateOf(false) }

    LaunchedEffect(isVisible, info) {
        if (isVisible) {
            animating = true
            runStaggeredOverlay(count, alreadyShown = frame.containerAlpha > 0f) { frame = it }
            animating = false
            onAnimationComplete()
        } else if (animating) {
            frame = StaggeredOverlayFrame.hidden(count)
            animating = false
            onAnimationComplete()
        }
    }

    if (frame.containerAlpha <= 0f) return

    Column(
        modifier = modifier
            .alpha(frame.containerAlpha)
            .padding(end = NuvioTheme.spacing.xxl, top = NuvioTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        horizontalAlignment = Alignment.End
    ) {
        val statusMessage = info.statusMessage?.takeIf { it.isNotBlank() }
        if (statusMessage != null) {
            Text(
                text = statusMessage,
                fontSize = 11.sp,
                color = NuvioTheme.colors.Secondary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        Row(verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier.padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(ROW_GAP),
                horizontalAlignment = Alignment.End
            ) {
                items.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier
                            .height(ROW_HEIGHT)
                            .alpha(frame.itemAlphas.getOrNull(index) ?: 0f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.first,
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.85f),
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.End
                        )
                        Text(
                            text = " - ",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.4f),
                        )
                        Text(
                            text = item.second.keepLtr(),
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.6f),
                            textAlign = TextAlign.End
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((totalLineHeight * frame.lineFraction).dp)
                    .clip(RoundedCornerShape(NuvioTheme.spacing.hairline))
                    .background(NuvioTheme.colors.Secondary)
            )
        }
    }
}

private fun formatHz(rate: Float): String {
    val rounded = (rate * 1000f).roundToInt() / 1000f
    val whole = rounded.roundToInt()
    val display = if (abs(rounded - whole.toFloat()) < 0.01f) {
        whole.toString()
    } else {
        String.format("%.3f", rounded)
    }
    return "$display Hz"
}
