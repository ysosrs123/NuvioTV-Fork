@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.MaterialTheme
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.Locale

@Composable
internal fun IptvTrackDialog(player: ExoPlayer, onDismiss: () -> Unit) {
    var tracks by remember(player) { mutableStateOf(player.currentTracks) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(value: Tracks) { tracks = value }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.iptv_live_tracks), width = 600.dp) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).focusRequester(first), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (type in listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) {
                val groups = tracks.groups.filter { it.type == type }
                val overridden = groups.any { group -> (0 until group.length).any(group::isTrackSelected) }
                SectionLabel(stringResource(if (type == C.TRACK_TYPE_AUDIO) R.string.iptv_live_audio else R.string.iptv_live_subtitles))
                SettingsActionRow(title = stringResource(R.string.iptv_live_tracks_auto), subtitle = null, trailingIcon = null,
                    titleTrailingIcon = Icons.Filled.Check.takeIf { !overridden && !player.trackSelectionParameters.disabledTrackTypes.contains(type) },
                    onClick = {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(type).setTrackTypeDisabled(type, false).build()
                        onDismiss()
                    })
                if (type == C.TRACK_TYPE_TEXT) SettingsActionRow(title = stringResource(R.string.iptv_live_tracks_off), subtitle = null, trailingIcon = null,
                    titleTrailingIcon = Icons.Filled.Check.takeIf { player.trackSelectionParameters.disabledTrackTypes.contains(type) },
                    onClick = {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(type).setTrackTypeDisabled(type, true).build()
                        onDismiss()
                    })
                if (groups.isEmpty()) Text(stringResource(R.string.iptv_live_tracks_none), color = NuvioTheme.colors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                groups.forEach { group ->
                    repeat(group.length) { index ->
                        val format = group.getTrackFormat(index)
                        val language = format.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).displayName }
                        val label = listOfNotNull(format.label?.take(80), language).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                            .ifEmpty { "${index + 1}" }
                        val supported = group.isTrackSupported(index)
                        SettingsActionRow(title = label, enabled = supported, trailingIcon = null,
                            subtitle = listOfNotNull(trackDetail(format), stringResource(R.string.iptv_live_tracks_unsupported).takeIf { !supported }).joinToString(" · ").ifEmpty { null },
                            titleTrailingIcon = Icons.Filled.Check.takeIf { group.isTrackSelected(index) },
                            onClick = {
                                if (player.currentTracks.groups.any { it.mediaTrackGroup == group.mediaTrackGroup && it.isTrackSupported(index) }) {
                                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                        .setTrackTypeDisabled(type, false)
                                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(index))).build()
                                }
                                onDismiss()
                            })
                    }
                }
            }
        }
    }
}

private fun trackDetail(format: androidx.media3.common.Format): String? = listOfNotNull(
    when (format.sampleMimeType) {
        androidx.media3.common.MimeTypes.AUDIO_AAC -> "AAC"
        androidx.media3.common.MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        androidx.media3.common.MimeTypes.AUDIO_E_AC3, androidx.media3.common.MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus"
        androidx.media3.common.MimeTypes.AUDIO_MPEG, androidx.media3.common.MimeTypes.AUDIO_MPEG_L2 -> "MPEG audio"
        androidx.media3.common.MimeTypes.TEXT_VTT -> "WebVTT"
        androidx.media3.common.MimeTypes.APPLICATION_CEA608, androidx.media3.common.MimeTypes.APPLICATION_CEA708 -> "Closed captions"
        androidx.media3.common.MimeTypes.APPLICATION_DVBSUBS -> "DVB"
        else -> format.sampleMimeType?.substringAfter('/')
    },
    format.channelCount.takeIf { it > 0 }?.let { if (it >= 6) "5.1" else if (it == 2) "Stereo" else "$it ch" }
).joinToString(" · ").ifEmpty { null }
