package com.nuvio.tv.ui.screens.backup

import android.Manifest
import android.text.format.DateFormat
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.backup.BackupCrypto
import com.nuvio.tv.core.backup.BackupDocument
import com.nuvio.tv.core.backup.BackupHistory
import com.nuvio.tv.core.backup.BackupPart
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.PinPad
import com.nuvio.tv.ui.components.pinAfterKey
import com.nuvio.tv.ui.screens.addon.QrCodeOverlay
import com.nuvio.tv.ui.screens.profile.ProfilePinBoxes
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionButton
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionRow
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.Date

/** Puts focus back on the row that opened a dialog, the QR panel or the PIN screen once it closes. */
@Composable
internal fun ReturnFocusWhenClosed(state: BackupUiState, opener: FocusRequester?) {
    val open = state.dialog != null || state.pin != null || state.busy
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) {
            wasOpen = true
            return@LaunchedEffect
        }
        if (!wasOpen || opener == null) return@LaunchedEffect
        wasOpen = false
        kotlinx.coroutines.delay(150)
        runCatching { opener.requestFocus() }
    }
}

/** The QR overlay for the phone page, drawn over whatever holds the flow. */
@Composable
internal fun BackupPhoneOverlay(state: BackupUiState, viewModel: BackupViewModel, modifier: Modifier = Modifier) {
    val phone = state.dialog as? BackupDialog.Phone ?: return
    // The page closes when Arc leaves the screen, not only when the code is closed.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.dismissDialog() }
    QrCodeOverlay(
        qrBitmap = phone.qr,
        serverUrl = phone.url,
        instruction = stringResource(
            if (phone.saving) R.string.backup_phone_save_instruction else R.string.backup_phone_restore_instruction
        ),
        onClose = viewModel::dismissDialog,
        modifier = modifier
    )
}

/** Every dialog of backing up and restoring, shared by Settings and the first sign-in screen. */
@Composable
internal fun BackupFlow(state: BackupUiState, viewModel: BackupViewModel) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.permissionResult(granted)
    }
    LaunchedEffect(state.needsPermission) {
        if (state.needsPermission) permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    state.pin?.let { step ->
        BackupPinOverlay(
            step = step,
            busy = state.busy,
            errorMessage = state.pinError,
            onClearError = viewModel::clearPinError,
            onSubmit = viewModel::pinEntered,
            onSkip = viewModel::pinSkipped
        )
        return
    }

    if (state.busy) {
        NuvioDialog(onDismiss = {}, title = stringResource(R.string.backup_title), subtitle = stringResource(R.string.backup_working)) {}
        return
    }

    when (val dialog = state.dialog) {
        null, is BackupDialog.Phone -> Unit
        is BackupDialog.Message -> NuvioDialog(
            onDismiss = viewModel::dismissDialog,
            title = stringResource(R.string.backup_title),
            subtitle = dialog.text
        ) {
            val close = remember { FocusRequester() }
            SettingsDialogActionRow {
                SettingsDialogActionButton(stringResource(R.string.action_close), viewModel::dismissDialog, modifier = Modifier.focusRequester(close))
            }
            LaunchedEffect(Unit) { runCatching { close.requestFocus() } }
        }
        is BackupDialog.Saved -> NuvioDialog(
            onDismiss = viewModel::dismissDialog,
            title = stringResource(R.string.backup_saved_title),
            subtitle = stringResource(R.string.backup_saved_where, dialog.saved.file.displayName.removeSuffix(".json"))
        ) {
            val parts = dialog.saved.document.header.parts
            val focus = remember { FocusRequester() }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (BackupPart.PROFILES in parts || BackupPart.SETTINGS in parts) {
                    SummaryRow(stringResource(R.string.backup_part_profiles), dialog.saved.document.header.profileNames.joinToString(", "))
                }
                if (BackupPart.SETTINGS in parts) {
                    SummaryRow(stringResource(R.string.backup_part_settings), stringResource(R.string.backup_part_settings_sub))
                }
                if (BackupPart.HISTORY in parts) {
                    val counts = remember(dialog.saved) {
                        BackupHistory.counts(dialog.saved.document.entries[BackupPart.HISTORY].orEmpty())
                    }
                    SummaryRow(
                        stringResource(R.string.backup_part_history),
                        stringResource(R.string.backup_part_history_counts, counts.saved, counts.watched, counts.inProgress)
                    )
                }
                if (BackupPart.SECRETS in parts) {
                    SummaryRow(stringResource(R.string.backup_part_secrets), stringResource(R.string.backup_part_secrets_saved))
                }
                viewModel.sections.filter { it.id in dialog.saved.document.header.sections }.forEach { section ->
                    SummaryRow(stringResource(section.titleRes), stringResource(section.subtitleRes))
                }
                if (BackupPart.DEVICE in parts) {
                    SummaryRow(stringResource(R.string.backup_part_device), dialog.saved.document.header.device.model)
                }
                SummaryRow(
                    stringResource(R.string.backup_supporter),
                    stringResource(if (dialog.saved.supporter) R.string.backup_supporter_yes else R.string.backup_supporter_no)
                )
            }
            SettingsDialogActionRow {
                SettingsDialogActionButton(stringResource(R.string.backup_done), viewModel::dismissDialog)
                SettingsDialogActionButton(
                    stringResource(R.string.backup_also_phone),
                    { viewModel.openPhone(saving = true) },
                    primary = true,
                    modifier = Modifier.focusRequester(focus)
                )
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
        is BackupDialog.Choose -> NuvioDialog(
            onDismiss = viewModel::dismissDialog,
            title = stringResource(R.string.backup_choose_title),
            subtitle = if (dialog.items.isEmpty()) stringResource(R.string.backup_choose_none) else null
        ) {
            val first = remember { FocusRequester() }
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (dialog.items.isEmpty()) {
                    SettingsActionRow(
                        title = stringResource(R.string.backup_restore_phone),
                        subtitle = stringResource(R.string.backup_restore_phone_sub),
                        onClick = { viewModel.openPhone(saving = false) },
                        modifier = Modifier.focusRequester(first)
                    )
                }
                dialog.items.forEachIndexed { index, item ->
                    val header = item.document?.header
                    SettingsActionRow(
                        title = dateTime(context, header?.createdAtMs?.takeIf { it > 0 } ?: item.file.modifiedMs),
                        subtitle = listOfNotNull(
                            header?.device?.model?.takeIf { it.isNotBlank() },
                            header?.profileNames?.takeIf { it.isNotEmpty() }?.joinToString(", ")
                        ).joinToString(" · ").ifBlank { item.file.displayName },
                        onClick = { viewModel.pick(item) },
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier
                    )
                }
            }
            SettingsDialogActionRow {
                SettingsDialogActionButton(stringResource(R.string.action_cancel), viewModel::dismissDialog)
            }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        }
        is BackupDialog.Restore -> RestoreDialog(dialog.document, viewModel)
        BackupDialog.WhereFrom -> NuvioDialog(
            onDismiss = viewModel::dismissDialog,
            title = stringResource(R.string.backup_signin_button),
            subtitle = stringResource(R.string.backup_where_sub)
        ) {
            val first = remember { FocusRequester() }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.backups.firstOrNull()?.let { newest ->
                    SettingsActionRow(
                        title = stringResource(R.string.backup_where_box),
                        subtitle = stringResource(R.string.backup_newest, dateTime(context, newest.modifiedMs)),
                        onClick = viewModel::openChoose,
                        modifier = Modifier.focusRequester(first)
                    )
                }
                SettingsActionRow(
                    title = stringResource(R.string.backup_where_phone),
                    subtitle = stringResource(R.string.backup_where_phone_sub),
                    onClick = { viewModel.openPhone(saving = false) },
                    modifier = if (state.backups.isEmpty()) Modifier.focusRequester(first) else Modifier
                )
            }
            SettingsDialogActionRow {
                SettingsDialogActionButton(stringResource(R.string.action_cancel), viewModel::dismissDialog)
            }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        }
    }
}

@Composable
private fun SummaryRow(title: String, subtitle: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.xs)) {
        Text(text = title, style = androidx.tv.material3.MaterialTheme.typography.bodyLarge, color = NuvioTheme.colors.TextPrimary)
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RestoreDialog(document: BackupDocument, viewModel: BackupViewModel) {
    val context = LocalContext.current
    val header = document.header
    val available = buildList {
        if (document.entries[BackupPart.PROFILES].orEmpty().isNotEmpty()) add(BackupPart.PROFILES)
        if (document.entries[BackupPart.SETTINGS].orEmpty().isNotEmpty()) add(BackupPart.SETTINGS)
        if (document.entries[BackupPart.HISTORY].orEmpty().isNotEmpty()) add(BackupPart.HISTORY)
        if (document.sealedSecrets != null) add(BackupPart.SECRETS)
        if (document.entries[BackupPart.DEVICE].orEmpty().isNotEmpty()) add(BackupPart.DEVICE)
    }
    val sections = remember(document) { viewModel.sections.filter { it.id in header.sections } }
    val historyOff = viewModel.isSignedIn
    var chosen by remember(document) {
        mutableStateOf(available.toSet() - BackupPart.DEVICE - setOfNotNull(BackupPart.HISTORY.takeIf { historyOff }))
    }
    var chosenSections by remember(document) { mutableStateOf(sections.map { it.id }.toSet()) }
    val thisModel = remember { android.os.Build.MODEL.orEmpty() }
    val first = remember { FocusRequester() }
    NuvioDialog(
        onDismiss = viewModel::dismissDialog,
        title = stringResource(R.string.backup_restore_title),
        subtitle = stringResource(
            R.string.backup_restore_from,
            dateTime(context, header.createdAtMs),
            header.device.model.ifBlank { header.device.manufacturer }
        ),
        width = 620.dp
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val focusSectionFirst = available.none { it != BackupPart.DEVICE }
            val sectionRows: @Composable () -> Unit = {
                sections.forEachIndexed { index, section ->
                    SettingsToggleRow(
                        title = stringResource(section.titleRes),
                        subtitle = stringResource(
                            if (header.sections[section.id] == true) section.subtitleWithSecretsRes else section.subtitleRes
                        ),
                        checked = section.id in chosenSections,
                        onToggle = {
                            chosenSections = if (section.id in chosenSections) chosenSections - section.id else chosenSections + section.id
                        },
                        modifier = if (focusSectionFirst && index == 0) Modifier.focusRequester(first) else Modifier
                    )
                }
            }
            available.forEachIndexed { index, part ->
                if (part == BackupPart.DEVICE) sectionRows()
                val subtitle = when (part) {
                    BackupPart.PROFILES -> stringResource(R.string.backup_part_profiles_sub)
                    BackupPart.SETTINGS -> stringResource(R.string.backup_part_settings_sub)
                    BackupPart.SECRETS -> stringResource(R.string.backup_part_secrets_needs_pin)
                    BackupPart.HISTORY -> stringResource(
                        if (historyOff) R.string.backup_part_history_signed_in else R.string.backup_part_history_sub
                    )
                    BackupPart.DEVICE -> if (header.device.model == thisModel) {
                        stringResource(R.string.backup_part_device_same, header.device.model)
                    } else {
                        stringResource(R.string.backup_part_device_other, header.device.model, thisModel)
                    }
                }
                SettingsToggleRow(
                    title = stringResource(partTitle(part)),
                    subtitle = subtitle,
                    checked = part in chosen,
                    onToggle = { chosen = if (part in chosen) chosen - part else chosen + part },
                    enabled = part != BackupPart.HISTORY || !historyOff,
                    modifier = if (index == 0 && (!focusSectionFirst || sections.isEmpty())) Modifier.focusRequester(first) else Modifier
                )
            }
            if (BackupPart.DEVICE !in available) sectionRows()
        }
        Text(
            text = stringResource(R.string.backup_restore_note),
            style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary
        )
        if (!viewModel.isSignedIn) {
            Text(
                text = stringResource(R.string.backup_restore_signin_note),
                style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(stringResource(R.string.action_cancel), viewModel::dismissDialog)
            SettingsDialogActionButton(
                stringResource(R.string.backup_restore_button),
                { viewModel.restore(document, chosen, chosenSections) },
                primary = true,
                enabled = chosen.isNotEmpty() || chosenSections.isNotEmpty()
            )
        }
    }
    LaunchedEffect(document) { runCatching { first.requestFocus() } }
}

/** Shown when a restore partly failed, before Arc restarts. */
@Composable
internal fun BackupPartialRestorePrompt(onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    NuvioDialog(
        onDismiss = onDone,
        title = stringResource(R.string.backup_title),
        subtitle = stringResource(R.string.backup_restore_partial)
    ) {
        SettingsDialogActionRow {
            SettingsDialogActionButton(
                stringResource(R.string.backup_done),
                onDone,
                primary = true,
                modifier = Modifier.focusRequester(focus)
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** Asked once after a restore done while signed out. */
@Composable
internal fun BackupSignInPrompt(onSignIn: () -> Unit, onNotNow: () -> Unit) {
    val focus = remember { FocusRequester() }
    NuvioDialog(
        onDismiss = onNotNow,
        title = stringResource(R.string.party_nuvio_signin),
        subtitle = stringResource(R.string.backup_signin_prompt_body)
    ) {
        SettingsDialogActionRow {
            SettingsDialogActionButton(stringResource(R.string.party_not_now), onNotNow)
            SettingsDialogActionButton(
                stringResource(R.string.auth_signin_title),
                onSignIn,
                primary = true,
                modifier = Modifier.focusRequester(focus)
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

private fun partTitle(part: BackupPart): Int = when (part) {
    BackupPart.PROFILES -> R.string.backup_part_profiles
    BackupPart.SETTINGS -> R.string.backup_part_settings
    BackupPart.SECRETS -> R.string.backup_part_secrets
    BackupPart.HISTORY -> R.string.backup_part_history
    BackupPart.DEVICE -> R.string.backup_part_device
}

internal fun dateTime(context: android.content.Context, ms: Long): String {
    val date = Date(ms)
    return "${DateFormat.getMediumDateFormat(context).format(date)}, ${DateFormat.getTimeFormat(context).format(date)}"
}

/** Arc's own PIN screen and pad, for a backup PIN of 6 to 10 digits. */
@Composable
private fun BackupPinOverlay(
    step: BackupPinStep,
    busy: Boolean,
    errorMessage: String?,
    onClearError: () -> Unit,
    onSubmit: (String) -> Unit,
    onSkip: () -> Unit
) {
    var pin by remember(step) { mutableStateOf("") }
    val focusRequester = remember(step) { FocusRequester() }
    val cursorAlpha by rememberInfiniteTransition(label = "backupPinCursor").animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(520), repeatMode = RepeatMode.Reverse),
        label = "backupPinCursorAlpha"
    )
    val heading = stringResource(
        when (step) {
            BackupPinStep.Create -> R.string.backup_pin_create_heading
            is BackupPinStep.Confirm -> R.string.backup_pin_confirm_heading
            is BackupPinStep.Restore -> R.string.backup_pin_restore_heading
        }
    )
    val support = when {
        !errorMessage.isNullOrEmpty() -> errorMessage
        busy -> stringResource(R.string.backup_pin_checking)
        step is BackupPinStep.Restore -> stringResource(R.string.backup_pin_restore_support)
        step is BackupPinStep.Confirm -> stringResource(R.string.backup_pin_confirm_support)
        else -> stringResource(R.string.backup_pin_create_support)
    }
    val backHint = stringResource(
        if (step is BackupPinStep.Restore) R.string.backup_pin_restore_back else R.string.backup_pin_create_back
    )
    val boxes = (pin.length + 1).coerceIn(BackupCrypto.MIN_PIN_LENGTH, BackupCrypto.MAX_PIN_LENGTH)
    val canFinish = BackupCrypto.isValidPin(pin) && !busy

    fun finish() {
        if (!canFinish) return
        val submitted = pin
        pin = ""
        onSubmit(submitted)
    }

    LaunchedEffect(step) {
        repeat(2) { androidx.compose.runtime.withFrameNanos { } }
        runCatching { focusRequester.requestFocus() }
    }
    LaunchedEffect(errorMessage) { if (!errorMessage.isNullOrEmpty()) pin = "" }

    Dialog(
        onDismissRequest = onSkip,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        BackHandler(onBack = onSkip)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NuvioTheme.colors.Background)
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    when (native.keyCode) {
                        AndroidKeyEvent.KEYCODE_DEL, AndroidKeyEvent.KEYCODE_CLEAR -> {
                            if (!busy && pin.isNotEmpty()) {
                                pin = pin.dropLast(1)
                                onClearError()
                            }
                            true
                        }
                        AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (canFinish) { finish(); true } else false
                        }
                        else -> {
                            val digit = digitOf(native.keyCode)
                            if (digit != null && !busy && pin.length < BackupCrypto.MAX_PIN_LENGTH) {
                                pin += digit
                                onClearError()
                                true
                            } else false
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = NuvioTheme.spacing.huge),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BoxWithConstraints(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    val gap = 10.dp
                    val boxSize = min(90.dp, (maxWidth - gap * (boxes - 1)) / boxes)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = heading,
                            color = NuvioTheme.colors.TextPrimary,
                            fontSize = 42.sp,
                            lineHeight = 48.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(42.dp))
                        ProfilePinBoxes(
                            value = pin,
                            isWorking = busy,
                            isErrorState = !errorMessage.isNullOrEmpty(),
                            isInputFocused = true,
                            cursorAlpha = cursorAlpha,
                            compactMode = true,
                            length = boxes,
                            boxSizeOverride = boxSize
                        )
                        Spacer(modifier = Modifier.height(26.dp))
                        Text(
                            text = support,
                            modifier = Modifier.widthIn(max = 720.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            color = if (!errorMessage.isNullOrEmpty()) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            lineHeight = 24.sp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = backHint,
                            color = NuvioTheme.colors.TextTertiary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                PinPad(
                    enabled = !busy,
                    onKey = { key ->
                        pin = pinAfterKey(pin, key, BackupCrypto.MAX_PIN_LENGTH)
                        onClearError()
                    },
                    startFocus = focusRequester,
                    doneLabel = stringResource(R.string.backup_done),
                    onDone = ::finish,
                    doneEnabled = canFinish
                )
            }
        }
    }
}

private fun digitOf(keyCode: Int): Char? = when (keyCode) {
    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> '0' + (keyCode - AndroidKeyEvent.KEYCODE_0)
    in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> '0' + (keyCode - AndroidKeyEvent.KEYCODE_NUMPAD_0)
    else -> null
}
