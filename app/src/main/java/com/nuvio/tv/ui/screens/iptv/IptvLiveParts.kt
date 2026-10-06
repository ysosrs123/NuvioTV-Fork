@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.nuvio.tv.core.iptv.ArchiveAvailability
import com.nuvio.tv.core.iptv.GuideGridRow
import com.nuvio.tv.core.iptv.GuideProgramme
import com.nuvio.tv.core.iptv.GuideProgrammeCell
import com.nuvio.tv.core.iptv.catchupType
import com.nuvio.tv.data.iptv.IptvListedChannel
import com.nuvio.tv.data.iptv.IptvStreamFormat
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.accentBrush
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.nuvioGlass
import com.nuvio.tv.ui.v2.components.nuvioV2Focus
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal const val MINUTE_MILLIS = 60_000L
internal val PanelShape = RoundedCornerShape(20.dp)
internal val ItemShape = RoundedCornerShape(12.dp)

@Composable
internal fun Modifier.iptvPanel(shape: Shape = PanelShape, role: GlassRole = GlassRole.PANEL): Modifier =
    if (LocalV2Appearance.current != null) nuvioGlass(role, shape = shape)
    else clip(shape).background(NuvioTheme.colors.BackgroundCard.copy(alpha = .92f), shape).border(1.dp, NuvioTheme.colors.Border, shape)

@Composable
internal fun Modifier.iptvItem(focused: Boolean, selected: Boolean = false, shape: Shape = ItemShape): Modifier =
    if (LocalV2Appearance.current != null) {
        nuvioV2Focus(focused, shape, stationary = true).clip(shape).background(when {
            focused -> NuvioTheme.colors.Secondary.copy(alpha = .18f)
            selected -> NuvioTheme.colors.TextPrimary.copy(alpha = .07f)
            else -> Color.Transparent
        }, shape)
    } else {
        clip(shape).background(when {
            focused -> NuvioTheme.colors.FocusBackground
            selected -> NuvioTheme.colors.SurfaceVariant
            else -> Color.Transparent
        }, shape).then(if (focused) Modifier.border(2.dp, NuvioTheme.colors.FocusRing, shape) else Modifier)
    }

@Composable
internal fun itemContent(focused: Boolean): Color =
    if (focused && LocalV2Appearance.current == null) NuvioTheme.colors.FocusContent else NuvioTheme.colors.TextPrimary

@Composable
internal fun ChannelLogo(url: String?, name: String, modifier: Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    val context = LocalContext.current
    val request = remember(url) { url?.let { ImageRequest.Builder(context).data(it).size(192, 108).build() } }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(NuvioTheme.colors.TextPrimary.copy(alpha = .07f)),
        contentAlignment = Alignment.Center) {
        if (request != null && !failed) {
            AsyncImage(request, null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit, onError = { failed = true })
        } else {
            Text(monogram(name), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextSecondary, maxLines = 1)
        }
    }
}

@Composable
internal fun ProgressLine(fraction: Float, modifier: Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(NuvioTheme.colors.TextPrimary.copy(alpha = .14f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(NuvioTheme.palette.accentBrush()))
    }
}

@Composable
internal fun Tag(text: String, modifier: Modifier = Modifier, live: Boolean = false) {
    Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1,
        color = if (live) Color.White else NuvioTheme.colors.TextSecondary,
        modifier = modifier.clip(RoundedCornerShape(6.dp))
            .background(if (live) NuvioTheme.colors.Error.copy(alpha = .9f) else NuvioTheme.colors.TextPrimary.copy(alpha = .1f))
            .padding(horizontal = 7.dp, vertical = 2.dp))
}

internal fun monogram(name: String): String {
    val words = name.replace(COUNTRY_PREFIX, "").split(' ', '-', '_', '.', '|').filter { word -> word.firstOrNull()?.isLetterOrDigit() == true }
    return words.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "TV" }
}

internal fun logoUrl(row: IptvListedChannel): String? = row.item.attributes["tvg-logo"]?.trim()
    ?.takeIf { it.length <= 2048 && (it.startsWith("https://") || it.startsWith("http://")) }

internal fun hasArchive(row: IptvListedChannel): Boolean =
    row.item.attributes["archive-availability"] == ArchiveAvailability.ADVERTISED.name ||
        catchupType(row.item.attributes["catchup"], row.item.attributes["catchup-source"]) != null

internal fun isSelect(keyCode: Int) = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER || keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
    keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER

internal fun programmeAt(row: GuideGridRow?, time: Long): GuideProgramme? =
    row?.cells?.firstOrNull { it is GuideProgrammeCell && time >= it.startMillis && time < it.endMillis }?.let { (it as GuideProgrammeCell).programme }

internal fun programmeAfter(row: GuideGridRow?, programme: GuideProgramme?, now: Long): GuideProgramme? =
    row?.cells?.filterIsInstance<GuideProgrammeCell>()?.firstOrNull { it.startMillis >= (programme?.stop?.epochMillis ?: now) }?.programme

internal fun airing(programme: GuideProgramme, now: Long) = programme.start.epochMillis <= now && (programme.stop?.epochMillis ?: Long.MAX_VALUE) > now

internal fun progress(programme: GuideProgramme, now: Long): Float? = programme.stop?.epochMillis?.let { stop ->
    ((now - programme.start.epochMillis).toFloat() / (stop - programme.start.epochMillis).coerceAtLeast(1)).coerceIn(0f, 1f)
}

internal fun channelName(row: IptvListedChannel) = row.item.overlay.customName ?: row.item.channel.data.name

internal fun title(programme: GuideProgramme): String {
    val language = Locale.getDefault().language
    return programme.titles.firstOrNull { it.language?.substringBefore('-') == language }?.text ?: programme.titles.firstOrNull()?.text.orEmpty()
}

internal fun description(programme: GuideProgramme): String? {
    val language = Locale.getDefault().language
    return (programme.descriptions.firstOrNull { it.language?.substringBefore('-') == language } ?: programme.descriptions.firstOrNull())?.text?.takeIf { it.isNotBlank() }
}

internal fun clock(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

internal fun timeRange(programme: GuideProgramme): String {
    val start = clock(programme.start.epochMillis)
    val stop = programme.stop?.epochMillis ?: return start
    return "$start – ${clock(stop)}"
}

internal fun formatLabel(format: IptvStreamFormat): Int = when (format) {
    IptvStreamFormat.AUTO -> com.nuvio.tv.R.string.iptv_live_format_auto
    IptvStreamFormat.HLS -> com.nuvio.tv.R.string.iptv_live_format_hls
    IptvStreamFormat.MPEG_TS -> com.nuvio.tv.R.string.iptv_live_format_ts
}

internal fun qualityBadges(player: ExoPlayer?): List<String> {
    val video = player?.videoFormat
    val audio = player?.audioFormat
    return buildList {
        if (video != null && video.height > 0) add(when {
            video.height >= 2000 -> "4K"
            video.height >= 1000 -> "1080p"
            video.height >= 700 -> "720p"
            else -> "${video.height}p"
        })
        video?.frameRate?.takeIf { it > 0 }?.let { add("${kotlin.math.round(it).toInt()} fps") }
        when (video?.sampleMimeType) {
            MimeTypes.VIDEO_H264 -> add("H.264")
            MimeTypes.VIDEO_H265 -> add("HEVC")
            MimeTypes.VIDEO_AV1 -> add("AV1")
            MimeTypes.VIDEO_MPEG2 -> add("MPEG-2")
        }
        when (video?.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> add("HDR10")
            C.COLOR_TRANSFER_HLG -> add("HLG")
        }
        val channels = audio?.channelCount?.takeIf { it > 0 }?.let { if (it >= 6) "5.1" else if (it == 2) "2.0" else "$it ch" }
        when (audio?.sampleMimeType) {
            MimeTypes.AUDIO_AAC -> add(listOfNotNull("AAC", channels).joinToString(" "))
            MimeTypes.AUDIO_AC3 -> add(listOfNotNull("Dolby Digital", channels).joinToString(" "))
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> add(listOfNotNull("Dolby Digital Plus", channels).joinToString(" "))
            MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> add(listOfNotNull("MPEG audio", channels).joinToString(" "))
            else -> channels?.let(::add)
        }
    }
}

private val COUNTRY_PREFIX = Regex("^\\s*[A-Za-z]{2,3}\\s*[:|]\\s*")
