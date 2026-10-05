@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nuvio.tv.ui.screens.iptv

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.media3.common.C
import androidx.media3.common.Player
import com.nuvio.tv.ui.screens.player.DebugStat
import com.nuvio.tv.ui.screens.player.DebugStatsPanel
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun IptvStatsOverlay(playback: IptvLivePlayback, modifier: Modifier = Modifier) {
    var stats by remember(playback) { mutableStateOf(emptyList<DebugStat>()) }
    LaunchedEffect(playback) {
        while (true) {
            val p = playback.player // All player reads stay on its main application thread.
            val sample = playback.telemetry.sample(SystemClock.elapsedRealtime())
            val video = p.videoFormat
            val audio = p.audioFormat
            val counters = p.videoDecoderCounters?.also { it.ensureUpdated() }
            val offset = p.currentLiveOffset.takeIf { it != C.TIME_UNSET && it >= 0 }
            stats = buildList {
                add(DebugStat("IPTV", when(p.playbackState) {
                    Player.STATE_BUFFERING -> "Buffering"
                    Player.STATE_READY -> if (p.isPlaying) "Playing" else "Ready"
                    Player.STATE_ENDED -> "Ended"
                    else -> "Preparing"
                }))
                add(DebugStat("video", video?.let {
                    listOfNotNull(it.sampleMimeType, if (it.width > 0 && it.height > 0) "${it.width}×${it.height}" else null,
                        it.frameRate.takeIf { rate -> rate > 0 }?.let { rate -> String.format(Locale.US, "%.2f fps source", rate) }).joinToString(" · ")
                } ?: "Unavailable"))
                add(DebugStat("audio", audio?.let { listOfNotNull(it.sampleMimeType,
                    it.channelCount.takeIf { count -> count > 0 }?.let { count -> "$count channels" }).joinToString(" · ") } ?: "Unavailable"))
                add(DebugStat("buffer", seconds(p.totalBufferedDuration) + " ahead"))
                add(DebugStat("live", offset?.let { seconds(it) + " offset (manifest)" } ?: "Offset unavailable"))
                add(DebugStat("network", sample.bitsPerSecond?.let { String.format(Locale.US, "%.2f Mbps · HTTP body", it / 1e6) } ?: "Sampling"))
                add(DebugStat("HTTP", "${playback.activeRequests} open · " + String.format(Locale.US, "%.1f MiB read", sample.bytes / 1048576.0)))
                add(DebugStat("tune", sample.firstFrameMs?.let { seconds(it) + " to first frame" } ?: "Waiting for first frame"))
                add(DebugStat("stalls", "${sample.rebuffers} · ${seconds(sample.rebufferMs)} total", sample.rebuffers > 0))
                add(DebugStat("dropped", counters?.let { "${it.droppedBufferCount} · renderer buffers" } ?: "Unavailable"))
            }
            delay(1000)
        }
    }
    DebugStatsPanel(stats, modifier)
}
private fun seconds(ms: Long) = String.format(Locale.US, "%.1f s", ms.coerceAtLeast(0) / 1000.0)
