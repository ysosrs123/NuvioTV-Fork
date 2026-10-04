package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.nuvioGlass

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import java.io.File

/** Quality judgement for a stats row. NONE = informational, no opinion. */
enum class StatsDot { NONE, GOOD, WARN, BAD }

data class StatsRow(
    val label: String,
    val value: String,
    val dot: StatsDot = StatsDot.NONE,
    val marquee: Boolean = false
)

/**
 * One sampled frame of the live playback stats HUD. Rows are
 * pre-formatted label/value/dot triples so this composable stays dumb; all
 * sampling, formatting and threshold judgement happens in
 * PlayerViewModel.samplePlaybackStats().
 */
data class PlaybackStatsSample(
    val isExoPlayer: Boolean,
    val engineLabel: String,
    val sections: List<StatsSection>
)

/** Section a stats row belongs to; declaration order is display order in the HUD. */
enum class StatsGroup(val header: String) {
    SOURCE("Source"),
    VIDEO("Video"),
    AUDIO("Audio"),
    NETWORK("Network"),
    SYSTEM("System")
}

/** A titled group of stats rows, rendered under an eyebrow header + hairline rule. */
data class StatsSection(
    val group: StatsGroup,
    val rows: List<StatsRow>
)

private val STATS_GROUP_BY_LABEL: Map<String, StatsGroup> = buildMap {
    listOf("Add-on", "Provider", "Server", "Source host", "File", "Size", "File bitrate")
        .forEach { put(it, StatsGroup.SOURCE) }
    listOf("Video", "HDR", "V bitrate", "DV", "DV HDR", "DV CM", "DV levels", "DV L1", "DV L5", "Format",
        "Decoder", "Dropped", "Frame lead", "Display")
        .forEach { put(it, StatsGroup.VIDEO) }
    listOf("Audio", "A bitrate", "Underruns", "Route", "A jitter")
        .forEach { put(it, StatsGroup.AUDIO) }
    listOf("Buffer", "Speed", "Ping", "Request", "Rate limit", "Hedge", "Load errors", "Loaded", "Rebuffers", "Pos. freeze", "Server latency", "Mux estimate")
        .forEach { put(it, StatsGroup.NETWORK) }
    listOf("App CPU", "Memory", "SoC temp", "CPU clock")
        .forEach { put(it, StatsGroup.SYSTEM) }
}

/**
 * Buckets a flat, emission-ordered row list into display sections. Order within a
 * section is preserved from emission. Owner-removed rows are projected out; the
 * whole-file mux average is named File bitrate. An unmapped label falls to SYSTEM so a newly
 * added row can never silently vanish. Empty sections are dropped (no bare header).
 */
internal fun buildStatsSections(rows: List<StatsRow>): List<StatsSection> {
    val presented = rows.filterNot { it.label in setOf("A bitrate", "Network reads", "Cache/local reads") }
        .map { if (it.label == "Mux bitrate") it.copy(label = "File bitrate") else it }
    val byGroup = presented.groupBy { STATS_GROUP_BY_LABEL[it.label] ?: StatsGroup.SYSTEM }
    return StatsGroup.values().mapNotNull { g -> byGroup[g]?.let { StatsSection(g, it) } }
}

/**
 * All quality thresholds for the HUD dots in one place so retuning is a
 * one-line patch. Recency-windowed dots (dropped frames, underruns, stalls)
 * go red only when the counter increased within RECENT_EVENT_WINDOW_MS.
 */
internal object PlaybackStatsThresholds {
    const val RECENT_EVENT_WINDOW_MS = 30_000L
    // Tuned for high-bitrate remuxes, where the loader cannot get 20 s ahead during
    // dense action even on an adequate link (the scene's instantaneous demand meets or
    // beats delivered rate), so the old 20/5 pair sat amber through most of such a film
    // and never discriminated a low-but-fine 6 s from a stall-imminent 0.1 s. 10/3
    // makes the dot track stall risk: green when comfortable (dialogue holds >=10 s),
    // amber getting-low, red genuine risk. Low-bitrate streams still hold 45 s and stay
    // green, so nothing regresses there.
    const val BUFFER_GOOD_SECONDS = 10.0
    const val BUFFER_WARN_SECONDS = 3.0
    const val BUFFER_STARTUP_GRACE_MS = 10_000L
    const val BUFFER_EOF_GUARD_MS = 5_000L
    const val SPEED_RATIO_GOOD = 2.0
    const val SPEED_RATIO_WARN = 1.0
    const val PING_GOOD_MS = 50L
    const val PING_WARN_MS = 150L
    const val CPU_GOOD_PERCENT = 30
    const val CPU_BAD_PERCENT = 60
    const val HEAP_GOOD_FRACTION = 0.60
    const val HEAP_BAD_FRACTION = 0.85
    const val FPS_MATCH_TOLERANCE = 0.02f

    // Measured mux (V bitrate). Bytes and media time accumulate per HUD tick; a jump
    // in buffered position larger than MUX_MAX_MEDIA_DELTA_MS in one ~1 s tick cannot
    // be a legitimate buffer fill (it would need tens of gigabits per second) and is
    // therefore a seek, which starts a fresh measurement epoch.
    const val MUX_MIN_MEDIA_MS = 5_000L
    const val MUX_MAX_MEDIA_DELTA_MS = 60_000L

    // Windowed ("now") mux rate. The HUD keeps the last MUX_WINDOW_TICKS running-total
    // snapshots (one per ~1 s tick) and divides the byte span by the media span across
    // them, provided that media span is at least MUX_WINDOW_MIN_MEDIA_MS. A ~10 s window
    // is long enough to smooth per-GOP swing but short enough to expose a scene change.
    const val MUX_WINDOW_TICKS = 10
    const val MUX_WINDOW_MIN_MEDIA_MS = 3_000L

    // No 720p-or-better stream muxes below a megabit, and no SD stream below a fifth
    // of one. A figure under the floor for its resolution means the byte counter is not
    // seeing the source's traffic, and the row says so instead of printing the number.
    // The floor is resolution-aware so a genuinely poor SD source is not misreported as
    // a broken instrument.
    const val MUX_IMPLAUSIBLE_FLOOR_BPS = 1_000_000L
    const val MUX_IMPLAUSIBLE_FLOOR_SD_BPS = 200_000L
    const val MUX_FLOOR_HD_MIN_HEIGHT = 720

    // Measured link throughput, sampled only across ticks where the loader was pulling.
    const val THROUGHPUT_MIN_WINDOW_MS = 500L
    const val THROUGHPUT_MAX_WINDOW_MS = 5_000L
    const val THROUGHPUT_SMOOTHING = 0.3

    // The bandwidth meter is trusted only while it has seen at least this fraction
    // (1/N) of the bytes the HUD counted crossing the network. On a plain progressive
    // stream it sees almost none of them.
    const val METER_COVERAGE_DIVISOR = 4L

    // SoC thermal row. Degree thresholds follow a common Amlogic vendor HAL throttle
    // ladder (light 65 / moderate 75 / severe 95 / emergency 105 / shutdown 120 C; such
    // a box idles around 66 C, so "light" is normal operation): amber at moderate, red
    // at severe. Headroom is normalised by the framework so 1.0 = severe on whatever
    // device this runs on; amber when within 10% of it. The degree pair can differ per device.
    // Audio-clock jitter: deviation of reported audio position from wall clock. A healthy
    // passthrough clock tracks within a few ms; a failing TrueHD passthrough clock can jump
    // ~50-57 ms about 30 times a second while every other counter reads clean.
    // The jitter dot is driven by 1 s-window drift (see PlaybackSpeedAwareAudioSink).
    // A clean playback has |drift| p90 around 23 ms/s.
    const val AUDIO_DRIFT_GOOD_MS = 40L
    const val AUDIO_DRIFT_BAD_MS = 100L

    const val SOC_TEMP_WARN_C = 75f
    const val SOC_TEMP_BAD_C = 95f
    const val THERMAL_HEADROOM_WARN = 0.90f
    const val THERMAL_HEADROOM_BAD = 1.0f
}

private val DotGood = Color(0xFF4CAF50)
private val DotWarn = Color(0xFFFFC107)
private val DotBad = Color(0xFFF44336)

/** Fixed HUD width; toggling stats never changes the player control layout. */
internal val StatsPanelWidth = 380.dp

/** Compact is a view of the same sample; full mode always retains every row. */
internal fun visibleStatsSections(sections: List<StatsSection>, compact: Boolean): List<StatsSection> {
    if (!compact) return sections
    val labels = setOf("Video", "HDR", "DV", "DV HDR", "Audio", "Route", "Speed", "Buffer", "Dropped", "Underruns", "Display")
    return sections.mapNotNull { section ->
        section.rows.filter { it.label in labels }.takeIf { it.isNotEmpty() }
            ?.let { section.copy(rows = it) }
    }
}

@Composable
fun PlaybackStatsOverlay(
    visible: Boolean,
    sample: PlaybackStatsSample?,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val isV2 = LocalV2Appearance.current != null
    AnimatedVisibility(
        visible = visible && sample != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .then(if (isV2) Modifier.nuvioGlass(GlassRole.HUD) else Modifier.background(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(NuvioTheme.radii.sm)
                ))
                .width(StatsPanelWidth)
                .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
        ) {
            if (sample?.isExoPlayer == false) {
                Text(
                    text = stringResource(R.string.player_stats_unavailable_mpv),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
            } else {
                visibleStatsSections(sample?.sections.orEmpty(), compact).forEach { section ->
                    Text(
                        text = section.group.header.uppercase(),
                        color = if (isV2) NuvioTheme.colors.TextSecondary else Color.White.copy(alpha = 0.5f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.5.sp
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.12f))
                    )
                    section.rows.forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(
                                        color = when (row.dot) {
                                            StatsDot.GOOD -> DotGood
                                            StatsDot.WARN -> DotWarn
                                            StatsDot.BAD -> DotBad
                                            StatsDot.NONE -> Color.Transparent
                                        },
                                        shape = CircleShape
                                    )
                            )
                            Text(
                                text = row.label,
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 10.sp,
                                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(88.dp)
                            )
                            if (row.marquee) {
                                Text(
                                    text = row.value,
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Clip,
                                    modifier = Modifier
                                        .weight(1f)
                                        .basicMarquee(
                                            iterations = 3,
                                            velocity = 20.dp,
                                            initialDelayMillis = 2000
                                        )
                                )
                            } else {
                                Text(
                                    text = row.value,
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Samples this process's own CPU usage from /proc/self/stat deltas. Global
 * /proc/stat is not readable on modern Android, so this is app CPU only
 * (normalised to total capacity across all cores); the hardware video
 * decoder's load does not appear here.
 */
internal class ProcessCpuSampler {
    private var lastCpuTicks: Long = -1L
    private var lastElapsedMs: Long = -1L
    private val clockTicksPerSecond: Long =
        try {
            android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK)
        } catch (_: Throwable) {
            100L
        }
    private val cores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /** App CPU as a whole percentage of total capacity, or null until the second sample. */
    fun samplePercent(): Int? {
        val ticks = readOwnCpuTicks() ?: return null
        val nowMs = SystemClock.elapsedRealtime()
        val result = if (lastCpuTicks >= 0L && nowMs > lastElapsedMs) {
            val cpuMs = (ticks - lastCpuTicks) * 1000.0 / clockTicksPerSecond
            val wallMs = (nowMs - lastElapsedMs).toDouble()
            ((cpuMs / (wallMs * cores)) * 100.0).toInt().coerceIn(0, 100)
        } else {
            null
        }
        lastCpuTicks = ticks
        lastElapsedMs = nowMs
        return result
    }

    private fun readOwnCpuTicks(): Long? = try {
        val stat = File("/proc/self/stat").readText()
        // utime and stime are stat fields 14 and 15 (1-indexed). The comm field
        // is parenthesised and may contain spaces, so index from after the ')':
        // the first post-comm field is field 3, putting utime/stime at split
        // indices 11 and 12.
        val fields = stat.substringAfterLast(')').trim().split(' ')
        val utime = fields.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = fields.getOrNull(12)?.toLongOrNull() ?: return null
        utime + stime
    } catch (_: Throwable) {
        null
    }
}
