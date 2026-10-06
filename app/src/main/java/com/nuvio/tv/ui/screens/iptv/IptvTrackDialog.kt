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
import androidx.tv.material3.Button
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
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.iptv_live_tracks)) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (type in listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) {
                Text(stringResource(if (type == C.TRACK_TYPE_AUDIO) R.string.iptv_live_audio else R.string.iptv_live_subtitles), color = NuvioTheme.colors.TextPrimary)
                Button(onClick = {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(type).setTrackTypeDisabled(type, false).build()
                    onDismiss()
                }) { Text(stringResource(R.string.iptv_live_tracks_auto)) }
                if (type == C.TRACK_TYPE_TEXT) Button(onClick = {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(type).setTrackTypeDisabled(type, true).build()
                    onDismiss()
                }) { Text(stringResource(R.string.iptv_live_tracks_off)) }
                val groups = tracks.groups.filter { it.type == type }
                if (groups.isEmpty()) Text(stringResource(R.string.iptv_live_tracks_none), color = NuvioTheme.colors.TextSecondary)
                groups.forEach { group ->
                    repeat(group.length) { index ->
                        val format = group.getTrackFormat(index)
                        val language = format.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).displayName }
                        val label = listOfNotNull(format.label?.take(80), language, format.sampleMimeType).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                        val supported = group.isTrackSupported(index)
                        Button(enabled = supported, onClick = {

                            if (player.currentTracks.groups.any { it.mediaTrackGroup == group.mediaTrackGroup && it.isTrackSupported(index) }) {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(type, false)
                                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(index))).build()
                            }
                            onDismiss()
                        }) {
                            Text((if (group.isTrackSelected(index)) "✓ " else "") + "${index+1}. " + label +
                                if (supported) "" else " · " + stringResource(R.string.iptv_live_tracks_unsupported))
                        }
                    }
                }
            }
        }
        Button(onClick = onDismiss) { Text(stringResource(R.string.iptv_setup_back)) }
    }
}
