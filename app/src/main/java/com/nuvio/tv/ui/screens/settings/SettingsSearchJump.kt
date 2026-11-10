package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import com.nuvio.tv.ui.theme.NuvioTheme

private const val SCROLL_STEP_OF_PAGE = 0.7f

internal data class SettingsJumpTarget(val id: Long, val title: String, val entry: SettingsSearchEntry)

@Stable
internal class SettingsJumpState {
    var target by mutableStateOf<SettingsJumpTarget?>(null)
        private set
    var landedTitle by mutableStateOf<String?>(null)
        private set
    private var nextId = 0L

    val busy: Boolean get() = target != null || landedTitle != null

    fun start(entry: SettingsSearchEntry, title: String) {
        landedTitle = null
        nextId += 1L
        target = SettingsJumpTarget(nextId, title, entry)
    }

    fun land() {
        landedTitle = target?.title
        target = null
    }

    fun giveUp() {
        target = null
    }

    fun clearLanded() {
        landedTitle = null
    }
}

internal val LocalSettingsJump = compositionLocalOf<SettingsJumpState?> { null }

/** Lets a settings row take focus when a search result points at it, whichever page it is on. */
@Composable
internal fun Modifier.settingsJumpAnchor(title: String, shape: Shape): Modifier {
    val jump = LocalSettingsJump.current ?: return this
    val requester = remember { FocusRequester() }
    val target = jump.target?.takeIf { it.title == title }
    LaunchedEffect(target?.id) {
        val id = target?.id ?: return@LaunchedEffect
        while (jump.target?.id == id) {
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) {
                jump.land()
            } else {
                withFrameNanos { }
            }
        }
    }
    val anchored = focusRequester(requester)
    return if (jump.landedTitle == title) {
        anchored.border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape)
    } else {
        anchored
    }
}

/**
 * A lazy list only builds the rows near what it shows, so a row further down cannot take focus yet.
 * Walks the list down from [startIndex] until the row has been built and has taken focus.
 */
@Composable
internal fun SettingsJumpScroll(
    listState: LazyListState,
    category: SettingsCategory,
    startIndex: Int = 0
) {
    val jump = LocalSettingsJump.current ?: return
    val id = jump.target?.takeIf { it.entry.category == category }?.id
    val walked = remember { booleanArrayOf(false) }
    LaunchedEffect(id) {
        if (id == null) {
            // A row that never came leaves the list at the bottom; the page's first row has to exist again.
            if (walked[0] && jump.landedTitle == null) runCatching { listState.scrollToItem(0) }
            walked[0] = false
            return@LaunchedEffect
        }
        walked[0] = true
        withFrameNanos { }
        if (jump.target?.id != id) return@LaunchedEffect
        runCatching { listState.scrollToItem(startIndex.coerceAtLeast(0)) }
        while (jump.target?.id == id) {
            withFrameNanos { }
            withFrameNanos { }
            if (jump.target?.id != id || !listState.canScrollForward) break
            runCatching { listState.scrollBy(listState.layoutInfo.viewportSize.height * SCROLL_STEP_OF_PAGE) }
        }
    }
}
