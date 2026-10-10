package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.LiveAudioDecoder
import com.nuvio.tv.core.iptv.LiveAudioOptions
import com.nuvio.tv.core.iptv.LiveBufferPolicy
import com.nuvio.tv.core.iptv.LiveCushion
import com.nuvio.tv.core.iptv.LiveFrameRateChoice
import com.nuvio.tv.core.iptv.LivePassthroughChoice
import com.nuvio.tv.core.iptv.LiveResolutionChoice
import com.nuvio.tv.core.iptv.LiveStartBuffer
import com.nuvio.tv.core.iptv.LiveUserAgent
import com.nuvio.tv.data.iptv.IptvLivePreferences
import com.nuvio.tv.data.iptv.IptvSourceRef
import com.nuvio.tv.data.iptv.IptvStreamingPreferences
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsPickerOption
import com.nuvio.tv.ui.screens.settings.SettingsSingleChoiceDialog
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.v2.components.NuvioActionPill
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class IptvStreamingSettingsState(val start: LiveStartBuffer = LiveStartBuffer.NORMAL, val cushion: LiveCushion = LiveCushion.SECONDS_20,
    val capMegabytes: Int = LiveBufferPolicy.NORMAL_BYTES / (1024 * 1024), val cornerPicture: Boolean = true, val surroundLift: Boolean = true,
    val frameRate: LiveFrameRateChoice = LiveFrameRateChoice.NUVIO, val resolution: LiveResolutionChoice = LiveResolutionChoice.NUVIO,
    val passthrough: LivePassthroughChoice = LivePassthroughChoice.NUVIO, val tunnelling: Boolean = false, val preferSurround: Boolean = false,
    val audioLanguage: String = LiveAudioOptions.LANGUAGE_DEFAULT, val audioDecoder: LiveAudioDecoder = LiveAudioDecoder.AUTOMATIC)

@HiltViewModel
class IptvStreamingSettingsViewModel @Inject constructor(@ApplicationContext context: Context, nuvioSettings: PlayerSettingsDataStore) : ViewModel() {
    private val preferences = IptvStreamingPreferences(context)
    private val capMegabytes = runCatching {
        val manager = requireNotNull(context.getSystemService(android.app.ActivityManager::class.java))
        val memory = android.app.ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        LiveBufferPolicy.capBytes(LiveBufferPolicy.lowMemory(memory.totalMem, manager.isLowRamDevice), Runtime.getRuntime().maxMemory()) / MEGABYTE
    }.getOrDefault(LiveBufferPolicy.LOW_MEMORY_BYTES / MEGABYTE)
    private val mutable = MutableStateFlow(IptvStreamingSettingsState(preferences.start, preferences.cushion, capMegabytes,
        preferences.cornerPicture, preferences.surroundLift, preferences.frameRate, preferences.resolution, preferences.passthrough,
        preferences.tunnelling, preferences.preferSurround, preferences.audioLanguage, preferences.audioDecoder))
    val state = mutable.asStateFlow()
    val nuvio: StateFlow<PlayerSettings?> = nuvioSettings.playerSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setStart(value: LiveStartBuffer) { preferences.start = value; mutable.update { it.copy(start = value) } }
    fun setCushion(value: LiveCushion) { preferences.cushion = value; mutable.update { it.copy(cushion = value) } }
    fun toggleCornerPicture() { val value = !mutable.value.cornerPicture; preferences.cornerPicture = value; mutable.update { it.copy(cornerPicture = value) } }
    fun toggleSurroundLift() { val value = !mutable.value.surroundLift; preferences.surroundLift = value; mutable.update { it.copy(surroundLift = value) } }
    fun setFrameRate(value: LiveFrameRateChoice) { preferences.frameRate = value; mutable.update { it.copy(frameRate = value) } }
    fun setResolution(value: LiveResolutionChoice) { preferences.resolution = value; mutable.update { it.copy(resolution = value) } }
    fun setPassthrough(value: LivePassthroughChoice) { preferences.passthrough = value; mutable.update { it.copy(passthrough = value) } }
    fun toggleTunnelling() { val value = !mutable.value.tunnelling; preferences.tunnelling = value; mutable.update { it.copy(tunnelling = value) } }
    fun togglePreferSurround() { val value = !mutable.value.preferSurround; preferences.preferSurround = value; mutable.update { it.copy(preferSurround = value) } }
    fun setAudioLanguage(value: String) { preferences.audioLanguage = value; mutable.update { it.copy(audioLanguage = value) } }
    fun setAudioDecoder(value: LiveAudioDecoder) { preferences.audioDecoder = value; mutable.update { it.copy(audioDecoder = value) } }
}

@Composable
internal fun IptvStreamingSettingsSection(viewModel: IptvStreamingSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val nuvio by viewModel.nuvio.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf<String?>(null) }
    val nuvioFrameRate = nuvio?.let { stringResource(afrLabel(it.frameRateMatchingMode)) }
    val nuvioResolution = nuvio?.let { stringResource(if (it.resolutionMatchingEnabled) R.string.iptv_play14_on else R.string.playback_afr_off) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroupCard(title = stringResource(R.string.iptv_stream_title), subtitle = stringResource(R.string.iptv_stream_description)) {
            SettingsActionRow(title = stringResource(R.string.iptv_stream_start), subtitle = stringResource(R.string.iptv_stream_start_subtitle),
                value = stringResource(startLabel(state.start)), onClick = { choosing = START })
            SettingsActionRow(title = stringResource(R.string.iptv_stream_cushion), subtitle = stringResource(R.string.iptv_stream_cushion_subtitle),
                value = cushionLabel(state.cushion), onClick = { choosing = CUSHION })
            SettingsToggleRow(title = stringResource(R.string.iptv_play10_corner_picture), subtitle = stringResource(R.string.iptv_play10_corner_picture_subtitle),
                checked = state.cornerPicture, onToggle = viewModel::toggleCornerPicture)
        }
        SettingsGroupCard(title = stringResource(R.string.iptv_play14_title), subtitle = stringResource(R.string.iptv_play14_description)) {
            SettingsActionRow(title = stringResource(R.string.iptv_play14_frame_rate), subtitle = stringResource(R.string.iptv_play14_frame_rate_subtitle),
                value = frameRateLabel(state.frameRate, nuvioFrameRate), onClick = { choosing = FRAME_RATE })
            SettingsActionRow(title = stringResource(R.string.playback_resolution_matching), subtitle = stringResource(R.string.iptv_play14_resolution_subtitle),
                value = resolutionLabel(state.resolution, nuvioResolution), onClick = { choosing = RESOLUTION })
            SettingsActionRow(title = stringResource(R.string.iptv_play14_passthrough), subtitle = stringResource(R.string.iptv_play14_passthrough_subtitle),
                value = stringResource(passthroughLabel(state.passthrough)), onClick = { choosing = PASSTHROUGH })
            SettingsActionRow(title = stringResource(R.string.iptv_play14_decoder), subtitle = stringResource(R.string.iptv_play14_decoder_subtitle),
                value = stringResource(decoderLabel(state.audioDecoder)), onClick = { choosing = DECODER })
            SettingsActionRow(title = stringResource(R.string.iptv_play14_language), subtitle = stringResource(R.string.iptv_play14_language_subtitle),
                value = languageLabel(state.audioLanguage), onClick = { choosing = LANGUAGE })
            SettingsToggleRow(title = stringResource(R.string.iptv_play14_surround), subtitle = stringResource(R.string.iptv_play14_surround_subtitle),
                checked = state.preferSurround, onToggle = viewModel::togglePreferSurround)
            SettingsToggleRow(title = stringResource(R.string.iptv_play10_surround_lift), subtitle = stringResource(R.string.iptv_play10_surround_lift_subtitle),
                checked = state.surroundLift, onToggle = viewModel::toggleSurroundLift)
            SettingsToggleRow(title = stringResource(R.string.iptv_play14_tunnel), subtitle = stringResource(R.string.iptv_play14_tunnel_subtitle),
                checked = state.tunnelling, onToggle = viewModel::toggleTunnelling)
        }
    }
    IptvOpaqueDialogs { when (choosing) {
        START -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_stream_start), subtitle = stringResource(R.string.iptv_stream_start_subtitle),
            options = LiveStartBuffer.entries.map { SettingsPickerOption(it, stringResource(startLabel(it))) },
            selectedValue = state.start, onOptionSelected = { viewModel.setStart(it); choosing = null }, onDismiss = { choosing = null }, width = 520.dp)
        CUSHION -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_stream_cushion),
            subtitle = stringResource(R.string.iptv_stream_cushion_hls) + " " + stringResource(R.string.iptv_stream_cushion_memory, state.capMegabytes),
            options = LiveCushion.entries.map { SettingsPickerOption(it, cushionLabel(it)) },
            selectedValue = state.cushion, onOptionSelected = { viewModel.setCushion(it); choosing = null }, onDismiss = { choosing = null }, width = 520.dp)
        FRAME_RATE -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_play14_frame_rate), subtitle = stringResource(R.string.iptv_play14_frame_rate_subtitle),
            options = LiveFrameRateChoice.entries.map { choice ->
                val match = choice.match
                if (match == null) SettingsPickerOption(choice, stringResource(R.string.iptv_play14_same_as_nuvio), nuvioFrameRate?.let { stringResource(R.string.iptv_play14_same_as_nuvio_hint, it) })
                else FrameRateMatchingMode.valueOf(match.name).let { mode -> SettingsPickerOption(choice, stringResource(afrLabel(mode)), stringResource(afrHint(mode))) }
            },
            selectedValue = state.frameRate, onOptionSelected = { viewModel.setFrameRate(it); choosing = null }, onDismiss = { choosing = null }, width = 520.dp)
        RESOLUTION -> SettingsSingleChoiceDialog(title = stringResource(R.string.playback_resolution_matching), subtitle = stringResource(R.string.iptv_play14_resolution_subtitle),
            options = LiveResolutionChoice.entries.map { choice ->
                if (choice == LiveResolutionChoice.NUVIO) SettingsPickerOption(choice, stringResource(R.string.iptv_play14_same_as_nuvio),
                    nuvioResolution?.let { stringResource(R.string.iptv_play14_same_as_nuvio_hint, it) })
                else SettingsPickerOption(choice, resolutionLabel(choice, null))
            },
            selectedValue = state.resolution, onOptionSelected = { viewModel.setResolution(it); choosing = null }, onDismiss = { choosing = null }, width = 520.dp)
        PASSTHROUGH -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_play14_passthrough), subtitle = stringResource(R.string.iptv_play14_passthrough_subtitle),
            options = LivePassthroughChoice.entries.map { choice ->
                SettingsPickerOption(choice, stringResource(passthroughLabel(choice)), stringResource(when (choice) {
                    LivePassthroughChoice.NUVIO -> R.string.iptv_play14_passthrough_nuvio_hint
                    LivePassthroughChoice.OFF -> R.string.iptv_play14_passthrough_off_hint
                }))
            },
            selectedValue = state.passthrough, onOptionSelected = { viewModel.setPassthrough(it); choosing = null }, onDismiss = { choosing = null }, width = 560.dp)
        DECODER -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_play14_decoder), subtitle = stringResource(R.string.iptv_play14_decoder_subtitle),
            options = LiveAudioDecoder.entries.map { choice ->
                SettingsPickerOption(choice, stringResource(decoderLabel(choice)), stringResource(when (choice) {
                    LiveAudioDecoder.AUTOMATIC -> R.string.iptv_play14_decoder_auto_hint
                    LiveAudioDecoder.PREFER_APP -> R.string.iptv_play14_decoder_app_hint
                }))
            },
            selectedValue = state.audioDecoder, onOptionSelected = { viewModel.setAudioDecoder(it); choosing = null }, onDismiss = { choosing = null }, width = 560.dp)
        LANGUAGE -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_play14_language), subtitle = stringResource(R.string.iptv_play14_language_subtitle),
            options = listOf(SettingsPickerOption(LiveAudioOptions.LANGUAGE_DEFAULT, stringResource(R.string.iptv_play14_language_stream)),
                SettingsPickerOption(LiveAudioOptions.LANGUAGE_DEVICE, stringResource(R.string.audio_lang_device))) +
                AVAILABLE_SUBTITLE_LANGUAGES.sortedBy { it.displayName.lowercase() }.map { SettingsPickerOption(it.code, it.displayName, trailing = it.code.uppercase()) },
            selectedValue = state.audioLanguage, onOptionSelected = { viewModel.setAudioLanguage(it); choosing = null }, onDismiss = { choosing = null },
            width = 460.dp, maxHeight = 420.dp)
    } }
}

@Composable
private fun frameRateLabel(choice: LiveFrameRateChoice, nuvio: String?): String = choice.match?.let { stringResource(afrLabel(FrameRateMatchingMode.valueOf(it.name))) }
    ?: nuvio?.let { stringResource(R.string.iptv_play14_same_as_nuvio_value, it) } ?: stringResource(R.string.iptv_play14_same_as_nuvio)

@Composable
private fun resolutionLabel(choice: LiveResolutionChoice, nuvio: String?): String = when (choice.enabled) {
    true -> stringResource(R.string.iptv_play14_on)
    false -> stringResource(R.string.playback_afr_off)
    null -> nuvio?.let { stringResource(R.string.iptv_play14_same_as_nuvio_value, it) } ?: stringResource(R.string.iptv_play14_same_as_nuvio)
}

@Composable
private fun languageLabel(code: String): String = when (code) {
    LiveAudioOptions.LANGUAGE_DEFAULT -> stringResource(R.string.iptv_play14_language_stream)
    LiveAudioOptions.LANGUAGE_DEVICE -> stringResource(R.string.audio_lang_device)
    else -> AVAILABLE_SUBTITLE_LANGUAGES.firstOrNull { it.code == code }?.displayName ?: code.uppercase()
}

private fun afrLabel(mode: FrameRateMatchingMode): Int = when (mode) {
    FrameRateMatchingMode.OFF -> R.string.playback_afr_off
    FrameRateMatchingMode.START -> R.string.playback_afr_on_start
    FrameRateMatchingMode.START_STOP -> R.string.playback_afr_on_start_stop
}

private fun afrHint(mode: FrameRateMatchingMode): Int = when (mode) {
    FrameRateMatchingMode.OFF -> R.string.playback_afr_off_sub
    FrameRateMatchingMode.START -> R.string.playback_afr_on_start_sub
    FrameRateMatchingMode.START_STOP -> R.string.playback_afr_on_start_stop_sub
}

private fun passthroughLabel(choice: LivePassthroughChoice): Int = when (choice) {
    LivePassthroughChoice.NUVIO -> R.string.iptv_play14_same_as_nuvio
    LivePassthroughChoice.OFF -> R.string.iptv_play14_passthrough_off
}

private fun decoderLabel(choice: LiveAudioDecoder): Int = when (choice) {
    LiveAudioDecoder.AUTOMATIC -> R.string.iptv_play14_decoder_auto
    LiveAudioDecoder.PREFER_APP -> R.string.iptv_play14_decoder_app
}

@Composable
internal fun IptvUserAgentRow(ref: IptvSourceRef, onClick: () -> Unit) {
    val context = LocalContext.current
    val choice = remember(ref) { IptvLivePreferences(context).userAgentChoice(ref) }
    SettingsActionRow(title = stringResource(R.string.iptv_user_agent), subtitle = null, value = userAgentLabel(LiveUserAgent.kind(choice)),
        onClick = onClick, leadingIcon = Icons.Filled.Badge)
}

@Composable
internal fun IptvUserAgentDialog(ref: IptvSourceRef, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { IptvLivePreferences(context) }
    val saved = remember(ref) { preferences.userAgentChoice(ref) }
    var editing by remember { mutableStateOf(false) }
    if (!editing) SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_user_agent), subtitle = stringResource(R.string.iptv_user_agent_description),
        options = (listOf(LiveUserAgent.DEFAULT) + LiveUserAgent.PRESETS.keys + LiveUserAgent.CUSTOM).map { kind ->
            SettingsPickerOption(kind, userAgentLabel(kind), when (kind) {
                LiveUserAgent.DEFAULT -> null
                LiveUserAgent.CUSTOM -> LiveUserAgent.customText(saved)
                else -> LiveUserAgent.PRESETS[kind]
            })
        },
        selectedValue = LiveUserAgent.kind(saved), onOptionSelected = { kind ->
            if (kind == LiveUserAgent.CUSTOM) editing = true
            else { preferences.setUserAgent(ref, kind); onDismiss() }
        }, onDismiss = onDismiss, width = 560.dp, maxHeight = 420.dp)
    else {
        var text by remember { mutableStateOf(LiveUserAgent.customText(saved).orEmpty()) }
        val field = remember { FocusRequester() }
        val custom = LiveUserAgent.custom(text)
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { field.requestFocus() } }
        NuvioDialog(onDismiss = { editing = false }, title = stringResource(R.string.iptv_user_agent_custom),
            subtitle = stringResource(R.string.iptv_user_agent_description), width = 640.dp) {
            SourceField(stringResource(R.string.iptv_user_agent), text, { text = it.take(LiveUserAgent.MAX_LENGTH + 1) }, Modifier.focusRequester(field),
                hint = stringResource(R.string.iptv_user_agent_custom_hint), last = true, error = text.isNotBlank() && custom == null)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NuvioActionPill({ custom?.let { preferences.setUserAgent(ref, it); onDismiss() } }, enabled = custom != null) {
                    Text(stringResource(R.string.iptv_setup_save))
                }
                NuvioActionPill({ editing = false }) { Text(stringResource(R.string.iptv_setup_cancel)) }
            }
        }
    }
}

@Composable
private fun userAgentLabel(kind: String): String = stringResource(when (kind) {
    "vlc" -> R.string.iptv_user_agent_vlc
    "kodi" -> R.string.iptv_user_agent_kodi
    "okhttp" -> R.string.iptv_user_agent_okhttp
    "smarters" -> R.string.iptv_user_agent_smarters
    LiveUserAgent.CUSTOM -> R.string.iptv_user_agent_custom
    else -> R.string.iptv_user_agent_default
})

@Composable
private fun cushionLabel(value: LiveCushion): String =
    if (value == LiveCushion.OFF) stringResource(R.string.iptv_stream_cushion_off) else pluralStringResource(R.plurals.iptv_stream_cushion_seconds, value.seconds, value.seconds)

private fun startLabel(value: LiveStartBuffer): Int = when (value) {
    LiveStartBuffer.FAST -> R.string.iptv_stream_start_fast
    LiveStartBuffer.NORMAL -> R.string.iptv_stream_start_normal
    LiveStartBuffer.SAFE -> R.string.iptv_stream_start_safe
}

private const val START = "start"
private const val CUSHION = "cushion"
private const val FRAME_RATE = "frame-rate"
private const val RESOLUTION = "resolution"
private const val PASSTHROUGH = "passthrough"
private const val DECODER = "decoder"
private const val LANGUAGE = "language"
private const val MEGABYTE = 1024 * 1024
