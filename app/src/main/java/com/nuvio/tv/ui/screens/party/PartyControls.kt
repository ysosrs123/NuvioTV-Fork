package com.nuvio.tv.ui.screens.party

import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.party.PartyCode
import com.nuvio.tv.core.party.PartyHoldReason
import com.nuvio.tv.core.party.PartyHoldView
import com.nuvio.tv.core.party.PartyMemberStatus
import com.nuvio.tv.core.party.PartyMemberView
import com.nuvio.tv.core.party.PartyRole
import com.nuvio.tv.core.party.PartyRuntime
import com.nuvio.tv.core.party.PartyState
import com.nuvio.tv.core.party.PartyStatus
import com.nuvio.tv.core.party.PartyTransportProblem
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.ui.screens.player.DialogButton
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlin.math.abs
import kotlinx.coroutines.delay

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PartyEntryPoint {
    fun partyRuntime(): PartyRuntime
}

@Composable
internal fun rememberPartyRuntime(): PartyRuntime {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        EntryPointAccessors.fromApplication(context, PartyEntryPoint::class.java).partyRuntime()
    }
}

/** "X is now the host" for a few seconds after the host changes; null otherwise. */
@Composable
internal fun rememberHostNotice(state: PartyState): String? {
    var seen by remember { mutableIntStateOf(state.hostChanges) }
    var showing by remember { mutableStateOf(false) }
    LaunchedEffect(state.hostChanges) {
        if (state.hostChanges == seen) return@LaunchedEffect
        seen = state.hostChanges
        showing = true
        delay(HOST_NOTICE_MS)
        showing = false
    }
    if (!showing || !state.isActive) return null
    if (state.role == PartyRole.HOST) return stringResource(R.string.party_host_you)
    val host = state.members.firstOrNull { it.isHost && !it.isSelf }?.name
    return if (host.isNullOrBlank()) stringResource(R.string.party_host_changed) else stringResource(R.string.party_host_other, host)
}

private const val HOST_NOTICE_MS = 5_000L

/** One line that says how this device stands in the party, for the player chip and the panels. */
@Composable
internal fun partyStatusLine(state: PartyState): String = when {
    state.problem == PartyTransportProblem.DEVICE_CLOCK_REJECTED -> stringResource(R.string.party_problem_clock)
    state.problem == PartyTransportProblem.NO_RELAY_REACHABLE -> stringResource(R.string.party_problem_unreachable)
    state.status == PartyStatus.CONNECTING -> stringResource(R.string.party_connecting)
    state.status == PartyStatus.RECONNECTING -> stringResource(R.string.party_reconnecting)
    state.hold != null -> partyHoldLine(state.hold)
    state.role == PartyRole.GUEST && !state.hostPresent -> stringResource(R.string.party_waiting_for_host)
    state.role == PartyRole.GUEST && !state.playingPartyTitle -> stringResource(R.string.party_open_title)
    state.selfPaused -> stringResource(R.string.party_self_paused)
    state.catchingUp -> stringResource(R.string.party_catching_up)
    state.role == PartyRole.GUEST && state.driftMs != null && abs(state.driftMs) >= DRIFT_SHOWN_FROM_MS ->
        if (state.driftMs > 0) {
            stringResource(R.string.party_behind, formatSeconds(state.driftMs))
        } else {
            stringResource(R.string.party_ahead, formatSeconds(-state.driftMs))
        }
    else -> stringResource(R.string.party_in_sync)
}

@Composable
internal fun partyHoldLine(hold: PartyHoldView): String = when {
    hold.reason == PartyHoldReason.LOADING -> stringResource(R.string.party_hold_lining_up)
    hold.isSelf -> stringResource(R.string.party_hold_waiting_self, hold.secondsLeft)
    hold.memberName.isNullOrBlank() -> stringResource(R.string.party_hold_waiting, hold.secondsLeft)
    else -> stringResource(R.string.party_hold_waiting_for, hold.memberName, hold.secondsLeft)
}

@Composable
private fun memberLine(member: PartyMemberView): String {
    val name = member.name.ifBlank { stringResource(R.string.party_member_unnamed) }
    val who = when {
        member.isSelf && member.isHost -> stringResource(R.string.party_member_you_host, name)
        member.isSelf -> stringResource(R.string.party_member_you, name)
        member.isHost -> stringResource(R.string.party_member_host, name)
        else -> name
    }
    val status = when (member.status) {
        PartyMemberStatus.IN_SYNC -> stringResource(R.string.party_member_in_sync)
        PartyMemberStatus.SYNCING -> stringResource(R.string.party_member_syncing)
        PartyMemberStatus.BUFFERING -> stringResource(R.string.party_member_buffering)
        PartyMemberStatus.CATCHING_UP -> stringResource(R.string.party_member_catching_up)
        PartyMemberStatus.AWAY -> stringResource(R.string.party_member_away)
    }
    return stringResource(R.string.party_member_line, who, status)
}

/**
 * The invite as two QR codes that open a text message with it typed in: iPhones only take the first spelling of the
 * link, Android only the second.
 */
@Composable
internal fun PartyInviteQr(code: String, large: Boolean = false, captionBetween: Boolean = false) {
    if (code.isEmpty()) return
    val invite = stringResource(R.string.party_invite_text, PartyCode.display(code))
    val body = remember(invite) { java.net.URLEncoder.encode(invite, "UTF-8").replace("+", "%20") }
    Row(
        horizontalArrangement = Arrangement.spacedBy(if (captionBetween) NuvioTheme.spacing.lg else NuvioTheme.spacing.xxl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QrCell(message = "sms:/open?addresses=&body=$body", label = stringResource(R.string.party_qr_iphone), large = large)
        if (captionBetween) {
            Text(
                text = stringResource(R.string.party_invite_qr),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(140.dp),
            )
        }
        QrCell(message = "sms:?body=$body", label = stringResource(R.string.party_qr_android), large = large)
    }
}

@Composable
private fun QrCell(message: String, label: String, large: Boolean) {
    val bitmap = remember(message) {
        runCatching { QrCodeGenerator.generate(message, QR_PIXELS, margin = 1).asImageBitmap() }.getOrNull()
    } ?: return
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs),
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.party_qr_description, label),
            modifier = Modifier.size(if (large) QR_SIZE_LARGE else QR_SIZE),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.colors.TextPrimary,
        )
    }
}

private const val QR_PIXELS = 360
private val QR_SIZE = 76.dp
private val QR_SIZE_LARGE = 96.dp

private fun formatSeconds(ms: Long): String = "%.1f".format(ms / 1000.0)

private const val DRIFT_SHOWN_FROM_MS = 500L

/** The code, who is in the room, and what this device may change. Shared by the player panel and the settings screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PartyRoomContent(
    state: PartyState,
    runtime: PartyRuntime,
    firstFocus: FocusRequester,
    canStartTogether: Boolean,
    canShareLink: Boolean,
    modifier: Modifier = Modifier,
    inviteList: Boolean = true,
) {
    val session = runtime.session
    val isHost = state.role == PartyRole.HOST
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                Text(
                    text = stringResource(if (isHost) R.string.party_share_code else R.string.party_joined_code),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                )
                Text(
                    text = PartyCode.display(state.code.orEmpty()),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 6.sp,
                    color = NuvioTheme.colors.TextPrimary,
                )
            }
            if (isHost) PartyInviteQr(code = state.code.orEmpty())
        }
        if (isHost) {
            Text(
                text = stringResource(R.string.party_invite_qr),
                style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextTertiary,
            )
        }
        Text(
            text = partyStatusLine(state),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
        )
        state.durationMismatchMs?.let { difference ->
            Text(
                text = stringResource(
                    if (difference > 0) R.string.party_copy_longer else R.string.party_copy_shorter,
                    formatSeconds(abs(difference)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
            state.members.forEach { member ->
                Text(
                    text = memberLine(member),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                )
            }
        }
        if (inviteList) PartyPanelInviteList(state = state, runtime = runtime)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        ) {
            if (isHost) {
                DialogButton(
                    text = stringResource(
                        if (state.guestsControl) R.string.party_guests_control_on else R.string.party_guests_control_off
                    ),
                    onClick = { session.setGuestsControl(!state.guestsControl) },
                    isPrimary = false,
                    modifier = Modifier.focusRequester(firstFocus),
                )
                if (canShareLink) {
                    DialogButton(
                        text = stringResource(if (state.shareLink) R.string.party_share_link_on else R.string.party_share_link_off),
                        onClick = { session.setShareLink(!state.shareLink) },
                        isPrimary = false,
                    )
                }
                if (canStartTogether) {
                    DialogButton(
                        text = stringResource(R.string.party_start_together),
                        onClick = { session.startTogether() },
                        isPrimary = false,
                        enabled = state.members.size > 1 && state.hold == null,
                    )
                }
                if (state.hold?.reason == PartyHoldReason.BUFFERING) {
                    DialogButton(
                        text = stringResource(R.string.party_continue_without),
                        onClick = { session.continueWithoutWaiting() },
                        isPrimary = false,
                    )
                }
                DialogButton(
                    text = stringResource(R.string.party_end),
                    onClick = { session.endParty() },
                    isPrimary = false,
                )
            } else {
                DialogButton(
                    text = stringResource(R.string.party_leave),
                    onClick = { session.leaveParty() },
                    isPrimary = false,
                    modifier = Modifier.focusRequester(firstFocus),
                )
            }
        }
    }
}
