package com.nuvio.tv.ui.screens.party

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.party.PartyAvatars
import com.nuvio.tv.core.party.PartyRuntime
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.screens.player.DialogButton
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.LocalGlassBackdrop
import com.nuvio.tv.ui.v2.components.LocalPopupGlassBackdrop
import com.nuvio.tv.ui.v2.components.nuvioControlSurface
import com.nuvio.tv.ui.v2.components.nuvioGlass
import com.nuvio.tv.ui.v2.quality.LocalGlassTokens
import kotlinx.coroutines.delay

private const val READY_AFTER_MS = 400L
private const val SAVED_SHOWN_MS = 2_000L
private const val AVATARS_PER_ROW = 7
private const val QUICK_PICKS = 7
private const val DEFAULT_TRANSPARENCY = 60
private const val FROST_BLURRED = 0.62f
private const val FROST_FLAT = 0.80f
private const val FROST_PER_PERCENT = 0.003f

/**
 * The party pop-ups' frosted glass: the app's pop-up glass with a denser wash in the theme's background colour, so
 * text behind does not show through. The Glass transparency setting still makes it a little lighter or darker.
 */
@Composable
internal fun Modifier.partyFrost(shape: Shape): Modifier {
    val appearance = LocalV2Appearance.current
        ?: return clip(shape).background(NuvioTheme.colors.BackgroundElevated)
    val base = if (LocalGlassTokens.current.liveBlur) FROST_BLURRED else FROST_FLAT
    val wash = (base + (DEFAULT_TRANSPARENCY - appearance.glassTransparencyPercent) * FROST_PER_PERCENT).coerceIn(0.5f, 0.92f)
    val colour = NuvioTheme.colors.Background
    return clip(shape)
        .nuvioGlass(GlassRole.MODAL, shape = shape)
        .background(Brush.verticalGradient(listOf(colour.copy(alpha = wash * 0.9f), colour.copy(alpha = wash))), shape)
        .border(BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border), shape)
}

/** A separate window for the party's pop-ups, with the page captured behind it for the glass. */
@Composable
internal fun PartyDialogWindow(
    onDismissRequest: () -> Unit,
    dim: Boolean,
    alignment: Alignment,
    content: @Composable BoxScope.() -> Unit,
) {
    val v2 = LocalV2Appearance.current != null
    val activityDensity = LocalDensity.current
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(if (dim) 0.5f else 0f) }
        CompositionLocalProvider(
            LocalDensity provides if (v2) activityDensity else LocalDensity.current,
            LocalGlassBackdrop provides (LocalPopupGlassBackdrop.current?.state ?: LocalGlassBackdrop.current),
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(NuvioTheme.spacing.xl), contentAlignment = alignment, content = content)
        }
    }
}

/** A resting tile in the theme's style: quiet glass under Nuvio V2, the card colour under Original. */
@Composable
private fun Modifier.partyTile(shape: Shape): Modifier =
    if (LocalV2Appearance.current != null) clip(shape).nuvioGlass(GlassRole.CONTROL, false, shape)
    else clip(shape).background(NuvioTheme.colors.BackgroundCard)

/** Your party look as a small chip on the page: avatar, name and a way to change them. */
@Composable
internal fun PartyLookChip(name: String, avatar: String?, colour: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    val v2 = LocalV2Appearance.current != null
    Surface(
        onClick = onClick,
        modifier = modifier.nuvioControlSurface(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (v2) Color.Transparent else NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = if (v2) Color.Transparent else NuvioTheme.colors.FocusBackground,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = if (v2) Border.None
            else Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape),
        ),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier.padding(start = 5.dp, end = NuvioTheme.spacing.lg, top = 5.dp, bottom = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PartyAvatar(name, colour, avatar, size = 38.dp)
            Column {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.party_look_chip_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

private enum class LookTab { PROFILE, NUVIO, FUN }

internal fun bundledAvatarSets(context: Context): List<Pair<String, List<String>>> =
    PartyAvatars.SETS.mapNotNull { set ->
        val names = runCatching { context.assets.list("${PartyAvatars.ASSET_DIR}/$set") }.getOrNull()
            ?.filter { it.endsWith(".svg") }?.map { it.removeSuffix(".svg") }?.sorted().orEmpty()
        if (names.isEmpty()) null else set to names.map { PartyAvatars.ref(set, it) }
    }

/**
 * Name and avatar for parties, per profile. A preview shows how the others see you; picking an avatar saves it at
 * once, the name has its own Save. Back closes.
 */
@Composable
internal fun PartyLookDialog(runtime: PartyRuntime, onDone: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(runtime.displayName) }
    var savedName by remember { mutableStateOf(runtime.displayName) }
    var choice by remember { mutableStateOf(runtime.partyAvatarChoice) }
    var saves by remember { mutableIntStateOf(0) }
    var savedShown by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    val catalogueLoads by runtime.avatarCatalogLoads.collectAsState()
    val profileAvatar = remember(catalogueLoads) { runtime.profileAvatar() }
    val nuvio = remember(catalogueLoads) { runtime.nuvioAvatars() }
    val sets = remember { bundledAvatarSets(context) }
    val quickPicks = remember(nuvio, sets) { (nuvio.take(2) + sets.map { it.second.first() }).distinct().take(QUICK_PICKS) }
    var tab by remember {
        mutableStateOf(
            when {
                choice == null -> LookTab.PROFILE
                PartyAvatars.isBundled(choice) -> LookTab.FUN
                else -> LookTab.NUVIO
            }
        )
    }
    val tabFocus = remember { FocusRequester() }
    val shownName = name.ifBlank { runtime.defaultName }
    val colour = runtime.profileColour

    LaunchedEffect(Unit) {
        delay(READY_AFTER_MS)
        ready = true
        runCatching { tabFocus.requestFocus() }
    }
    LaunchedEffect(saves) {
        if (saves == 0) return@LaunchedEffect
        savedShown = true
        delay(SAVED_SHOWN_MS)
        savedShown = false
    }

    fun pick(ref: String?) {
        if (!ready) return
        choice = ref
        runtime.setPartyAvatar(ref)
        saves++
    }

    fun saveName() {
        runtime.displayName = name
        savedName = runtime.displayName
        name = savedName
        runtime.session.announceName()
        saves++
    }

    PartyDialogWindow(onDismissRequest = { if (ready) onDone() }, dim = true, alignment = Alignment.Center) {
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .width(760.dp)
                    .heightIn(max = maxHeight)
                    .partyFrost(RoundedCornerShape(NuvioTheme.radii.xl))
                    .padding(NuvioTheme.spacing.xl),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
            ) {
                LookPreview(
                    name = shownName,
                    avatar = choice ?: profileAvatar,
                    colour = colour,
                    saved = savedShown,
                    modifier = Modifier.width(196.dp).verticalScroll(rememberScrollState()),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.party_look_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = NuvioTheme.colors.TextPrimary,
                        )
                        Text(
                            text = stringResource(R.string.party_look_dialog_subtitle, runtime.profileName),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InputField(
                            value = name,
                            onValueChange = { name = it.take(PartyRuntime.MAX_NAME_LENGTH) },
                            placeholder = runtime.defaultName,
                            keyboardType = KeyboardType.Text,
                            onImeAction = { if (name.trim() != savedName) saveName() },
                            modifier = Modifier.weight(1f),
                        )
                        DialogButton(
                            text = stringResource(R.string.party_look_save_name),
                            onClick = { if (ready) saveName() },
                            isPrimary = false,
                            enabled = name.trim() != savedName,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                        listOf(
                            LookTab.PROFILE to R.string.party_look_tab_profile,
                            LookTab.NUVIO to R.string.party_look_tab_nuvio,
                            LookTab.FUN to R.string.party_look_tab_fun,
                        ).forEach { (value, label) ->
                            DialogButton(
                                text = stringResource(label),
                                onClick = { tab = value },
                                isPrimary = tab == value,
                                modifier = if (tab == value) Modifier.focusRequester(tabFocus) else Modifier,
                            )
                        }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(AVATARS_PER_ROW),
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 220.dp),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                    ) {
                        when (tab) {
                            LookTab.PROFILE -> {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    ProfilePictureCard(
                                        name = shownName,
                                        profileName = runtime.profileName,
                                        colour = colour,
                                        avatar = profileAvatar,
                                        inUse = choice == null,
                                        onClick = { pick(null) },
                                    )
                                }
                                if (quickPicks.isNotEmpty()) {
                                    item(span = { GridItemSpan(maxLineSpan) }) { SetCaption(stringResource(R.string.party_look_quick_picks)) }
                                    items(quickPicks) { ref ->
                                        AvatarCell(shownName, colour, ref, selected = ref == choice) { pick(ref) }
                                    }
                                }
                            }
                            LookTab.NUVIO -> items(nuvio) { ref ->
                                AvatarCell(shownName, colour, ref, selected = ref == choice) { pick(ref) }
                            }
                            LookTab.FUN -> sets.forEach { (set, refs) ->
                                item(span = { GridItemSpan(maxLineSpan) }) { SetCaption(stringResource(avatarSetTitle(set))) }
                                items(refs) { ref ->
                                    AvatarCell(shownName, colour, ref, selected = ref == choice) { pick(ref) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** How the others see you: big, then as a member row in a party and on a friend's invite card. */
@Composable
private fun LookPreview(name: String, avatar: String?, colour: String?, saved: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.party_look_preview).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.colors.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
        PartyAvatar(name, colour, avatar, size = 96.dp)
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.party_look_saved),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.colors.Success,
            modifier = Modifier.alpha(if (saved) 1f else 0f),
        )
        PreviewCaption(stringResource(R.string.party_look_preview_room))
        Row(
            modifier = Modifier.fillMaxWidth().partyTile(shape).padding(horizontal = NuvioTheme.spacing.sm, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PartyAvatar(name, colour, avatar, size = 24.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.party_member_role_you), style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
            }
            Text(stringResource(R.string.party_member_in_sync), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.Success)
        }
        PreviewCaption(stringResource(R.string.party_look_preview_invite))
        Row(
            modifier = Modifier.fillMaxWidth().partyTile(shape).padding(horizontal = NuvioTheme.spacing.sm, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PartyAvatar(name, colour, avatar, size = 24.dp)
            Text(
                text = stringResource(R.string.party_invite_title, name),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PreviewCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = NuvioTheme.colors.TextTertiary,
        modifier = Modifier.fillMaxWidth().padding(top = NuvioTheme.spacing.xs),
    )
}

@Composable
private fun SetCaption(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
}

/** The profile's own picture, large, with its name and colour; pressing it goes back to it. */
@Composable
private fun ProfilePictureCard(name: String, profileName: String, colour: String?, avatar: String?, inUse: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(NuvioTheme.radii.lg)
    PartyRowSurface(onClick = onClick, modifier = Modifier.partyTile(shape)) {
        PartyAvatar(name, colour, avatar, size = 72.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
            Text(
                text = stringResource(R.string.party_look_profile_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val dot = colour?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
                if (dot != null) Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
                Text(
                    text = if (avatar == null) stringResource(R.string.party_look_profile_none)
                    else stringResource(R.string.party_look_profile_detail, profileName),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                )
            }
        }
        PartyActionLabel(
            text = stringResource(if (inUse) R.string.party_look_in_use else R.string.party_look_use_this),
            done = inUse,
        )
    }
}

private fun avatarSetTitle(set: String): Int = when (set) {
    "fun-emoji" -> R.string.party_avatar_set_fun_emoji
    "animals" -> R.string.party_avatar_set_animals
    "adventurer" -> R.string.party_avatar_set_adventurer
    "lorelei" -> R.string.party_avatar_set_lorelei
    "notionists" -> R.string.party_avatar_set_notionists
    "bottts" -> R.string.party_avatar_set_bottts
    "pixel-art" -> R.string.party_avatar_set_pixel_art
    else -> R.string.party_avatar_set_thumbs
}

@Composable
private fun AvatarCell(name: String, colour: String?, avatar: String?, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(56.dp).nuvioControlSurface(CircleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.Secondary), shape = CircleShape) else Border.None,
            focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = CircleShape),
        ),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
            PartyAvatar(name, colour, avatar, size = 48.dp)
        }
    }
}
