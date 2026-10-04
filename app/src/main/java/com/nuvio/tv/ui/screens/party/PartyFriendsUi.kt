package com.nuvio.tv.ui.screens.party

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.party.PartyAvatars
import com.nuvio.tv.core.party.PartyFriend
import com.nuvio.tv.core.party.PartyRuntime
import com.nuvio.tv.core.party.PartyState
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.screens.player.DialogButton
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.nuvioGlass
import com.nuvio.tv.ui.v2.components.nuvioV2Focus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private const val DEFAULT_COLOUR = "#1E88E5"
private const val PAGE_FRIENDS = 4
private const val NOTICE_MS = 5_000L
private const val READY_AFTER_MS = 400L

@Composable
internal fun PartyAvatar(name: String, colour: String?, avatarUrl: String?, size: Dp = 34.dp) {
    ProfileAvatarCircle(
        name = name.ifBlank { "?" },
        colorHex = colour ?: DEFAULT_COLOUR,
        size = size,
        avatarImageUrl = PartyAvatars.imageUri(avatarUrl),
    )
}

private val dayFormat = DateTimeFormatter.ofPattern("d MMM")
private val dayYearFormat = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
internal fun lastTogetherLine(friend: PartyFriend): String {
    val day = remember(friend.lastTogetherAt) {
        val date = Instant.ofEpochMilli(friend.lastTogetherAt).atZone(ZoneId.systemDefault()).toLocalDate()
        (if (date.year == LocalDate.now().year) dayFormat else dayYearFormat).format(date)
    }
    return stringResource(R.string.party_friend_last, day)
}

/**
 * A friend as one selectable row: the whole row takes focus, so the remote reaches it from anywhere above or below,
 * and [action] names what OK does.
 */
@Composable
internal fun PartyFriendRow(
    friend: PartyFriend,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    done: Boolean = false,
) {
    PartyRowSurface(onClick = onClick, modifier = modifier) {
        PartyAvatar(friend.name, friend.colour, friend.avatarUrl)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = friend.name.ifBlank { stringResource(R.string.party_member_unnamed) },
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = lastTogetherLine(friend),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
            )
            friend.lastTitle?.takeIf { it.isNotBlank() }?.let { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        PartyActionLabel(text = action, done = done)
    }
}

/** Whether the row around a [PartyActionLabel] has the focus: only the label lights up, the row stays plain. */
private val LocalPartyRowFocused = compositionLocalOf { false }

/** What OK does on a row, drawn like the app's buttons; it lights up while its row has the focus. */
@Composable
internal fun PartyActionLabel(text: String, done: Boolean = false, quiet: Boolean = false) {
    val focused = LocalPartyRowFocused.current
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    val v2 = LocalV2Appearance.current != null
    val surface = when {
        quiet && !focused -> Modifier
        v2 -> Modifier.nuvioV2Focus(focused, shape, stationary = true).nuvioGlass(GlassRole.CONTROL, focused, shape)
        focused -> Modifier.clip(shape).background(NuvioTheme.colors.FocusBackground)
            .border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape)
        else -> Modifier.clip(shape).background(NuvioTheme.colors.BackgroundCard)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = when {
            done && !focused -> NuvioTheme.colors.Success
            v2 || quiet -> NuvioTheme.colors.TextPrimary
            focused -> NuvioTheme.colors.Primary
            else -> NuvioTheme.colors.TextSecondary
        },
        maxLines = 1,
        modifier = surface.padding(horizontal = 14.dp, vertical = 5.dp),
    )
}

/**
 * A full-width row the remote lands on as a whole (so it is reachable from anywhere above or below). The row itself
 * draws nothing when focused; its [PartyActionLabel] lights up instead.
 */
@Composable
internal fun PartyRowSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border.None),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        CompositionLocalProvider(LocalPartyRowFocused provides focused) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/** Invite, or "Cancel invite" once this friend was invited to the party this device is in. */
@Composable
internal fun PartyInviteRow(
    friend: PartyFriend,
    state: PartyState,
    invited: Map<String, String>,
    runtime: PartyRuntime,
    modifier: Modifier = Modifier,
) {
    val done = state.isActive && state.code != null && invited[friend.key] == state.code
    PartyFriendRow(
        friend = friend,
        action = stringResource(if (done) R.string.party_friend_cancel_invite else R.string.party_friend_invite),
        onClick = { if (done) runtime.cancelInvite(friend) else runtime.inviteFriend(friend) },
        done = done,
        modifier = modifier,
    )
}

/** The page's friends: the most recent few and a way to all of them. */
@Composable
internal fun PartyFriendsSection(
    state: PartyState,
    runtime: PartyRuntime,
    onShowAll: () -> Unit,
    modifier: Modifier = Modifier,
    firstFocus: FocusRequester? = null,
    limit: Int = PAGE_FRIENDS,
) {
    val friends by runtime.friends.collectAsState()
    val invited by runtime.invited.collectAsState()
    val activeProfile by runtime.activeProfileId.collectAsState()
    val profileName = remember(activeProfile) { runtime.profileName }
    val inRoom = state.members.mapNotNull { it.friendKey }.toSet()
    val shown = friends.filter { it.key !in inRoom }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
        Text(
            text = if (state.isActive) stringResource(R.string.party_invite_friends)
            else stringResource(R.string.party_friends_of_profile, profileName),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
        )
        shown.take(limit).forEachIndexed { index, friend ->
            PartyInviteRow(
                friend = friend,
                state = state,
                invited = invited,
                runtime = runtime,
                modifier = if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
        if (friends.isNotEmpty()) {
            PartyLinkRow(text = stringResource(R.string.party_friends_all, friends.size), onClick = onShowAll)
        }
        if (friends.isEmpty()) {
            Text(
                text = stringResource(R.string.party_friends_hint),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary,
            )
        }
    }
}

@Composable
internal fun PartyLinkRow(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PartyRowSurface(onClick = onClick, modifier = modifier) {
        PartyActionLabel(text = "$text  ›", quiet = true)
    }
}

/** All friends in two columns, newest first, with a mode to remove them. Drawn inside the party page. */
@Composable
internal fun PartyAllFriends(
    state: PartyState,
    runtime: PartyRuntime,
    onBack: () -> Unit,
    card: @Composable (Modifier, @Composable () -> Unit) -> Unit,
) {
    BackHandler { onBack() }
    val friends by runtime.friends.collectAsState()
    val invited by runtime.invited.collectAsState()
    var removing by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<PartyFriend?>(null) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(removing, friends.size) {
        delay(150)
        runCatching { firstFocus.requestFocus() }
    }
    LaunchedEffect(friends.isEmpty()) {
        if (friends.isEmpty()) onBack()
    }

    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
        Column {
            Text(
                text = stringResource(R.string.party_friends),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary,
            )
            Text(
                text = stringResource(R.string.party_friends_page_subtitle, friends.size),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
            )
        }
        card(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                friends.chunked(2).forEachIndexed { rowIndex, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)) {
                        pair.forEachIndexed { columnIndex, friend ->
                            val focus = if (rowIndex == 0 && columnIndex == 0) Modifier.focusRequester(firstFocus) else Modifier
                            if (removing) {
                                PartyFriendRow(
                                    friend = friend,
                                    action = stringResource(R.string.party_friend_remove),
                                    onClick = { confirm = friend },
                                    modifier = Modifier.weight(1f).then(focus),
                                )
                            } else {
                                PartyInviteRow(friend, state, invited, runtime, modifier = Modifier.weight(1f).then(focus))
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        card(Modifier.fillMaxWidth()) {
            PartyLinkRow(
                text = stringResource(if (removing) R.string.party_friends_remove_done else R.string.party_friends_remove),
                onClick = { removing = !removing },
            )
        }
    }

    confirm?.let { friend ->
        NuvioDialog(
            onDismiss = { confirm = null },
            title = stringResource(R.string.party_friend_remove_title, friend.name),
            subtitle = stringResource(R.string.party_friend_remove_body),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                DialogButton(
                    text = stringResource(R.string.party_friend_remove),
                    onClick = {
                        runtime.removeFriend(friend.key)
                        confirm = null
                    },
                    isPrimary = true,
                )
                DialogButton(text = stringResource(R.string.party_not_now), onClick = { confirm = null }, isPrimary = false)
            }
        }
    }
}

/** The invite list in the player's party panel; it scrolls inside the panel so the buttons below stay put. */
@Composable
internal fun PartyPanelInviteList(state: PartyState, runtime: PartyRuntime) {
    val friends by runtime.friends.collectAsState()
    val invited by runtime.invited.collectAsState()
    val inRoom = state.members.mapNotNull { it.friendKey }.toSet()
    val shown = friends.filter { it.key !in inRoom }
    if (shown.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
        Text(
            text = stringResource(R.string.party_invite_friends),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
        )
        Column(
            modifier = Modifier
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
        ) {
            shown.forEach { friend ->
                PartyInviteRow(friend, state, invited, runtime)
            }
        }
    }
}

/** Friend requests, invites and "now your friend" notes, wherever the person is in the app. */
@Composable
fun PartyFriendOverlays(runtime: PartyRuntime, onShown: () -> Unit) {
    val request by runtime.session.friendRequest.collectAsState()
    val invite by runtime.invite.collectAsState()
    val notice by runtime.friendNotice.collectAsState()
    val party by runtime.state.collectAsState()
    val activeProfile by runtime.activeProfileId.collectAsState()
    LaunchedEffect(request?.memberId, invite?.invite?.id) {
        if (request != null || invite != null) onShown()
    }

    notice?.let { current ->
        LaunchedEffect(current) {
            delay(NOTICE_MS)
            runtime.acknowledgeFriendNotice()
        }
        Box(modifier = Modifier.fillMaxSize().padding(top = 28.dp), contentAlignment = Alignment.TopCenter) {
            val shape = RoundedCornerShape(NuvioTheme.radii.md)
            Text(
                text = stringResource(R.string.party_friend_added, current.name),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier
                    .then(
                        if (LocalV2Appearance.current != null) Modifier.nuvioGlass(GlassRole.HUD, shape = shape)
                        else Modifier.clip(shape).background(NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.9f))
                    )
                    .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs),
            )
        }
    }

    val pendingRequest = request
    val pendingInvite = invite
    if (pendingRequest != null) key(pendingRequest.memberId) {
        PartyPopup(
            alignment = Alignment.Center,
            dim = true,
            onDismiss = { runtime.session.answerFriendRequest(accept = false) },
            avatar = { PartyAvatar(pendingRequest.name, pendingRequest.colour, pendingRequest.avatarUrl, size = 46.dp) },
            title = stringResource(R.string.party_friend_request_title, pendingRequest.name),
            body = stringResource(R.string.party_friend_request_body, pendingRequest.name),
            confirm = stringResource(R.string.party_friend_request_accept),
            onConfirm = { runtime.session.answerFriendRequest(accept = true) },
        )
    } else if (pendingInvite != null) key(pendingInvite.invite.id) {
        val card = pendingInvite.invite
        val otherProfile = pendingInvite.profileId != activeProfile
        val detail = if (card.title.isNullOrBlank()) {
            stringResource(R.string.party_invite_detail_nothing, card.host, card.watching)
        } else {
            stringResource(R.string.party_invite_detail, card.title, card.host, card.watching)
        }
        val leaving = if (party.isActive) "\n" + stringResource(R.string.party_invite_leaves_current) else ""
        val locked = runtime.needsProfileUnlock(pendingInvite)
        val forProfile = when {
            locked -> "\n" + stringResource(R.string.party_invite_for_locked_profile, pendingInvite.profileName)
            otherProfile -> "\n" + stringResource(R.string.party_invite_for_profile, pendingInvite.profileName)
            else -> ""
        }
        PartyPopup(
            alignment = Alignment.BottomEnd,
            dim = false,
            onDismiss = runtime::dismissInvite,
            avatar = { PartyAvatar(card.name, card.colour, card.avatarUrl, size = 40.dp) },
            title = if (otherProfile) stringResource(R.string.party_invite_title_for, card.name, pendingInvite.profileName)
            else stringResource(R.string.party_invite_title, card.name),
            body = detail + forProfile + leaving,
            confirm = when {
                locked -> stringResource(R.string.party_invite_later)
                otherProfile -> stringResource(R.string.party_invite_join_as, pendingInvite.profileName)
                else -> stringResource(R.string.party_invite_join)
            },
            onConfirm = runtime::joinInvite,
        )
    }
}

/** A focus-taking card: centred for questions, in the corner for invites. Back means Not now. */
@Composable
private fun PartyPopup(
    alignment: Alignment,
    dim: Boolean,
    onDismiss: () -> Unit,
    avatar: @Composable () -> Unit,
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(READY_AFTER_MS)
        ready = true
        runCatching { focus.requestFocus() }
    }
    PartyDialogWindow(onDismissRequest = { if (ready) onDismiss() }, dim = dim, alignment = alignment) {
        PopupCard {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                avatar()
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextPrimary,
                )
            }
            Text(text = body, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                DialogButton(
                    text = confirm,
                    onClick = { if (ready) onConfirm() },
                    isPrimary = true,
                    modifier = Modifier.focusRequester(focus),
                )
                DialogButton(
                    text = stringResource(R.string.party_not_now),
                    onClick = { if (ready) onDismiss() },
                    isPrimary = false,
                )
            }
        }
    }
}

@Composable
private fun PopupCard(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(NuvioTheme.radii.xl)
    Column(
        modifier = Modifier
            .width(460.dp)
            .partyFrost(shape)
            .padding(NuvioTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
    ) {
        content()
    }
}
