package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.DolbyVisionConversionStats
import com.nuvio.tv.core.player.DoviBridge
import android.content.Context
import android.os.Debug
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.MimeTypes
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.debrid.DirectDebridStreamPreparer
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackSessionStore
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackProgressStore
import com.nuvio.tv.core.cloud.CloudLibraryRepository
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.player.StreamAutoPlayPolicy
import com.nuvio.tv.core.tracking.TrackingScrobbleCoordinator
import com.nuvio.tv.core.torrent.TorrentService
import com.nuvio.tv.core.torrent.TorrentSettings
import com.nuvio.tv.data.local.AudioDelayRouteDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.DeviceLocalPlayerPreferences
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.local.StreamLinkCacheDataStore
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.data.repository.ParentalGuideRepository
import com.nuvio.tv.data.repository.MDBListRepository
import com.nuvio.tv.data.repository.SkipIntroRepository
import com.nuvio.tv.data.repository.TraktEpisodeMappingService
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.data.repository.TraktRelatedService
import com.nuvio.tv.data.trailer.TrailerService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watchProgressRepository: WatchProgressRepository,
    private val metaRepository: MetaRepository,
    private val streamRepository: StreamRepository,
    private val addonRepository: AddonRepository,
    private val pluginManager: PluginManager,
    private val subtitleRepository: com.nuvio.tv.domain.repository.SubtitleRepository,
    private val parentalGuideRepository: ParentalGuideRepository,
    private val trackingScrobbleCoordinator: TrackingScrobbleCoordinator,
    private val traktEpisodeMappingService: TraktEpisodeMappingService,
    private val skipIntroRepository: SkipIntroRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val deviceLocalPlayerPreferences: DeviceLocalPlayerPreferences,
    private val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    private val streamBadgeSettingsDataStore: StreamBadgeSettingsDataStore,
    private val bingeGroupCacheDataStore: com.nuvio.tv.data.local.BingeGroupCacheDataStore,
    private val layoutPreferenceDataStore: com.nuvio.tv.data.local.LayoutPreferenceDataStore,
    private val episodeShufflePlayback: com.nuvio.tv.core.player.EpisodeShufflePlayback,
    private val watchedItemsPreferences: com.nuvio.tv.data.local.WatchedItemsPreferences,
    private val watchedSeriesStateHolder: WatchedSeriesStateHolder,
    private val trackPreferenceDataStore: com.nuvio.tv.data.local.TrackPreferenceDataStore,
    private val audioDelayRouteDataStore: AudioDelayRouteDataStore,
    private val torrentService: TorrentService,
    private val torrentSettings: TorrentSettings,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val mdbListRepository: MDBListRepository,
    private val mdbListSettingsDataStore: MDBListSettingsDataStore,
    private val trailerPlayerPool: com.nuvio.tv.core.player.TrailerPlayerPool,
    private val trailerService: TrailerService,
    private val trailerSettingsDataStore: TrailerSettingsDataStore,
    private val traktRelatedService: TraktRelatedService,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val simklRelatedService: com.nuvio.tv.data.simkl.SimklRelatedService,
    private val simklAuthRepository: com.nuvio.tv.data.simkl.SimklAuthRepository,
    private val directDebridResolver: DirectDebridResolver,
    private val youTubeStreamResolver: com.nuvio.tv.core.streams.YouTubeStreamResolver,
    private val directDebridStreamPreparer: DirectDebridStreamPreparer,
    private val cloudLibraryRepository: CloudLibraryRepository,
    private val cloudPlaybackProgressStore: CloudLibraryPlaybackProgressStore,
    private val cloudPlaybackSessionStore: CloudLibraryPlaybackSessionStore,
    private val streamBadgePresentation: com.nuvio.tv.core.streams.StreamBadgePresentation,
    private val debridSettingsDataStore: com.nuvio.tv.data.local.DebridSettingsDataStore,
    private val playbackIssueReportRepository: com.nuvio.tv.data.repository.PlaybackIssueReportRepository,
    private val externalPlaybackTracker: com.nuvio.tv.core.player.ExternalPlaybackTracker,
    private val subtitleFileCache: com.nuvio.tv.core.player.SubtitleFileCache,
    private val prefetchSelectionSupplier: com.nuvio.tv.core.stream.PrefetchSelectionSupplier,
    private val screensaverController: com.nuvio.tv.core.player.ScreensaverController,
    private val tvRecommendationManager: com.nuvio.tv.core.recommendations.TvRecommendationManager,
    private val serverPlayback: com.nuvio.tv.data.mediaserver.ServerPlayback,
    private val serverStreams: com.nuvio.tv.data.mediaserver.ServerStreams,
    profileManager: com.nuvio.tv.core.profile.ProfileManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val diagnosticPlaybackLease = com.nuvio.tv.core.network.DiagnosticPlaybackGuard.shared.enterPlayback()

    init {
        // Release trailer player codec resources so the full-screen player can
        // claim hardware decoders without contention (prevents black screen).
        trailerPlayerPool.yield()
    }

    internal val controller = PlayerRuntimeController(
        context = context,
        watchProgressRepository = watchProgressRepository,
        metaRepository = metaRepository,
        streamRepository = streamRepository,
        prefetchSelectionSupplier = prefetchSelectionSupplier,
        addonRepository = addonRepository,
        pluginManager = pluginManager,
        subtitleRepository = subtitleRepository,
        parentalGuideRepository = parentalGuideRepository,
        trackingScrobbleCoordinator = trackingScrobbleCoordinator,
        traktEpisodeMappingService = traktEpisodeMappingService,
        skipIntroRepository = skipIntroRepository,
        playerSettingsDataStore = playerSettingsDataStore,
        deviceLocalPlayerPreferences = deviceLocalPlayerPreferences,
        streamLinkCacheDataStore = streamLinkCacheDataStore,
        streamBadgeSettingsDataStore = streamBadgeSettingsDataStore,
        bingeGroupCacheDataStore = bingeGroupCacheDataStore,
        layoutPreferenceDataStore = layoutPreferenceDataStore,
        watchedItemsPreferences = watchedItemsPreferences,
        episodeShufflePlayback = episodeShufflePlayback,
        trackPreferenceDataStore = trackPreferenceDataStore,
        audioDelayRouteDataStore = audioDelayRouteDataStore,
        torrentService = torrentService,
        torrentSettings = torrentSettings,
        tmdbService = tmdbService,
        tmdbMetadataService = tmdbMetadataService,
        tmdbSettingsDataStore = tmdbSettingsDataStore,
        directDebridResolver = directDebridResolver,
        youTubeStreamResolver = youTubeStreamResolver,
        directDebridStreamPreparer = directDebridStreamPreparer,
        cloudLibraryRepository = cloudLibraryRepository,
        cloudPlaybackProgressStore = cloudPlaybackProgressStore,
        cloudPlaybackSessionStore = cloudPlaybackSessionStore,
        streamBadgePresentation = streamBadgePresentation,
        debridSettingsDataStore = debridSettingsDataStore,
        playbackIssueReportRepository = playbackIssueReportRepository,
        tvRecommendationManager = tvRecommendationManager,
        serverPlayback = serverPlayback,
        serverStreams = serverStreams,
        profileId = savedStateHandle.get<String>("profileId")?.toIntOrNull()
            ?: profileManager.activeProfileId.value,
        savedStateHandle = savedStateHandle,
        scope = viewModelScope
    )

    private val postPlayRecommendationController = PostPlayRecommendationController(
        playbackController = controller,
        profileManager = profileManager,
        playerSettingsDataStore = playerSettingsDataStore,
        metaRepository = metaRepository,
        tmdbService = tmdbService,
        tmdbMetadataService = tmdbMetadataService,
        tmdbSettingsDataStore = tmdbSettingsDataStore,
        mdbListRepository = mdbListRepository,
        mdbListSettingsDataStore = mdbListSettingsDataStore,
        traktRelatedService = traktRelatedService,
        traktAuthDataStore = traktAuthDataStore,
        traktSettingsDataStore = traktSettingsDataStore,
        simklRelatedService = simklRelatedService,
        simklAuthRepository = simklAuthRepository,
        layoutPreferenceDataStore = layoutPreferenceDataStore,
        watchProgressRepository = watchProgressRepository,
        watchedSeriesStateHolder = watchedSeriesStateHolder,
        trailerService = trailerService,
        trailerSettingsDataStore = trailerSettingsDataStore,
        trailerPlayerPool = trailerPlayerPool,
        scope = viewModelScope
    )

    val uiState: StateFlow<PlayerUiState>
        get() = controller.uiState

    init {
        // OLED screensaver: mirror playback-active (playing or buffering) so the idle
        // dimmer never engages during playback, restarts its clock on pause, and
        // auto-wakes on resume (including MediaSession resumes).
        viewModelScope.launch {
            controller.uiState
                .map { it.isPlaying || it.isBuffering }
                .distinctUntilChanged()
                .collect { active -> screensaverController.setPlaybackActive(active) }
        }
    }

    val playbackTimeline: StateFlow<PlaybackTimelineState>
        get() = controller.playbackTimeline

    val postPlayRecommendationUiState: StateFlow<PostPlayRecommendationUiState>
        get() = postPlayRecommendationController.uiState

    val effectiveAutoplayEnabled = playerSettingsDataStore.playerSettings
        .map(StreamAutoPlayPolicy::isEffectivelyEnabled)
        .distinctUntilChanged()

    val exoPlayer: ExoPlayer?
        get() = controller.exoPlayer

    /** Called only on the player's application thread, by the two HUD samplers. */
    internal fun sampleVideoPresentation(): PlaybackVideoPresentation {
        val player = controller.exoPlayer ?: return PlaybackVideoPresentation.UNCONFIGURED
        return PlaybackVideoPresentation.resolve(
            hasSelectedVideo = player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO),
            configured = player.playbackState != Player.STATE_IDLE && player.videoFormat != null,
            appliedTunneling = player.isTunnelingEnabled,
            decoderName = controller.playbackAnalyticsDiagnostics.hudSample().videoDecoderName,
            hasCounters = player.videoDecoderCounters != null
        )
    }

    private val droppedFrameSampler = PlaybackDroppedFrameSampler()

    internal fun sampleDroppedFrames(presentation: PlaybackVideoPresentation = sampleVideoPresentation()): PlaybackDroppedFrameSample =
        droppedFrameSampler.sample(controller.exoPlayer?.videoDecoderCounters,
            presentation.supportsDropCounters, android.os.SystemClock.elapsedRealtime())

    /** Release filename for the current stream, surfaced for the loading overlay. */
    val currentFilename: String?
        get() = controller.currentFilename

    private val playbackStatsCpuSampler = ProcessCpuSampler()
    private val playbackStatsThermalSampler by lazy { PlaybackThermalSampler(context) }
    private val playbackStatsCpuClockSampler = PlaybackCpuClockSampler()

    // Recency state for event-counter dots (dropped frames, underruns, stalls):
    // a dot goes red only when its counter increased within the recency window.
    private var statsLastStreamUrl: String? = null
    private var statsPrevUnderruns: Int = -1
    private var statsUnderrunsIncreasedAtMs: Long = 0L
    private var statsPrevStalls: Int = -1
    private var statsStallsIncreasedAtMs: Long = 0L

    // Measured mux accumulator: bytes and media time are accumulated per tick rather
    // than divided cumulatively, so a seek (an implausible jump in buffered position)
    // starts a fresh epoch instead of poisoning the ratio. Both are reset when the
    // stream changes.
    private var statsPrevTransferredBytes: Long = -1L
    private var statsPrevBufferedMs: Long = -1L
    private var statsMuxBytes: Long = 0L
    private var statsMuxMediaMs: Long = 0L

    // Windowed mux rate ("now"): a short ring of recent (statsMuxBytes, statsMuxMediaMs)
    // running-total snapshots, one per HUD tick (~1 s). The windowed rate is the byte
    // delta over the media delta across the oldest-to-newest span in the ring. This is
    // download-derived mux, directly comparable to the Speed row: when a dense scene's
    // demand outruns the link the two converge, and it can never exceed delivered rate
    // because it is measured from bytes arriving. It is NOT decoder-side video bitrate.
    private val statsMuxWindowBytes = ArrayDeque<Long>()
    private val statsMuxWindowMediaMs = ArrayDeque<Long>()

    private val transferRateSampler = PlaybackTransferRateSampler()
    private var statsLastTransferSession: Long? = null

    internal fun sampleTransferSnapshot(): PlaybackTransferSnapshot = PlaybackByteCounter.snapshot()

    // Recency state for the Rebuffers row (real STATE_BUFFERING count from the
    // controller), mirroring the dropped/underrun/stall recency fields above.
    private var statsPrevRebuffers: Int = -1
    private var statsRebuffersIncreasedAtMs: Long = 0L

    /** Fresh TCP diagnostic with a bounded caller wait and one owned resolver worker. */
    internal suspend fun samplePing(): PlaybackConnectSample? = PlaybackConnectProbe.shared.sample()

    /**
     * One ~1 Hz sample for the live playback stats overlay.
     * Called from a main-thread LaunchedEffect in PlayerScreen while the overlay
     * is visible; player state must be read on the application thread.
     * displayRefreshRateHz comes from the hosting view's display; lastPingMs is
     * the most recent samplePing() result.
     */
    internal fun samplePlaybackStats(
        displayRefreshRateHz: Float?,
        displayRateOptions: Int?,
        displayModeWidth: Int?,
        displayModeHeight: Int?,
        lastPingMs: PlaybackConnectSample?
    ): PlaybackStatsSample {
        val engine = controller.currentInternalPlayerEngine
        val mpvActive = controller.isUsingMpvEngine() ||
            (engine == InternalPlayerEngine.AUTO && controller.mpvView != null)
        if (mpvActive) {
            return PlaybackStatsSample(isExoPlayer = false, engineLabel = "libmpv", sections = emptyList())
        }
        val t = PlaybackStatsThresholds
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val rows = mutableListOf<StatsRow>()
        val player = controller.exoPlayer
        val hud = controller.playbackAnalyticsDiagnostics.hudSample()
        val streamUrl = runCatching { controller.getCurrentStreamUrl() }.getOrNull()

        // Reset recency state when the stream changes.
        if (streamUrl != statsLastStreamUrl || hud.transfer.sessionId != statsLastTransferSession) {
            statsLastTransferSession = hud.transfer.sessionId
            statsLastStreamUrl = streamUrl
            statsPrevUnderruns = -1
            statsUnderrunsIncreasedAtMs = 0L
            statsPrevStalls = -1
            statsStallsIncreasedAtMs = 0L
            statsPrevTransferredBytes = -1L
            statsPrevBufferedMs = -1L
            statsMuxBytes = 0L
            statsMuxMediaMs = 0L
            statsMuxWindowBytes.clear()
            statsMuxWindowMediaMs.clear()
            statsPrevRebuffers = -1
            statsRebuffersIncreasedAtMs = 0L
        }
        if (statsPrevUnderruns in 0 until hud.audioUnderrunCount) statsUnderrunsIncreasedAtMs = nowMs
        if (statsPrevStalls in 0 until hud.positionStallCount) statsStallsIncreasedAtMs = nowMs
        statsPrevUnderruns = hud.audioUnderrunCount
        statsPrevStalls = hud.positionStallCount
        val rebuffers = controller.rebufferCount
        if (statsPrevRebuffers in 0 until rebuffers) statsRebuffersIncreasedAtMs = nowMs
        statsPrevRebuffers = rebuffers

        fun recencyDot(total: Int, increasedAtMs: Long): StatsDot = when {
            total <= 0 -> StatsDot.GOOD
            nowMs - increasedAtMs <= t.RECENT_EVENT_WINDOW_MS -> StatsDot.BAD
            else -> StatsDot.WARN
        }

        // Byte accounting. The bandwidth meter cannot see a plain progressive
        // stream's bytes (it only publishes a sample when a transfer ends, and such a
        // load holds one transfer open for the whole file), so the HUD counts them
        // itself (PlaybackByteCounter) and derives both the mux rate and the link
        // throughput here.
        player?.let { p ->
            val bytesNow = hud.transferredBytesTotal
            val bufferedNow = p.bufferedPosition
            if (statsPrevTransferredBytes in 0L..bytesNow && statsPrevBufferedMs >= 0L) {
                val mediaDelta = bufferedNow - statsPrevBufferedMs
                if (mediaDelta in 0L..t.MUX_MAX_MEDIA_DELTA_MS) {
                    statsMuxBytes += bytesNow - statsPrevTransferredBytes
                    statsMuxMediaMs += mediaDelta
                } else {
                    // Seek: the bytes already counted no longer correspond to the
                    // media span ahead of us. Start a new epoch.
                    statsMuxBytes = 0L
                    statsMuxMediaMs = 0L
                    statsMuxWindowBytes.clear()
                    statsMuxWindowMediaMs.clear()
                }
            }
            statsPrevTransferredBytes = bytesNow
            statsPrevBufferedMs = bufferedNow

            // Record this tick's running totals and keep only the last WINDOW ticks.
            statsMuxWindowBytes.addLast(statsMuxBytes)
            statsMuxWindowMediaMs.addLast(statsMuxMediaMs)
            while (statsMuxWindowBytes.size > t.MUX_WINDOW_TICKS) {
                statsMuxWindowBytes.removeFirst()
                statsMuxWindowMediaMs.removeFirst()
            }


        }

        // Server + file (informational). The file name comes from the stream
        // URL's last path segment (the addon's stream label is a marketing
        // string, not the file); fall back to the label when the URL doesn't
        // end in something filename-shaped.
        val host = streamUrl?.let { runCatching { URI(it).host }.getOrNull() }
        // Add-on + provider. Add-on is retained UI state; provider is parsed
        // from the stream's marketing label (debrid store / library / host).
        val statsUi = controller.uiState.value
        statsUi.currentStreamAddonName?.takeIf { it.isNotBlank() }
            ?.let { rows += StatsRow("Add-on", it) }
        (controller.serverProviderLabel() ?: resolveStreamProvider(
            streamName = statsUi.currentStreamName,
            streamDescription = controller.currentStreamDescription,
            addonName = statsUi.currentStreamAddonName,
            host = host
        ))?.let { rows += StatsRow("Provider", it) }
        // Resolved only by this media source's network data sources; background calls cannot set it.
        hud.transfer.endpoint?.let { rows += StatsRow("Server", it.display) }
            ?: host?.let { rows += StatsRow("Source host", "$it · serving endpoint unavailable") }
        val urlFileName = streamUrl
            ?.let { runCatching { URI(it).path }.getOrNull() }
            ?.substringAfterLast('/')
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() ?: it }
            ?.takeIf { it.isNotBlank() && it.contains('.') }
        val scraperFileName = controller.currentFilename?.takeIf { it.isNotBlank() }
        (scraperFileName ?: urlFileName ?: controller.uiState.value.currentStreamName)
            ?.takeIf { it.isNotBlank() }
            ?.let { rows += StatsRow("File", it.replace("\n", " \u00b7 "), marquee = true) }

        // File size, whenever the source declared a content length at open(). It also
        // yields the exact average mux rate (size / duration), which needs no
        // measurement at all: it is immune to seeks, to prefetch, and to any gap in byte
        // accounting, and it is available as soon as the duration is known.
        val fileSizeBytes = hud.transfer.contentLength
        val durationMs = player?.duration?.takeIf { it > 0L }
        val exactMuxBps = if (fileSizeBytes != null && durationMs != null) {
            (fileSizeBytes.toDouble() * 8_000.0 / durationMs).toLong()
        } else {
            null
        }
        fileSizeBytes?.let { rows += StatsRow("Size", formatPlaybackStatsBytes(it)) }

        // Video line, HDR, bitrates.
        var effectiveMuxBps: Long? = null
        player?.videoFormat?.let { format ->
            val size = if (format.width > 0 && format.height > 0) "${format.width}×${format.height}" else null
            val fps = format.frameRate.takeIf { it > 0f }
                ?.let { String.format("%.3f", it).trimEnd('0').trimEnd('.') + " fps" }
            val codec = format.sampleMimeType?.substringAfter('/')
            listOfNotNull(size, fps, codec).joinToString(" · ")
                .takeIf { it.isNotBlank() }?.let { rows += StatsRow("Video", it) }
            // The MKV Colour element is optional, and neither MatroskaExtractor nor the
            // vendored dvmkv copy falls back to the HEVC SPS when it is missing, so a
            // remux muxed without it reports colorInfo == null even for a genuine HDR10
            // stream that the decoder and display take as HDR from the SPS.
            // PlaybackVideoColorTransfer reads the SPS when the container is silent.
            val colorTransfer = PlaybackVideoColorTransfer.of(format)
            val hdr = when {
                format.sampleMimeType?.contains("dolby-vision") == true -> "Dolby Vision"
                colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10 / PQ"
                colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
                else -> "SDR"
            }
            rows += StatsRow("HDR", hdr)

            // Video bitrate, in descending order of authority: a rate the container
            // declares; the file's exact average mux rate (size / duration); the rate
            // measured from bytes accumulated against media time since playback started
            // or the last seek. "avg mux" is the whole container, not video-only.
            val declared = (if (format.bitrate > 0) format.bitrate.toLong() else null)
                ?: (if (format.averageBitrate > 0) format.averageBitrate.toLong() else null)
            val floorBps = if (format.height >= t.MUX_FLOOR_HD_MIN_HEIGHT) {
                t.MUX_IMPLAUSIBLE_FLOOR_BPS
            } else {
                t.MUX_IMPLAUSIBLE_FLOOR_SD_BPS
            }
            val measuredMuxBps = if (hud.transfer.coverage == PlaybackTransferCoverage.PROGRESSIVE && statsMuxMediaMs > t.MUX_MIN_MEDIA_MS) {
                statsMuxBytes * 8_000L / statsMuxMediaMs
            } else {
                null
            }
            // Windowed ("now") mux rate over the ring span, when the ring holds a full
            // window and the media delta across it is large enough to be meaningful.
            val windowMuxBps = if (hud.transfer.coverage == PlaybackTransferCoverage.PROGRESSIVE && statsMuxWindowBytes.size >= t.MUX_WINDOW_TICKS) {
                val byteSpan = statsMuxWindowBytes.last() - statsMuxWindowBytes.first()
                val mediaSpan = statsMuxWindowMediaMs.last() - statsMuxWindowMediaMs.first()
                if (mediaSpan >= t.MUX_WINDOW_MIN_MEDIA_MS && byteSpan >= 0L) {
                    byteSpan * 8_000L / mediaSpan
                } else {
                    null
                }
            } else {
                null
            }
            when {
                declared != null ->
                    rows += StatsRow("V bitrate", String.format("%.1f Mbit/s", declared / 1_000_000.0))

                exactMuxBps != null -> {
                    effectiveMuxBps = exactMuxBps
                    rows += StatsRow(
                        "Mux bitrate",
                        String.format("%.1f Mbit/s average", exactMuxBps / 1_000_000.0)
                    )
                }

                measuredMuxBps == null -> Unit

                measuredMuxBps >= floorBps -> {
                    effectiveMuxBps = measuredMuxBps
                    rows += StatsRow(
                        "Mux estimate",
                        String.format("about %.1f Mbit/s, measured", measuredMuxBps / 1_000_000.0) +
                            (windowMuxBps?.let { String.format(" · now %.1f", it / 1_000_000.0) } ?: "")
                    )
                }

                else ->
                    // Below the floor for this resolution the counter is not seeing the
                    // source's bytes. Say that, rather than print a number that reads as a
                    // broken stream (and leave effectiveMuxBps null so the Speed dot does
                    // not judge against it).
                    rows += StatsRow("Mux estimate", "Unavailable · too little read so far", StatsDot.NONE)
            }
        }

        // DV state (fork-specific). Judged from the actual source profile first: a stream that
        // is already Profile 8 (or 5) needs no conversion. Text only, no health dot, for
        // example Profile 7.6 FEL with RPU + BL + EL (native dual-layer) or Profile 7.6 MEL
        // to 8.1 (libdovi conversion).
        val dvDiag = controller.lastPlaybackDiagnosticsForReport
        dvDiag.dvSourceProfile?.takeIf { it.isNotBlank() }?.let { sourceProfile ->
            // The first-frame diagnostics snapshot can freeze zeros when it races the
            // converter's first RPU; the live per-playback counters (reset at player init)
            // are authoritative for "actively working".
            val converting = DoviBridge.getConversionSuccessCount() > 0L ||
                DolbyVisionConversionStats.getCodecStringRewriteCount() > 0L ||
                dvDiag.dv7DoviSignalRewrites > 0 || dvDiag.dv7DoviSuccess > 0
            val text = PlaybackStatsDv.profileRowText(
                sourceProfile = sourceProfile,
                elType = dvDiag.dvElType,
                modeEffective = dvDiag.dv7ModeEffective,
                sourceCodecs = controller.currentVideoTrackCodecs ?: player?.videoFormat?.codecs,
                converting = converting
            )
            rows += StatsRow("DV", text)
        }

        // DV source mastering metadata (static per stream), read from the
        // RPU. Gated on its own value rather than the DV-conversion line above,
        // so it also appears on strip/HDR10 paths where no source-profile line is
        // emitted. Informational only, no health dot.
        (DolbyVisionConversionStats.getLastRpuMetadata()?.toDiagnosticLine() ?: dvDiag.dvHdrMastering)?.takeIf { it.isNotBlank() }?.let { mastering ->
            rows += StatsRow("DV HDR", mastering, StatsDot.NONE, marquee = mastering.length > 40)
        }

        // RPU display-management rows. The summary is sampled every ~0.5 s of media
        // by the feeding path (native FEL renderer / conversion extractor hooks) and looked up
        // at the playback position; CM version and levels fall back to the first RPU.
        // Only for a DV stream: a late write from the previous title's loader (after the per-playback reset)
        // must not put DV rows on a non-DV title.
        val dvStream = !dvDiag.dvSourceProfile.isNullOrBlank()
        val dmLive = if (dvStream) player?.let { DolbyVisionConversionStats.liveDmAt(it.currentPosition * 1000L) } else null
        val dmFirst = if (dvStream) DolbyVisionConversionStats.getFirstDm() else null
        (dmLive ?: dmFirst)?.let { dm ->
            PlaybackStatsDv.cmText(dm)?.let { rows += StatsRow("DV CM", it) }
            dm.levelsLabel().takeIf { it.isNotEmpty() }?.let { rows += StatsRow("DV levels", it) }
        }
        dmLive?.let { dm ->
            PlaybackStatsDv.l1Text(dm)?.let { rows += StatsRow("DV L1", it) }
            PlaybackStatsDv.l5Text(dm, player?.videoFormat?.width ?: 0, player?.videoFormat?.height ?: 0)
                ?.let { rows += StatsRow("DV L5", it) }
        }
        player?.videoFormat?.let { format ->
            PlaybackStatsDv.formatRowText(
                format = format,
                dm = dmFirst,
                nativeFel = dvDiag.dv7ModeEffective == "NATIVE_FEL"
            )?.let { rows += StatsRow("Format", it, marquee = it.length > 46) }
        }

        hud.videoDecoderName?.let { rows += StatsRow("Decoder", it) }

        val videoPresentation = sampleVideoPresentation()
        val tunnelled = videoPresentation.isTunnelled
        val dropped = sampleDroppedFrames(videoPresentation)
        val dropCount = dropped.count
        val droppedText = when {
            videoPresentation == PlaybackVideoPresentation.NATIVE -> "Not counted on the native route"
            dropCount == null -> "Unavailable"
            tunnelled -> "$dropCount · renderer source only (tunnelled)"
            else -> "$dropCount"
        }
        val droppedDot = when {
            dropCount == null || (tunnelled && dropCount == 0) -> StatsDot.NONE
            dropCount == 0 -> StatsDot.GOOD
            dropped.increasedAtMs?.let { nowMs - it <= t.RECENT_EVENT_WINDOW_MS } == true -> StatsDot.BAD
            else -> StatsDot.WARN
        }
        rows += StatsRow("Dropped", droppedText, droppedDot)
        if (videoPresentation.supportsFrameLead) {
            // Read only the current decoder's samples. Historical analytics averages can
            // belong to a released renderer and must not be used as a live fallback.
            player?.videoDecoderCounters?.let { counters ->
                counters.ensureUpdated()
                if (counters.videoFrameProcessingOffsetCount > 0) {
                    val avgUs = counters.totalVideoFrameProcessingOffsetUs / counters.videoFrameProcessingOffsetCount
                    rows += StatsRow("Frame lead", String.format("%+.1f ms", avgUs / 1_000.0),
                        if (avgUs >= 0L) StatsDot.GOOD else StatsDot.BAD)
                }
            }
        }

        // Refresh rate vs content fps: an AFR-worked check, but only where the app is in
        // a position to know. Two cases where it is not, and where a red dot would be an
        // alarm about something the app can neither see nor fix:
        //
        //  - Tunnelled: the hardware presents the frames. What Android reports as the
        //    display rate says nothing about what the panel is doing with the content.
        //  - One mode at this resolution: preferredDisplayModeId has nothing to switch to,
        //    so no app-side mechanism can change the rate. On a TV the logical display is
        //    often a fixed 60 Hz UI plane while video runs on a separate plane at its own
        //    rate, which Android never reports. The number is honest; the judgement is not
        //    ours to make.
        displayRefreshRateHz?.takeIf { it > 0f }?.let { hz ->
            val singleMode = displayRateOptions != null && displayRateOptions <= 1
            val contentFps = player?.videoFormat?.frameRate?.takeIf { it > 0f }
            val dot = when {
                !videoPresentation.supportsCadenceJudgement || singleMode -> StatsDot.NONE
                contentFps != null -> {
                    val ratio = hz / contentFps
                    val nearestMultiple = kotlin.math.round(ratio).coerceAtLeast(1f)
                    if (kotlin.math.abs(ratio - nearestMultiple) / nearestMultiple <= t.FPS_MATCH_TOLERANCE) {
                        StatsDot.GOOD
                    } else {
                        StatsDot.BAD
                    }
                }
                else -> StatsDot.NONE
            }
            val notes = buildList {
                if (tunnelled) add("tunnelled")
                if (videoPresentation == PlaybackVideoPresentation.NATIVE) add("native output")
                if (videoPresentation == PlaybackVideoPresentation.UNCONFIGURED) add("video unconfigured")
                if (videoPresentation == PlaybackVideoPresentation.UNSUPPORTED) add("metrics unavailable")
                if (singleMode) add("1 mode")
            }
            val suffix = if (notes.isEmpty()) "" else " · " + notes.joinToString(", ")
            // Output resolution first, mode-descriptor style ("3840×2160 · 23.98 Hz").
            // This is the negotiated output MODE, distinct from the Video row's SOURCE
            // resolution above it: a 4K file on a 1080p output reads Video 3840×2160 /
            // Display 1920×1080, making the downscale self-evident. Deliberately no dot
            // change: an output below source is not something the app can fix.
            val modeSize = if ((displayModeWidth ?: 0) > 0 && (displayModeHeight ?: 0) > 0) {
                "${displayModeWidth}×${displayModeHeight} · "
            } else ""
            rows += StatsRow("Display", modeSize + String.format("%.2f Hz", hz) + suffix, dot)
        }

        // Buffer health with startup grace and end-of-file guard.
        player?.let {
            val aheadMs = (it.bufferedPosition - it.currentPosition).coerceAtLeast(0L)
            val aheadS = aheadMs / 1000.0
            val duration = it.duration
            val nearEof = duration > 0L && duration - it.bufferedPosition <= t.BUFFER_EOF_GUARD_MS
            val inGrace = it.currentPosition < t.BUFFER_STARTUP_GRACE_MS
            val dot = when {
                nearEof -> StatsDot.GOOD
                inGrace -> StatsDot.NONE
                aheadS >= t.BUFFER_GOOD_SECONDS -> StatsDot.GOOD
                aheadS >= t.BUFFER_WARN_SECONDS -> StatsDot.WARN
                else -> StatsDot.BAD
            }
            rows += StatsRow("Buffer", String.format("%.1f s ahead", aheadS), dot)
        }

        val transferRate = transferRateSampler.sample(hud.transfer)
        val coverage = hud.transfer.coverage
        val speedText = when {
            !coverage.available -> "Unavailable · route not instrumented"
            transferRate.currentBps == null -> "Measuring"
            transferRate.currentBps < 1_000.0 -> "Idle"
            else -> String.format("%.1f Mbit/s", transferRate.currentBps / 1_000_000.0)
        }
        // A zero is an idle counted interval, not proof of starvation or a full buffer.
        rows += StatsRow("Speed", speedText, StatsDot.NONE)
        lastPingMs?.takeIf { it.matches(hud.transfer) }?.let { ping ->
            rows += StatsRow("Server latency", "${ping.elapsedMs} ms", StatsDot.NONE)
        }

        // Last media request duration (dotless: legitimate values vary
        // too much with chunk size and path for honest absolute thresholds).
        // Completed MEDIA loads only. On progressive streams the single
        // long read only ever ends by cancellation (seek/stop), so its running
        // duration is playback bookkeeping, not request latency, and the row
        // stays absent there.
        hud.lastCompletedMediaLoadDurationMs?.takeIf { it > 0L }?.let {
            rows += StatsRow("Request", "$it ms")
        }
        // Rate-limit clamp: absent until the parallel engine's 429
        // clamp has tripped this session; WARN while latched (cooldown
        // counting down), informational once recovered. Reads the
        // ParallelRangeDataSource companion mirror (same package).
        if (ParallelRangeDataSource.hudClampTrips > 0) {
            if (ParallelRangeDataSource.hudClampLatched) {
                val nextS = ParallelRangeDataSource
                    .hudClampCooldownRemainingMs(android.os.SystemClock.uptimeMillis()) / 1000L
                // When the server is actively
                // throttling (clamp latched) AND measured delivery is materially below the
                // mux rate the stream needs, name it out loud. Uses the Speed row's own
                // SPEED_RATIO_WARN so the "materially below" bar matches the Speed dot.
                // Absent when either figure is unknown or delivery is keeping up. No client
                // logic recovers throughput here; the row only reports it.
                val servingSuffix = run {
                    val measured = transferRate.currentBps?.takeIf { coverage == PlaybackTransferCoverage.PROGRESSIVE }
                    val needed = effectiveMuxBps
                    if (measured != null && needed != null && needed > 0L &&
                        measured.toDouble() / needed < t.SPEED_RATIO_WARN) {
                        String.format(
                            " \u00b7 serving %.1f/%.1f Mbit/s",
                            measured / 1_000_000.0, needed / 1_000_000.0
                        )
                    } else ""
                }
                // Cooldown countdown and the serving figure are mutually exclusive
                // so this row never overflows -- serving (the throttle's actual cost) wins
                // when present, else the depth-recovery countdown shows.
                val cooldownOrServing =
                    if (servingSuffix.isNotEmpty()) servingSuffix else " \u00b7 +1 in ${nextS}s"
                rows += StatsRow(
                    "Rate limit",
                    "429 \u00b7 depth ${ParallelRangeDataSource.hudDepthCap}/${ParallelRangeDataSource.hudDepthConfigured}" +
                        cooldownOrServing,
                    StatsDot.WARN
                )
            } else {
                rows += StatsRow("Rate limit", "recovered \u00b7 ${ParallelRangeDataSource.hudClampTrips} \u00d7 429")
            }
        }
        // Hedge: body-stall restart activity this session. Hidden until the
        // first fresh-connection restart fires. "N restarts" while escaping; appends
        // "(cap hit)" if any chunk exhausted the restart budget (uniformly-slow origin).
        if (ParallelRangeDataSource.hudHedgeRestarts > 0) {
            val hedgeExhausted = ParallelRangeDataSource.hudHedgeExhausted
            val hedgeValue = "${ParallelRangeDataSource.hudHedgeRestarts} restarts" +
                if (hedgeExhausted) " (cap hit)" else ""
            rows += StatsRow("Hedge", hedgeValue, if (hedgeExhausted) StatsDot.BAD else StatsDot.WARN)
        }
        if (hud.loadErrorCount > 0) {
            rows += StatsRow("Load errors", hud.loadErrorCount.toString(), StatsDot.WARN)
        }
        if (coverage.available) {
            val suffix = if (coverage == PlaybackTransferCoverage.CHUNKED_PARTIAL) " · partial" else ""
            rows += StatsRow("Network reads", formatPlaybackStatsBytes(hud.transfer.networkBytes) + suffix)
            rows += StatsRow("Cache/local reads", formatPlaybackStatsBytes(
                (hud.transfer.readBytes - hud.transfer.networkBytes).coerceAtLeast(0L)) + suffix)
        }

        // Rebuffers: real STATE_BUFFERING count from the runtime controller,
        // hidden until the first rebuffer. positionStallCount only counts a "zombie"
        // freeze (player READY+playing yet position frozen) and reads 0 through a
        // genuine rebuffer, so it drives "Pos. freeze" below instead.
        if (rebuffers > 0) {
            rows += StatsRow(
                "Rebuffers",
                String.format("%d (%.1f s total)", rebuffers, controller.rebufferTotalMs / 1000.0),
                recencyDot(rebuffers, statsRebuffersIncreasedAtMs)
            )
        }
        // Pos. freeze: renderer/clock wedge -- position frozen while the player
        // still reports READY+playing (the VC-1-class fault). Hidden until non-zero.
        if (hud.positionStallCount > 0) run {
            val longest = hud.longestPositionStallMs
            val value = if (hud.positionStallCount > 0 && longest > 0L) {
                String.format("%d (longest %.1f s)", hud.positionStallCount, longest / 1000.0)
            } else {
                hud.positionStallCount.toString()
            }
            rows += StatsRow("Pos. freeze", value, recencyDot(hud.positionStallCount, statsStallsIncreasedAtMs))
        }

        // Audio: format + passthrough state + bitrate.
        player?.audioFormat?.let { format ->
            val codec = audioCodecLabel(format.sampleMimeType)
            val srcChannels = format.channelCount.takeIf { it > 0 }
            // Output channel count: for a PCM decode (incl. FFmpeg downmix) the sink's
            // configured input IS the decoder's PCM output, so its channelCount is the
            // real post-downmix count. Passthrough leaves this null - no arrow shown.
            val outChannels = controller.playbackSpeedAwareAudioSink?.activeInputFormat
                ?.takeIf { it.sampleMimeType == MimeTypes.AUDIO_RAW }
                ?.channelCount?.takeIf { it > 0 }
            val channels = srcChannels?.let { s ->
                if (outChannels != null && outChannels != s) "${s}ch→${outChannels}ch" else "${s}ch"
            }
            val rate = format.sampleRate.takeIf { it > 0 }?.let { "${it / 1000} kHz" }
            val routing = controller.getAudioRoutingSnapshot()
            val mode = routing?.outputFormat
            val losslessMime = format.sampleMimeType?.let {
                it.contains("true-hd") || it.contains("truehd") || it.contains("dts")
            } == true
            val dot = when (routing?.mode) {
                AudioRoutingMode.PASSTHROUGH_IEC,
                AudioRoutingMode.PASSTHROUGH_DIRECT,
                AudioRoutingMode.TRANSCODE_AC3 -> StatsDot.GOOD
                AudioRoutingMode.PCM -> if (losslessMime) StatsDot.WARN else StatsDot.NONE
                null -> StatsDot.NONE
            }
            listOfNotNull(codec, channels, rate, mode).joinToString(" · ")
                .takeIf { it.isNotBlank() }?.let { rows += StatsRow("Audio", it, dot) }
            // Nothing declares a bitrate for a lossless track (MKV has no field for it and
            // TrueHD / DTS-HD MA are genuinely variable-rate), so under passthrough the sink
            // measures the encoded bitstream on its way out.
            val declaredAudioBitrate = (if (format.bitrate > 0) format.bitrate.toLong() else null)
                ?: (if (format.averageBitrate > 0) format.averageBitrate.toLong() else null)
            val measuredAudioBitrate = PlayerAudioBitrateMeter.bitrateBps()?.toLong()
            rows += StatsRow(
                "A bitrate",
                when {
                    declaredAudioBitrate != null -> formatPlaybackStatsBitrate(declaredAudioBitrate)
                    measuredAudioBitrate != null -> "meas " + formatPlaybackStatsBitrate(measuredAudioBitrate)
                    losslessMime -> "- (VBR)"
                    else -> "- (undeclared)"
                }
            )
        }
        // Underruns. media3's count is event-driven off AudioTrack.getUnderrunCount() deltas,
        // polled only while the renderer is feeding; the native figure is read straight off the
        // track on this tick. A native count above the event count means media3 missed
        // underruns the hardware reported. Both at zero while the HAL logs underrun-restarts
        // means the failure is below the client buffer, inside the vendor HAL.
        // The native figure is shown whenever it is available, even when it agrees with
        // media3's count: "media3=0, native 0" is itself the diagnostic result on a
        // stuttering file, since the hardware reported no underruns and the fault is below
        // the client buffer. A native count above media3's warns (media3 missed hardware
        // underruns).
        val nativeUnderruns = controller.playbackSpeedAwareAudioSink?.nativeAudioTrackUnderrunCount()
        rows += StatsRow(
            "Underruns",
            if (nativeUnderruns != null) {
                "${hud.audioUnderrunCount} · native $nativeUnderruns"
            } else {
                hud.audioUnderrunCount.toString()
            },
            if (nativeUnderruns != null && nativeUnderruns > hud.audioUnderrunCount) {
                StatsDot.WARN
            } else {
                recencyDot(hud.audioUnderrunCount, statsUnderrunsIncreasedAtMs)
            }
        )

        // Route: the AudioTrack's routed output device and how many times it has
        // changed mid-track this playback. A count ticking up while Buffer stays healthy
        // and Underruns stays flat is the route-steal static signature (system sounds,
        // capture tools, HDMI renegotiation). Dotless: the number judges itself in context.
        controller.playbackSpeedAwareAudioSink?.sampleAudioRoute()?.let { route ->
            rows += StatsRow("Route", "${route.deviceLabel} · ${route.changeCount} changes")
        }

        // Audio-clock jitter. Under passthrough the audio clock is the master clock, so a
        // clock that jumps drags the video renderer into bulk frame drops, the visible skips
        // of a vendor HAL failing to pack its output. Nothing else on this panel sees that.
        controller.playbackSpeedAwareAudioSink?.audioClockJitter()?.let { jitter ->
            // The dot is driven by 1 s-window drift, not per-sample max. Per-sample
            // deviations alias sampling noise; sustained drift of position vs wall clock
            // over 1 s windows is the real clock-health signal (a healthy clock shows
            // |drift| p90 around 23 ms/s). The red threshold is provisional: the known MS12
            // fault is state-dependent and hard to reproduce.
            val dot = when {
                jitter.driftWindows == 0 -> StatsDot.GOOD
                jitter.driftMeanAbsMs < t.AUDIO_DRIFT_GOOD_MS &&
                    kotlin.math.abs(jitter.driftLastMs) < t.AUDIO_DRIFT_BAD_MS -> StatsDot.GOOD
                jitter.driftMeanAbsMs < t.AUDIO_DRIFT_BAD_MS -> StatsDot.WARN
                else -> StatsDot.BAD
            }
            rows += StatsRow(
                "A jitter",
                "drift avg ${jitter.driftMeanAbsMs} ms/s · max ${jitter.driftMaxAbsMs} · " +
                    "${jitter.events} ev",
                dot
            )
        }

        // App CPU and memory.
        playbackStatsCpuSampler.samplePercent()?.let { pct ->
            val dot = when {
                pct < t.CPU_GOOD_PERCENT -> StatsDot.GOOD
                pct <= t.CPU_BAD_PERCENT -> StatsDot.WARN
                else -> StatsDot.BAD
            }
            rows += StatsRow("App CPU", "$pct %", dot)
        }
        val runtime = Runtime.getRuntime()
        val heapUsed = runtime.totalMemory() - runtime.freeMemory()
        val heapMax = runtime.maxMemory()
        val nativeMb = android.os.Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
        rows += StatsRow(
            "Memory",
            "Java heap ${heapUsed / (1024L * 1024L)}/${heapMax / (1024L * 1024L)} MB · native heap $nativeMb MB",
            StatsDot.NONE
        )

        // SoC thermal. Real degrees when sysfs is app-readable on this device;
        // otherwise the framework's normalised headroom (1.0 = severe throttling).
        // Hidden entirely when neither source answers.
        playbackStatsThermalSampler.sample()?.let { thermal ->
            when {
                thermal.celsius != null -> {
                    val dot = when {
                        thermal.celsius < t.SOC_TEMP_WARN_C -> StatsDot.GOOD
                        thermal.celsius < t.SOC_TEMP_BAD_C -> StatsDot.WARN
                        else -> StatsDot.BAD
                    }
                    rows += StatsRow("SoC temp", String.format("%.1f °C", thermal.celsius), dot)
                }
                thermal.headroom != null -> {
                    val dot = when {
                        thermal.headroom < t.THERMAL_HEADROOM_WARN -> StatsDot.GOOD
                        thermal.headroom < t.THERMAL_HEADROOM_BAD -> StatsDot.WARN
                        else -> StatsDot.BAD
                    }
                    rows += StatsRow(
                        "SoC temp",
                        String.format("headroom %.2f (1.0 = throttle)", thermal.headroom),
                        dot
                    )
                }
            }
        }

        // CPU clock / throttle. No app-visible SoC temperature exists on some boxes (only a
        // TYPE_CPU sensor, which getThermalHeadroom ignores; sysfs thermal is SELinux-blocked),
        // so this reads cpufreq instead: the SoC throttles by capping clock frequency, and the
        // cap (scaling_max_freq) dropping below the hardware max is the throttle signal. Red
        // when capped. Hidden when cpufreq is unreadable.
        playbackStatsCpuClockSampler.sample()?.let { clock ->
            val dot = if (clock.isThrottled) StatsDot.BAD else StatsDot.GOOD
            rows += StatsRow(
                "CPU clock",
                String.format(
                    "%.2f GHz · cap %.2f GHz",
                    clock.currentKHz / 1_000_000f,
                    clock.capKHz / 1_000_000f
                ),
                dot
            )
        }
        return PlaybackStatsSample(isExoPlayer = true, engineLabel = "ExoPlayer", sections = buildStatsSections(rows))
    }

    /**
     * Human names for the audio MIME types this fork actually sees; the raw MIME suffix
     * ("vnd.dts.hd") is not a codec name anyone recognises. This is the codec the
     * *container* declares, which is all that can be known at this level: object
     * extensions (DTS:X inside DTS-HD MA, Atmos inside TrueHD) are not signalled in the
     * container and ride inside the bitstream, so they are visible only to the receiver
     * that decodes it. A soundbar reporting DTS:X on a track shown here as DTS-HD MA is
     * both of them being right.
     */
    private fun audioCodecLabel(mimeType: String?): String? = when (mimeType) {
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        // MIME-honest: vnd.dts.hd covers both MA and HRA; the container label may
        // say MA but the MIME cannot confirm it. Matches the diagnostics card.
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
        MimeTypes.AUDIO_DTS_X -> "DTS:X"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3 JOC"
        MimeTypes.AUDIO_E_AC3 -> "E-AC3"
        MimeTypes.AUDIO_AC3 -> "AC3"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_RAW -> "PCM"
        else -> mimeType?.substringAfter('/')
    }

    private fun formatPlaybackStatsBitrate(bps: Long): String = if (bps >= 1_000_000L) {
        String.format("%.2f Mbit/s", bps / 1_000_000.0)
    } else {
        String.format("%.0f kbit/s", bps / 1_000.0)
    }

    private fun formatPlaybackStatsBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format("%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format("%.1f MB", bytes / 1_048_576.0)
        else -> "${bytes / 1024L} KB"
    }

    fun getCurrentStreamUrl(): String = controller.getCurrentStreamUrl()

    fun getCurrentHeaders(): Map<String, String> = controller.getCurrentHeaders()

    /** Stable identity for a title's stored thumbnails (the videoId carries season and episode). */
    fun seekThumbnailIdentity(): String? =
        controller.currentVideoId?.takeIf { it.isNotBlank() } ?: controller.contentId?.takeIf { it.isNotBlank() }

    fun getCurrentFileSizeBytes(): Long? = controller.currentVideoSize

    fun currentStreamMimeType(): String? = controller.currentStreamMimeType

    fun isAutoPlayedEpisode(): Boolean = controller.currentEpisodeAutoPlayed

    fun pauseForSeekThumbnails(): Boolean? = controller.pauseForSeekThumbnails()

    /** Connections playback keeps open to the stream. */
    fun playbackConnectionCount(): Int = controller.mediaSourceFactory.let {
        if (it.useParallelConnections) it.parallelConnectionCount else 1
    }

    /** Playback's read-ahead reserve; null until the player is built. */
    fun seekThumbnailBufferReserve(): com.nuvio.tv.core.player.thumbnail.PlaybackBufferReserve? =
        controller._loadControl as? com.nuvio.tv.core.player.thumbnail.PlaybackBufferReserve

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun getPlayerSampleAllocatorBytes(): Long? {
        val allocator = controller._loadControl?.allocator as? androidx.media3.exoplayer.upstream.DefaultAllocator ?: return null
        return allocator.totalBytesAllocated.toLong().coerceAtLeast(0L)
    }

    internal fun getAudioRoutingSnapshot(): AudioRoutingSnapshot? = controller.getAudioRoutingSnapshot()

    fun stopAndRelease() {
        postPlayRecommendationController.stop()
        controller.stopAndRelease()
    }

    fun playPostPlayTrailer() {
        postPlayRecommendationController.playTrailer()
    }

    fun onPostPlayTrailerEnded() {
        postPlayRecommendationController.onTrailerEnded()
    }

    fun showPreviousPostPlayRecommendation() {
        postPlayRecommendationController.showPreviousRecommendation()
    }

    fun showNextPostPlayRecommendation() {
        postPlayRecommendationController.showNextRecommendation()
    }

    fun returnToPlayerFromPostPlay() {
        postPlayRecommendationController.returnToPlayer()
    }

    fun scheduleHideControls() {
        controller.scheduleHideControls()
    }

    fun onUserInteraction() {
        controller.onUserInteraction()
    }

    fun hideControls() {
        controller.hideControls()
    }

    fun attachHostActivity(activity: android.app.Activity?) {
        controller.attachHostActivity(activity)
    }

    fun attachMpvView(view: NuvioMpvSurfaceView?) {
        controller.attachMpvView(view)
    }

    fun pauseForLifecycle() {
        controller.pauseForLifecycle()
    }

    fun resumeForLifecycle() {
        controller.resumeForLifecycle()
    }

    fun startInitialPlaybackIfNeeded() {
        controller.startInitialPlaybackIfNeeded()
    }

    fun onEvent(event: PlayerEvent) {
        controller.onEvent(event)
    }

    fun bindExoSubtitleView(subtitleView: androidx.media3.ui.SubtitleView?) {
        controller.bindExoSubtitleView(subtitleView)
    }

    fun consumePendingExitReason() {
        controller.consumePendingExitReason()
    }

    override fun onCleared() {
        screensaverController.setPlaybackActive(false)
        postPlayRecommendationController.stop()
        controller.onCleared()
        diagnosticPlaybackLease.close()
        // Allow the trailer player to be re-created when returning to home screen.
        trailerPlayerPool.reclaim()
        super.onCleared()
    }

    /**
     * Save watch progress returned by an external player after "Open in External Player".
     * Uses the controller's current content metadata (contentId, season, episode, etc.)
     * which are still available since the controller hasn't been cleared yet.
     */
    fun saveExternalPlayerProgress(positionMs: Long, durationMs: Long?) {
        val effectiveDuration = durationMs ?: controller.playbackTimeline.value.duration
        controller.saveWatchProgressInternal(
            position = positionMs,
            duration = effectiveDuration
        )
    }

    /**
     * Launch the current stream in an external player via the centralized tracker.
     *
     * Keep the ViewModel alive until the external intent has been handed to the launcher.
     * This lets the caller navigate away only after a successful handoff, while failures
     * remain visible on the current player screen (#2560).
     */
    fun launchInExternalPlayer(
        activityContext: Context,
        resumePositionMs: Long,
        onResult: (Boolean) -> Unit
    ) {
        val url = controller.getCurrentStreamUrl()
        if (url.isBlank()) {
            onResult(false)
            return
        }
        val contentId = controller.contentId
            ?: controller.cloudPlaybackContext?.item?.stableKey
            ?: run {
            onResult(false)
            return
        }
        val videoId = controller.currentVideoId ?: contentId
        val metadata = com.nuvio.tv.core.player.ExternalPlaybackMetadata(
            contentId = contentId,
            contentType = controller.contentType ?: "movie",
            contentName = controller.contentName ?: controller.title,
            poster = controller.poster,
            backdrop = controller.backdrop,
            logo = controller.logo,
            videoId = videoId,
            season = controller.currentSeason,
            episode = controller.currentEpisode,
            episodeTitle = controller.currentEpisodeTitle,
            year = controller.year,
            profileId = controller.profileId
        )
        val headers = controller.getCurrentHeaders()
        // Capture already-loaded addon subtitles before handing off. Preparation stays in the
        // ViewModel scope because the player screen remains alive until the intent is sent.
        val subtitleInputs = if (controller.uiState.value.subtitleStyle.preferredLanguage.trim().lowercase() != "none") {
            val addonSubtitles = controller.uiState.value.addonSubtitles
            if (addonSubtitles.isNotEmpty()) {
                addonSubtitles.map {
                    com.nuvio.tv.core.player.SubtitleInput(
                        url = it.url,
                        name = "${it.getDisplayLanguage()} - ${it.addonName}",
                        lang = it.lang
                    )
                }
            } else null
        } else null

        viewModelScope.launch {
            val cachedSubtitles = subtitleInputs?.let { inputs ->
                try {
                    withTimeoutOrNull(10_000L) {
                        subtitleFileCache.cacheSubtitles(inputs)
                    }
                } catch (_: Exception) {
                    // Subtitle forwarding is best-effort; the external launch must still proceed.
                    null
                }
            }

            // Stop the internal player only after preparation has completed and immediately
            // before sending the external intent.
            val nextEpisodeSnapshot = controller.metaVideos.takeIf { it.isNotEmpty() }?.let {
                externalPlaybackTracker.resolveNextEpisodeSnapshot(metadata, it)
            }
            controller.stopAndRelease()
            val launched = try {
                externalPlaybackTracker.launchPlayer(
                    metadata = metadata,
                    url = url,
                    title = metadata.buildPlayerTitle(),
                    headers = headers,
                    resumePositionMs = resumePositionMs,
                    subtitles = cachedSubtitles,
                    nextEpisodeSnapshot = nextEpisodeSnapshot,
                    cloudSessionToken = controller.cloudSessionToken,
                    context = activityContext
                )
            } catch (_: Exception) {
                false
            }
            onResult(launched)
        }
    }
}
