package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.report.PlaybackRecorder
import com.nuvio.tv.core.report.recordingClock
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.nuvioControlSurface
import kotlinx.coroutines.delay

internal enum class MarkKey { PRESS, RELEASE, OTHER }

/** The mark happens on the confirm key going down, so it cannot be lost to a release that was swallowed elsewhere. */
internal fun markKey(keyCode: Int, action: Int, repeatCount: Int): MarkKey = when {
    !PauseOnTimeline.isConfirmKey(keyCode) -> MarkKey.OTHER
    PauseOnTimeline.firstPress(action, repeatCount) -> MarkKey.PRESS
    action == android.view.KeyEvent.ACTION_UP || action == android.view.KeyEvent.ACTION_DOWN -> MarkKey.RELEASE
    else -> MarkKey.OTHER
}

/**
 * Shown the whole time a problem is being recorded, in the top corner away from subtitles: the running time and
 * marks, then Mark and Stop. The buttons show only their icons until focused. With the player controls hidden, OK
 * puts the focus on Mark; a few seconds without a key hands it back to the player (Back does too, in PlayerScreen).
 */
@Composable
internal fun RecordingStrip(
    marks: Int,
    markFocus: FocusRequester,
    downFocus: FocusRequester?,
    leaveWhenIdle: Boolean,
    onMark: () -> Unit,
    onStop: () -> Unit,
    onInteraction: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stopFocus = remember { FocusRequester() }
    var markFocused by remember { mutableStateOf(false) }
    var stopFocused by remember { mutableStateOf(false) }
    var keys by remember { mutableIntStateOf(0) }
    var elapsedMs by remember { mutableLongStateOf(PlaybackRecorder.recordingElapsedMs()) }
    val focused = markFocused || stopFocused
    LaunchedEffect(Unit) {
        while (true) {
            elapsedMs = PlaybackRecorder.recordingElapsedMs()
            delay(1_000L)
        }
    }
    LaunchedEffect(focused, keys, leaveWhenIdle) {
        if (!focused || !leaveWhenIdle) return@LaunchedEffect
        delay(STRIP_IDLE_MS)
        onLeave()
    }
    val down: (() -> Unit)? = downFocus?.let { target ->
        fun() {
            runCatching { target.requestFocus() }
        }
    }
    val clock = recordingClock(elapsedMs)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val outwardFromMark = if (rtl) android.view.KeyEvent.KEYCODE_DPAD_RIGHT else android.view.KeyEvent.KEYCODE_DPAD_LEFT
    val outwardFromStop = if (rtl) android.view.KeyEvent.KEYCODE_DPAD_LEFT else android.view.KeyEvent.KEYCODE_DPAD_RIGHT
    Row(
        modifier = modifier.onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            val pressed = native.action == android.view.KeyEvent.ACTION_DOWN
            if (pressed) keys++
            if (!focused) return@onPreviewKeyEvent false
            when {
                markFocused && native.keyCode == outwardFromStop -> {
                    if (pressed) runCatching { stopFocus.requestFocus() }
                    true
                }
                stopFocused && native.keyCode == outwardFromMark -> {
                    if (pressed) runCatching { markFocus.requestFocus() }
                    true
                }
                !leaveWhenIdle -> false
                native.keyCode == outwardFromMark -> markFocused
                native.keyCode == outwardFromStop -> stopFocused
                else -> false
            }
        },
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (marks > 0) {
                pluralStringResource(R.plurals.report_recording_pill_marks, marks, clock, marks)
            } else {
                stringResource(R.string.report_recording_pill, clock)
            },
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier
                .nuvioControlSurface(RoundedCornerShape(50))
                .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm)
        )
        StripButton(
            icon = Icons.Default.Flag,
            label = stringResource(R.string.report_mark_problem),
            focused = markFocused,
            onClick = onMark,
            modifier = Modifier
                .focusRequester(markFocus)
                .onFocusChanged {
                    markFocused = it.isFocused
                    if (it.isFocused) onInteraction()
                }
                .focusProperties { end = stopFocus }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    when (markKey(native.keyCode, native.action, native.repeatCount)) {
                        MarkKey.PRESS -> {
                            onMark()
                            true
                        }
                        MarkKey.RELEASE -> true
                        MarkKey.OTHER -> if (native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && down != null) {
                            if (native.action == android.view.KeyEvent.ACTION_DOWN) down()
                            true
                        } else false
                    }
                }
        )
        StripButton(
            icon = Icons.Default.Stop,
            label = stringResource(R.string.report_stop_recording),
            focused = stopFocused,
            onClick = onStop,
            modifier = Modifier
                .focusRequester(stopFocus)
                .onFocusChanged {
                    stopFocused = it.isFocused
                    if (it.isFocused) onInteraction()
                }
                .focusProperties { start = markFocus }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && down != null) {
                        if (native.action == android.view.KeyEvent.ACTION_DOWN) down()
                        true
                    } else false
                }
        )
    }
}

@Composable
private fun StripButton(
    icon: ImageVector,
    label: String,
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isV2 = LocalV2Appearance.current != null
    Button(
        onClick = onClick,
        modifier = modifier.nuvioControlSurface(RoundedCornerShape(50)),
        colors = ButtonDefaults.colors(
            containerColor = if (isV2) Color.Transparent else Color.White.copy(alpha = 0.14f),
            focusedContainerColor = if (isV2) Color.Transparent else Color.White,
            contentColor = Color.White,
            focusedContentColor = if (isV2) Color.White else Color.Black
        ),
        scale = if (isV2) ButtonDefaults.scale(focusedScale = 1f) else ButtonDefaults.scale(),
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
        contentPadding = PaddingValues(horizontal = if (focused) NuvioTheme.spacing.md else NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.sm)
    ) {
        Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(18.dp))
        if (focused) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private const val STRIP_IDLE_MS = 4_000L
