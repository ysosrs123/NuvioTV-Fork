package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
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
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.LiveBufferPolicy
import com.nuvio.tv.core.iptv.LiveCushion
import com.nuvio.tv.core.iptv.LiveStartBuffer
import com.nuvio.tv.core.iptv.LiveUserAgent
import com.nuvio.tv.data.iptv.IptvLivePreferences
import com.nuvio.tv.data.iptv.IptvSourceRef
import com.nuvio.tv.data.iptv.IptvStreamingPreferences
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class IptvStreamingSettingsState(val start: LiveStartBuffer = LiveStartBuffer.NORMAL, val cushion: LiveCushion = LiveCushion.SECONDS_20,
    val capMegabytes: Int = LiveBufferPolicy.NORMAL_BYTES / (1024 * 1024), val cornerPicture: Boolean = true, val surroundLift: Boolean = true)

@HiltViewModel
class IptvStreamingSettingsViewModel @Inject constructor(@ApplicationContext context: Context) : ViewModel() {
    private val preferences = IptvStreamingPreferences(context)
    private val capMegabytes = runCatching {
        val manager = requireNotNull(context.getSystemService(android.app.ActivityManager::class.java))
        val memory = android.app.ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        LiveBufferPolicy.capBytes(LiveBufferPolicy.lowMemory(memory.totalMem, manager.isLowRamDevice), Runtime.getRuntime().maxMemory()) / MEGABYTE
    }.getOrDefault(LiveBufferPolicy.LOW_MEMORY_BYTES / MEGABYTE)
    private val mutable = MutableStateFlow(IptvStreamingSettingsState(preferences.start, preferences.cushion, capMegabytes,
        preferences.cornerPicture, preferences.surroundLift))
    val state = mutable.asStateFlow()

    fun setStart(value: LiveStartBuffer) { preferences.start = value; mutable.update { it.copy(start = value) } }
    fun setCushion(value: LiveCushion) { preferences.cushion = value; mutable.update { it.copy(cushion = value) } }
    fun toggleCornerPicture() { val value = !mutable.value.cornerPicture; preferences.cornerPicture = value; mutable.update { it.copy(cornerPicture = value) } }
    fun toggleSurroundLift() { val value = !mutable.value.surroundLift; preferences.surroundLift = value; mutable.update { it.copy(surroundLift = value) } }
}

@Composable
internal fun IptvStreamingSettingsSection(viewModel: IptvStreamingSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf<String?>(null) }
    SettingsGroupCard(title = stringResource(R.string.iptv_stream_title), subtitle = stringResource(R.string.iptv_stream_description)) {
        SettingsActionRow(title = stringResource(R.string.iptv_stream_start), subtitle = stringResource(R.string.iptv_stream_start_subtitle),
            value = stringResource(startLabel(state.start)), onClick = { choosing = START })
        SettingsActionRow(title = stringResource(R.string.iptv_stream_cushion), subtitle = stringResource(R.string.iptv_stream_cushion_subtitle),
            value = cushionLabel(state.cushion), onClick = { choosing = CUSHION })
        SettingsToggleRow(title = stringResource(R.string.iptv_play10_corner_picture), subtitle = stringResource(R.string.iptv_play10_corner_picture_subtitle),
            checked = state.cornerPicture, onToggle = viewModel::toggleCornerPicture)
        SettingsToggleRow(title = stringResource(R.string.iptv_play10_surround_lift), subtitle = stringResource(R.string.iptv_play10_surround_lift_subtitle),
            checked = state.surroundLift, onToggle = viewModel::toggleSurroundLift)
    }
    when (choosing) {
        START -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_stream_start), subtitle = stringResource(R.string.iptv_stream_start_subtitle),
            options = LiveStartBuffer.entries.map { SettingsPickerOption(it, stringResource(startLabel(it))) },
            selectedValue = state.start, onOptionSelected = { viewModel.setStart(it); choosing = null }, onDismiss = { choosing = null }, width = 520.dp)
        CUSHION -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_stream_cushion),
            subtitle = stringResource(R.string.iptv_stream_cushion_hls) + " " + stringResource(R.string.iptv_stream_cushion_memory, state.capMegabytes),
            options = LiveCushion.entries.map { SettingsPickerOption(it, cushionLabel(it)) },
            selectedValue = state.cushion, onOptionSelected = { viewModel.setCushion(it); choosing = null }, onDismiss = { choosing = null }, width = 520.dp)
    }
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
private const val MEGABYTE = 1024 * 1024
