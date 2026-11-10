package com.nuvio.tv.ui.scout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.domain.model.ScoutAskTarget
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.PanelActionRow
import kotlinx.coroutines.delay

private const val SETTLE_AFTER_PLAYER_MS = 500L

/** Asks "How was it?" over whichever page the player returned to. */
@Composable
fun ScoutAskHost(isPlayerShowing: Boolean, viewModel: ScoutRatingViewModel = hiltViewModel()) {
    val pending by viewModel.pendingAsk.collectAsStateWithLifecycle()
    var showing by remember { mutableStateOf<ScoutAskTarget?>(null) }

    LaunchedEffect(pending, isPlayerShowing) {
        val target = pending
        if (target == null) {
            showing = null
            return@LaunchedEffect
        }
        if (isPlayerShowing) return@LaunchedEffect
        delay(SETTLE_AFTER_PLAYER_MS)
        if (viewModel.shouldAsk(target)) showing = target else viewModel.dropAsk()
    }

    val target = showing ?: return
    val focus = remember(target) { FocusRequester() }
    NuvioDialog(
        onDismiss = { viewModel.answerAsk(target, null) },
        title = stringResource(R.string.scout_ask_title, target.title.name),
        subtitle = stringResource(R.string.scout_ask_subtitle)
    ) {
        ScoutRateRow(
            current = null,
            onRate = { viewModel.answerAsk(target, it) },
            firstFocus = focus
        )
        PanelActionRow(
            label = stringResource(R.string.scout_ask_skip),
            onClick = { viewModel.answerAsk(target, null) }
        )
    }
    LaunchedEffect(target) {
        focus.requestFocusAfterFrames()
    }
}
