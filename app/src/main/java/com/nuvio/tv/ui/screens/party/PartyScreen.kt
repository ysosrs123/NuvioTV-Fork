package com.nuvio.tv.ui.screens.party

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Groups
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.party.PartyCode
import com.nuvio.tv.core.party.PartyHoldReason
import com.nuvio.tv.core.party.PartyMemberStatus
import com.nuvio.tv.core.party.PartyMemberView
import com.nuvio.tv.core.party.PartyRole
import com.nuvio.tv.core.party.PartyRuntime
import com.nuvio.tv.core.party.PartyState
import com.nuvio.tv.domain.model.SettingsPresentation
import com.nuvio.tv.ui.screens.player.DialogButton
import com.nuvio.tv.ui.screens.settings.SettingsPageAtmosphere
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.nuvioControlSurface
import com.nuvio.tv.ui.v2.components.nuvioGlass
import kotlinx.coroutines.delay

/** Settings > Watch party: start or join a party, or follow the one this device is in. */
@Composable
fun PartyScreen(onBackPress: () -> Unit) {
    BackHandler { onBackPress() }

    val runtime = rememberPartyRuntime()
    val state by runtime.state.collectAsState()
    val firstFocus = remember { FocusRequester() }
    var showLook by remember { mutableStateOf(false) }
    var lookChanges by remember { mutableIntStateOf(0) }
    val activeProfile by runtime.activeProfileId.collectAsState()
    LaunchedEffect(activeProfile) { showLook = false }
    var showAll by remember { mutableStateOf(false) }

    LaunchedEffect(state.isActive, state.role, showAll) {
        if (showAll) return@LaunchedEffect
        delay(150)
        runCatching { firstFocus.requestFocus() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SettingsPageAtmosphere()
        if (showAll) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 36.dp, vertical = 28.dp),
            ) {
                PartyAllFriends(
                    state = state,
                    runtime = runtime,
                    onBack = { showAll = false },
                    card = { modifier, content -> PartyCard(modifier = modifier) { content() } },
                )
            }
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight)
                        .padding(horizontal = 40.dp, vertical = 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                    ) {
                        if (state.isActive) {
                            PartyRoomStage(
                                state = state,
                                runtime = runtime,
                                firstFocus = firstFocus,
                                lookChanges = lookChanges,
                                onLook = { showLook = true },
                            )
                        } else {
                            PartyStartStage(
                                state = state,
                                runtime = runtime,
                                firstFocus = firstFocus,
                                lookChanges = lookChanges,
                                onLook = { showLook = true },
                            )
                        }
                    }
                    PartyCard(modifier = Modifier.width(if (state.isActive) 340.dp else 300.dp)) {
                        if (state.isActive) PartyMembers(state = state, runtime = runtime)
                        PartyFriendsSection(
                            state = state,
                            runtime = runtime,
                            onShowAll = { showAll = true },
                            limit = if (state.isActive) 2 else 4,
                        )
                    }
                }
            }
        }
    }

    if (showLook) {
        PartyLookDialog(runtime = runtime, onDone = {
            showLook = false
            lookChanges++
        })
    }
}

@Composable
private fun pageSubtitle(state: PartyState): String {
    if (!state.isActive) return stringResource(R.string.party_settings_subtitle)
    val count = state.members.size
    return if (state.role == PartyRole.HOST) {
        stringResource(R.string.party_page_hosting, count)
    } else {
        val host = state.members.firstOrNull { it.isHost && !it.isSelf }?.name
        if (host.isNullOrBlank()) stringResource(R.string.party_page_guest_unknown, count)
        else stringResource(R.string.party_page_guest, host, count)
    }
}

/** A small line in the accent colour above a headline. */
@Composable
private fun Overline(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 2.sp,
        color = NuvioTheme.colors.Secondary,
    )
}

/** Not in a party: a headline on the page's background, two large buttons and your look as a chip. */
@Composable
private fun PartyStartStage(
    state: PartyState,
    runtime: PartyRuntime,
    firstFocus: FocusRequester,
    lookChanges: Int,
    onLook: () -> Unit,
) {
    var joining by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    fun leaveCode() {
        joining = false
        code = ""
        invalid = false
    }

    fun join() {
        if (runtime.session.joinParty(code)) leaveCode() else invalid = true
    }

    BackHandler(enabled = joining) { leaveCode() }
    LaunchedEffect(joining) {
        if (!joining) {
            delay(100)
            runCatching { firstFocus.requestFocus() }
        }
    }

    Overline(stringResource(R.string.party_title))
    if (state.endedByHost) {
        Text(
            text = stringResource(R.string.party_ended_by_host),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextPrimary,
        )
    }
    Text(
        text = stringResource(R.string.party_stage_headline),
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
        color = NuvioTheme.colors.TextPrimary,
    )
    Text(
        text = stringResource(R.string.party_stage_lead),
        style = MaterialTheme.typography.bodyLarge,
        color = NuvioTheme.colors.TextSecondary,
        modifier = Modifier.widthIn(max = 480.dp),
    )
    if (joining) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PartyCodeField(
                value = code,
                onValueChange = { typed ->
                    code = typed.uppercase().filter(PartyCode::isAllowedChar).take(PartyCode.LENGTH)
                    invalid = false
                    runtime.session.acknowledgeEnded()
                },
                onDone = { if (code.length == PartyCode.LENGTH) join() },
                autoEdit = true,
            )
            DialogButton(
                text = stringResource(R.string.party_join),
                onClick = ::join,
                isPrimary = true,
                enabled = code.length == PartyCode.LENGTH,
            )
            DialogButton(text = stringResource(R.string.action_cancel), onClick = ::leaveCode, isPrimary = false)
        }
        if (invalid) {
            Text(
                text = stringResource(R.string.party_code_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.Error,
            )
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            StageButton(
                text = stringResource(R.string.party_start_title),
                icon = Icons.Default.Groups,
                isPrimary = true,
                onClick = { runtime.session.createParty() },
                modifier = Modifier.focusRequester(firstFocus),
            )
            StageButton(
                text = stringResource(R.string.party_join_title),
                icon = Icons.Default.Dialpad,
                isPrimary = false,
                onClick = { joining = true },
            )
        }
    }
    PartyLook(runtime = runtime, lookChanges = lookChanges, onLook = onLook)
    StepsLine()
}

/** Your name and avatar in parties as a chip; it opens [PartyLookDialog]. */
@Composable
private fun PartyLook(runtime: PartyRuntime, lookChanges: Int, onLook: () -> Unit) {
    val activeProfile by runtime.activeProfileId.collectAsState()
    val catalogueLoads by runtime.avatarCatalogLoads.collectAsState()
    val name = remember(activeProfile, lookChanges) { runtime.displayName.ifBlank { runtime.defaultName } }
    val avatar = remember(activeProfile, catalogueLoads, lookChanges) { runtime.partyAvatarChoice ?: runtime.profileAvatar() }
    PartyLookChip(name = name, avatar = avatar, colour = runtime.profileColour, onClick = onLook)
}

@Composable
private fun StepsLine() {
    val accent = NuvioTheme.colors.Secondary
    val steps = listOf(R.string.party_step_short_one, R.string.party_step_short_two, R.string.party_step_short_three)
        .map { stringResource(it) }
    val line = buildAnnotatedString {
        steps.forEachIndexed { index, step ->
            if (index > 0) append("  ·  ")
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append("${index + 1}") }
            append(" ")
            append(step)
        }
    }
    Text(
        text = line,
        style = MaterialTheme.typography.bodySmall,
        color = NuvioTheme.colors.TextTertiary,
        modifier = Modifier.padding(top = NuvioTheme.spacing.sm),
    )
}

/** A larger version of the app's dialog button for the page's two main actions. */
@Composable
private fun StageButton(text: String, icon: ImageVector, isPrimary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val v2 = LocalV2Appearance.current != null
    val shape = RoundedCornerShape(NuvioTheme.radii.lg)
    Button(
        onClick = onClick,
        modifier = modifier.nuvioControlSurface(shape),
        colors = ButtonDefaults.colors(
            containerColor = if (v2) Color.Transparent else if (isPrimary) NuvioTheme.colors.Secondary else NuvioTheme.colors.BackgroundCard,
            contentColor = if (v2) Color.White else if (isPrimary) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextSecondary,
            focusedContainerColor = if (v2) Color.Transparent else if (isPrimary) NuvioTheme.colors.SecondaryVariant else NuvioTheme.colors.FocusBackground,
            focusedContentColor = if (v2) Color.White else if (isPrimary) NuvioTheme.colors.OnSecondaryVariant else NuvioTheme.colors.Primary,
        ),
        border = if (v2) ButtonDefaults.border(border = Border.None, focusedBorder = Border.None) else ButtonDefaults.border(
            focusedBorder = Border(
                border = if (isPrimary) BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.SecondaryVariant)
                else NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = shape,
            ),
        ),
        shape = ButtonDefaults.shape(shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text = text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** The page's cards: glass under Nuvio V2 with the Glass presentation, a solid card otherwise. */
@Composable
private fun PartyCard(
    modifier: Modifier = Modifier,
    padding: Dp = NuvioTheme.spacing.lg,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.xl)
    val glass = LocalV2Appearance.current?.settingsPresentation == SettingsPresentation.GLASS
    Column(
        modifier = modifier
            .clip(shape)
            .then(
                if (glass) Modifier.nuvioGlass(GlassRole.PANEL, shape = shape)
                else Modifier.background(NuvioTheme.colors.BackgroundCard.copy(alpha = 0.72f))
            )
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        content = content,
    )
}

/** Six boxes for the code. Pressing OK opens the keyboard, like the app's other text fields. */
@Composable
private fun PartyCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    autoEdit: Boolean = false,
) {
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(autoEdit) }

    LaunchedEffect(editing) {
        if (editing) {
            fieldFocus.requestFocus()
            keyboard?.show()
        }
    }

    Surface(
        onClick = { editing = true },
        modifier = modifier,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md),
            ),
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .padding(NuvioTheme.spacing.xs)
                .focusRequester(fieldFocus)
                .onFocusChanged {
                    if (!it.isFocused && editing) {
                        editing = false
                        keyboard?.hide()
                    }
                },
            singleLine = true,
            cursorBrush = SolidColor(androidx.compose.ui.graphics.Color.Transparent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = {
                editing = false
                keyboard?.hide()
                onDone()
            }),
            decorationBox = { inner ->
                Box {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        repeat(PartyCode.LENGTH) { index ->
                            if (index == PartyCode.LENGTH / 2) Spacer(Modifier.width(8.dp))
                            val current = editing && index == value.length.coerceAtMost(PartyCode.LENGTH - 1)
                            Box(
                                modifier = Modifier
                                    .width(38.dp)
                                    .height(46.dp)
                                    .clip(RoundedCornerShape(NuvioTheme.radii.sm))
                                    .background(NuvioTheme.colors.BackgroundCard)
                                    .border(
                                        BorderStroke(
                                            if (current) NuvioTheme.spacing.xxs else NuvioTheme.spacing.hairline,
                                            if (current) NuvioTheme.colors.FocusRing else NuvioTheme.colors.Border,
                                        ),
                                        RoundedCornerShape(NuvioTheme.radii.sm),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = value.getOrNull(index)?.toString().orEmpty(),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = NuvioTheme.colors.TextPrimary,
                                )
                            }
                        }
                    }
                    Box(Modifier.size(1.dp)) { inner() }
                }
            },
        )
    }
}

/** In a party: the code as the headline, the phone codes under it, what plays and the room's buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PartyRoomStage(
    state: PartyState,
    runtime: PartyRuntime,
    firstFocus: FocusRequester,
    lookChanges: Int,
    onLook: () -> Unit,
) {
    val session = runtime.session
    val isHost = state.role == PartyRole.HOST
    val media = session.partyMedia
    Overline(pageSubtitle(state))
    rememberHostNotice(state)?.let { notice ->
        Text(
            text = notice,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.colors.Secondary,
        )
    }
    Text(
        text = PartyCode.display(state.code.orEmpty()),
        style = MaterialTheme.typography.displayLarge,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 7.sp,
        maxLines = 1,
        color = NuvioTheme.colors.TextPrimary,
    )
    if (isHost) PartyInviteQr(code = state.code.orEmpty(), large = true, captionBetween = true)
    Text(
        text = partyStatusLine(state),
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.TextSecondary,
    )
    if (media != null) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = media.poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(44.dp)
                    .height(66.dp)
                    .clip(RoundedCornerShape(NuvioTheme.radii.sm))
                    .background(NuvioTheme.colors.BackgroundCard),
            )
            Column {
                val title = listOfNotNull(
                    media.title.ifBlank { null },
                    media.episodeTitle?.takeIf { media.season != null },
                ).joinToString(" · ")
                Text(
                    text = stringResource(R.string.party_now_watching, title),
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioTheme.colors.TextPrimary,
                )
                val detail = listOfNotNull(
                    if (media.season != null && media.episode != null) "S${media.season} E${media.episode}" else null,
                    media.streamName?.lineSequence()?.firstOrNull()?.ifBlank { null },
                ).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
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
            if (!state.playingPartyTitle && media != null) {
                DialogButton(
                    text = stringResource(R.string.party_open_party_title),
                    onClick = { session.requestPartyTitle() },
                    isPrimary = true,
                    modifier = Modifier.focusRequester(firstFocus),
                )
            }
            DialogButton(
                text = stringResource(R.string.party_leave),
                onClick = { session.leaveParty() },
                isPrimary = false,
                modifier = if (state.playingPartyTitle || media == null) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
    PartyLook(runtime = runtime, lookChanges = lookChanges, onLook = onLook)
}

/** Who is in the party, for the column beside the code. */
@Composable
private fun PartyMembers(state: PartyState, runtime: PartyRuntime) {
    val friends by runtime.friends.collectAsState()
    Text(
        text = stringResource(R.string.party_in_the_party),
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.TextSecondary,
    )
    state.members.forEach { member ->
        val isFriend = member.friendKey != null && friends.any { it.key == member.friendKey }
        PartyMemberRow(
            member = member,
            canAdd = !member.isSelf && !isFriend,
            asked = member.id in state.friendAsked,
            onAdd = { runtime.session.addFriend(member.id) },
        )
    }
    Spacer(Modifier.height(NuvioTheme.spacing.xs))
}

@Composable
private fun PartyMemberRow(member: PartyMemberView, canAdd: Boolean, asked: Boolean, onAdd: () -> Unit) {
    val name = member.name.ifBlank { stringResource(R.string.party_member_unnamed) }
    val role = when {
        member.isSelf && member.isHost -> stringResource(R.string.party_member_role_you_host)
        member.isSelf -> stringResource(R.string.party_member_role_you)
        member.isHost -> stringResource(R.string.party_member_role_host)
        else -> null
    }
    val (status, color) = when (member.status) {
        PartyMemberStatus.IN_SYNC -> stringResource(R.string.party_member_in_sync) to NuvioTheme.colors.Success
        PartyMemberStatus.SYNCING -> stringResource(R.string.party_member_syncing) to NuvioTheme.colors.TextSecondary
        PartyMemberStatus.BUFFERING -> stringResource(R.string.party_member_buffering) to NuvioTheme.colors.Warning
        PartyMemberStatus.CATCHING_UP -> stringResource(R.string.party_member_catching_up) to NuvioTheme.colors.Warning
        PartyMemberStatus.AWAY -> stringResource(R.string.party_member_away) to NuvioTheme.colors.TextTertiary
    }
    val content: @Composable RowScope.() -> Unit = {
        PartyAvatar(name = name, colour = member.colour, avatarUrl = member.avatarUrl)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
            Text(text = name, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
            if (canAdd) {
                PartyActionLabel(
                    text = stringResource(if (asked) R.string.party_friend_asked else R.string.party_friend_add),
                    done = asked,
                )
            } else if (role != null) {
                Text(text = role, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
            }
        }
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier
                .clip(RoundedCornerShape(NuvioTheme.radii.md))
                .background(NuvioTheme.colors.BackgroundCard)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
    if (canAdd) {
        PartyRowSurface(onClick = { if (!asked) onAdd() }, content = content)
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
