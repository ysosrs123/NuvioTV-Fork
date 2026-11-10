@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.support

import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.domain.model.MemberTier
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.badgeStyle
import com.nuvio.tv.ui.components.rememberMemberBadgeGradientBrush
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.nuvioGlass
import com.nuvio.tv.ui.v2.components.nuvioV2Focus
import com.nuvio.tv.ui.v2.components.usesClearGlassTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val PatreonMembershipsUrl = "https://www.patreon.com/settings/memberships"

private val supportUrl: String
    get() = BuildConfig.SUPPORT_URL.takeIf { it.isNotBlank() } ?: "https://nuvio.tv/support"

@Composable
fun SupportNuvioDialog(
    onDismiss: () -> Unit,
    onSignIn: (() -> Unit)? = null,
    onContinueWithoutAccount: (() -> Unit)? = null,
    onSuggestionsHidden: () -> Unit = {},
    viewModel: SupportNuvioViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshStatus() }

    when (state.status) {
        SupportStatus.SUPPORTER -> SupportInviteDialog(
            state = state,
            onDismiss = onDismiss
        )
        SupportStatus.NOT_SUPPORTER, SupportStatus.UNAVAILABLE -> SupportGentleDialog(
            unavailable = state.status == SupportStatus.UNAVAILABLE,
            isRefreshing = state.isRefreshing,
            onDismiss = onDismiss,
            onCheckAgain = viewModel::checkAgain,
            onNoThanks = {
                viewModel.setSuggestionsHidden(true)
                onDismiss()
                onSuggestionsHidden()
            }
        )
        SupportStatus.SIGNED_OUT -> SupportInviteDialog(
            state = state,
            onDismiss = onDismiss,
            onSignIn = onSignIn,
            onContinueWithoutAccount = onContinueWithoutAccount
        )
        SupportStatus.CHECKING -> SupportCheckingDialog(onDismiss = onDismiss)
    }
}

@Composable
private fun SupportInviteDialog(
    state: SupportNuvioUiState,
    onDismiss: () -> Unit,
    onSignIn: (() -> Unit)? = null,
    onContinueWithoutAccount: (() -> Unit)? = null
) {
    val supporter = state.status == SupportStatus.SUPPORTER
    val firstButton = remember { FocusRequester() }
    LaunchedEffect(supporter) { firstButton.requestFocusAfterFrames() }

    NuvioDialog(
        onDismiss = onDismiss,
        title = "",
        width = 880.dp,
        usePlatformDefaultWidth = false,
        suppressFirstKeyUp = false,
        solidWithoutBlur = true,
        contentSpacing = NuvioTheme.spacing.lg
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            SupportHeader(
                title = stringResource(if (supporter) R.string.support_nuvio_thanks_title else R.string.support_nuvio_title),
                subtitle = stringResource(if (supporter) R.string.support_nuvio_thanks_subtitle else R.string.support_nuvio_subtitle)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)) {
                PerkGrid(perksOn = supporter, modifier = Modifier.weight(1f))
                Column(
                    modifier = Modifier.width(250.dp),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                ) {
                    SupportCard(modifier = Modifier.fillMaxWidth()) {
                        if (supporter) {
                            SupporterStatus(
                                tier = state.tier ?: MemberTier.SUPPORTER,
                                since = state.supporterSince,
                                passEndsAt = state.passEndsAt,
                                manageOnPatreon = state.manageOnPatreon
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.support_nuvio_already_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = NuvioTheme.colors.TextPrimary
                            )
                            Spacer(Modifier.height(NuvioTheme.spacing.xs))
                            Text(
                                text = stringResource(R.string.support_nuvio_already_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }
                    }
                    if (!supporter) {
                        SupportCard(modifier = Modifier.fillMaxWidth(), centered = true) {
                            SupportQr(url = supportUrl, size = 100.dp)
                            Spacer(Modifier.height(NuvioTheme.spacing.sm))
                            Text(
                                text = stringResource(R.string.support_nuvio_scan_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = NuvioTheme.colors.TextPrimary,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = stringResource(R.string.support_nuvio_scan_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                if (supporter) {
                    SupportDialogButton(stringResource(R.string.support_nuvio_close), onDismiss, focusRequester = firstButton)
                } else {
                    var first = true
                    if (onSignIn != null) {
                        SupportDialogButton(stringResource(R.string.support_nuvio_sign_in), onSignIn, focusRequester = firstButton)
                        first = false
                    }
                    if (onContinueWithoutAccount != null) {
                        SupportDialogButton(
                            stringResource(R.string.auth_qr_continue_without_account),
                            onContinueWithoutAccount,
                            focusRequester = if (first) firstButton else null
                        )
                        first = false
                    }
                    SupportDialogButton(
                        stringResource(R.string.support_nuvio_close),
                        onDismiss,
                        focusRequester = if (first) firstButton else null
                    )
                }
            }
        }
    }
}

@Composable
private fun SupportGentleDialog(
    unavailable: Boolean,
    isRefreshing: Boolean,
    onDismiss: () -> Unit,
    onCheckAgain: () -> Unit,
    onNoThanks: () -> Unit
) {
    val closeButton = remember { FocusRequester() }
    LaunchedEffect(Unit) { closeButton.requestFocusAfterFrames() }

    NuvioDialog(
        onDismiss = onDismiss,
        title = "",
        width = 680.dp,
        usePlatformDefaultWidth = false,
        suppressFirstKeyUp = false,
        solidWithoutBlur = true,
        contentSpacing = NuvioTheme.spacing.lg
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.support_nuvio_gentle_eyebrow),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextTertiary
                )
                Spacer(Modifier.height(NuvioTheme.spacing.xs))
                Text(
                    text = stringResource(R.string.support_nuvio_gentle_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioTheme.colors.TextPrimary
                )
                Spacer(Modifier.height(NuvioTheme.spacing.sm))
                Text(
                    text = stringResource(if (unavailable) R.string.support_nuvio_gentle_unavailable else R.string.support_nuvio_gentle_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
                Spacer(Modifier.height(NuvioTheme.spacing.md))
                PerkList(
                    listOf(
                        R.string.support_nuvio_perk_themes,
                        R.string.support_nuvio_perk_backgrounds,
                        R.string.support_nuvio_perk_avatars,
                        R.string.support_nuvio_perk_badge,
                        R.string.support_nuvio_perk_discord,
                        R.string.support_nuvio_perk_votes_chip
                    )
                )
            }
            Column(
                modifier = Modifier.width(150.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(NuvioTheme.spacing.lg))
                SupportQr(url = supportUrl, size = 96.dp)
                Spacer(Modifier.height(NuvioTheme.spacing.xs))
                Text(
                    text = stringResource(R.string.support_nuvio_gentle_scan),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(NuvioTheme.spacing.sm))
                SupportDialogButton(
                    label = stringResource(R.string.support_nuvio_paid_check_again),
                    onClick = onCheckAgain,
                    enabled = !isRefreshing,
                    quiet = true
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SupportDialogButton(stringResource(R.string.support_nuvio_close), onDismiss, focusRequester = closeButton)
            SupportDialogButton(stringResource(R.string.support_nuvio_no_thanks), onNoThanks)
            Text(
                text = stringResource(R.string.support_nuvio_no_thanks_hint),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SupportCheckingDialog(onDismiss: () -> Unit) {
    val closeButton = remember { FocusRequester() }
    LaunchedEffect(Unit) { closeButton.requestFocusAfterFrames() }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.support_nuvio_title),
        subtitle = stringResource(R.string.support_nuvio_checking),
        suppressFirstKeyUp = false,
        solidWithoutBlur = true
    ) {
        SupportDialogButton(stringResource(R.string.support_nuvio_close), onDismiss, focusRequester = closeButton)
    }
}

@Composable
private fun SupportHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
    }
}

private data class Perk(val title: Int, val description: Int, val plusOnly: Boolean = false)

private val perks = listOf(
    Perk(R.string.support_nuvio_perk_themes, R.string.support_nuvio_perk_themes_desc),
    Perk(R.string.support_nuvio_perk_backgrounds, R.string.support_nuvio_perk_backgrounds_desc),
    Perk(R.string.support_nuvio_perk_avatars, R.string.support_nuvio_perk_avatars_desc),
    Perk(R.string.support_nuvio_perk_badge, R.string.support_nuvio_perk_badge_desc),
    Perk(R.string.support_nuvio_perk_discord, R.string.support_nuvio_perk_discord_desc),
    Perk(R.string.support_nuvio_perk_votes, R.string.support_nuvio_perk_votes_desc, plusOnly = true)
)

@Composable
private fun PerkGrid(perksOn: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
        perks.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                row.forEach { perk -> PerkCard(perk, perksOn, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PerkCard(perk: Perk, on: Boolean, modifier: Modifier = Modifier) {
    SupportCard(modifier = modifier) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(perk.title),
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    modifier = Modifier.weight(1f, fill = false)
                )
                val tag = when {
                    on -> stringResource(R.string.support_nuvio_on_tag)
                    perk.plusOnly -> stringResource(R.string.support_nuvio_plus_tag)
                    else -> null
                }
                if (tag != null) {
                    Spacer(Modifier.width(NuvioTheme.spacing.sm))
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (on) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(NuvioTheme.radii.full))
                            .background(if (on) NuvioTheme.colors.Secondary else cardColor())
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = stringResource(perk.description),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
        }
    }
}

@Composable
private fun PerkList(labels: List<Int>) {
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
        labels.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                row.forEach { label ->
                    Text(
                        text = stringResource(label),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SupporterStatus(tier: MemberTier, since: String?, passEndsAt: String?, manageOnPatreon: Boolean) {
    val tierSize = remember { mutableStateOf(IntSize.Zero) }
    val tierBrush = rememberMemberBadgeGradientBrush(style = remember(tier) { tier.badgeStyle() }, size = tierSize.value)
    Text(
        text = stringResource(R.string.supporter_membership_you_are),
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.TextSecondary
    )
    Text(
        text = stringResource(
            when (tier) {
                MemberTier.SUPPORTER -> R.string.supporter_membership_tier_supporter
                MemberTier.SUPPORTER_PLUS -> R.string.supporter_membership_tier_supporter_plus
            }
        ),
        style = MaterialTheme.typography.headlineSmall.copy(brush = tierBrush),
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.onSizeChanged { tierSize.value = it }
    )
    Spacer(Modifier.height(NuvioTheme.spacing.xs))
    Text(
        text = stringResource(R.string.supporter_membership_thank_you) +
            (since?.let { " " + stringResource(R.string.supporter_membership_supporter_since, formatSupporterSince(it)) } ?: ""),
        style = MaterialTheme.typography.bodySmall,
        color = NuvioTheme.colors.TextSecondary
    )
    if (passEndsAt != null) {
        Spacer(Modifier.height(NuvioTheme.spacing.sm))
        Text(
            text = stringResource(R.string.support_nuvio_pass_until, formatSupporterSince(passEndsAt)),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary
        )
    }
    if (manageOnPatreon) {
        Spacer(Modifier.height(NuvioTheme.spacing.md))
        Row(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SupportQr(url = PatreonMembershipsUrl, size = 72.dp)
            Text(
                text = stringResource(R.string.support_nuvio_patreon_manage),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
        }
    }
}

@Composable
private fun SupportCard(
    modifier: Modifier = Modifier,
    centered: Boolean = false,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(if (isV2()) 16.dp else 8.dp))
            .background(cardColor())
            .padding(NuvioTheme.spacing.md),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
    ) {
        content()
    }
}

@Composable
private fun SupportQr(url: String, size: Dp) {
    val bitmap = remember(url) { runCatching { QrCodeGenerator.generate(url, 360, margin = 1) }.getOrNull() } ?: return
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(if (isV2()) 12.dp else 6.dp))
            .background(Color.White)
            .padding(6.dp)
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.cd_support_nuvio_qr),
            modifier = Modifier.size(size)
        )
    }
}

@Composable
private fun SupportDialogButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    quiet: Boolean = false,
    focusRequester: FocusRequester? = null
) {
    val v2 = isV2()
    var focused by remember { mutableStateOf(false) }
    val shape = if (v2) RoundedCornerShape(12.dp) else RoundedCornerShape(NuvioTheme.radii.full)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (v2) {
                    Modifier.nuvioV2Focus(focused, shape)
                        .then(if (quiet) Modifier else Modifier.nuvioGlass(GlassRole.CONTROL, focused, shape))
                } else {
                    Modifier
                }
            ),
        colors = ButtonDefaults.colors(
            containerColor = if (quiet || v2) Color.Transparent else cardColor(),
            focusedContainerColor = if (v2) Color.Transparent else NuvioTheme.colors.Secondary,
            contentColor = if (quiet) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary,
            focusedContentColor = if (v2) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.OnSecondary,
            disabledContainerColor = if (quiet) Color.Transparent else cardColor().copy(alpha = 0.5f),
            disabledContentColor = NuvioTheme.colors.TextTertiary
        ),
        shape = ButtonDefaults.shape(shape),
        scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Text(
            text = label,
            style = if (quiet) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun isV2(): Boolean = LocalV2Appearance.current != null

@Composable
private fun cardColor(): Color = when {
    usesClearGlassTheme() -> Color.Black.copy(alpha = 0.025f)
    isV2() -> NuvioTheme.colors.TextPrimary.copy(alpha = 0.055f)
    else -> NuvioTheme.colors.BackgroundCard
}

private fun formatSupporterSince(raw: String): String = runCatching {
    LocalDate.parse(raw.substringBefore('T')).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
}.getOrDefault(raw)
