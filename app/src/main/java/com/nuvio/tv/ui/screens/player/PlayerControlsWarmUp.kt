package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import com.nuvio.tv.R
import com.nuvio.tv.data.local.PlayerControlAction
import com.nuvio.tv.ui.v2.player.V2PlayerActions

/** The first build of the controls is slow; doing it once behind the loading screen keeps the first show quick. */
internal fun shouldWarmPlayerControls(
    loadingOverlayVisible: Boolean,
    alreadyWarmed: Boolean,
    playerScreenActive: Boolean,
    controlsVisible: Boolean
): Boolean = loadingOverlayVisible && !alreadyWarmed && playerScreenActive && !controlsVisible

private val WARM_UP_BADGES = listOf(
    R.drawable.badge_res_2160,
    R.drawable.badge_remux,
    R.drawable.badge_dv,
    R.drawable.badge_truehd,
    R.drawable.badge_atmos,
    R.drawable.badge_ch_7_1
).map { PlayerBadge.BuiltIn(it) }

/** Invisible, unfocusable copy of the controls with its own focus state and no callbacks. */
@Composable
internal fun PlayerControlsWarmUp(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    available: Set<PlayerControlAction>,
    playerBadges: List<PlayerBadge>,
    reportCodeVisible: Boolean
) {
    val layout = uiState.controlLayout ?: return
    val focus = remember { PlayerCustomFocusState() }
    val requester = remember { FocusRequester() }
    val none: () -> Unit = {}
    val badges = if (uiState.showPlayerBadges) playerBadges.ifEmpty { WARM_UP_BADGES } else playerBadges
    Box(
        Modifier
            .fillMaxSize()
            .alpha(0f)
            .focusProperties {
                canFocus = false
                onEnter = { cancelFocusChange() }
            }
            .focusGroup()
    ) {
        PlayerCustomControls(
            state = uiState,
            viewModel = viewModel,
            layout = layout,
            available = available,
            focus = focus,
            progressFocus = requester,
            progressUpFocus = null,
            containerFocus = requester,
            hasSeekTimeline = !uiState.isLive,
            playerBadges = badges,
            onSkipAnchorChanged = {},
            actions = V2PlayerActions(
                playPause = none, playNext = none, seekToStart = none, episodes = none, sources = none,
                audio = none, subtitles = none, speed = none, aspect = none, engine = none, report = none,
                more = none, external = none, info = none, stats = none, interaction = none, hide = none
            ),
            reportCodeVisible = reportCodeVisible,
            warming = true
        )
    }
}
