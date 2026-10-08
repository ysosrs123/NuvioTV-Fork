@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import com.nuvio.tv.ui.components.FocusMarqueeText
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import com.nuvio.tv.ui.screens.settings.settingsFocusFillColor
import com.nuvio.tv.ui.screens.settings.settingsItemColor
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
internal fun Modifier.iptvPanel(shape: Shape = PanelShape, role: GlassRole = GlassRole.PANEL, edge: Boolean = false): Modifier {
    val solid = LocalIptvAppearance.current.solidPanels
    val v2 = LocalV2Appearance.current != null
    return if (v2 && !solid) nuvioGlass(role, shape = shape, trailingEdgeOnly = edge)
    else if (v2) clip(shape).background(NuvioTheme.colors.BackgroundCard, shape).then(
        if (edge) Modifier.drawWithContent {
            drawContent()
            val x = if (layoutDirection == LayoutDirection.Ltr) size.width - 1.dp.toPx() / 2 else 1.dp.toPx() / 2
            drawLine(Color.White.copy(alpha = .10f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
        } else Modifier.border(1.dp, Color.White.copy(alpha = .08f), shape))
    else clip(shape).background(if (solid) NuvioTheme.colors.BackgroundCard else NuvioTheme.colors.BackgroundCard.copy(alpha = .96f), shape)
        .border(1.dp, NuvioTheme.colors.Border, shape)
}

@Composable
@ReadOnlyComposable
internal fun iptvTitleStyle(): TextStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)

@Composable
@ReadOnlyComposable
internal fun iptvItemStyle(emphasis: Boolean, compact: Boolean = false): TextStyle =
    (if (compact) MaterialTheme.typography.bodySmall.copy(lineHeight = 15.sp) else MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp))
        .copy(fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Medium)

@Composable
@ReadOnlyComposable
internal fun iptvMetaStyle(): TextStyle = MaterialTheme.typography.labelMedium

@Composable
@ReadOnlyComposable
internal fun iptvHeadingStyle(): TextStyle = MaterialTheme.typography.labelLarge

@Composable
internal fun IptvRailItem(text: String, count: Int?, selected: Boolean, modifier: Modifier, onClick: () -> Unit,
    icon: ImageVector? = null, onHold: (() -> Unit)? = null, dim: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val longPress = rememberLongPressKeyTracker()
    var held by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().heightIn(min = 46.dp)
        .onFocusChanged { focused = it.isFocused }
        .iptvItem(focused)
        .graphicsLayer { alpha = if (dim && !focused) .55f else 1f }
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (onHold != null && longPress.handle(native, ::isSelect) { held = true; onHold() }) {
                if (native.action == AndroidKeyEvent.ACTION_UP) held = false
                return@onPreviewKeyEvent true
            }
            if (onHold != null && native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_MENU) { onHold(); return@onPreviewKeyEvent true }
            if (native.action == AndroidKeyEvent.ACTION_UP && isSelect(native.keyCode)) { if (!held) onClick(); held = false; true } else false
        }
        .focusable().padding(start = 6.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val content = if (focused) itemContent(true) else if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
        Box(Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) NuvioTheme.colors.Secondary else Color.Transparent))
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = content)
        FocusMarqueeText(text, focused, iptvItemStyle(selected || focused), Modifier.weight(1f), color = content)
        if (count != null) Text("$count", style = iptvMetaStyle(), color = NuvioTheme.colors.TextTertiary, maxLines = 1)
    }
}

@Composable
internal fun IptvSearchField(value: String, hint: String, focus: FocusRequester, onChange: (String) -> Unit, onDone: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val keyboard = LocalSoftwareKeyboardController.current
    Row(Modifier.fillMaxWidth().height(52.dp).fieldFocus(focused, shape).iptvPanel(shape, GlassRole.CONTROL)
        .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = NuvioTheme.colors.TextSecondary)
        BasicTextField(value, onChange, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = NuvioTheme.colors.TextPrimary),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onDone() }),
            cursorBrush = SolidColor(NuvioTheme.colors.TextPrimary),
            decorationBox = { inner ->
                Box { if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = NuvioTheme.colors.TextTertiary); inner() }
            },
            modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN) { onDone(); true } else false
                })
    }
}

@Composable
internal fun Modifier.fieldFocus(focused: Boolean, shape: Shape): Modifier =
    if (LocalV2Appearance.current != null) nuvioV2Focus(focused, shape, stationary = true)
    else if (focused) border(2.dp, NuvioTheme.colors.FocusRing, shape) else this

@Composable
internal fun Modifier.iptvItem(focused: Boolean, selected: Boolean = false, shape: Shape = ItemShape): Modifier =
    if (LocalV2Appearance.current != null) {
        nuvioV2Focus(focused, shape, stationary = true).clip(shape).background(when {
            focused -> settingsFocusFillColor()
            selected -> settingsItemColor(Color.Transparent)
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
internal fun Tag(text: String, modifier: Modifier = Modifier, live: Boolean = false, scrim: Boolean = live) {
    if (!scrim) {
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, color = NuvioTheme.colors.TextSecondary, modifier = modifier)
        return
    }
    Row(modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = .45f)).padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (live) Box(Modifier.size(6.dp).clip(CircleShape).background(NuvioTheme.colors.Error))
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, color = Color.White)
    }
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

internal fun guideRow(state: IptvLiveState, id: String): GuideGridRow? = state.guide[id] ?: state.extraGuide[id]

internal fun liveProgramme(state: IptvLiveState, id: String, time: Long): GuideProgramme? =
    programmeAt(guideRow(state, id), time) ?: state.shortGuide[id]?.firstOrNull { it.start.epochMillis <= time && (it.stop?.epochMillis ?: Long.MAX_VALUE) > time }

internal fun nextProgramme(state: IptvLiveState, id: String, programme: GuideProgramme?, now: Long): GuideProgramme? =
    programmeAfter(guideRow(state, id), programme, now)
        ?: state.shortGuide[id]?.firstOrNull { it.start.epochMillis >= (programme?.stop?.epochMillis ?: now) }

internal fun catchupProgrammes(state: IptvLiveState, id: String): List<GuideProgramme> {
    val fromGuide = guideRow(state, id)?.cells?.filterIsInstance<GuideProgrammeCell>()?.map { it.programme }.orEmpty()
    val loaded = if (state.playingId == id) state.scrubProgrammes else emptyList()
    return (loaded + fromGuide + listOfNotNull(state.catchup.takeIf { state.playingId == id }))
        .distinctBy { it.start.epochMillis }.sortedBy { it.start.epochMillis }
}

internal fun catchupShown(state: IptvLiveState, position: Long): GuideProgramme? {
    val catchup = state.catchup ?: return null
    val id = state.playingId ?: return catchup
    return com.nuvio.tv.core.iptv.CatchupScrub.programmeAt(catchupProgrammes(state, id), position) ?: catchup
}

internal fun playbackTag(state: IptvLiveState, shown: GuideProgramme?, now: Long): Int = when {
    state.catchup == null -> com.nuvio.tv.R.string.iptv_live_playing
    state.catchupFrom != null && shown != null && airing(shown, now) -> com.nuvio.tv.R.string.iptv_live_behind
    else -> com.nuvio.tv.R.string.iptv_live_catchup
}

internal fun programmeArt(programme: GuideProgramme?): String? = com.nuvio.tv.core.iptv.guideIconUrl(programme?.icon)

@Composable
internal fun ProgrammeArt(url: String?, modifier: Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    val context = LocalContext.current
    val request = remember(url) { url?.let { ImageRequest.Builder(context).data(it).size(320, 180).build() } }
    if (request == null || failed) return
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(NuvioTheme.colors.TextPrimary.copy(alpha = .07f))) {
        AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onError = { failed = true })
    }
}

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
