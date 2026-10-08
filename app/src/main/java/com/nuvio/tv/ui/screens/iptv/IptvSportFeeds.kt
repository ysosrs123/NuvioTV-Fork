@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.FixtureLinkReason
import com.nuvio.tv.data.iptv.IptvFixtureLink
import com.nuvio.tv.data.iptv.IptvListedChannel
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme

internal fun backupFeed(item: IptvFixtureItem, playingId: String?): IptvListedChannel? =
    item.links.firstOrNull { it.row.item.channel.id != playingId }?.row?.takeIf { item.links.any { link -> link.row.item.channel.id == playingId } }

@Composable
internal fun IptvSportFeedsPanel(item: IptvFixtureItem, state: IptvLiveState, backupArmed: Boolean, onWatch: (IptvListedChannel) -> Unit,
    onBackup: ((Boolean) -> Unit)?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val first = remember { FocusRequester() }
    val backup = backupFeed(item, state.playingId)
    BackHandler { onClose() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    Column(modifier.fillMaxHeight().iptvPanel().padding(horizontal = 22.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.iptv_sport5p_feeds_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.colors.TextPrimary, maxLines = 1)
        Text(stringResource(R.string.iptv_sport5p_feeds_subtitle, sportTitle(item.fixture)), style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(item.links, key = { _, link -> link.row.item.channel.id }) { index, link ->
                FeedRow(link, state, backup?.item?.channel?.id == link.row.item.channel.id && backupArmed,
                    if (index == 0) Modifier.focusRequester(first) else Modifier) { onWatch(link.row) }
            }
        }
        if (backup != null && onBackup != null) SettingsToggleRow(title = stringResource(R.string.iptv_sport5p_backup_toggle),
            subtitle = stringResource(R.string.iptv_sport5p_backup_subtitle, channelName(backup)), checked = backupArmed, onToggle = { onBackup(!backupArmed) },
            modifier = Modifier.focusProperties { left = FocusRequester.Cancel; right = FocusRequester.Cancel })
    }
}

@Composable
private fun FeedRow(link: IptvFixtureLink, state: IptvLiveState, backup: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val row = link.row
    val playing = row.item.channel.id == state.playingId
    val source = state.sources.firstOrNull { it.ref.sourceId == row.item.channel.sourceId }?.label
    val reason = stringResource(when (link.reason) {
        FixtureLinkReason.GUIDE_TEAMS -> R.string.iptv_sport5p_reason_teams
        FixtureLinkReason.GUIDE_LEAGUE -> R.string.iptv_sport5p_reason_league
        FixtureLinkReason.BROADCASTER -> R.string.iptv_sport5p_reason_broadcaster
    })
    Row(modifier.fillMaxWidth()
        .focusProperties { left = FocusRequester.Cancel; right = FocusRequester.Cancel }
        .onFocusChanged { focused = it.isFocused }
        .iptvItem(focused, selected = playing)
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (isSelect(native.keyCode)) { if (native.action == AndroidKeyEvent.ACTION_UP) onClick(); true } else false
        }
        .focusable().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ChannelLogo(logoUrl(row), channelName(row), Modifier.size(56.dp, 32.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(channelName(row), style = iptvItemStyle(playing || focused), color = itemContent(focused), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (playing) Tag(stringResource(R.string.iptv_sport5p_feed_playing), live = true)
                else if (backup) Tag(stringResource(R.string.iptv_sport5p_feed_backup))
            }
            Text(listOfNotNull(source, reason, link.programme?.let { "${timeRange(it)} · ${title(it)}" }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
