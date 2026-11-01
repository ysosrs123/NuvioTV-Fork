package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.SubtitleFont
import com.nuvio.tv.data.local.SubtitleEdgeStyle
import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.ui.AspectRatioFrameLayout
import com.nuvio.tv.core.streams.CompiledStreamBadgeFilter
import com.nuvio.tv.core.streams.StreamBadgePlacement
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.LibassRenderType
import com.nuvio.tv.data.local.LibassRenderMode
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.EpisodeOptionsOverlayStyle
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.components.SourceChipItem

enum class PlayerExitReason {
    StillWatchingPrompt
}

enum class PlaybackIssueReportStatus {
    Idle,
    Sending,
    Sent,
    Failed
}

sealed interface PostPlayMode {
    val nextEpisode: NextEpisodeInfo

    data class AutoPlay(
        override val nextEpisode: NextEpisodeInfo,
        val searching: Boolean = false,
        val sourceName: String? = null,
        val countdownSec: Int? = null,
    ) : PostPlayMode

    data class StillWatching(
        override val nextEpisode: NextEpisodeInfo,
        val countdownSec: Int? = null,
    ) : PostPlayMode

    fun copyWithNextEpisode(nextEpisode: NextEpisodeInfo): PostPlayMode {
        if (nextEpisode == this.nextEpisode) return this
        return when (this) {
            is AutoPlay -> copy(nextEpisode = nextEpisode)
            is StillWatching -> copy(nextEpisode = nextEpisode)
        }
    }

    fun blocksNaturalCompletion(): Boolean = when (this) {
        is StillWatching -> true
        is AutoPlay -> searching || countdownSec != null
    }
}

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val playbackEnded: Boolean = false,
    val pendingExitReason: PlayerExitReason? = null,
    val title: String = "",
    val contentName: String? = null, // Series/show name (for series content)
    val releaseYear: String? = null, // Release year for movies
    val contentType: String? = null,
    val currentStreamName: String? = null, // Name of the current stream source
    val currentStreamUrl: String? = null,
    val currentStreamInfoHash: String? = null, // InfoHash of the currently playing stream (for debrid matching)
    val currentStreamFileIdx: Int? = null, // FileIdx of the currently playing stream (for debrid matching)
    val currentStreamAddonName: String? = null, // Addon name of the currently playing stream
    val backdrop: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val castMembers: List<MetaCastMember> = emptyList(),
    val showControls: Boolean = true,
    val showSeekOverlay: Boolean = false,
    val pendingPreviewSeekPosition: Long? = null,
    val previewThumbPositionMs: Long? = null,
    // Counts the pauses made at the remote; each one puts focus on the timeline.
    val pauseFocusRequest: Int = 0,
    val playbackSpeed: Float = 1f,
    val loadingOverlayEnabled: Boolean = true,
    val showPlayerLoadingStatus: Boolean = false,
    // Keep source details hidden until the saved profile setting has arrived.
    val showPlayerLoadingSource: Boolean = false,
    val playbackIssueReportsEnabled: Boolean = false,
    val showLoadingOverlay: Boolean = true,
    val loadingMessage: String? = null,
    val loadingProgress: Float? = null,
    val loadingIssueReportVisible: Boolean = false,
    val loadingIssueElapsedMs: Long = 0L,
    val pauseOverlayEnabled: Boolean = false,
    val osdClockEnabled: Boolean = true,
    val transparentLetterbox: Boolean = false,
    val playerStatsHudEnabled: Boolean = false,
    val playerStatsHudButtonAvailable: Boolean = false,
    val showPauseOverlay: Boolean = false,
    val audioTracks: List<TrackInfo> = emptyList(),
    val serverAudioTracks: List<TrackInfo> = emptyList(),
    val serverSubtitleTracks: List<TrackInfo> = emptyList(),
    val isServerStream: Boolean = false,
    val subtitleTracks: List<TrackInfo> = emptyList(),
    val selectedAudioTrackIndex: Int = -1,
    val selectedSubtitleTrackIndex: Int = -1,
    val audioDelayMs: Int = 0,
    val audioAmplificationDb: Int = 0,
    val nightMode: com.nuvio.tv.data.local.NightModeLevel = com.nuvio.tv.data.local.NightModeLevel.OFF,
    val nightModeLastOn: com.nuvio.tv.data.local.NightModeLevel = com.nuvio.tv.data.local.NightModeLevel.DEFAULT_ON,
    val dialogueBoostState: DialogueBoostState = DialogueBoostState.STEREO_TRACK,
    val isAudioAmplificationAvailable: Boolean = false,
    val persistAudioAmplification: Boolean = false,
    val centerMixLevelDb: Int = 0,
    val isCenterMixAvailable: Boolean = false,
    val showAudioOverlay: Boolean = false,
    val showSubtitleOverlay: Boolean = false,
    val showSubtitleStylePanel: Boolean = false,
    val showSubtitleTimingDialog: Boolean = false,
    val showSubtitleDelayOverlay: Boolean = false,
    val subtitleDelayMs: Int = 0,
    val subtitleAutoSyncCues: List<SubtitleSyncCue> = emptyList(),
    val subtitleAutoSyncCapturedVideoMs: Long? = null,
    val subtitleAutoSyncStatus: String? = null,
    val subtitleAutoSyncError: String? = null,
    val subtitleAutoSyncLoading: Boolean = false,
    val subtitleAutoSyncLoadedTrackKey: String? = null,
    val showSpeedDialog: Boolean = false,
    val showMoreDialog: Boolean = false,
    val dimHdrOverlays: Boolean = false,
    val isHdrVideo: Boolean = false,
    // Subtitle style settings
    val subtitleStyle: SubtitleStyleSettings = SubtitleStyleSettings(),
    // Addon subtitles
    val addonSubtitles: List<Subtitle> = emptyList(),
    val isLoadingAddonSubtitles: Boolean = false,
    val selectedAddonSubtitle: Subtitle? = null,
    val addonSubtitlesError: String? = null,
    val installedSubtitleAddonOrder: List<String> = emptyList(),
    // Episodes/streams side panel (for series)
    val showEpisodesPanel: Boolean = false,
    val showChaptersPanel: Boolean = false,
    val chapters: List<PlayerChapter> = emptyList(),
    val isLoadingEpisodes: Boolean = false,
    val episodesError: String? = null,
    val episodesAll: List<Video> = emptyList(),
    val episodesAvailableSeasons: List<Int> = emptyList(),
    val episodesSelectedSeason: Int? = null,
    val episodes: List<Video> = emptyList(),
    val currentSeason: Int? = null,
    val currentEpisode: Int? = null,
    val currentVideoId: String? = null,
    val currentEpisodeTitle: String? = null,
    val blurUnwatchedEpisodes: Boolean = false,
    val episodeOptionsOverlayStyle: EpisodeOptionsOverlayStyle = EpisodeOptionsOverlayStyle.BLUR,
    val episodeWatchProgressMap: Map<Pair<Int, Int>, WatchProgress> = emptyMap(),
    val watchedEpisodeKeys: Set<Pair<Int, Int>> = emptySet(),
    val showEpisodeStreams: Boolean = false,
    val isLoadingEpisodeStreams: Boolean = false,
    val episodeStreamsError: String? = null,
    val episodeAllStreams: List<Stream> = emptyList(),
    val episodeSelectedAddonFilter: String? = null, // null means "All"
    val episodeFilteredStreams: List<Stream> = emptyList(),
    val episodeAvailableAddons: List<String> = emptyList(),
    val episodeSourceChips: List<SourceChipItem> = emptyList(),
    val episodeStreamsForVideoId: String? = null,
    val episodeStreamsSeason: Int? = null,
    val episodeStreamsEpisode: Int? = null,
    val episodeStreamsTitle: String? = null,
    // Stream sources side panel (for switching streams during playback)
    val showSourcesPanel: Boolean = false,
    val isLoadingSourceStreams: Boolean = false,
    val sourceStreamsError: String? = null,
    val sourceAllStreams: List<Stream> = emptyList(),
    // URLs proven dead this session (non-media body / 404 / 410); greyed in the panel.
    val deadSourceStreamUrls: Set<String> = emptySet(),
    val sourceSelectedAddonFilter: String? = null, // null means "All"
    val sourceFilteredStreams: List<Stream> = emptyList(),
    val sourceAvailableAddons: List<String> = emptyList(),
    val sourceChips: List<SourceChipItem> = emptyList(),
    val showFileSizeBadges: Boolean = true,
    val showAddonLogo: Boolean = true,
    val streamBadgePlacement: StreamBadgePlacement = StreamBadgePlacement.BOTTOM,
    val playerBadgeFilters: List<CompiledStreamBadgeFilter> = emptyList(),
    val showPlayerBadges: Boolean = false,
    val error: String? = null,
    val showSwitchToMpvErrorAction: Boolean = false,
    val playbackIssueReportStatus: PlaybackIssueReportStatus = PlaybackIssueReportStatus.Idle,
    val playbackIssueReportId: String? = null,
    val playbackIssueReportError: String? = null,
    val pendingSeekPosition: Long? = null, // For resuming from saved progress
    // Parental guide overlay
    val parentalWarnings: List<ParentalWarning> = emptyList(),
    val showParentalGuide: Boolean = false,
    val parentalGuideHasShown: Boolean = false,
    // Skip intro
    val activeSkipInterval: SkipInterval? = null,
    val activeSkipTargetsPostCredits: Boolean = false,
    val skipIntervalDismissed: Boolean = false,
    // Next episode card
    val nextEpisode: NextEpisodeInfo? = null,
    val isNextEpisodeMetadataResolved: Boolean = false,
    val postPlayMode: PostPlayMode? = null,
    val postPlayDismissedForCurrentEpisode: Boolean = false,
    val streamAutoPlayMode: StreamAutoPlayMode = StreamAutoPlayMode.MANUAL,
    val streamAutoPlayNextEpisodeEnabled: Boolean = false,
    val streamAutoPlayPreferBingeGroupForNextEpisode: Boolean = false,
    // Stream source badge
    val showStreamSourceIndicator: Boolean = false,
    val streamSourceIndicatorText: String = "",
    val showPlayerEngineSwitchInfo: Boolean = false,
    val playerEngineSwitchInfoText: String = "",
    // Frame rate matching
    val detectedFrameRateRaw: Float = 0f,
    val detectedFrameRateSource: FrameRateSource? = null,
    val detectedFrameRate: Float = 0f,
    val afrProbeRunning: Boolean = false,
    val internalPlayerEngine: InternalPlayerEngine = InternalPlayerEngine.EXOPLAYER,
    val frameRateMatchingMode: FrameRateMatchingMode = FrameRateMatchingMode.OFF,
    val useLibass: Boolean = false,
    val libassRenderType: LibassRenderType = LibassRenderType.OVERLAY_OPEN_GL,
    val libassRenderMode: LibassRenderMode = LibassRenderMode.DEFAULT,
    val displayModeInfo: DisplayModeInfo? = null,
    val showDisplayModeInfo: Boolean = false,
    // Aspect ratio / resize mode
    val resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    val aspectMode: AspectMode = AspectMode.ORIGINAL,
    val tunneledSurfaceFill: Boolean = false,
    val tunnelingEnabled: Boolean = false,
    val showAspectRatioIndicator: Boolean = false,
    val aspectRatioIndicatorText: String = "",
    // Stream info overlay
    val showStreamInfoOverlay: Boolean = false,
    val showPartyPanel: Boolean = false,
    val streamInfoData: StreamInfoData? = null,
    // Live playback stats overlay
    val showPlaybackStatsOverlay: Boolean = false,
    // Torrent streaming state
    val isTorrentStream: Boolean = false,
    val torrentDownloadSpeed: Long = 0L,
    val torrentUploadSpeed: Long = 0L,
    val torrentPeers: Int = 0,
    val torrentSeeds: Int = 0,
    val torrentBufferProgress: Float = 0f,
    val torrentTotalProgress: Float = 0f,
    val showTorrentStats: Boolean = false,
    // Torrent mid-playback rebuffering (shown on the buffering spinner, not loading overlay)
    val torrentBufferingMessage: String? = null,
    val torrentBufferingProgress: Float = 0f,
    // When true, suppress all torrent stats text (buffer, seeds, peers, speed)
    // from loading overlay, rebuffering indicator, and corner overlay.
    val hideTorrentStats: Boolean = true,
    val controlLayout: com.nuvio.tv.data.local.PlayerControlLayout? = null,
    val isLive: Boolean = false
) {
    /** Subtitle style to render with, after applying the HDR override for the current video. */
    val effectiveSubtitleStyle: SubtitleStyleSettings
        get() = subtitleStyle.forVideo(isHdrVideo)

    /** In-player style edits to the text color go to the HDR override while this is true. */
    val isEditingHdrSubtitleStyle: Boolean
        get() = isHdrVideo && subtitleStyle.hdrStyleEnabled

    val editableSubtitleStyle: SubtitleStyleSettings
        get() = subtitleStyle.editableForVideo(isHdrVideo)

    /** Brightness factor for the player controls and menus drawn over the video (1 = undimmed). */
    val playerControlsBrightness: Float
        get() = subtitleStyle.playerControlsBrightness(isHdrVideo)
}

internal fun PlayerUiState.clearFrameRateDetection(): PlayerUiState = copy(
    detectedFrameRateRaw = 0f,
    detectedFrameRate = 0f,
    detectedFrameRateSource = null
)

data class PlaybackTimelineState(
    /** Display/seek-preview position used by the timers. */
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    /** Position (ms) up to which the player has buffered ahead of the playhead. */
    val bufferedPosition: Long = 0L,
    /** True for live windows (Live TV / live HLS), not VOD HLS. */
    val isLive: Boolean = false,
    /** Wall-clock time spent playing the current live stream. */
    val watchedDurationMs: Long = 0L,
    /** Actual playback position; preview-only input must not move it. */
    val playbackPosition: Long = currentPosition
) {
    /** Omitted positions preserve the preview and playhead independently. */
    internal fun withPositions(preview: Long? = null, actual: Long? = preview) = copy(
        currentPosition = (preview ?: currentPosition).coerceAtLeast(0L),
        playbackPosition = (actual ?: playbackPosition).coerceAtLeast(0L)
    )
}

data class TrackInfo(
    val index: Int,
    val name: String,
    val language: String?,
    val trackId: String? = null,
    val codec: String? = null,
    val channelCount: Int? = null,
    val isForced: Boolean = false,
    val isSelected: Boolean = false,
    val sampleRate: Int? = null,
    /** The track's own name from the file, as written there. */
    val title: String? = null,
    val role: TrackRole? = null
)

enum class TrackRole { COMMENTARY, AUDIO_DESCRIPTION, HEARING_IMPAIRED, ORIGINAL }

data class NextEpisodeInfo(
    val videoId: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val thumbnail: String?,
    val overview: String?,
    val released: String?,
    val hasAired: Boolean,
    val available: Boolean? = null,
    val unairedMessage: String?,
    val isOtherType: Boolean = false
)

data class SubtitleSyncCue(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String
)

sealed class PlayerEvent {
    data object OnPlayPause : PlayerEvent()
    data object OnSeekForward : PlayerEvent()
    data object OnSeekBackward : PlayerEvent()
    data class OnSeekBy(val deltaMs: Long) : PlayerEvent()
    data class OnPreviewSeekBy(val deltaMs: Long) : PlayerEvent()
    /** A seek key repeat: the key is held, so the cursor glides until the commit. */
    data class OnScrubHold(val forward: Boolean, val downTime: Long, val eventTime: Long) : PlayerEvent()
    data object OnCommitPreviewSeek : PlayerEvent()
    /** [exact] lands on the frame itself instead of the nearest keyframe (chapter jumps). */
    data class OnSeekTo(val position: Long, val exact: Boolean = false) : PlayerEvent()
    data class OnSelectAudioTrack(val index: Int) : PlayerEvent()
    data class OnSetAudioDelayMs(val delayMs: Int) : PlayerEvent()
    data class OnSetAudioAmplificationDb(val db: Int) : PlayerEvent()
    data class OnSetPersistAudioAmplification(val enabled: Boolean) : PlayerEvent()
    data class OnSetCenterMixLevelDb(val db: Int) : PlayerEvent()
    data class OnSetNightMode(val level: com.nuvio.tv.data.local.NightModeLevel) : PlayerEvent()
    data class OnSelectSubtitleTrack(val index: Int, val openPanel: Boolean = true) : PlayerEvent()
    data object OnDisableSubtitles : PlayerEvent()
    data object OnDisableSubtitlesQuietly : PlayerEvent()
    data class OnSelectAddonSubtitle(val subtitle: Subtitle, val openPanel: Boolean = true) : PlayerEvent()
    data class OnSetPlaybackSpeed(val speed: Float) : PlayerEvent()
    data object OnToggleControls : PlayerEvent()
    data object OnShowAudioOverlay : PlayerEvent()
    data object OnShowSubtitleOverlay : PlayerEvent()
    data object OnOpenSubtitleStylePanel : PlayerEvent()
    data object OnDismissSubtitleStylePanel : PlayerEvent()
    data object OnShowSubtitleTimingDialog : PlayerEvent()
    data object OnDismissSubtitleTimingDialog : PlayerEvent()
    data object OnCaptureSubtitleAutoSyncTime : PlayerEvent()
    data class OnApplySubtitleAutoSyncCue(val cueStartTimeMs: Long) : PlayerEvent()
    data object OnReloadSubtitleAutoSyncCues : PlayerEvent()
    data object OnShowSubtitleDelayOverlay : PlayerEvent()
    data object OnHideSubtitleDelayOverlay : PlayerEvent()
    data class OnAdjustSubtitleDelay(val deltaMs: Int, val showOverlay: Boolean = true) : PlayerEvent()
    data class OnResetSubtitleDelay(val showOverlay: Boolean = true) : PlayerEvent()
    data object OnShowSpeedDialog : PlayerEvent()
    data object OnShowMoreDialog : PlayerEvent()
    data object OnDismissMoreDialog : PlayerEvent()
    data object OnShowEpisodesPanel : PlayerEvent()
    data object OnDismissEpisodesPanel : PlayerEvent()
    data object OnShowChaptersPanel : PlayerEvent()
    data object OnDismissChaptersPanel : PlayerEvent()
    data class OnSelectChapter(val startMs: Long) : PlayerEvent()
    data object OnNextChapter : PlayerEvent()
    data object OnPreviousChapter : PlayerEvent()
    data object OnBackFromEpisodeStreams : PlayerEvent()
    data class OnEpisodeSeasonSelected(val season: Int) : PlayerEvent()
    data class OnEpisodeSelected(val video: Video, val manualSelection: Boolean = false) : PlayerEvent()
    data object OnReloadEpisodeStreams : PlayerEvent()
    data class OnEpisodeAddonFilterSelected(val addonName: String?) : PlayerEvent()
    data class OnEpisodeStreamSelected(val stream: Stream) : PlayerEvent()
    data object OnShowSourcesPanel : PlayerEvent()
    data object OnDismissSourcesPanel : PlayerEvent()
    data object OnReloadSourceStreams : PlayerEvent()
    data class OnSourceAddonFilterSelected(val addonName: String?) : PlayerEvent()
    data class OnSourceStreamSelected(val stream: Stream) : PlayerEvent()
    data object OnDismissTransientOverlay : PlayerEvent()
    data object OnRetry : PlayerEvent()
    data object OnReportPlaybackIssue : PlayerEvent()
    data object OnParentalGuideHide : PlayerEvent()
    data class OnShowDisplayModeInfo(val info: DisplayModeInfo) : PlayerEvent()
    data object OnHideDisplayModeInfo : PlayerEvent()
    data object OnDismissPauseOverlay : PlayerEvent()
    data object OnToggleDetails : PlayerEvent()
    data object OnCloseDetails : PlayerEvent()
    data object OnSkipIntro : PlayerEvent()
    data object OnDismissSkipIntro : PlayerEvent()
    data object OnPlayNextEpisode : PlayerEvent()
    data object OnDismissNextEpisodeCard : PlayerEvent()
    data object OnStillWatchingContinue : PlayerEvent()
    data object OnDismissStillWatchingPrompt : PlayerEvent()

    // Subtitle style events (for in-player style tab)
    data class OnSetSubtitleBitmapSize(val size: Int) : PlayerEvent()
    data class OnSetSubtitleSize(val size: Int) : PlayerEvent()
    data class OnSetSubtitleHdrBrightness(val brightness: Int) : PlayerEvent()
    data class OnSetSubtitleTextColor(val color: Int) : PlayerEvent()
    data class OnSetSubtitleBold(val bold: Boolean) : PlayerEvent()
    data class OnSetSubtitleFont(val font: SubtitleFont?) : PlayerEvent()
    data class OnSetSubtitleEdgeStyle(val style: SubtitleEdgeStyle) : PlayerEvent()
    data class OnSetSubtitleOutlineEnabled(val enabled: Boolean) : PlayerEvent()
    data class OnSetSubtitleOutlineColor(val color: Int) : PlayerEvent()
    data class OnSetSubtitleVerticalOffset(val offset: Int) : PlayerEvent()
    data object OnResetSubtitleDefaults : PlayerEvent()
    data object OnToggleAspectRatio : PlayerEvent()
    data object OnSwitchInternalPlayerEngine : PlayerEvent()
    data object OnSwitchToMpvPlayer : PlayerEvent()
    data object OnShowStreamInfo : PlayerEvent()
    data object OnDismissStreamInfo : PlayerEvent()
    data object OnShowPartyPanel : PlayerEvent()
    data object OnDismissPartyPanel : PlayerEvent()
    data object OnTogglePlayerStatsHud : PlayerEvent()
    data object OnToggleTorrentStats : PlayerEvent()
    data object OnTogglePlaybackStats : PlayerEvent()
}

data class ParentalWarning(
    val label: String,
    val severity: String
)

data class DisplayModeInfo(
    val width: Int,
    val height: Int,
    val refreshRate: Float,
    val statusMessage: String? = null
)

enum class FrameRateSource {
    TRACK,
    PROBE
}

val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

data class StreamInfoData(
    // Stream source
    val addonName: String? = null,
    val addonLogo: String? = null,
    val streamName: String? = null,
    val streamDescription: String? = null,
    // File info
    val filename: String? = null,
    val fileSize: Long? = null,
    // Video
    val videoCodec: String? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoFrameRate: Float? = null,
    val videoBitrate: Int? = null,
    val fileBitrate: Int? = null,
    // Audio
    val audioCodec: String? = null,
    val audioChannels: String? = null,
    val audioSampleRate: Int? = null,
    val audioLanguage: String? = null,
    // Subtitle
    val subtitleName: String? = null,
    val subtitleCodec: String? = null,
    val subtitleLanguage: String? = null,
    val subtitleSource: String? = null,
    val playerEngine: String? = null,
    val serverPlayback: String? = null
)
