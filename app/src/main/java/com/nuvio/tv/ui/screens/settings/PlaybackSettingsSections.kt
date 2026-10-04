package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.core.player.thumbnail.SeekThumbMode
import com.nuvio.tv.core.player.thumbnail.SeekThumbnailPreferences
import com.nuvio.tv.core.player.thumbnail.SeekThumbnails
import com.nuvio.tv.core.player.thumbnail.ThumbPrepareMode
import com.nuvio.tv.core.player.thumbnail.ThumbnailCache
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.components.P2pConsentDialog
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
    initialFocusRequester: FocusRequester? = null,
    onOpenConnectedServices: (() -> Unit)? = null,
    onShowControlLayoutEditor: (() -> Unit)? = null
) {
    val sections = visiblePlaybackSections(playerSettings)
    var expandedSections by rememberSaveable { mutableStateOf(emptyList<String>()) }
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
                    focusRequester = if (section == sections.first()) initialFocusRequester else null,
                    modifier = Modifier.testTag(PlaybackSettingsTestTags.section(section))
                ) {
                    PlaybackSectionContent(
                        section = section,
                        playerSettings = playerSettings,
                        p2p = p2p,
                        transparentLetterbox = transparentLetterbox,
                        onUpdate = onUpdate,
                        onOpenDialog = onOpenDialog,
                        onMemorySettingChanged = onMemorySettingChanged,
                        onClearTorrentCache = onClearTorrentCache,
                        onOpenConnectedServices = onOpenConnectedServices,
                        onShowControlLayoutEditor = onShowControlLayoutEditor
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
    onUpdate: PlaybackSettingsUpdate,
    onOpenDialog: (PlaybackDialog) -> Unit,
    onMemorySettingChanged: () -> Unit,
    onClearTorrentCache: () -> Unit,
    onOpenConnectedServices: (() -> Unit)?,
    onShowControlLayoutEditor: (() -> Unit)?
) {
    when (section) {
        PlaybackSection.PLAYER -> PlaybackPlayerSection(playerSettings, onUpdate, onOpenDialog)
        PlaybackSection.STREAM_SELECTION -> PlaybackStreamSelectionSection(
            playerSettings,
            onUpdate,
            onOpenDialog,
            onOpenConnectedServices
        )
        PlaybackSection.UP_NEXT -> PlaybackUpNextSection(playerSettings, onUpdate, onOpenDialog)
        PlaybackSection.SKIP_SEGMENTS -> PlaybackSkipSegmentsSection(playerSettings, onUpdate)
        PlaybackSection.PLAYER_INTERFACE -> {
            PlaybackThumbnailRows(playerSettings, onOpenDialog)
            PlaybackPlayerInterfaceSection(playerSettings, onUpdate)
            SettingsActionRow(
                title = stringResource(R.string.player_layout_title),
                subtitle = stringResource(R.string.player_layout_settings_subtitle),
                onClick = { onShowControlLayoutEditor?.invoke() },
                enabled = onShowControlLayoutEditor != null
            )
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
    val seekThumbMode by SeekThumbnailPreferences.modeFlow(context).collectAsState(initial = SeekThumbMode.OFF)
    val seekThumbPrepare by SeekThumbnailPreferences.prepareModeFlow(context)
        .collectAsState(initial = ThumbPrepareMode.OFF)
    var seekThumbStoredBytes by remember { mutableStateOf(-1L) }
    val seekThumbScope = rememberCoroutineScope()
    val seekThumbsCleared = stringResource(R.string.seek_thumbnails_cleared)
    LaunchedEffect(Unit) {
        seekThumbStoredBytes = withContext(Dispatchers.IO) { SeekThumbnails.storedBytes(context) }
    }

    SettingsActionRow(
        title = stringResource(R.string.seek_thumbnails_title),
        subtitle = null,
        value = stringResource(seekThumbMode.label),
        enabled = internalPlayer,
        onClick = { onOpenDialog(PlaybackDialog.SEEK_THUMBNAILS) }
    )
    SettingsActionRow(
        title = stringResource(R.string.seek_thumbnails_prepare_title),
        subtitle = null,
        value = stringResource(seekThumbPrepare.label),
        enabled = internalPlayer,
        onClick = { onOpenDialog(PlaybackDialog.SEEK_THUMBNAIL_PREPARE) }
    )
    SettingsActionRow(
        title = stringResource(R.string.seek_thumbnails_clear_title),
        subtitle = stringResource(R.string.seek_thumbnails_clear_subtitle),
        value = when {
            seekThumbStoredBytes < 0L -> null
            seekThumbStoredBytes == 0L -> stringResource(R.string.seek_thumbnails_stored_empty)
            seekThumbStoredBytes < 1024L * 1024L -> stringResource(R.string.seek_thumbnails_stored_under_1mb)
            else -> stringResource(
                R.string.seek_thumbnails_stored_mb,
                ((seekThumbStoredBytes + 512L * 1024L) / (1024L * 1024L)).toInt()
            )
        },
        trailingIcon = null,
        onClick = {
            seekThumbScope.launch {
                withContext(Dispatchers.IO) { ThumbnailCache.clearAll(context) }
                seekThumbStoredBytes = 0L
                Toast.makeText(context, seekThumbsCleared, Toast.LENGTH_SHORT).show()
            }
        }
    )
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
        PlaybackDialog.SEEK_THUMBNAILS -> SeekThumbnailModeDialog(onUpdate = onUpdate, onDismiss = onDismiss)
        PlaybackDialog.SEEK_THUMBNAIL_PREPARE -> SeekThumbnailPrepareDialog(onUpdate = onUpdate, onDismiss = onDismiss)
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
private fun SeekThumbnailModeDialog(onUpdate: PlaybackSettingsUpdate, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val mode by SeekThumbnailPreferences.modeFlow(context).collectAsState(initial = SeekThumbMode.OFF)
    SettingsSingleChoiceDialog(
        title = stringResource(R.string.seek_thumbnails_title),
        subtitle = stringResource(R.string.seek_thumbnails_subtitle),
        options = SeekThumbMode.entries.map {
            SettingsPickerOption(it, stringResource(it.label), stringResource(it.description))
        },
        selectedValue = mode,
        onOptionSelected = { chosen ->
            onUpdate { SeekThumbnailPreferences.setMode(context, chosen) }
            onDismiss()
        },
        onDismiss = onDismiss
    )
}

@Composable
private fun SeekThumbnailPrepareDialog(onUpdate: PlaybackSettingsUpdate, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val prepare by SeekThumbnailPreferences.prepareModeFlow(context).collectAsState(initial = ThumbPrepareMode.OFF)
    SettingsSingleChoiceDialog(
        title = stringResource(R.string.seek_thumbnails_prepare_title),
        subtitle = stringResource(R.string.seek_thumbnails_prepare_subtitle),
        options = ThumbPrepareMode.entries.filter { it != ThumbPrepareMode.ASK }.map {
            SettingsPickerOption(it, stringResource(it.label), stringResource(it.description))
        },
        selectedValue = prepare,
        onOptionSelected = { chosen ->
            onUpdate { SeekThumbnailPreferences.setPrepareMode(context, chosen) }
            onDismiss()
        },
        onDismiss = onDismiss
    )
}
