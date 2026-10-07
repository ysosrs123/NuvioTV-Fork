@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.FixtureSection
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsFixtureSections
import com.nuvio.tv.core.iptv.SportsLeagues
import com.nuvio.tv.core.iptv.SportsService
import com.nuvio.tv.data.iptv.IptvFixtureLink
import com.nuvio.tv.data.iptv.IptvListedChannel
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.data.iptv.IptvSourceRef
import com.nuvio.tv.data.iptv.IptvSportsFixturesRepository
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.components.NuvioActionPill
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class IptvFixtureItem(val fixture: SportsFixture, val links: List<IptvFixtureLink>)

data class IptvFixturesState(val enabled: Boolean = false, val loading: Boolean = false, val sections: List<Pair<FixtureSection, List<IptvFixtureItem>>> = emptyList(),
    val failed: Boolean = false, val missingKey: Boolean = false, val noLeagues: Boolean = false)

@HiltViewModel
class IptvSportsFixturesViewModel @Inject constructor(private val repository: IptvSportsFixturesRepository) : ViewModel() {
    private val mutable = MutableStateFlow(IptvFixturesState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var opened: Pair<IptvSourceRef, Set<String>>? = null

    fun open(ref: IptvSourceRef, hiddenCategories: Set<String>) {
        if (job?.isActive == true && opened == ref to hiddenCategories) return
        job?.cancel()
        opened = ref to hiddenCategories
        job = viewModelScope.launch {
            var refresh = false
            var linked = emptyMap<String, List<IptvFixtureLink>>()
            var signature: List<Pair<String, Long>>? = null
            var linkedAt = 0L
            while (isActive) {
                val now = System.currentTimeMillis()
                val zone = ZoneId.systemDefault()
                if (!refresh) mutable.update { it.copy(loading = it.sections.isEmpty()) }
                try {
                    val result = repository.load(now, zone, refresh)
                    if (result.service == SportsService.OFF) { mutable.value = IptvFixturesState(); return@launch }
                    val sections = SportsFixtureSections.group(result.fixtures, now, zone)
                    val shown = sections.flatMap { it.second }
                    val current = shown.map { it.league + ":" + it.id to it.startMillis }
                    if (current != signature || now - linkedAt >= RELINK_MILLIS) {
                        linked = try { repository.links(ref, shown, now, hiddenCategories) }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (error: Exception) { IptvLog.failure("sports links", error); linked }
                        signature = current; linkedAt = now
                    }
                    mutable.value = IptvFixturesState(true, loading = !refresh && sections.isEmpty() && !result.missingKey && !result.noLeagues,
                        sections = sections.map { (section, fixtures) -> section to fixtures.map { IptvFixtureItem(it, linked[it.id].orEmpty()) } },
                        failed = result.failed, missingKey = result.missingKey, noLeagues = result.noLeagues)
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    IptvLog.failure("sports fixtures", error)
                    mutable.update { it.copy(enabled = true, loading = false, failed = true) }
                }
                if (refresh) delay(TICK_MILLIS)
                refresh = true
            }
        }
    }

    fun close() { job?.cancel(); job = null }

    private companion object {
        const val TICK_MILLIS = 60_000L
        const val RELINK_MILLIS = 5L * 60 * 1000
    }
}

@Composable
internal fun IptvSportsFixturesRow(source: IptvSourceRef?, hiddenCategories: Set<String>, playingId: String?, onWatch: (IptvListedChannel) -> Unit,
    modifier: Modifier = Modifier, viewModel: IptvSportsFixturesViewModel = hiltViewModel()) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, source, hiddenCategories) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && source != null) viewModel.open(source, hiddenCategories)
            if (event == Lifecycle.Event.ON_STOP) viewModel.close()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); viewModel.close() }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.enabled || source == null) return
    var choosing by remember { mutableStateOf<IptvFixtureItem?>(null) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.iptv_sport_fixtures), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary)
            val message = when {
                state.missingKey -> R.string.iptv_sport_key_missing
                state.noLeagues -> R.string.iptv_sport_no_leagues
                state.failed -> R.string.iptv_sport_failed
                state.loading -> R.string.iptv_sport_loading
                state.sections.isEmpty() -> R.string.iptv_sport_none
                else -> null
            }
            if (message != null) Text(stringResource(message), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (state.failed || state.missingKey) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary)
        }
        if (state.sections.isNotEmpty()) LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            state.sections.forEach { (section, fixtures) ->
                item(key = "section-${section.name}") { SectionLabel(section, fixtures.first().fixture) }
                items(fixtures, key = { "${it.fixture.league}:${it.fixture.id}" }) { item ->
                    FixtureCard(item, playing = item.links.any { it.row.item.channel.id == playingId }, onClick = {
                        if (item.links.size == 1) onWatch(item.links.first().row) else choosing = item
                    })
                }
            }
        }
    }
    choosing?.let { item -> FixtureChannelsDialog(item, onWatch = { choosing = null; onWatch(it) }, onDismiss = { choosing = null }) }
}

@Composable
private fun SectionLabel(section: FixtureSection, first: SportsFixture) {
    val text = when (section) {
        FixtureSection.LIVE -> stringResource(R.string.iptv_sport_live_now)
        FixtureSection.TODAY -> stringResource(R.string.iptv_sport_later_today)
        FixtureSection.TOMORROW -> stringResource(R.string.iptv_sport_tomorrow)
        FixtureSection.LATER -> remember(first.startMillis) { SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(first.startMillis)) }
    }
    Box(Modifier.height(FIXTURE_HEIGHT).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 2,
            color = if (section == FixtureSection.LIVE) NuvioTheme.colors.Error else NuvioTheme.colors.Secondary, modifier = Modifier.widthIn(max = 96.dp))
    }
}

@Composable
private fun FixtureCard(item: IptvFixtureItem, playing: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fixture = item.fixture
    val live = fixture.status == FixtureStatus.LIVE
    val scores = fixture.score?.split('–')?.takeIf { it.size == 2 && live }
    Column(Modifier.width(260.dp).height(FIXTURE_HEIGHT)
        .clip(ItemShape).background(NuvioTheme.colors.TextPrimary.copy(alpha = .05f), ItemShape)
        .onFocusChanged { focused = it.isFocused }
        .iptvItem(focused, playing)
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (isSelect(native.keyCode)) { if (native.action == AndroidKeyEvent.ACTION_UP) onClick(); true } else false
        }
        .focusable().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(SportsLeagues.byId(fixture.league)?.name ?: fixture.league, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = NuvioTheme.colors.TextTertiary, modifier = Modifier.weight(1f))
            if (live) Tag(fixture.detail?.takeIf { it.length <= 12 } ?: stringResource(R.string.iptv_sport_live), live = true)
            else Text(clock(fixture.startMillis), style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 1)
        }
        val home = fixture.home
        val away = fixture.away
        if (home != null && away != null) {
            TeamLine(home.shortName ?: home.name, scores?.getOrNull(0), focused)
            TeamLine(away.shortName ?: away.name, scores?.getOrNull(1), focused)
        } else {
            Text(fixture.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = itemContent(focused))
        }
        Spacer(Modifier.weight(1f))
        val first = item.links.firstOrNull()
        Text(if (first == null) stringResource(R.string.iptv_sport_no_channel) else channelName(first.row) + if (item.links.size > 1) " +${item.links.size - 1}" else "",
            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (first == null) NuvioTheme.colors.TextTertiary else NuvioTheme.colors.Secondary)
    }
}

@Composable
private fun TeamLine(name: String, score: String?, focused: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = itemContent(focused), modifier = Modifier.weight(1f))
        if (score != null) Text(score.trim(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, color = itemContent(focused))
    }
}

@Composable
private fun FixtureChannelsDialog(item: IptvFixtureItem, onWatch: (IptvListedChannel) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val fixture = item.fixture
    NuvioDialog(onDismiss = onDismiss, title = fixture.title,
        subtitle = stringResource(if (item.links.isEmpty()) R.string.iptv_sport_no_channel_description else R.string.iptv_sport_choose_channel), width = 560.dp) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item.links.forEachIndexed { index, link ->
                SettingsActionRow(title = channelName(link.row),
                    subtitle = link.programme?.let { "${timeRange(it)} · ${title(it)}" } ?: link.broadcaster?.let { stringResource(R.string.iptv_sport_broadcaster, it) },
                    onClick = { onWatch(link.row) }, leadingIcon = Icons.Filled.LiveTv, trailingIcon = null,
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier)
            }
            if (item.links.isEmpty()) NuvioActionPill(onDismiss, Modifier.focusRequester(first)) { Text(stringResource(R.string.iptv_sport_close)) }
        }
    }
}

private val FIXTURE_HEIGHT = 88.dp
