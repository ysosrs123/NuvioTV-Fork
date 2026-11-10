package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

internal enum class MarkKey { PRESS, RELEASE, OTHER }

/** The mark happens on the confirm key going down, so it cannot be lost to a release that was swallowed elsewhere. */
internal fun markKey(keyCode: Int, action: Int, repeatCount: Int): MarkKey = when {
    !PauseOnTimeline.isConfirmKey(keyCode) -> MarkKey.OTHER
    PauseOnTimeline.firstPress(action, repeatCount) -> MarkKey.PRESS
    action == android.view.KeyEvent.ACTION_UP || action == android.view.KeyEvent.ACTION_DOWN -> MarkKey.RELEASE
    else -> MarkKey.OTHER
}

/**
 * The two controls shown with the player controls while a problem is being recorded. The mark button is the one the
 * OK key lands on, so OK twice marks on any remote.
 */
@Composable
internal fun RecordingMarkControls(
    markFocus: FocusRequester,
    downFocus: FocusRequester?,
    onMark: () -> Unit,
    onStop: () -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stopFocus = remember { FocusRequester() }
    val down: (() -> Unit)? = downFocus?.let { target ->
        fun() {
            runCatching { target.requestFocus() }
        }
    }
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
        PillControlButton(
            icon = Icons.Default.Flag,
            label = stringResource(R.string.report_mark_problem),
            onClick = onMark,
            modifier = Modifier.onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                when (markKey(native.keyCode, native.action, native.repeatCount)) {
                    MarkKey.PRESS -> {
                        onMark()
                        true
                    }
                    MarkKey.RELEASE -> true
                    MarkKey.OTHER -> false
                }
            },
            focusRequester = markFocus,
            onDownKey = down,
            onFocused = onInteraction,
            labelMaxLines = 1
        )
        PillControlButton(
            icon = Icons.Default.Stop,
            label = stringResource(R.string.report_stop_recording),
            onClick = onStop,
            focusRequester = stopFocus,
            onDownKey = down,
            onFocused = onInteraction,
            labelMaxLines = 1
        )
    }
}
