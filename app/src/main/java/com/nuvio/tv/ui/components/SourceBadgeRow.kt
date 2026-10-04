package com.nuvio.tv.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.DirectDebridStreamFilter
import com.nuvio.tv.core.stream.SourcePrefetchPhase
import com.nuvio.tv.core.stream.SourcePrefetchSignal
import com.nuvio.tv.domain.model.DebridStreamAudioChannel
import com.nuvio.tv.domain.model.DebridStreamAudioTag
import com.nuvio.tv.domain.model.DebridStreamQuality
import com.nuvio.tv.domain.model.DebridStreamResolution
import com.nuvio.tv.domain.model.DebridStreamVisualTag

/**
 * The details-page source line (fork feature): parsed badges for the
 * locally ranked auto-play winner, a spinner while searching,
 * and a green tick once the link is usable. Renders nothing when [signal] is
 * null (MANUAL mode, or no target).
 *
 * Badge order per the signed row rules: resolution, source, one video-tech
 * badge (DV wins), audio codec, Atmos, channels, then the release-group chip.
 */
@Composable
fun SourceBadgeRow(
    signal: SourcePrefetchSignal?,
    modifier: Modifier = Modifier,
    badgeHeight: androidx.compose.ui.unit.Dp = 22.dp
) {
    if (signal == null || signal.phase == SourcePrefetchPhase.EMPTY) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        when (signal.phase) {
            SourcePrefetchPhase.SEARCHING -> {
                SourceLineSpinner()
                Text(
                    text = "Finding best source",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.55f)
                )
            }
            SourcePrefetchPhase.RANKED -> {
                SourceBadges(signal.facts, signal.badges, badgeHeight, signal.fileSizeBytes)
            }
            SourcePrefetchPhase.READY -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF4CAF7D),
                    modifier = Modifier.size(18.dp)
                )
                SourceBadges(signal.facts, signal.badges, badgeHeight, signal.fileSizeBytes)
            }
        }
    }
}

@Composable
private fun SourceBadges(
    facts: DirectDebridStreamFilter.StreamFacts?,
    fusionBadges: List<com.nuvio.tv.domain.model.StreamBadge>,
    badgeHeight: androidx.compose.ui.unit.Dp,
    fileSizeBytes: Long?
) {
    val useFusion = fusionBadges.isNotEmpty()
    val resources = if (useFusion) emptyList() else facts?.let(::sourceBadgeResources).orEmpty()
    val group = facts?.releaseGroup.orEmpty()
    val hasChip = group.isNotBlank()
    val size = fileSizeBytes?.takeIf { it > 0 }
    val tailCount = (if (hasChip) 1 else 0) + (if (size != null) 1 else 0)
    Layout(
        content = {
            if (useFusion) {
                for (badge in fusionBadges) {
                    StreamImportedBadgeChip(badge = badge)
                }
            } else {
                for (res in resources) {
                    Image(
                        painter = painterResource(id = res),
                        contentDescription = null,
                        modifier = Modifier.height(badgeHeight),
                        contentScale = ContentScale.FillHeight
                    )
                }
            }
            if (hasChip) {
                SourceMetadataChip(group)
            }
            size?.let { StreamFileSizeBadge(it, sourceLine = true) }
        }
    ) { measurables, constraints ->
        // Greedy channels-first tail-drop (signed row rule): the spinner/tick and the
        // release-group chip always render; badges are kept in their priority order
        // (res, src, tech, audio, Atmos, channels) until the width budget is spent, so
        // overflow sheds the tail - channels first - instead of clipping. Unbounded
        // width keeps everything.
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val tail = placeables.takeLast(tailCount)
        val badges = placeables.dropLast(tailCount)
        val spacingPx = 9.dp.roundToPx()
        val budget = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val kept = mutableListOf<Placeable>()
        var used = tail.sumOf { it.width } + spacingPx * (tail.size - 1).coerceAtLeast(0)
        for (badge in badges) {
            val candidate = used + badge.width + if (used > 0) spacingPx else 0
            if (candidate > budget) break
            kept.add(badge)
            used = candidate
        }
        val rowWidth = used.coerceIn(constraints.minWidth, budget)
        val rowHeight = ((kept + tail).maxOfOrNull { it.height } ?: 0)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(rowWidth, rowHeight) {
            var x = 0
            for (p in kept) {
                p.placeRelative(x, (rowHeight - p.height) / 2)
                x += p.width + spacingPx
            }
            tail.forEach {
                it.placeRelative(x, (rowHeight - it.height) / 2)
                x += it.width + spacingPx
            }
        }
    }
}

@Composable
internal fun SourceMetadataChip(label: String) {
    Text(label, fontSize = 12.sp, color = Color.White.copy(alpha = .9f),
        modifier = Modifier.background(Color.White.copy(alpha = .08f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp))
}

internal fun sourceBadgeResources(facts: DirectDebridStreamFilter.StreamFacts): List<Int> {
    val out = mutableListOf<Int>()
    when (facts.resolution) {
        DebridStreamResolution.P2160 -> out.add(R.drawable.badge_res_2160)
        DebridStreamResolution.P1080 -> out.add(R.drawable.badge_res_1080)
        DebridStreamResolution.P720 -> out.add(R.drawable.badge_res_720)
        DebridStreamResolution.P480 -> out.add(R.drawable.badge_res_480)
        else -> Unit
    }
    when (facts.quality) {
        DebridStreamQuality.BLURAY_REMUX -> out.add(R.drawable.badge_remux)
        DebridStreamQuality.BLURAY -> out.add(R.drawable.badge_bluray)
        DebridStreamQuality.WEB_DL -> out.add(R.drawable.badge_webdl)
        DebridStreamQuality.WEBRIP -> out.add(R.drawable.badge_webrip)
        DebridStreamQuality.HDTV -> out.add(R.drawable.badge_hdtv)
        DebridStreamQuality.DVDRIP -> out.add(R.drawable.badge_dvdrip)
        else -> Unit
    }
    val v = facts.visualTags
    when {
        DebridStreamVisualTag.DV in v || DebridStreamVisualTag.DV_ONLY in v || DebridStreamVisualTag.HDR_DV in v ->
            out.add(R.drawable.badge_dv)
        DebridStreamVisualTag.HDR10_PLUS in v -> out.add(R.drawable.badge_hdr10_plus)
        DebridStreamVisualTag.HDR10 in v -> out.add(R.drawable.badge_hdr10)
        DebridStreamVisualTag.HDR in v || DebridStreamVisualTag.HDR_ONLY in v -> out.add(R.drawable.badge_hdr)
        else -> Unit
    }
    val a = facts.audioTags
    when {
        DebridStreamAudioTag.TRUEHD in a -> out.add(R.drawable.badge_truehd)
        DebridStreamAudioTag.DTS_HD_MA in a -> out.add(R.drawable.badge_dts_hd_ma)
        DebridStreamAudioTag.DTS_X in a -> out.add(R.drawable.badge_dts_x)
        DebridStreamAudioTag.DTS_HD in a -> out.add(R.drawable.badge_dts_hd)
        DebridStreamAudioTag.DD_PLUS in a -> out.add(R.drawable.badge_ddp)
        DebridStreamAudioTag.DTS in a || DebridStreamAudioTag.DTS_ES in a -> out.add(R.drawable.badge_dts)
        DebridStreamAudioTag.DD in a -> out.add(R.drawable.badge_dd)
        else -> Unit
    }
    if (DebridStreamAudioTag.ATMOS in a) out.add(R.drawable.badge_atmos)
    val ch = facts.audioChannels
    when {
        DebridStreamAudioChannel.CH_7_1 in ch -> out.add(R.drawable.badge_ch_7_1)
        DebridStreamAudioChannel.CH_5_1 in ch -> out.add(R.drawable.badge_ch_5_1)
        else -> Unit
    }
    return out
}

@Composable
private fun SourceLineSpinner() {
    val transition = rememberInfiniteTransition(label = "sourceLineSpinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "sourceLineSpinnerAngle"
    )
    Canvas(modifier = Modifier.size(14.dp)) {
        drawArc(
            color = Color.White.copy(alpha = 0.15f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = 2.dp.toPx())
        )
        drawArc(
            color = Color.White.copy(alpha = 0.85f),
            startAngle = angle,
            sweepAngle = 90f,
            useCenter = false,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}