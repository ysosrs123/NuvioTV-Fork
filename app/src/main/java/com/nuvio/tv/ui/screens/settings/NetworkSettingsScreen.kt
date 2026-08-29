@file:OptIn(ExperimentalTvMaterial3Api::class)


package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.core.network.StreamSweepEngine
import com.nuvio.tv.ui.theme.NuvioTheme

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.nuvio.tv.ui.components.FocusMarqueeText
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.domain.model.ExperienceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
private interface ClearCwCacheEntryPoint {
    fun cwEnrichmentCache(): com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
private interface ClearImageCacheEntryPoint {
    fun catalogRepository(): com.nuvio.tv.domain.repository.CatalogRepository
    fun homeRefreshSignal(): com.nuvio.tv.core.util.HomeRefreshSignal
    fun okHttpClient(): okhttp3.OkHttpClient

    @javax.inject.Named("validated")
    fun validatedOkHttpClient(): okhttp3.OkHttpClient
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
private interface ProfileManagerEntryPoint {
    fun profileManager(): com.nuvio.tv.core.profile.ProfileManager
}

private enum class NetworkTestState { Idle, TestingLatency, TestingDownload, Done, Error }

private enum class ConnectionType { WiFi, Ethernet, Offline }

private fun getConnectionType(context: android.content.Context): ConnectionType {
    val cm = context.getSystemService<ConnectivityManager>() ?: return ConnectionType.Offline
    val network = cm.activeNetwork ?: return ConnectionType.Offline
    val caps = cm.getNetworkCapabilities(network) ?: return ConnectionType.Offline
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionType.Ethernet
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WiFi
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.WiFi // treat cellular as connected
        else -> ConnectionType.Offline
    }
}

@Composable
private fun ConnectionStatusBadge(type: ConnectionType) {
    val (icon, label, color) = when (type) {
        ConnectionType.WiFi -> Triple(Icons.Default.Wifi, stringResource(R.string.network_connection_wifi), NuvioTheme.colors.Success)
        ConnectionType.Ethernet -> Triple(Icons.Default.Wifi, stringResource(R.string.network_connection_ethernet), NuvioTheme.colors.Success)
        ConnectionType.Offline -> Triple(Icons.Default.SignalWifiOff, stringResource(R.string.network_connection_offline), NuvioTheme.colors.Error)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = NuvioTheme.spacing.md, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(NuvioTheme.spacing.lg),
            tint = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = color
        )
    }
}

private suspend fun fetchFastComUrls(context: android.content.Context): List<String> = withContext(Dispatchers.IO) {
    // 1. Load fast.com page to find the app JS bundle URL
    val html = (URL("https://fast.com").openConnection() as HttpURLConnection).run {
        connectTimeout = 10_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", "Mozilla/5.0")
        inputStream.bufferedReader().use { it.readText() }.also { disconnect() }
    }
    val scriptPath = Regex("""<script src="(/app[^"]+\.js)"""").find(html)?.groupValues?.get(1)
        ?: throw Exception(context.getString(com.nuvio.tv.R.string.network_fast_error_script_path_missing))

    // 2. Extract the API token from the JS bundle
    val js = (URL("https://fast.com$scriptPath").openConnection() as HttpURLConnection).run {
        connectTimeout = 10_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "Mozilla/5.0")
        inputStream.bufferedReader().use { it.readText() }.also { disconnect() }
    }
    val token = Regex("""token:"([^"]+)"""").find(js)?.groupValues?.get(1)
        ?: throw Exception(context.getString(com.nuvio.tv.R.string.network_fast_error_token_missing))

    // 3. Fetch CDN URLs from the speed-test API
    val apiJson = (URL("https://api.fast.com/netflix/speedtest/v2?https=true&token=$token&urlCount=15")
        .openConnection() as HttpURLConnection).run {
        connectTimeout = 5_000
        readTimeout = 10_000
        setRequestProperty("User-Agent", "Mozilla/5.0")
        inputStream.bufferedReader().use { it.readText() }.also { disconnect() }
    }
    val targets = JSONObject(apiJson).getJSONArray("targets")
    (0 until targets.length()).map { targets.getJSONObject(it).getString("url") }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun AdvancedSettingsContent(
    initialFocusRequester: FocusRequester? = null,
    viewModel: AdvancedSettingsViewModel = hiltViewModel(),
    experienceModeViewModel: ExperienceModeSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var connectionType by remember { mutableStateOf(getConnectionType(context)) }
    var testState by remember { mutableStateOf(NetworkTestState.Idle) }
    var latencyMs by remember { mutableStateOf<Long?>(null) }
    var downloadMbps by remember { mutableStateOf<Double?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val unknownError = stringResource(R.string.error_unknown)

    // DV Diagnostics: reuse the playback settings store for the conversion-mode
    // override and the last-playback diagnostics card.
    val playbackVm: PlaybackSettingsViewModel = hiltViewModel()
    val dvPlayerSettings by playbackVm.playerSettings.collectAsStateWithLifecycle(
        initialValue = com.nuvio.tv.data.local.PlayerSettings()
    )
    val dvDiagnostics by playbackVm.lastPlaybackDiagnostics.collectAsStateWithLifecycle(
        initialValue = com.nuvio.tv.core.player.LastPlaybackDiagnostics.EMPTY
    )

    // Stream Speed Test States. streamTestState is "Idle"/"Done"/"Error" or
    // the label of the pass currently running; results accumulate in order as
    // a dynamic list (the greedy sweep decides later passes from earlier ones,
    // so the pass set is not fixed upfront).
    var streamTestState by remember { mutableStateOf("Idle") }
    var streamPassResults by remember { mutableStateOf(listOf<Pair<String, Double?>>()) }
    // N3d-a2: why a row has no number, keyed by row label. Held beside the
    // results rather than inside them so the label-keyed update above keeps
    // working unchanged.
    var streamPassNotes by remember {
        mutableStateOf(mapOf<String, StreamSweepEngine.PassNote>())
    }
    var streamErrorMessage by remember { mutableStateOf<String?>(null) }
    var streamVerdict by remember { mutableStateOf<String?>(null) }
    // N6 V2 follow-on: the host that actually served this TEST's bytes. The
    // tester shares PlayerPlaybackNetworking.playbackHttpClient, so the
    // NET_CONN listener captures the test's own post-redirect host at zero
    // extra network cost; updated after each measured pass (a measured pass
    // proves bytes flowed during this test, so the holder is not a stale
    // playback value). Falls back at display time to the value persisted at
    // the last playback's first frame.
    var liveServingHost by remember { mutableStateOf<String?>(null) }

    val lastStreamUrl = dvDiagnostics.streamUrl
    val lastHeadersJson = dvDiagnostics.headersJson

    val lastHeadersMap = remember(lastHeadersJson) {
        if (!lastHeadersJson.isNullOrBlank()) {
            runCatching {
                val json = org.json.JSONObject(lastHeadersJson)
                val map = mutableMapOf<String, String>()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = json.getString(key)
                }
                map
            }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
    }

    var estimatedBitrate by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(dvDiagnostics) {
        val formatBitrate = dvDiagnostics.videoBitrate.takeIf { it > 0 }?.toLong()
        if (formatBitrate != null) {
            estimatedBitrate = formatBitrate
        } else if (!lastStreamUrl.isNullOrBlank() && dvDiagnostics.durationMs > 0) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val size = com.nuvio.tv.core.network.StreamSpeedTester.getStreamContentLength(lastStreamUrl, lastHeadersMap)
                if (size > 0) {
                    val durationSecs = dvDiagnostics.durationMs / 1000.0
                    if (durationSecs > 0) {
                        estimatedBitrate = ((size * 8.0) / durationSecs).toLong()
                    }
                }
            }
        } else {
            estimatedBitrate = null
        }
    }

    // Stream sweep: the algorithm now lives in core.network.StreamSweepEngine
    // (single source of truth, shared with the Device Assessment). This wrapper
    // only owns the Compose state; pass labels, stop rules, memory gating and
    // verdict strings are produced by the engine and are unchanged.
    fun runStreamDiagnostics() {
        if (lastStreamUrl.isNullOrBlank()) return
        scope.launch {
            streamPassResults = emptyList()
            streamPassNotes = emptyMap()
            streamErrorMessage = null
            streamVerdict = null
            liveServingHost = null

            val outcome = com.nuvio.tv.core.network.StreamSweepEngine.run(
                context = context,
                streamUrl = lastStreamUrl,
                headers = lastHeadersMap,
                estimatedBitrate = estimatedBitrate,
                onState = { streamTestState = it },
                onPassAdded = { label ->
                    streamPassResults = streamPassResults + (label to null)
                },
                onPassResult = { label, mbps, note ->
                    streamPassResults = streamPassResults.map { if (it.first == label) label to mbps else it }
                    if (note != null) streamPassNotes = streamPassNotes + (label to note)
                    if (mbps != null && mbps > 0.0) {
                        liveServingHost =
                            com.nuvio.tv.ui.screens.player.PlaybackConnectionEvents.resolvedHost()
                    }
                }
            )

            streamErrorMessage = outcome.errorText
            streamVerdict = outcome.verdictText
            streamTestState = if (outcome.errorText != null) "Error" else "Done"
        }
    }

    fun runSpeedTest() {
        scope.launch {
            connectionType = getConnectionType(context)
            testState = NetworkTestState.TestingLatency
            latencyMs = null
            downloadMbps = null
            errorMessage = null

            try {
                // ── Latency: average 3 round-trips to Cloudflare ─────────────
                var totalMs = 0L
                withContext(Dispatchers.IO) {
                    repeat(3) {
                        val conn = URL("https://cloudflare.com/cdn-cgi/trace")
                            .openConnection() as HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.connectTimeout = 5_000
                        conn.readTimeout = 5_000
                        val t0 = System.currentTimeMillis()
                        conn.connect()
                        conn.inputStream.use { it.read() }
                        totalMs += System.currentTimeMillis() - t0
                        conn.disconnect()
                    }
                }
                latencyMs = totalMs / 3

                // ── Download: parallel streams from fast.com for 10 s ────────
                testState = NetworkTestState.TestingDownload
                val (totalBytes, elapsed) = withContext(Dispatchers.IO) {
                    val urls = fetchFastComUrls(context)
                    val deadline = System.currentTimeMillis() + 10_000L
                    val startTime = System.currentTimeMillis()

                    // Open 4 connections per URL → 60 parallel streams total
                    val streams = urls.flatMap { url -> List(4) { url } }
                    coroutineScope {
                        val jobs = streams.map { url ->
                            async {
                                var bytes = 0L
                                val buf = ByteArray(65536)
                                try {
                                    val conn = URL(url).openConnection() as HttpURLConnection
                                    conn.connectTimeout = 5_000
                                    conn.readTimeout = 15_000
                                    conn.connect()
                                    conn.inputStream.use { stream ->
                                        var read: Int = 0
                                        while (System.currentTimeMillis() < deadline &&
                                            stream.read(buf).also { read = it } != -1
                                        ) {
                                            bytes += read
                                        }
                                    }
                                    conn.disconnect()
                                } catch (_: Exception) {}
                                bytes
                            }
                        }
                        val total = jobs.awaitAll().sum()
                        Pair(total, System.currentTimeMillis() - startTime)
                    }
                }
                downloadMbps = if (elapsed > 0) (totalBytes * 8.0) / (elapsed * 1000.0) else 0.0
                testState = NetworkTestState.Done

            } catch (e: Exception) {
                errorMessage = e.localizedMessage ?: unknownError
                testState = NetworkTestState.Error
            }
        }
    }

    // Device assessment state lives at screen level so scrolling result
    // cards out of composition can't destroy them (lazy items are disposed;
    // the screen composable is not), and a mid-run scroll can't cancel the
    // sweep (the run launches on this screen's scope).
    val assessmentState = rememberDeviceAssessmentState()

    val networkListState = rememberLazyListState()

    // The same treatment for the device-assessment results card. It grows the
    // same way and overflows the same way - it just never had the effect,
    // because deviceAssessmentItems contributes item() blocks to THIS
    // screen's LazyColumn and has no list state of its own to scroll. Keyed
    // on the row count and on the note count, so a row that resolves to
    // "Rate-limited" or "Skipped" (no number, so it never counts as
    // completed) still triggers a re-measure.
    LaunchedEffect(
        assessmentState.passRows.size,
        assessmentState.passRows.count { it.second != null },
        assessmentState.passNotes.size,
        assessmentState.running
    ) {
        if (assessmentState.passRows.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        val card = networkListState.layoutInfo.visibleItemsInfo
            .find { it.key == "assessment_passes" } ?: return@LaunchedEffect
        val overflow = (card.offset + card.size) - networkListState.layoutInfo.viewportEndOffset
        if (overflow > 0) {
            networkListState.animateScrollBy(overflow.toFloat() + 16f)
        }
    }

    // Keep the growing stream-test results card in view: each completed pass adds
    // a row and the card can extend past the bottom of the screen. When that
    // happens, scroll by exactly the overflow so the newest rows stay visible.
    // If the card is not on screen (the user scrolled elsewhere), do nothing.
    // streamPassNotes.size is part of the key for the same reason it is on the
    // assessment effect: a row that resolves to a note never gains a number,
    // so it never changes streamCompletedPasses, and without this the card
    // would not re-measure after exactly the rows this workstream added.
    val streamCompletedPasses = streamPassResults.count { it.second != null }
    LaunchedEffect(
        streamPassResults.size, streamCompletedPasses, streamPassNotes.size, streamVerdict
    ) {
        if (streamPassResults.isEmpty()) return@LaunchedEffect
        // Let the newly added row be measured before reading layout info.
        withFrameNanos { }
        withFrameNanos { }
        val resultsItem = networkListState.layoutInfo.visibleItemsInfo
            .find { it.key == "stream_speed_results" } ?: return@LaunchedEffect
        val overflow = (resultsItem.offset + resultsItem.size) -
            networkListState.layoutInfo.viewportEndOffset
        if (overflow > 0) {
            networkListState.animateScrollBy(overflow.toFloat() + 16f)
        }
    }
    var showExperienceModeConfirmation by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = networkListState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SettingsDetailHeader(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.settings_advanced),
                    subtitle = stringResource(R.string.settings_advanced_subtitle)
                )
                AnimatedVisibility(
                    visible = testState != NetworkTestState.Idle,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    ConnectionStatusBadge(type = connectionType)
                }
            }
        }

        item(key = "experience_mode_settings") {
            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.experience_mode_group_title)
            ) {
                SettingsActionRow(
                    title = stringResource(R.string.experience_mode_switch_to_essential),
                    subtitle = stringResource(R.string.experience_mode_switch_to_essential_subtitle),
                    value = stringResource(R.string.experience_mode_advanced),
                    onClick = { showExperienceModeConfirmation = true },
                    modifier = if (initialFocusRequester != null) {
                        Modifier.focusRequester(initialFocusRequester)
                    } else {
                        Modifier
                    }
                )
            }
        }

        item(key = "performance_header") {
            Text(
                text = stringResource(R.string.advanced_section_performance),
                style = MaterialTheme.typography.titleSmall,
                color = NuvioTheme.colors.TextTertiary,
                modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
            )
        }

        item(key = "performance_settings") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                SettingsToggleRow(
                    title = stringResource(R.string.advanced_fast_horizontal_navigation),
                    subtitle = stringResource(R.string.advanced_fast_horizontal_navigation_subtitle),
                    checked = uiState.fastHorizontalNavigationEnabled,
                    onToggle = {
                        viewModel.onEvent(
                            AdvancedSettingsEvent.SetFastHorizontalNavigationEnabled(
                                !uiState.fastHorizontalNavigationEnabled
                            )
                        )
                    }
                )
                SettingsToggleRow(
                    title = stringResource(R.string.advanced_nuvio_focus_scroll),
                    subtitle = stringResource(R.string.advanced_nuvio_focus_scroll_subtitle),
                    checked = uiState.smoothBringIntoViewEnabled,
                    onToggle = {
                        viewModel.onEvent(
                            AdvancedSettingsEvent.SetSmoothBringIntoViewEnabled(
                                !uiState.smoothBringIntoViewEnabled
                            )
                        )
                    }
                )
                SettingsToggleRow(
                    title = "Add-on health indicators",
                    subtitle = "Show OK/Slow/Down status on add-ons and resolvers. Passive - no background scanning.",
                    checked = uiState.addonHealthEnabled,
                    onToggle = {
                        viewModel.onEvent(
                            AdvancedSettingsEvent.SetAddonHealthEnabled(
                                !uiState.addonHealthEnabled
                            )
                        )
                    }
                )
                val profileManager = remember {
                    dagger.hilt.android.EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        ProfileManagerEntryPoint::class.java
                    ).profileManager()
                }
                val rememberLastProfileEnabled by profileManager.rememberLastProfileEnabled.collectAsState()
                SettingsToggleRow(
                    title = stringResource(R.string.advanced_remember_last_profile),
                    subtitle = stringResource(R.string.advanced_remember_last_profile_subtitle),
                    checked = rememberLastProfileEnabled,
                    onToggle = {
                        scope.launch {
                            profileManager.setRememberLastProfileEnabled(!rememberLastProfileEnabled)
                        }
                    }
                )

                val confirmExitEnabled by profileManager.confirmExitEnabled.collectAsState()
                SettingsToggleRow(
                    title = stringResource(R.string.advanced_confirm_exit),
                    subtitle = stringResource(R.string.advanced_confirm_exit_subtitle),
                    checked = confirmExitEnabled,
                    onToggle = {
                        scope.launch {
                            profileManager.setConfirmExitEnabled(!confirmExitEnabled)
                        }
                    }
                )
            }
        }

        item(key = "diagnostics_header") {
            Text(
                text = stringResource(R.string.advanced_section_diagnostics),
                style = MaterialTheme.typography.titleSmall,
                color = NuvioTheme.colors.TextTertiary,
                modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
            )
        }

        item(key = "playback_issue_reports") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                SettingsToggleRow(
                    title = stringResource(R.string.advanced_playback_issue_reports),
                    subtitle = stringResource(R.string.advanced_playback_issue_reports_subtitle),
                    checked = uiState.playbackIssueReportsEnabled,
                    onToggle = {
                        viewModel.onEvent(
                            AdvancedSettingsEvent.SetPlaybackIssueReportsEnabled(
                                !uiState.playbackIssueReportsEnabled
                            )
                        )
                    }
                )
                // Fork: upstream's stats-HUD toggle is not offered; the fork's own HUD ('i') is the stats surface.
            }
        }

        item(key = "speed_test") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                val isRunning = testState == NetworkTestState.TestingLatency ||
                        testState == NetworkTestState.TestingDownload
                SettingsActionRow(
                    title = stringResource(
                        if (isRunning) R.string.network_speed_test_running
                        else R.string.network_speed_test_run
                    ),
                    subtitle = stringResource(R.string.network_speed_test_subtitle),
                    value = if (isRunning) stringResource(
                        when (testState) {
                            NetworkTestState.TestingLatency -> R.string.network_testing_latency
                            else -> R.string.network_testing_download
                        }
                    ) else null,
                    onClick = { if (!isRunning) runSpeedTest() }
                )
            }
        }

        if (testState != NetworkTestState.Idle) {
            item(key = "speed_results") {
                SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(NuvioTheme.spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
                    ) {
                        Text(
                            text = stringResource(R.string.network_results_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = NuvioTheme.colors.TextSecondary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)
                        ) {
                            NetworkMetricCard(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.Timer,
                                label = stringResource(R.string.network_latency_label),
                                value = latencyMs?.let { "$it ms" },
                                loading = testState == NetworkTestState.TestingLatency
                            )
                            NetworkMetricCard(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.Speed,
                                label = stringResource(R.string.network_download_label),
                                value = downloadMbps?.let { "%.1f Mbps".format(it) },
                                loading = testState == NetworkTestState.TestingDownload
                            )
                        }

                        if (testState == NetworkTestState.Error && errorMessage != null) {
                            Text(
                                text = stringResource(R.string.network_error_prefix, errorMessage!!),
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.Error
                            )
                        }

                        Text(
                            text = stringResource(R.string.network_powered_by_fast),
                            style = MaterialTheme.typography.labelSmall,
                            color = NuvioTheme.colors.TextSecondary.copy(alpha = 0.45f)
                        )
                    }
                }
            }
        }

        item(key = "stream_speed_test") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                val isStreamRunning = streamTestState != "Idle" && streamTestState != "Done" && streamTestState != "Error"
                val hasStream = !lastStreamUrl.isNullOrBlank()
                SettingsActionRow(
                    title = stringResource(
                        if (isStreamRunning) R.string.stream_test_btn_running
                        else R.string.stream_test_card_title
                    ),
                    subtitle = if (hasStream) null else stringResource(R.string.stream_test_no_stream),
                    subtitleContent = if (hasStream) {
                        { focused, contentAlpha ->
                            val lineStyle = MaterialTheme.typography.bodySmall
                            val lineColor = NuvioTheme.colors.TextSecondary.copy(alpha = contentAlpha)
                            Text(
                                text = stringResource(R.string.stream_test_server_label, lastStreamUrl.let { android.net.Uri.parse(it).host } ?: stringResource(R.string.stream_quality_unknown)),
                                style = lineStyle,
                                color = lineColor
                            )
                            // N6 V2 follow-on: the host that actually served the
                            // bytes. Prefers the live value captured during this
                            // test run, falling back to the host persisted at the
                            // last playback's first frame. Suppressed when nothing
                            // was captured or the redirect resolved to the same
                            // host as the Server line above.
                            run {
                                val redirectorHost = lastStreamUrl?.let { android.net.Uri.parse(it).host }
                                (liveServingHost ?: dvDiagnostics.resolvedServingHost)
                                    ?.takeIf { !it.equals(redirectorHost, ignoreCase = true) }
                                    ?.let { servingHost ->
                                        Text(
                                            text = stringResource(R.string.stream_test_serving_host_label, servingHost),
                                            style = lineStyle,
                                            color = lineColor
                                        )
                                    }
                            }
                            dvDiagnostics.filename?.let { name ->
                                // Long remux filenames marquee-scroll while the card is
                                // focused instead of ellipsising, at Compose's default 30.dp/s
                                // (slower than the app-wide 45) so dense release names stay
                                // readable.
                                FocusMarqueeText(
                                    text = stringResource(R.string.stream_test_file_label, name),
                                    focused = focused,
                                    style = lineStyle,
                                    color = lineColor,
                                    velocity = 30.dp
                                )
                            }
                            if (isStreamRunning) {
                                Text(
                                    text = stringResource(R.string.stream_test_btn_measuring_dyn, streamTestState),
                                    style = lineStyle,
                                    color = lineColor
                                )
                            }
                        }
                    } else null,
                    enabled = hasStream && !isStreamRunning,
                    onClick = { if (hasStream && !isStreamRunning) runStreamDiagnostics() }
                )
            }
        }

        if (streamTestState != "Idle") {
            item(key = "stream_speed_results") {
                SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(NuvioTheme.spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.stream_test_section_header),
                                style = MaterialTheme.typography.titleSmall,
                                color = NuvioTheme.colors.TextSecondary
                            )

                            val bitrateMbps = estimatedBitrate?.takeIf { it > 0 }?.let { it.toDouble() / 1_000_000.0 }
                            if (bitrateMbps != null) {
                                Text(
                                    text = stringResource(R.string.stream_test_video_bitrate, "%.1f Mbps".format(bitrateMbps)),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = NuvioTheme.colors.TextPrimary
                                )
                            }
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
                        ) {
                            streamPassResults.forEach { (label, speed) ->
                                StreamTestResultRow(
                                    label = label,
                                    speed = speed,
                                    note = streamPassNotes[label],
                                    isRunning = streamTestState == label && speed == null &&
                                        streamPassNotes[label] == null
                                )
                            }

                            if (streamTestState == "Error" && streamErrorMessage != null) {
                                Text(
                                    text = stringResource(R.string.stream_test_error_prefix, streamErrorMessage!!),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NuvioTheme.colors.Error
                                )
                            }

                            streamVerdict?.let { verdict ->
                                Text(
                                    text = verdict,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = NuvioTheme.colors.TextPrimary
                                )
                            }

                            if (streamTestState == "Done") {
                                Text(
                                    text = stringResource(R.string.stream_test_caveat),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NuvioTheme.colors.TextSecondary.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "assessment_header") {
            Text(
                text = stringResource(R.string.assessment_section_header),
                style = MaterialTheme.typography.titleSmall,
                color = NuvioTheme.colors.TextTertiary,
                modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
            )
        }

        deviceAssessmentItems(
            state = assessmentState,
            diagnostics = dvDiagnostics,
            onRun = {
                runDeviceAssessment(
                    scope = scope,
                    context = context,
                    state = assessmentState,
                    settings = dvPlayerSettings,
                    diagnostics = dvDiagnostics
                )
            },
            onApply = {
                runApplyAssessment(scope = scope, context = context, state = assessmentState)
            },
            onRevert = {
                runRevertAssessment(scope = scope, context = context, state = assessmentState)
            }
        )

        item(key = "cache_header") {
            Text(
                text = stringResource(R.string.advanced_section_cache),
                style = MaterialTheme.typography.titleSmall,
                color = NuvioTheme.colors.TextTertiary,
                modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
            )
        }

        item(key = "clear_cw_cache") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                var cleared by remember { mutableStateOf(false) }
                SettingsActionRow(
                    title = stringResource(R.string.advanced_clear_cw_cache),
                    subtitle = if (cleared) {
                        stringResource(R.string.advanced_clear_cw_cache_done)
                    } else {
                        stringResource(R.string.advanced_clear_cw_cache_subtitle)
                    },
                    onClick = {
                        if (!cleared) {
                            scope.launch {
                                val entryPoint = dagger.hilt.android.EntryPointAccessors
                                    .fromApplication(
                                        context.applicationContext,
                                        ClearCwCacheEntryPoint::class.java
                                    )
                                entryPoint.cwEnrichmentCache().clearAll()
                                cleared = true
                            }
                        }
                    }
                )
            }
        }

        item(key = "clear_image_cache") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                // 0 = idle, 1 = clearing, 2 = done
                var clearState by remember { mutableStateOf(0) }
                SettingsActionRow(
                    title = stringResource(R.string.advanced_clear_image_cache),
                    subtitle = when (clearState) {
                        1 -> stringResource(R.string.advanced_clear_image_cache_working)
                        2 -> stringResource(R.string.advanced_clear_image_cache_done)
                        else -> stringResource(R.string.advanced_clear_image_cache_subtitle)
                    },
                    onClick = {
                        if (clearState == 0) {
                            clearState = 1
                            scope.launch {
                                val entryPoint = dagger.hilt.android.EntryPointAccessors
                                    .fromApplication(
                                        context.applicationContext,
                                        ClearImageCacheEntryPoint::class.java
                                    )
                                withContext(Dispatchers.IO) {
                                    // Coil 3.3.0: DiskCache.clear() and
                                    // MemoryCache.clear() both exist; the
                                    // memory cache is lock-guarded so an IO
                                    // thread call is safe.
                                    val loader = coil3.SingletonImageLoader
                                        .get(context.applicationContext)
                                    runCatching { loader.memoryCache?.clear() }
                                    runCatching { loader.diskCache?.clear() }
                                    runCatching { entryPoint.okHttpClient().cache?.evictAll() }
                                    runCatching { entryPoint.validatedOkHttpClient().cache?.evictAll() }
                                    runCatching { entryPoint.catalogRepository().clearCaches() }
                                }
                                entryPoint.homeRefreshSignal().requestRefresh()
                                clearState = 2
                            }
                        }
                    }
                )
            }
        }

        if (dvPlayerSettings.internalPlayerEngine == InternalPlayerEngine.EXOPLAYER ||
            dvPlayerSettings.internalPlayerEngine == InternalPlayerEngine.AUTO) {
            item(key = "dv_diagnostics_header") {
                Text(
                    text = stringResource(R.string.advanced_section_dv_diagnostics),
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
                )
            }

            item(key = "dv_conversion_mode") {
                val overrideEnabled = dvPlayerSettings.dv7HandlingMode == Dv7HandlingMode.DV81_LIBDOVI
                var showModeDialog by remember { mutableStateOf(false) }
                val modeOptions = listOf(
                    SettingsPickerOption(
                        -1,
                        stringResource(R.string.dv7_libdovi_mode_none_title),
                        stringResource(R.string.dv7_libdovi_mode_none_sub)
                    ),
                    SettingsPickerOption(0, stringResource(R.string.dv7_libdovi_mode_0_title), stringResource(R.string.dv7_libdovi_mode_0_sub)),
                    SettingsPickerOption(1, stringResource(R.string.dv7_libdovi_mode_1_title), stringResource(R.string.dv7_libdovi_mode_1_sub)),
                    SettingsPickerOption(2, stringResource(R.string.dv7_libdovi_mode_2_title), stringResource(R.string.dv7_libdovi_mode_2_sub)),
                    SettingsPickerOption(3, stringResource(R.string.dv7_libdovi_mode_3_title), stringResource(R.string.dv7_libdovi_mode_3_sub)),
                    SettingsPickerOption(4, stringResource(R.string.dv7_libdovi_mode_4_title), stringResource(R.string.dv7_libdovi_mode_4_sub))
                )
                // Show None whenever the row is disabled so a stale stored override
                // never displays as a selected mode.
                val effectiveOverride = if (overrideEnabled) dvPlayerSettings.dv7LibdoviModeOverride else -1
                val currentLabel = modeOptions.firstOrNull { it.value == effectiveOverride }?.title
                    ?: modeOptions.first().title
                SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                    SettingsActionRow(
                        title = stringResource(R.string.dv7_libdovi_mode_row_title),
                        subtitle = stringResource(R.string.dv7_libdovi_mode_caption),
                        value = currentLabel,
                        onClick = { showModeDialog = true },
                        enabled = overrideEnabled
                    )
                }
                if (showModeDialog) {
                    SettingsSingleChoiceDialog(
                        title = stringResource(R.string.dv7_libdovi_mode_row_title),
                        subtitle = stringResource(R.string.dv7_libdovi_mode_caption),
                        options = modeOptions,
                        selectedValue = effectiveOverride,
                        onOptionSelected = { value ->
                            scope.launch { playbackVm.setDv7LibdoviModeOverride(value) }
                            showModeDialog = false
                        },
                        onDismiss = { showModeDialog = false },
                        width = 460.dp,
                        maxHeight = 360.dp
                    )
                }
            }

            diagnosticsCardItems(
                diagnostics = dvDiagnostics,
                dvCurrentlyEnabled = dvPlayerSettings.dv7HandlingMode != Dv7HandlingMode.OFF
            )
        }
    }
        SettingsVerticalScrollIndicators(state = networkListState)
    }

    if (showExperienceModeConfirmation) {
        ExperienceModeConfirmationDialog(
            targetMode = ExperienceMode.ESSENTIAL,
            onConfirm = { experienceModeViewModel.setMode(ExperienceMode.ESSENTIAL) },
            onDismiss = { showExperienceModeConfirmation = false }
        )
    }
}

@Composable
private fun NetworkMetricCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String?,
    loading: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Column(
        modifier = modifier.padding(NuvioTheme.spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        Icon(
            imageVector = if (loading) Icons.Default.Refresh else icon,
            contentDescription = null,
            modifier = Modifier
                .size(28.dp)
                .then(if (loading) Modifier.rotate(rotation) else Modifier),
            tint = if (loading) Color.White else Color.White
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary
        )
        Text(
            text = when {
                loading -> "..."
                value != null -> value
                else -> "–"
            },
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = if (value != null && !loading) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextTertiary
        )
    }
}

@Composable
private fun StreamTestResultRow(
    label: String,
    speed: Double?,
    note: StreamSweepEngine.PassNote? = null,
    isRunning: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            note?.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary
                )
            }
        }
        Text(
            text = when {
                isRunning -> stringResource(R.string.stream_test_btn_running)
                speed != null -> "%.1f Mbps".format(speed)
                note != null -> note.state
                else -> "---"
            },
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = if (speed != null && !isRunning) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextTertiary
        )
    }
}
