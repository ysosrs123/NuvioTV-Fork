@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.CatchupScrub
import com.nuvio.tv.core.iptv.GuideProgramme
import com.nuvio.tv.core.iptv.LiveTimeshift
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.accentBrush
import kotlinx.coroutines.delay

@Composable
internal fun rememberCatchupPosition(state: IptvLiveState): State<Long?> = produceState<Long?>(null, state.player, state.catchup, state.catchupFrom, state.scrubTarget) {
    while (true) {
        val catchup = state.catchup
        val player = state.player
        value = state.scrubTarget ?: if (catchup != null && player != null) LiveTimeshift.position(catchup, state.catchupFrom, player.currentPosition) else null
        if (catchup == null) break
        delay(500)
    }
}

internal fun behindLive(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    val hours = seconds / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, (seconds / 60) % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
internal fun ScrubTimeline(programme: GuideProgramme?, position: Long?, now: Long, pending: Boolean, modifier: Modifier,
    focus: FocusRequester? = null, onScrub: ((Long) -> Unit)? = null, onFocused: () -> Unit = {}) {
    val ticking by produceState(maxOf(now, System.currentTimeMillis())) { while (true) { delay(1_000); value = System.currentTimeMillis() } }
    val current = maxOf(now, ticking)
    val at = position ?: current
    val bar = CatchupScrub.bar(programme, at, current)
    var focused by remember { mutableStateOf(false) }
    val interactive = onScrub != null
    Column(modifier.then(if (interactive) Modifier
        .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
            val step = CatchupScrub.STEP_MILLIS * if (native.repeatCount > 3) 4 else 1
            when (native.keyCode) {
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { onScrub?.invoke(step); true }
                AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { onScrub?.invoke(-step); true }
                else -> false
            }
        }
        .focusable() else Modifier), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
            val width = maxWidth
            val track = RoundedCornerShape(3.dp)
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(if (focused) 6.dp else 4.dp).clip(track)
                .background(NuvioTheme.colors.TextPrimary.copy(alpha = .16f))) {
                bar.live?.let { live -> Box(Modifier.fillMaxWidth(live).fillMaxHeight().background(NuvioTheme.colors.TextPrimary.copy(alpha = .14f))) }
                Box(Modifier.fillMaxWidth(bar.position).fillMaxHeight().background(NuvioTheme.palette.accentBrush()))
            }
            bar.live?.let { live ->
                Box(Modifier.align(Alignment.CenterStart).padding(start = (width * live - 1.dp).coerceAtLeast(0.dp)).width(2.dp).height(14.dp)
                    .background(NuvioTheme.colors.Error))
            }
            if (position != null) {
                val knob = if (focused) 16.dp else 10.dp
                Box(Modifier.align(Alignment.CenterStart).padding(start = (width * bar.position - knob / 2).coerceIn(0.dp, (width - knob).coerceAtLeast(0.dp)))
                    .size(knob).clip(CircleShape).background(if (pending) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.Secondary)
                    .then(if (focused) Modifier.border(2.dp, NuvioTheme.colors.FocusRing, CircleShape) else Modifier))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(clock(bar.startMillis), style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextTertiary)
            Spacer(Modifier.weight(1f))
            if (position != null) {
                Text(clock(at), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    color = if (pending) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextPrimary)
                Text(stringResource(R.string.iptv_scrub_behind, behindLive(current - at)), style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextSecondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(NuvioTheme.colors.Error))
                Text(stringResource(R.string.iptv_scrub_live_edge, clock(current)), style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextSecondary)
            }
            Spacer(Modifier.weight(1f))
            Text(clock(bar.endMillis), style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextTertiary)
        }
        if (focused && interactive) Text(stringResource(R.string.iptv_scrub_hint), style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.TextTertiary, maxLines = 1)
    }
}
