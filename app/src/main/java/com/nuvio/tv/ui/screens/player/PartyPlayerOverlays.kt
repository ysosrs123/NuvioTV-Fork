package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.party.PartyState
import com.nuvio.tv.ui.screens.party.PartyRoomContent
import com.nuvio.tv.ui.screens.party.partyHoldLine
import com.nuvio.tv.ui.screens.party.partyStatusLine
import com.nuvio.tv.ui.screens.party.rememberPartyRuntime
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.nuvioGlass
import kotlinx.coroutines.delay

/** The watch party panel inside the player: start a party from what is playing, or see and manage the room. */
@Composable
internal fun PartyPanelOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val runtime = rememberPartyRuntime()
    val state by runtime.state.collectAsState()
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(visible, state.isActive, state.role) {
        if (visible) {
            delay(120)
            runCatching { firstFocus.requestFocus() }
        }
    }

    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xxxl, vertical = 36.dp),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(520.dp)
                .then(
                    if (LocalV2Appearance.current != null) {
                        Modifier.nuvioGlass(GlassRole.PANEL, shape = RoundedCornerShape(NuvioTheme.radii.xxl))
                    } else {
                        Modifier
                            .clip(RoundedCornerShape(NuvioTheme.radii.xl))
                            .background(NuvioTheme.colors.BackgroundElevated)
                    }
                )
                .padding(NuvioTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        ) {
            Text(
                text = stringResource(R.string.party_title),
                style = MaterialTheme.typography.headlineSmall,
                color = NuvioTheme.colors.TextPrimary,
            )
            if (state.isActive) {
                PartyRoomContent(
                    state = state,
                    runtime = runtime,
                    firstFocus = firstFocus,
                    canStartTogether = true,
                    canShareLink = runtime.session.attachedMedia?.sharedUrl != null,
                )
            } else {
                val canCreate = runtime.session.attachedMedia != null
                Text(
                    text = stringResource(if (canCreate) R.string.party_create_description else R.string.party_not_available),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    DialogButton(
                        text = stringResource(R.string.party_create),
                        onClick = { runtime.session.createParty() },
                        isPrimary = true,
                        enabled = canCreate,
                        modifier = Modifier.focusRequester(firstFocus),
                    )
                    DialogButton(
                        text = stringResource(R.string.party_close),
                        onClick = onDismiss,
                        isPrimary = false,
                        modifier = if (canCreate) Modifier else Modifier.focusRequester(firstFocus),
                    )
                }
            }
        }
    }
}

/** A small line in the corner while the controls are up, so a member can see where they stand. */
@Composable
internal fun PartyStatusChip(state: PartyState, modifier: Modifier = Modifier) {
    PartyPill(
        text = stringResource(R.string.party_chip, state.members.size, partyStatusLine(state)),
        modifier = modifier,
    )
}

/** A few seconds of "X is now the host" when the host changes, with or without the controls. */
@Composable
internal fun PartyHostNotice(state: PartyState, modifier: Modifier = Modifier) {
    val notice = com.nuvio.tv.ui.screens.party.rememberHostNotice(state) ?: return
    PartyPill(text = notice, modifier = modifier)
}

/** Shown to everyone while the room is paused for a member, with or without the controls. */
@Composable
internal fun PartyHoldBanner(state: PartyState, modifier: Modifier = Modifier) {
    val hold = state.hold ?: return
    PartyPill(text = partyHoldLine(hold), modifier = modifier)
}

@Composable
private fun PartyPill(text: String, modifier: Modifier = Modifier) {
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
            maxLines = 1,
        )
    }
}
