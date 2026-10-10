@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nuvio.tv.ui.screens.iptv

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import com.nuvio.tv.data.iptv.IptvStreamFormat
import com.nuvio.tv.ui.screens.player.PlaybackStatsOverlay
import com.nuvio.tv.ui.screens.player.PlaybackStatsSample
import com.nuvio.tv.ui.screens.player.StatsDot
import com.nuvio.tv.ui.screens.player.StatsGroup
import com.nuvio.tv.ui.screens.player.StatsRow
import com.nuvio.tv.ui.screens.player.StatsSection
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun IptvStatsOverlay(playback: IptvLivePlayback, provider: String?, modifier: Modifier = Modifier) {
    var sample by remember(playback) { mutableStateOf<PlaybackStatsSample?>(null) }
    LaunchedEffect(playback) {
        var dropped = 0
        var droppedAt = 0L
        var stalls = 0
        var stalledAt = 0L
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val p = playback.player
            val telemetry = playback.telemetry.sample(now)
            val video = p.videoFormat
            val audio = p.audioFormat
            val counters = p.videoDecoderCounters?.also { it.ensureUpdated() }
            if ((counters?.droppedBufferCount ?: 0) > dropped) { dropped = counters?.droppedBufferCount ?: 0; droppedAt = now }
            if (telemetry.rebuffers > stalls) { stalls = telemetry.rebuffers; stalledAt = now }
            val buffered = p.totalBufferedDuration
            val offset = p.currentLiveOffset.takeIf { it != C.TIME_UNSET && it >= 0 }
            val source = listOfNotNull(
                provider?.let { StatsRow("Provider", it, marquee = true) },
                playback.host?.let { StatsRow("Server", it, marquee = true) },
                StatsRow("Format", when (playback.streamFormat) {
                    IptvStreamFormat.AUTO -> containerLabel(video ?: audio) ?: "Auto"
                    IptvStreamFormat.HLS -> "HLS"
                    IptvStreamFormat.MPEG_TS -> "MPEG-TS"
                }),
                StatsRow("Mode", if (playback.live) "Live" else "Catch-up"))
            val videoRows = listOfNotNull(
                StatsRow("Video", video?.let { videoLabel(it) } ?: "Unavailable"),
                video?.colorInfo?.colorTransfer?.let { transfer -> when (transfer) {
                    C.COLOR_TRANSFER_ST2084 -> StatsRow("HDR", "HDR10")
                    C.COLOR_TRANSFER_HLG -> StatsRow("HDR", "HLG")
                    else -> null
                } },
                video?.bitrate?.takeIf { it > 0 }?.let { StatsRow("V bitrate", mbps(it.toDouble())) },
                counters?.let { StatsRow("Dropped", "${it.droppedBufferCount}",
                    if (it.droppedBufferCount == 0) StatsDot.GOOD else if (now - droppedAt < RECENT_MS) StatsDot.BAD else StatsDot.WARN) })
            val audioRows = listOfNotNull(StatsRow("Audio", audio?.let { audioLabel(it) } ?: "Unavailable"),
                audio?.bitrate?.takeIf { it > 0 }?.let { StatsRow("A bitrate", String.format(Locale.US, "%d kbps", it / 1000)) },
                playback.appliedBoostDb.takeIf { it > 0 }?.let { gain -> StatsRow("Gain", listOfNotNull("+$gain dB",
                    playback.autoBoostDb.takeIf { it > 0 }?.let { "surround +$it" }).joinToString(" · ")) })
            val target = playback.bufferTargetMs
            val playbackSpeed = playback.playbackSpeed
            val network = listOfNotNull(
                StatsRow("Buffer", if (target > 0) "${seconds(buffered)} / ${seconds(target)}" else seconds(buffered), when {
                    buffered >= 6_000 -> StatsDot.GOOD
                    buffered >= 1_500 -> StatsDot.WARN
                    else -> StatsDot.BAD
                }),
                playback.behindLiveMs()?.takeIf { it >= 1_000 }?.let { StatsRow("Behind live", seconds(it)) }
                    ?: offset?.let { StatsRow("Live offset", seconds(it)) },
                playbackSpeed.takeIf { it != 1f }?.let { StatsRow("Playback", String.format(Locale.US, "%.2f×", it), StatsDot.WARN) },
                playback.protocol?.let { StatsRow("Protocol", it) },
                playback.altSvcH3?.let { StatsRow("HTTP/3", if (it) "Advertised" else "Not advertised") },
                StatsRow("Reconnects", "${playback.reconnects}", if (playback.reconnects == 0) StatsDot.GOOD else StatsDot.WARN),
                StatsRow("Speed", telemetry.bitsPerSecond?.let { mbps(it) } ?: "Sampling",
                    telemetry.bitsPerSecond?.let { rate -> val needed = (video?.bitrate?.takeIf { it > 0 } ?: 0).toDouble()
                        if (needed <= 0) StatsDot.NONE else if (rate >= needed * 1.5) StatsDot.GOOD else if (rate >= needed) StatsDot.WARN else StatsDot.BAD } ?: StatsDot.NONE),
                StatsRow("Loaded", String.format(Locale.US, "%.1f MiB", telemetry.bytes / 1_048_576.0)),
                StatsRow("Request", "${playback.activeRequests} open"),
                StatsRow("Start time", telemetry.firstFrameMs?.let { seconds(it) } ?: "Waiting",
                    telemetry.firstFrameMs?.let { if (it <= 2_000) StatsDot.GOOD else if (it <= 5_000) StatsDot.WARN else StatsDot.BAD } ?: StatsDot.NONE),
                StatsRow("Rebuffers", "${telemetry.rebuffers} · ${seconds(telemetry.rebufferMs)}",
                    if (telemetry.rebuffers == 0) StatsDot.GOOD else if (now - stalledAt < RECENT_MS) StatsDot.BAD else StatsDot.WARN),
                StatsRow("State", when (p.playbackState) {
                    Player.STATE_BUFFERING -> "Buffering"
                    Player.STATE_READY -> if (p.isPlaying) "Playing" else "Paused"
                    Player.STATE_ENDED -> "Ended"
                    else -> "Preparing"
                }))
            val runtime = Runtime.getRuntime()
            val used = runtime.totalMemory() - runtime.freeMemory()
            val fraction = used.toDouble() / runtime.maxMemory()
            val system = listOf(StatsRow("Memory", String.format(Locale.US, "%d / %d MiB", used / 1_048_576, runtime.maxMemory() / 1_048_576),
                if (fraction < .6) StatsDot.GOOD else if (fraction < .85) StatsDot.WARN else StatsDot.BAD))
            sample = PlaybackStatsSample(true, "ExoPlayer", listOf(
                StatsSection(StatsGroup.SOURCE, source), StatsSection(StatsGroup.VIDEO, videoRows), StatsSection(StatsGroup.AUDIO, audioRows),
                StatsSection(StatsGroup.NETWORK, network), StatsSection(StatsGroup.SYSTEM, system)))
            delay(1_000)
        }
    }
    PlaybackStatsOverlay(visible = true, sample = sample, modifier = modifier)
}

private const val RECENT_MS = 30_000L

private fun containerLabel(format: Format?): String? = when (format?.containerMimeType) {
    MimeTypes.APPLICATION_M3U8 -> "HLS"
    MimeTypes.VIDEO_MP2T -> "MPEG-TS"
    null -> null
    else -> format.containerMimeType?.substringAfter('/')?.uppercase(Locale.US)
}

private fun videoLabel(format: Format): String = listOfNotNull(
    if (format.width > 0 && format.height > 0) "${format.width}×${format.height}" else null,
    when (format.sampleMimeType) {
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        else -> format.sampleMimeType?.substringAfter('/')
    },
    format.frameRate.takeIf { it > 0 }?.let { String.format(Locale.US, "%.2f fps", it) }
).joinToString(" · ")

private fun audioLabel(format: Format): String = listOfNotNull(
    when (format.sampleMimeType) {
        MimeTypes.AUDIO_AAC -> when (format.codecs?.lowercase(Locale.US)) {
            "mp4a.40.2" -> "AAC-LC"
            "mp4a.40.5" -> "HE-AAC"
            "mp4a.40.29" -> "HE-AAC v2"
            else -> "AAC"
        }
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus"
        MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MPEG audio"
        else -> format.sampleMimeType?.substringAfter('/')
    },
    format.channelCount.takeIf { it > 0 }?.let { when (it) { 1 -> "Mono"; 2 -> "2.0"; 6 -> "5.1 (6 ch)"; 8 -> "7.1 (8 ch)"; else -> "$it ch" } },
    format.sampleRate.takeIf { it > 0 }?.let { "${it / 1000.0} kHz".replace(".0 kHz", " kHz") },
    format.language?.takeIf { it != "und" }
).joinToString(" · ")

private fun seconds(ms: Long) = String.format(Locale.US, "%.1f s", ms.coerceAtLeast(0) / 1000.0)

private fun mbps(bits: Double) = String.format(Locale.US, "%.2f Mbps", bits / 1e6)
