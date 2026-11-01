package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.core.player.thumbnail.MemoryGovernor
import com.nuvio.tv.core.player.thumbnail.SeekThumbMode
import com.nuvio.tv.core.player.thumbnail.SeekThumbnailPreferences
import com.nuvio.tv.core.player.thumbnail.SeekThumbnails
import com.nuvio.tv.core.player.thumbnail.ThumbPrepareMode
import com.nuvio.tv.core.player.thumbnail.ThumbsBeforePlay
import com.nuvio.tv.core.player.thumbnail.ThumbnailCache
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.RemoteButtonMap
import com.nuvio.tv.data.local.SeekingMode
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.P2pConsentDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object PlaybackSettingsTestTags {
    fun section(section: PlaybackSection): String = "playback_section_${section.name.lowercase()}"
}

@Composable
internal fun PlaybackSettingsSections(
    playerSettings: PlayerSettings,
    p2p: P2pSettingsUi,
    transparentLetterbox: Boolean,
    onUpdate: PlaybackSettingsUpdate,
    onOpenDialog: (PlaybackDialog) -> Unit,
    onMemorySettingChanged: () -> Unit,
    onClearTorrentCache: () -> Unit,
    remoteButtons: RemoteButtonMap = RemoteButtonMap.DEFAULT,
    initialFocusRequester: FocusRequester? = null,
    onOpenConnectedServices: (() -> Unit)? = null,
    onShowControlLayoutEditor: (() -> Unit)? = null,
    returnToQualityRules: Boolean = false
) {
    val sections = visiblePlaybackSections(playerSettings)
    var expandedSections by rememberSaveable {
        mutableStateOf(
            if (returnToQualityRules) listOf(PlaybackSection.STREAM_SELECTION.name) else emptyList()
        )
    }
    val qualityRulesFocusRequester = initialFocusRequester.takeIf { returnToQualityRules }
    val headerFocusRequester = initialFocusRequester.takeIf { !returnToQualityRules }
    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = NuvioTheme.spacing.xs, bottom = NuvioTheme.spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(if (isV2Settings()) 6.dp else NuvioTheme.spacing.md)
        ) {
            items(items = sections, key = { it.name }) { section ->
                val expanded = section.name in expandedSections
                SettingsCollapsibleSection(
                    title = stringResource(section.title),
                    description = stringResource(section.description),
                    icon = section.icon,
                    expanded = expanded,
                    onToggle = {
                        expandedSections = if (expanded) {
                            expandedSections - section.name
                        } else {
                            expandedSections + section.name
                        }
                    },
                    focusRequester = if (section == sections.first()) headerFocusRequester else null,
                    modifier = Modifier.testTag(PlaybackSettingsTestTags.section(section))
                ) {
                    PlaybackSectionContent(
                        section = section,
                        playerSettings = playerSettings,
                        p2p = p2p,
                        transparentLetterbox = transparentLetterbox,
                        remoteButtons = remoteButtons,
                        onUpdate = onUpdate,
                        onOpenDialog = onOpenDialog,
                        onMemorySettingChanged = onMemorySettingChanged,
                        onClearTorrentCache = onClearTorrentCache,
                        onOpenConnectedServices = onOpenConnectedServices,
                        onShowControlLayoutEditor = onShowControlLayoutEditor,
                        qualityRulesFocusRequester = qualityRulesFocusRequester
                    )
                }
            }
        }
        SettingsVerticalScrollIndicators(state = listState)
    }
}

@Composable
private fun PlaybackSectionContent(
    section: PlaybackSection,
    playerSettings: PlayerSettings,
    p2p: P2pSettingsUi,
    transparentLetterbox: Boolean,
    remoteButtons: RemoteButtonMap,
    onUpdate: PlaybackSettingsUpdate,
    onOpenDialog: (PlaybackDialog) -> Unit,
    onMemorySettingChanged: () -> Unit,
    onClearTorrentCache: () -> Unit,
    onOpenConnectedServices: (() -> Unit)?,
    onShowControlLayoutEditor: (() -> Unit)?,
    qualityRulesFocusRequester: FocusRequester?
) {
    when (section) {
        PlaybackSection.PLAYER -> PlaybackPlayerSection(playerSettings, onUpdate, onOpenDialog)
        PlaybackSection.STREAM_SELECTION -> PlaybackStreamSelectionSection(
            playerSettings,
            onUpdate,
            onOpenDialog,
            onOpenConnectedServices,
            qualityRulesFocusRequester
        )
        PlaybackSection.UP_NEXT -> PlaybackUpNextSection(playerSettings, onUpdate, onOpenDialog)
        PlaybackSection.SKIP_SEGMENTS -> PlaybackSkipSegmentsSection(playerSettings, onUpdate)
        PlaybackSection.PLAYER_INTERFACE -> {
            SettingsActionRow(
                title = stringResource(R.string.playback_seeking),
                subtitle = null,
                value = stringResource(seekingLabel(playerSettings.seekingMode)),
                enabled = playerSettings.playerPreference != PlayerPreference.EXTERNAL,
                onClick = { onOpenDialog(PlaybackDialog.SEEKING) }
            )
            PlaybackThumbnailRows(playerSettings, onOpenDialog)
            PlaybackPlayerInterfaceSection(playerSettings, onUpdate)
            SettingsActionRow(
                title = stringResource(R.string.player_layout_title),
                subtitle = stringResource(R.string.player_layout_settings_subtitle),
                onClick = { onShowControlLayoutEditor?.invoke() },
                enabled = onShowControlLayoutEditor != null
            )
        }
        PlaybackSection.REMOTE_BUTTONS -> PlaybackRemoteButtonsSection(remoteButtons) { map ->
            onUpdate { setRemoteButtons(map) }
        }
        PlaybackSection.AUDIO -> PlaybackAudioSection(playerSettings, onUpdate, onOpenDialog)
        PlaybackSection.SUBTITLES -> PlaybackSubtitlesSection(playerSettings, onUpdate, onOpenDialog)
        PlaybackSection.VIDEO -> PlaybackVideoSection(playerSettings, transparentLetterbox, onUpdate, onOpenDialog)
        PlaybackSection.BUFFER_NETWORK -> PlaybackBufferNetworkSection(playerSettings, onUpdate, onMemorySettingChanged)
        PlaybackSection.P2P -> PlaybackP2pSection(p2p, onUpdate, onOpenDialog, onClearTorrentCache)
    }
}

@Composable
private fun PlaybackThumbnailRows(
    settings: PlayerSettings,
    onOpenDialog: (PlaybackDialog) -> Unit
) {
    val context = LocalContext.current
    val internalPlayer = settings.playerPreference != PlayerPreference.EXTERNAL
    // Null until the store has answered: the rows below are not greyed out for that first frame.
    val seekThumbModeRead by SeekThumbnailPreferences.modeFlow(context)
        .collectAsState<SeekThumbMode, SeekThumbMode?>(initial = null)
    val seekThumbsOn = seekThumbModeRead != SeekThumbMode.OFF
    val seekThumbPrepareRead by SeekThumbnailPreferences.prepareModeFlow(context)
        .collectAsState<ThumbPrepareMode, ThumbPrepareMode?>(initial = null)
    val seekThumbPrepare = seekThumbPrepareRead ?: ThumbPrepareMode.OFF
    val seekThumbBeforePlay by SeekThumbnailPreferences.beforePlayFlow(context)
        .collectAsState(initial = ThumbsBeforePlay.FASTER)
    val seekThumbHardware by SeekThumbnailPreferences.hardwareFlow(context).collectAsState(initial = true)
    var seekThumbStoredBytes by remember { mutableStateOf(-1L) }
    val seekThumbScope = rememberCoroutineScope()
    var showClearSeekThumbs by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        seekThumbStoredBytes = withContext(Dispatchers.IO) { SeekThumbnails.storedBytes(context) }
    }

    SettingsToggleRow(
        title = stringResource(R.string.seek_thumbnails_title),
        subtitle = stringResource(R.string.seek_thumbnails_mode_all_desc),
        checked = seekThumbModeRead == SeekThumbMode.ALL,
        onToggle = {
            val read = seekThumbModeRead ?: return@SettingsToggleRow
            seekThumbScope.launch {
                SeekThumbnailPreferences.setMode(context, if (read == SeekThumbMode.OFF) SeekThumbMode.ALL else SeekThumbMode.OFF)
            }
        },
        enabled = internalPlayer
    )
    SettingsActionRow(
        title = stringResource(R.string.seek_thumbnails_prepare_title),
        subtitle = null,
        value = stringResource(seekThumbPrepare.label),
        enabled = internalPlayer && seekThumbsOn,
        onClick = { onOpenDialog(PlaybackDialog.SEEK_THUMBNAIL_PREPARE) }
    )
    SettingsActionRow(
        title = stringResource(R.string.seek_thumbnails_before_play_title),
        subtitle = null,
        value = stringResource(seekThumbBeforePlay.label),
        enabled = internalPlayer && seekThumbsOn && seekThumbPrepareRead != ThumbPrepareMode.OFF,
        onClick = { onOpenDialog(PlaybackDialog.SEEK_THUMBNAILS_BEFORE_PLAY) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.seek_thumbnails_hardware_title),
        subtitle = stringResource(R.string.seek_thumbnails_hardware_subtitle),
        checked = seekThumbHardware,
        onToggle = { seekThumbScope.launch { SeekThumbnailPreferences.setHardware(context, !seekThumbHardware) } },
        enabled = internalPlayer && seekThumbsOn
    )
    val seekThumbStoredLabel = when {
        seekThumbStoredBytes < 0L -> null
        seekThumbStoredBytes == 0L -> stringResource(R.string.seek_thumbnails_stored_empty)
        seekThumbStoredBytes < 1024L * 1024L -> stringResource(R.string.seek_thumbnails_stored_under_1mb)
        else -> stringResource(
            R.string.seek_thumbnails_stored_mb,
            ((seekThumbStoredBytes + 512L * 1024L) / (1024L * 1024L)).toInt()
        )
    }
    SettingsActionRow(
        title = stringResource(R.string.seek_thumbnails_clear_title),
        subtitle = stringResource(R.string.seek_thumbnails_clear_subtitle),
        value = seekThumbStoredLabel,
        trailingIcon = null,
        onClick = { showClearSeekThumbs = true }
    )
    if (showClearSeekThumbs) {
        ClearSeekThumbnailsDialog(
            storedLabel = seekThumbStoredLabel,
            onConfirm = {
                showClearSeekThumbs = false
                seekThumbScope.launch {
                    withContext(Dispatchers.IO) { ThumbnailCache.clearAll(context) }
                    seekThumbStoredBytes = 0L
                }
            },
            onDismiss = { showClearSeekThumbs = false }
        )
    }
}

@Composable
private fun ClearSeekThumbnailsDialog(
    storedLabel: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val cancelFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        cancelFocusRequester.requestFocusAfterFrames()
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.seek_thumbnails_clear_title),
        subtitle = storedLabel,
        suppressFirstKeyUp = false
    ) {
        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                modifier = Modifier.focusRequester(cancelFocusRequester)
            )
            SettingsDialogActionButton(
                text = stringResource(R.string.action_clear),
                onClick = onConfirm,
                primary = true
            )
        }
    }
}

@Composable
internal fun PlaybackSettingsDialogs(
    dialog: PlaybackDialog?,
    settings: PlayerSettings,
    p2p: P2pSettingsUi,
    installedAddonNames: List<String>,
    enabledPluginNames: List<String>,
    onUpdate: PlaybackSettingsUpdate,
    onDismiss: () -> Unit
) {
    when (dialog) {
        null -> Unit
        PlaybackDialog.PLAYER_PREFERENCE -> PlayerPreferenceDialog(
            currentPreference = settings.playerPreference,
            onPreferenceSelected = { preference ->
                onUpdate { setPlayerPreference(preference) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        PlaybackDialog.INTERNAL_ENGINE -> InternalPlayerEngineDialog(
            currentEngine = settings.internalPlayerEngine,
            onEngineSelected = { engine ->
                onUpdate { setInternalPlayerEngine(engine) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        PlaybackDialog.P2P_CONSENT -> P2pConsentDialog(
            onEnableP2p = {
                onUpdate { setP2pEnabled(true) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        PlaybackDialog.SEEK_THUMBNAIL_PREPARE -> SeekThumbnailPrepareDialog(onUpdate = onUpdate, onDismiss = onDismiss)
        PlaybackDialog.SEEK_THUMBNAILS_BEFORE_PLAY -> SeekThumbnailBeforePlayDialog(onUpdate = onUpdate, onDismiss = onDismiss)
        PlaybackDialog.SEEKING -> SeekingDialog(settings.seekingMode, onUpdate = onUpdate, onDismiss = onDismiss)
        else -> {
            AutoPlaySettingsDialogs(dialog, settings, installedAddonNames, enabledPluginNames, onUpdate, onDismiss)
            AudioSettingsDialogs(dialog, settings, onUpdate, onDismiss)
            SubtitleSettingsDialogs(dialog, settings, onUpdate, onDismiss)
            VideoSettingsDialogs(dialog, settings, onUpdate, onDismiss)
            P2pSettingsDialogs(dialog, p2p, onUpdate, onDismiss)
        }
    }
}

@Composable
private fun SeekThumbnailPrepareDialog(onUpdate: PlaybackSettingsUpdate, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val prepare by SeekThumbnailPreferences.prepareModeFlow(context).collectAsState(initial = ThumbPrepareMode.OFF)
    val smallBox = remember(context) { MemoryGovernor.isSmallHardware(context) }
    val smallBoxNote = stringResource(R.string.seek_thumbnails_prepare_on_small_desc)
    SettingsSingleChoiceDialog(
        title = stringResource(R.string.seek_thumbnails_prepare_title),
        subtitle = stringResource(R.string.seek_thumbnails_prepare_subtitle),
        options = ThumbPrepareMode.entries.filter { it != ThumbPrepareMode.ASK }.map {
            val description = stringResource(it.description)
            SettingsPickerOption(it, stringResource(it.label),
                if (smallBox && it != ThumbPrepareMode.OFF) "$description $smallBoxNote" else description)
        },
        selectedValue = prepare,
        onOptionSelected = { chosen ->
            onUpdate { SeekThumbnailPreferences.setPrepareMode(context, chosen) }
            onDismiss()
        },
        onDismiss = onDismiss
    )
}

@Composable
private fun SeekThumbnailBeforePlayDialog(onUpdate: PlaybackSettingsUpdate, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val choice by SeekThumbnailPreferences.beforePlayFlow(context).collectAsState(initial = ThumbsBeforePlay.FASTER)
    SettingsSingleChoiceDialog(
        title = stringResource(R.string.seek_thumbnails_before_play_title),
        subtitle = stringResource(R.string.seek_thumbnails_before_play_subtitle),
        options = ThumbsBeforePlay.entries.map {
            SettingsPickerOption(it, stringResource(it.label), stringResource(it.description))
        },
        selectedValue = choice,
        onOptionSelected = { chosen ->
            onUpdate { SeekThumbnailPreferences.setBeforePlay(context, chosen) }
            onDismiss()
        },
        onDismiss = onDismiss
    )
}

private fun seekingLabel(mode: SeekingMode): Int = when (mode) {
    SeekingMode.GENTLE -> R.string.playback_seeking_gentle
    SeekingMode.BALANCED -> R.string.playback_seeking_balanced
    SeekingMode.FAST -> R.string.playback_seeking_fast
    SeekingMode.CONFIRM -> R.string.playback_seeking_confirm
}

private fun seekingDescription(mode: SeekingMode): Int = when (mode) {
    SeekingMode.GENTLE -> R.string.playback_seeking_gentle_desc
    SeekingMode.BALANCED -> R.string.playback_seeking_balanced_desc
    SeekingMode.FAST -> R.string.playback_seeking_fast_desc
    SeekingMode.CONFIRM -> R.string.playback_seeking_confirm_desc
}

@Composable
private fun SeekingDialog(current: SeekingMode, onUpdate: PlaybackSettingsUpdate, onDismiss: () -> Unit) {
    SettingsSingleChoiceDialog(
        title = stringResource(R.string.playback_seeking),
        subtitle = stringResource(R.string.playback_seeking_sub),
        options = SeekingMode.entries.map {
            SettingsPickerOption(it, stringResource(seekingLabel(it)), stringResource(seekingDescription(it)))
        },
        selectedValue = current,
        onOptionSelected = { chosen ->
            onUpdate { setSeekingMode(chosen) }
            onDismiss()
        },
        onDismiss = onDismiss
    )
}
