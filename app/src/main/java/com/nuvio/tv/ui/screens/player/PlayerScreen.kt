@file:OptIn(
    ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.thumbnail.handsPlayerBackOnRestart
import com.nuvio.tv.core.player.thumbnail.isThumbnailSource
import com.nuvio.tv.ui.v2.components.GlassRole

import com.nuvio.tv.ui.v2.components.nuvioGlass

import com.nuvio.tv.ui.v2.components.nuvioV2Focus

import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.player.V2PlayerActions
import com.nuvio.tv.ui.v2.player.V2PlayerControls
import com.nuvio.tv.ui.v2.player.V2PlayerControl
import com.nuvio.tv.ui.v2.player.V2PlayerFocusState
import com.nuvio.tv.ui.v2.components.nuvioControlSurface
import com.nuvio.tv.ui.v2.components.NuvioSideSheet
import androidx.compose.runtime.withFrameNanos
import android.graphics.RectF
import com.nuvio.tv.ui.theme.NuvioMotion
import kotlinx.coroutines.delay

import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.accentBrush

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.painter.Painter
import androidx.annotation.RawRes
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.party.rememberPartyRuntime
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.rememberAsyncImagePainter
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.player.PlayerWindowBackdrop
import com.nuvio.tv.ui.util.localizeEpisodeTitle
import com.nuvio.tv.data.local.PlayerControlAction
import com.nuvio.tv.data.local.nextForKey
import com.nuvio.tv.data.local.RemoteButtonBinding
import com.nuvio.tv.data.local.RemoteButtonJob
import com.nuvio.tv.data.local.RemoteButtonMap
import com.nuvio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.nuvio.tv.data.local.SubtitleLanguageOption
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.core.player.thumbnail.SeekThumbnailPreferences
import com.nuvio.tv.core.player.thumbnail.NextEpisodeThumbs
import com.nuvio.tv.core.player.thumbnail.SeekThumbnails
import com.nuvio.tv.core.player.thumbnail.ThumbSource
import com.nuvio.tv.data.local.LibassRenderMode
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PanelActionRow
import com.nuvio.tv.ui.components.PlayerPanelRow
import android.text.format.DateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.layout.boundsInWindow
import com.nuvio.tv.ui.components.NuvioDialog
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.libass.media.widget.AssSubtitleSurfaceView
import kotlin.math.abs
import kotlin.math.roundToInt

private const val PLAYER_CONTROLS_FADE_DURATION_MS = 200

private fun PlayerUiState.returnFocusSeasonEpisode(completed: Boolean): Pair<Int?, Int?> {
    val next = nextEpisode?.takeIf {
        it.hasAired && !(it.released.isNullOrBlank() && it.available == false)
    }
    return if (completed && next != null) {
        next.season to next.episode
    } else {
        currentSeason to currentEpisode
    }
}

internal fun playerBackTreatsSkipAsAutoPlay(
    streamAutoPlayMode: StreamAutoPlayMode,
    preferBingeGroupForNextEpisode: Boolean
): Boolean = streamAutoPlayMode != StreamAutoPlayMode.MANUAL || preferBingeGroupForNextEpisode

internal fun playerBackOpensCurrentEpisodeStreams(
    episodeChangedInPlace: Boolean,
    autoPlayEnabled: Boolean
): Boolean = episodeChangedInPlace && !autoPlayEnabled

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel = hiltViewModel(),
    onBackPress: (currentVideoId: String?, currentSeason: Int?, currentEpisode: Int?, autoPlayEnabled: Boolean, playbackCompleted: Boolean) -> Unit,
    onPlaybackErrorBack: () -> Unit = { onBackPress(null, null, null, false, false) },
    onPlaybackEnded: ((nextVideoId: String?, nextSeason: Int?, nextEpisode: Int?, exitReason: PlayerExitReason?) -> Unit)? = null,
    onPlayRecommendation: (PostPlayRecommendation, manualSelection: Boolean) -> Unit = { _, _ -> },
    onOpenRecommendationDetails: (PostPlayRecommendation) -> Unit = {}
) {
    val savedUiState by viewModel.uiState.collectAsState()
    val isV2 = LocalV2Appearance.current != null
    val uiState = savedUiState.copy(controlLayout = com.nuvio.tv.data.local.PlayerControlLayout.effective(savedUiState.controlLayout, isV2))
    val playerBadges = rememberPlayerBadges(viewModel, uiState)
    PartyPlayerBinding(viewModel)
    val partyState by rememberPartyRuntime().state.collectAsState()
    val cinematicGlass = LocalV2Appearance.current?.visualStyle == com.nuvio.tv.domain.model.VisualStyle.CINEMATIC_GLASS
    val v2Focus = remember { V2PlayerFocusState() }
    val customFocus = remember { PlayerCustomFocusState() }
    val nativeVideoSelected by viewModel.controller.nativeVideoSelection.changes.collectAsState()
    val controlsTimeline by remember(viewModel) {
        viewModel.playbackTimeline.map { PlaybackTimelineState(duration = it.duration, isLive = it.isLive) }.distinctUntilChanged()
    }.collectAsState(initial = PlaybackTimelineState())
    val customAvailable = playerControlAvailability(uiState, controlsTimeline, nativeVideoSelected,
        viewModel.exoPlayer?.isTunnelingEnabled == true)
    val customHasTimeline = !controlsTimeline.isLive && controlsTimeline.duration > 0L
    val remoteButtons by remember(viewModel) {
        viewModel.controller.deviceLocalPlayerPreferences.remoteButtons
    }.collectAsState(initial = RemoteButtonMap.DEFAULT)
    var remoteButtonBanner by remember { mutableStateOf<RemoteButtonBanner?>(null) }
    var remoteButtonBannerText by remember { mutableStateOf("") }
    val subtitleNudge = remember { SubtitleNudge() }
    var remoteSubtitleRestore by remember(uiState.currentVideoId) { mutableStateOf<Any?>(null) }
    val remoteClaim = remember { RemoteKeyClaim() }
    val remoteHold = remember { RemotePressTracker(ViewConfiguration.getLongPressTimeout().toLong()) }
    val postPlayRecommendationState by viewModel.postPlayRecommendationUiState.collectAsState()
    val effectiveAutoplayEnabled by viewModel.effectiveAutoplayEnabled.collectAsState(initial = false)
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val containerFocusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }
    val progressBarFocusRequester = remember { FocusRequester() }
    val episodesFocusRequester = remember { FocusRequester() }
    val streamsFocusRequester = remember { FocusRequester() }
    val sourceStreamsFocusRequester = remember { FocusRequester() }
    val skipIntroFocusRequester = remember { FocusRequester() }
    val streamInfoFocusRequester = remember { FocusRequester() }
    val postPlayRecommendationFocusRequester = remember { FocusRequester() }
    val postPlayRecommendationPlayerWindowFocusRequester = remember { FocusRequester() }
    var skipButtonActuallyVisible by remember { mutableStateOf(false) }
    var restoreStreamInfoFocus by remember { mutableStateOf(false) }
    var focusPlayAfterMoreBack by remember { mutableStateOf(false) }
    var hideControlsAfterStreamInfo by remember { mutableStateOf(false) }
    val nextEpisodeFocusRequester = remember { FocusRequester() }
    var subtitleDelayFocusTarget by remember { mutableStateOf(SubtitleDelayFocusTarget.SLIDER) }
    val subtitleDelayResetFocusRequester = remember { FocusRequester() }
    val subtitleDelaySyncLineFocusRequester = remember { FocusRequester() }
    var subtitleTimingConsumeNextConfirmKeyUp by remember { mutableStateOf(false) }
    // Measured "clear of the scrubber" bottom offset for the Skip button while controls
    // are visible, reported by PlayerControlsOverlay. Default is the safe flat-case value.
    var controlsSkipAnchor by remember { mutableStateOf(148.dp) }
    var reportCodeVisible by remember { mutableStateOf(false) }
    var exitDispatched by remember { mutableStateOf(false) }
    var externalHandoffInProgress by remember { mutableStateOf(false) }
    val autoPlayEnabledForBack = playerBackTreatsSkipAsAutoPlay(
        streamAutoPlayMode = uiState.streamAutoPlayMode,
        preferBingeGroupForNextEpisode = uiState.streamAutoPlayPreferBingeGroupForNextEpisode
    )

    val exitPlayer: () -> Unit = exitPlayer@{
        if (exitDispatched) return@exitPlayer
        exitDispatched = true
        val timeline = viewModel.playbackTimeline.value
        viewModel.stopAndRelease()
        val completed = postPlayRecommendationState.isVisible || uiState.playbackEnded ||
            (!timeline.isLive &&
                timeline.duration > 0L &&
                (timeline.currentPosition.toFloat() / timeline.duration.toFloat()) >= WatchProgress.COMPLETED_THRESHOLD)
        val (focusSeason, focusEpisode) = uiState.returnFocusSeasonEpisode(completed)
        onBackPress(
            uiState.currentVideoId,
            focusSeason,
            focusEpisode,
            autoPlayEnabledForBack,
            completed
        )
    }
    val exitPlayerFromError: () -> Unit = exitPlayerFromError@{
        if (exitDispatched) return@exitPlayerFromError
        exitDispatched = true
        viewModel.stopAndRelease()
        onPlaybackErrorBack()
    }
    val dismissStreamInfoOverlay = {
        viewModel.onEvent(PlayerEvent.OnDismissStreamInfo)
        if (hideControlsAfterStreamInfo) viewModel.hideControls()
        hideControlsAfterStreamInfo = false
    }
    val returnToPlayerFromPostPlay = {
        viewModel.returnToPlayerFromPostPlay()
    }

    val currentOnPlaybackEnded by rememberUpdatedState(onPlaybackEnded)
    val currentOnBackPress by rememberUpdatedState(onBackPress)
    val currentOnPlayRecommendation by rememberUpdatedState(onPlayRecommendation)
    val currentOnOpenRecommendationDetails by rememberUpdatedState(onOpenRecommendationDetails)
    val nextEpisodeForEndPrompt = uiState.nextEpisode?.takeIf {
        it.hasAired && !(it.released.isNullOrBlank() && it.available == false)
    }
    val shouldConfirmNextEpisodeOnEnd =
        uiState.playbackEnded &&
            uiState.error == null &&
            (uiState.streamAutoPlayMode != StreamAutoPlayMode.MANUAL ||
                uiState.streamAutoPlayPreferBingeGroupForNextEpisode) &&
            !uiState.streamAutoPlayNextEpisodeEnabled &&
            nextEpisodeForEndPrompt != null
    val returnToDetailsFromEndPrompt = {
        viewModel.stopAndRelease()
        val (focusSeason, focusEpisode) = uiState.returnFocusSeasonEpisode(completed = true)
        currentOnBackPress(
            uiState.currentVideoId,
            focusSeason,
            focusEpisode,
            true,
            true
        )
    }
    val continueToNextEpisodeFromEndPrompt = {
        val next = nextEpisodeForEndPrompt
        if (next != null) {
            viewModel.stopAndRelease()
            val cb = currentOnPlaybackEnded
            if (cb != null) {
                cb(next.videoId, next.season, next.episode, null)
            } else {
                val (focusSeason, focusEpisode) = uiState.returnFocusSeasonEpisode(completed = true)
                currentOnBackPress(
                    uiState.currentVideoId,
                    focusSeason,
                    focusEpisode,
                    false,
                    true
                )
            }
        }
    }

    LaunchedEffect(uiState.playbackIssueReportStatus, uiState.playbackIssueReportId) {
        if (uiState.playbackIssueReportStatus == PlaybackIssueReportStatus.Sent &&
            !uiState.playbackIssueReportId.isNullOrBlank()
        ) {
            reportCodeVisible = true
            viewModel.scheduleHideControls()
            viewModel.onUserInteraction()
            delay(5000)
            reportCodeVisible = false
        } else if (uiState.playbackIssueReportStatus != PlaybackIssueReportStatus.Sent) {
            reportCodeVisible = false
        }
    }

    val handleBackPress = handleBackPress@{
        if (externalHandoffInProgress) return@handleBackPress
        if (viewModel.cancelSeekSettle()) return@handleBackPress
        // "Generate thumbnails before play": Back = start watching now.
        if (SeekThumbnails.prepareUi.value !is SeekThumbnails.PrepareUi.Hidden) {
            SeekThumbnails.startWatchingNow()
            return@handleBackPress
        }
        if (postPlayRecommendationState.canReturnToPlayer && !uiState.playbackEnded) {
            returnToPlayerFromPostPlay()
            viewModel.hideControls()
        } else if (postPlayRecommendationState.isVisible || postPlayRecommendationState.isLoadingRecommendation) {
            exitPlayer()
        } else if (shouldConfirmNextEpisodeOnEnd) {
            returnToDetailsFromEndPrompt()
        } else if (uiState.error != null) {
            exitPlayerFromError()
        } else if (isV2 && uiState.showSpeedDialog) {
            // V2 Speed is Activity-local, so the root key dispatcher sees Back.
            // Original's separate Dialog window continues handling its own Back.
            viewModel.onEvent(PlayerEvent.OnDismissTransientOverlay)
        } else if (uiState.showAudioOverlay || uiState.showSubtitleOverlay) {
            viewModel.onEvent(PlayerEvent.OnDismissTransientOverlay)
        } else if (uiState.showStreamInfoOverlay) {
            dismissStreamInfoOverlay()
        } else if (uiState.showPartyPanel) {
            viewModel.onEvent(PlayerEvent.OnDismissPartyPanel)
        } else if (uiState.showPauseOverlay) {
            viewModel.onEvent(PlayerEvent.OnCloseDetails)
        } else if (uiState.showMoreDialog) {
            focusPlayAfterMoreBack = true
            viewModel.onEvent(PlayerEvent.OnDismissMoreDialog)
        } else if (uiState.showSubtitleTimingDialog) {
            viewModel.onEvent(PlayerEvent.OnDismissSubtitleTimingDialog)
        } else if (uiState.showSubtitleDelayOverlay) {
            viewModel.onEvent(PlayerEvent.OnHideSubtitleDelayOverlay)
        } else if (uiState.showSubtitleStylePanel) {
            viewModel.onEvent(PlayerEvent.OnDismissSubtitleStylePanel)
        } else if (uiState.showSourcesPanel) {
            if (uiState.currentStreamUrl.isNullOrBlank()) {
                exitPlayer()
            } else {
                viewModel.onEvent(PlayerEvent.OnDismissSourcesPanel)
            }
        } else if (uiState.showChaptersPanel) {
            viewModel.onEvent(PlayerEvent.OnDismissChaptersPanel)
        } else if (uiState.showEpisodesPanel) {
            if (uiState.showEpisodeStreams) {
                viewModel.onEvent(PlayerEvent.OnBackFromEpisodeStreams)
            } else {
                viewModel.onEvent(PlayerEvent.OnDismissEpisodesPanel)
            }
        } else if (uiState.postPlayMode is PostPlayMode.AutoPlay &&
            postPlayRecommendationState.recommendation == null
        ) {
            viewModel.onEvent(PlayerEvent.OnDismissNextEpisodeCard)
            // Transfer focus to skip button if it's still visible
            if (skipButtonActuallyVisible) {
                runCatching { skipIntroFocusRequester.requestFocus() }
            }
        } else if (skipButtonActuallyVisible && !uiState.showControls) {
            viewModel.onEvent(PlayerEvent.OnDismissSkipIntro)
        } else if (uiState.postPlayMode is PostPlayMode.StillWatching) {
            viewModel.onEvent(PlayerEvent.OnDismissStillWatchingPrompt)
        } else if (uiState.showControls) {
            viewModel.hideControls()
        } else {
            exitPlayer()
        }
    }

    BackHandler {
        handleBackPress()
    }

    LaunchedEffect(
        uiState.playbackEnded,
        uiState.error,
        uiState.pendingExitReason,
        shouldConfirmNextEpisodeOnEnd,
        postPlayRecommendationState.blocksNaturalCompletion
    ) {
        val explicitReason = uiState.pendingExitReason
        val shouldDispatchNatural = uiState.playbackEnded &&
            uiState.error == null &&
            uiState.postPlayMode?.blocksNaturalCompletion() != true &&
            !postPlayRecommendationState.blocksNaturalCompletion &&
            !shouldConfirmNextEpisodeOnEnd &&
            explicitReason == null
        when {
            explicitReason == PlayerExitReason.StillWatchingPrompt -> {
                viewModel.stopAndRelease()
                val cb = currentOnPlaybackEnded
                if (cb != null) {
                    cb(null, null, null, PlayerExitReason.StillWatchingPrompt)
                } else {
                    val (focusSeason, focusEpisode) = uiState.returnFocusSeasonEpisode(completed = true)
                    currentOnBackPress(
                        uiState.currentVideoId,
                        focusSeason,
                        focusEpisode,
                        autoPlayEnabledForBack,
                        true
                    )
                }
                viewModel.consumePendingExitReason()
            }
            shouldDispatchNatural -> {
                viewModel.stopAndRelease()
                val next = uiState.nextEpisode?.takeIf {
                    it.hasAired && !(it.released.isNullOrBlank() && it.available == false)
                }
                val cb = currentOnPlaybackEnded
                if (cb != null) {
                    cb(next?.videoId, next?.season, next?.episode, null)
                } else {
                    val (focusSeason, focusEpisode) = uiState.returnFocusSeasonEpisode(completed = true)
                    currentOnBackPress(
                        uiState.currentVideoId,
                        focusSeason,
                        focusEpisode,
                        autoPlayEnabledForBack,
                        true
                    )
                }
            }
        }
    }

    // Handle lifecycle events
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    SeekThumbnails.hostResumed = false
                    NextEpisodeThumbs.cancel()
                    viewModel.pauseForLifecycle()
                }
                Lifecycle.Event.ON_STOP -> {
                    // This back stack entry also stops when the player is navigated away from or
                    // exited, where the normal release has to run; only act when the app itself
                    // left the foreground.
                    val hostActivity = context as? androidx.activity.ComponentActivity
                    if (
                        hostActivity != null &&
                        isAppBackgroundStop(
                            isFinishing = hostActivity.isFinishing,
                            isChangingConfigurations = hostActivity.isChangingConfigurations,
                            hostState = hostActivity.lifecycle.currentState
                        )
                    ) {
                        viewModel.releaseForBackground()
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    SeekThumbnails.hostResumed = true
                    NextEpisodeThumbs.resume()
                    // Re-create the MediaSession so media controls work in foreground.
                    // Don't auto-resume playback — let the user press play.
                    viewModel.resumeForLifecycle()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Bump UI thread priority to THREAD_PRIORITY_DISPLAY (-4) while the player is active.
    // The Linux scheduler favors the thread under CPU pressure (background addon prefetch,
    // Trakt sync, image decode), reducing dropped frames at scene cuts and during decoder
    // spin-up. Restored on dispose so non-player screens stay at default priority.
    DisposableEffect(Unit) {
        val tid = android.os.Process.myTid()
        val previousPriority = runCatching { android.os.Process.getThreadPriority(tid) }.getOrDefault(0)
        runCatching {
            android.os.Process.setThreadPriority(tid, android.os.Process.THREAD_PRIORITY_DISPLAY)
        }
        onDispose {
            runCatching {
                android.os.Process.setThreadPriority(tid, previousPriority)
            }
        }
    }

    // Seek thumbnails follow the stream and the engine; ExoPlayer only (with MPV the provider returns null).
    val seekThumbsEnabled = SeekThumbnailPreferences.enabledFlow(context).collectAsState(initial = false)
    val thumbsOnMpv = uiState.internalPlayerEngine == InternalPlayerEngine.MVP_PLAYER
    val lastThumbRun = remember { mutableStateOf<Pair<String?, Boolean>?>(null) }
    LaunchedEffect(seekThumbsEnabled.value, uiState.currentStreamUrl, uiState.isTorrentStream, thumbsOnMpv) {
        val thumbSourceUrl = uiState.currentStreamUrl
        val previousRun = lastThumbRun.value
        SeekThumbnails.stopSession(handBackPlayer = handsPlayerBackOnRestart(previousRun, thumbSourceUrl, thumbsOnMpv))
        val engineChanged = previousRun != null && previousRun.first == thumbSourceUrl && previousRun.second != thumbsOnMpv
        lastThumbRun.value = thumbSourceUrl to thumbsOnMpv
        if (!seekThumbsEnabled.value || thumbSourceUrl.isNullOrBlank() || thumbsOnMpv) {
            SeekThumbnails.dropServerMemory()
            return@LaunchedEffect
        }
        // A media server's own thumbnails first (also for its HLS transcodes); without them our own engine runs.
        val serverCheckStart = SystemClock.elapsedRealtime()
        val serverThumbs = viewModel.serverThumbnails(thumbSourceUrl)
        if (serverThumbs != null && SeekThumbnails.startServerWhenEligible(
                context = context.applicationContext,
                source = serverThumbs,
                playerProvider = { viewModel.exoPlayer }
            )
        ) return@LaunchedEffect
        // A long server check means the film is already playing: our own engine must not hold it now.
        val serverCheckLong = SystemClock.elapsedRealtime() - serverCheckStart > SERVER_CHECK_HOLD_LIMIT_MS
        if (!isThumbnailSource(thumbSourceUrl, viewModel.currentStreamMimeType())) {
            SeekThumbnails.dropServerMemory()
            return@LaunchedEffect
        }
        val copySources = viewModel.thumbnailCopySources()
        // A torrent's own pieces stay with playback: its thumbnails come from an HTTP or Debrid copy of the title.
        val torrent = uiState.isTorrentStream
        val usenet = !torrent && copySources.playingIsUsenet(thumbSourceUrl)
        SeekThumbnails.startWhenEligible(
            context = context.applicationContext,
            source = ThumbSource(
                url = thumbSourceUrl,
                headers = viewModel.getCurrentHeaders(),
                identity = viewModel.seekThumbnailIdentity() ?: uiState.title,
                fileSizeHint = viewModel.getCurrentFileSizeBytes(),
                holdBeforePlay = !engineChanged && !serverCheckLong && !viewModel.isAutoPlayedEpisode(),
                playbackConnections = viewModel.playbackConnectionCount(),
                identityExact = viewModel.seekThumbnailIdentityExact(),
                copySources = copySources,
                usenet = usenet,
                mediaServer = viewModel.isServerStream(thumbSourceUrl),
                torrent = torrent,
                kindNote = { viewModel.seekThumbnailKindNote(thumbSourceUrl, usenet) },
                settingsNote = { viewModel.seekThumbnailSettingsNote() },
            ),
            playerProvider = { viewModel.exoPlayer },
            reserveProvider = { viewModel.seekThumbnailBufferReserve() },
            pauseForPrepare = { viewModel.pauseForSeekThumbnails() },
            letGoForPrepare = { viewModel.letGoForSeekThumbnails() },
            loadAgainAfterPrepare = { play -> viewModel.loadAgainAfterSeekThumbnails(play) },
            skipReason = { viewModel.seekThumbnailSkipReason() }
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            SeekThumbnails.playerClosed()
            NextEpisodeThumbs.cancel()
        }
    }
    val activity = LocalContext.current as? android.app.Activity
    LaunchedEffect(activity) {
        viewModel.attachHostActivity(activity)
        viewModel.startInitialPlaybackIfNeeded()
    }
    DisposableEffect(activity) {
        onDispose {
            viewModel.attachHostActivity(null)
        }
    }
    LaunchedEffect(uiState.frameRateMatchingMode) {
        if (activity != null &&
            uiState.frameRateMatchingMode == com.nuvio.tv.data.local.FrameRateMatchingMode.OFF
        ) {
            com.nuvio.tv.core.player.FrameRateUtils.restoreOriginalDisplayMode(activity)
        }
    }
    // Restore original display mode when leaving the player
    val currentFrameRateMatchingMode by rememberUpdatedState(uiState.frameRateMatchingMode)
    DisposableEffect(activity) {
        onDispose {
            if (activity != null) {
                if (currentFrameRateMatchingMode == com.nuvio.tv.data.local.FrameRateMatchingMode.START_STOP) {
                    com.nuvio.tv.core.player.FrameRateUtils.restoreOriginalDisplayMode(activity)
                } else {
                    com.nuvio.tv.core.player.FrameRateUtils.cleanupDisplayListener()
                    com.nuvio.tv.core.player.FrameRateUtils.clearOriginalDisplayMode()
                }
            }
        }
    }

    var seenPauseFocus by remember { mutableStateOf(uiState.pauseFocusRequest) }
    val keyPresses = remember { intArrayOf(0) }
    val focusOnControl = remember { booleanArrayOf(false) }
    // Request focus for key events when controls visibility or panel state changes
    LaunchedEffect(
        uiState.showControls,
        uiState.showEpisodesPanel,
        uiState.showChaptersPanel,
        uiState.showSourcesPanel,
        uiState.showSubtitleStylePanel,
        uiState.showSubtitleDelayOverlay,
        uiState.showSubtitleTimingDialog,
        uiState.showAudioOverlay,
        uiState.showSubtitleOverlay,
        uiState.showSpeedDialog,
        (isV2 || uiState.controlLayout != null) && uiState.showStreamInfoOverlay,
        uiState.controlLayout == null,
        if (uiState.controlLayout?.focusOrder(customAvailable)?.isEmpty() == true) customHasTimeline else null,
        shouldConfirmNextEpisodeOnEnd,
        postPlayRecommendationState.isVisible,
        uiState.pauseFocusRequest,
    ) {
        val pauseRequest = uiState.pauseFocusRequest
        val pauseSeen = seenPauseFocus
        val pressesAtStart = keyPresses[0]
        seenPauseFocus = pauseRequest
        if (!uiState.showControls) customFocus.controlsHidden(panelOpen = uiState.showEpisodesPanel || uiState.showChaptersPanel ||
            uiState.showSourcesPanel || uiState.showAudioOverlay || uiState.showSubtitleOverlay ||
            uiState.showSubtitleStylePanel || uiState.showSubtitleDelayOverlay || uiState.showSubtitleTimingDialog ||
            uiState.showSpeedDialog || uiState.showStreamInfoOverlay || uiState.showPartyPanel)
        if (shouldConfirmNextEpisodeOnEnd || postPlayRecommendationState.isVisible) return@LaunchedEffect
        if (uiState.error != null) return@LaunchedEffect
        if (uiState.showControls && !uiState.showEpisodesPanel && !uiState.showChaptersPanel && !uiState.showSourcesPanel &&
            !uiState.showAudioOverlay && !uiState.showSubtitleOverlay &&
            !uiState.showSubtitleStylePanel && !uiState.showSubtitleDelayOverlay &&
            !uiState.showSubtitleTimingDialog &&
            !uiState.showSpeedDialog && !((isV2 || uiState.controlLayout != null) && uiState.showStreamInfoOverlay)
        ) {
            // Let the returning control deck attach after the outgoing panel fades.
            val returning = if (isV2 && uiState.controlLayout == null) v2Focus.pendingReturn else null
            if (returning == V2PlayerControl.SPEED) {
                viewModel.onEvent(PlayerEvent.OnShowMoreDialog)
            }
            delay(250)
            val returningLayout = uiState.controlLayout
            val settle = PauseOnTimeline.afterSettle(
                pauseRequest = pauseRequest,
                seenRequest = pauseSeen,
                hasTimeline = customHasTimeline,
                thumbnailsOn = seekThumbsEnabled.value,
                pressedSincePause = keyPresses[0] != pressesAtStart,
                onControl = focusOnControl[0],
            )
            when {
                settle == PauseOnTimeline.Settle.STAY -> Unit
                settle == PauseOnTimeline.Settle.TIMELINE -> runCatching { progressBarFocusRequester.requestFocus() }
                returningLayout != null -> customFocus.restore(returningLayout, customAvailable)
                // The utility row may have scrolled or been rebuilt while the
                // panel was open. Let it attach the item before requesting focus.
                returning != null -> v2Focus.requestedControl = returning
                else -> runCatching { playPauseFocusRequester.requestFocus() }
            }
            v2Focus.pendingReturn = null
        } else if (!uiState.showControls) {
            // When controls are hidden, let skip intro button take focus if visible
            val skipVisible = uiState.activeSkipInterval != null && !uiState.skipIntervalDismissed
            val nextEpisodeVisible = uiState.postPlayMode is PostPlayMode.AutoPlay
            if (!skipVisible && !nextEpisodeVisible) {
                try {
                    containerFocusRequester.requestFocus()
                } catch (e: Exception) {
                    // Focus requester may not be ready yet
                }
            }
            // If skip or next episode card is visible, their own LaunchedEffect will request focus
        }
    }

    // Initial focus on container - the LaunchedEffect above will handle focusing controls
    LaunchedEffect(Unit) {
        if (uiState.error == null) {
            containerFocusRequester.requestFocus()
        }
    }
    val prepareVisible by remember {
        derivedStateOf { SeekThumbnails.prepareUi.value !is SeekThumbnails.PrepareUi.Hidden }
    }
    var prepareWasVisible by remember { mutableStateOf(false) }
    // A key-down that moved focus; its key-up must not click the control it landed on.
    var swallowKeyUp by remember { mutableStateOf(-1) }
    // A failed stream must not stay hidden behind the overlay.
    LaunchedEffect(uiState.error != null, prepareVisible) {
        if (uiState.error != null && prepareVisible) {
            SeekThumbnails.startWatchingNow()
        }
    }
    LaunchedEffect(prepareVisible) {
        if (prepareVisible) {
            prepareWasVisible = true
        } else if (prepareWasVisible) {
            prepareWasVisible = false
            if (uiState.error == null) runCatching { containerFocusRequester.requestFocus() }
        }
    }
    LaunchedEffect(uiState.showSubtitleDelayOverlay) {
        subtitleDelayFocusTarget = SubtitleDelayFocusTarget.SLIDER
    }
    LaunchedEffect(uiState.showSubtitleTimingDialog) {
        if (!uiState.showSubtitleTimingDialog) {
            subtitleTimingConsumeNextConfirmKeyUp = false
        }
    }
    LaunchedEffect(uiState.showStreamInfoOverlay, uiState.showControls, uiState.showMoreDialog) {
        if (uiState.controlLayout == null && !uiState.showStreamInfoOverlay && uiState.showControls && uiState.showMoreDialog && restoreStreamInfoFocus) {
            delay(250)
            runCatching { streamInfoFocusRequester.requestFocus() }
            restoreStreamInfoFocus = false
        }
    }
    val moreDialogOpen by rememberUpdatedState(uiState.showMoreDialog)
    val controlsVisibleForMoreBack by rememberUpdatedState(uiState.showControls)
    val playerHasError by rememberUpdatedState(uiState.error != null)
    LaunchedEffect(focusPlayAfterMoreBack) {
        if (!focusPlayAfterMoreBack) return@LaunchedEffect
        runCatching { playPauseFocusRequester.requestFocus() }
        delay(200)
        if (!moreDialogOpen && controlsVisibleForMoreBack && !playerHasError) {
            playPauseFocusRequester.requestFocusAfterFrames(frames = 0)
        }
        focusPlayAfterMoreBack = false
    }

    val openInExternalPlayer: () -> Unit = {
        if (!externalHandoffInProgress) {
            externalHandoffInProgress = true
            val timeline = viewModel.playbackTimeline.value
            val completed = !timeline.isLive &&
                timeline.duration > 0L &&
                (timeline.currentPosition.toFloat() / timeline.duration.toFloat()) >= WatchProgress.COMPLETED_THRESHOLD
            viewModel.launchInExternalPlayer(context, timeline.currentPosition) { launched ->
                externalHandoffInProgress = false
                if (launched && !exitDispatched) {
                    exitDispatched = true
                    val (focusSeason, focusEpisode) = uiState.returnFocusSeasonEpisode(completed)
                    currentOnBackPress(
                        uiState.currentVideoId,
                        focusSeason,
                        focusEpisode,
                        autoPlayEnabledForBack,
                        completed
                    )
                }
            }
        }
    }
    val remoteButtonsReady = uiState.error == null &&
        !uiState.showEpisodesPanel && !uiState.showChaptersPanel && !uiState.showSourcesPanel &&
        !uiState.showAudioOverlay && !uiState.showSubtitleOverlay &&
        !uiState.showSubtitleStylePanel && !uiState.showSpeedDialog &&
        !uiState.showSubtitleDelayOverlay && !uiState.showSubtitleTimingDialog &&
        !uiState.showMoreDialog && !uiState.showStreamInfoOverlay && !uiState.showPartyPanel &&
        !shouldConfirmNextEpisodeOnEnd &&
        uiState.postPlayMode !is PostPlayMode.StillWatching &&
        !postPlayRecommendationState.isVisible &&
        !postPlayRecommendationState.isLoadingRecommendation &&
        !postPlayRecommendationState.isTrailerPlaying
    val showSubtitleNudge: (Int) -> Unit = { offset ->
        viewModel.onEvent(PlayerEvent.OnSetSubtitleVerticalOffset(offset))
        val text = "${context.getString(R.string.subtitle_style_bottom_offset)}: $offset"
        remoteButtonBanner = RemoteButtonBanner(text)
        remoteButtonBannerText = text
    }
    // Runs on the first key-down of a button. True claims the button, so its repeats and release stay here too.
    val runRemoteButton: (RemoteButtonBinding, KeyEvent) -> Boolean = runRemoteButton@{ binding, native ->
        val job = binding.job
        fun banner(text: String) {
            remoteButtonBanner = RemoteButtonBanner(text)
            remoteButtonBannerText = text
        }
        fun unavailable(): Boolean {
            // Next and Previous kept their old meaning (live edge, restart) where the job has nothing to do.
            if (binding == RemoteButtonMap.DEFAULTS[native.keyCode] &&
                (native.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || native.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            ) return false
            banner(context.getString(R.string.remote_buttons_banner_unavailable, remoteJobLabel(context, job)))
            return true
        }
        fun whenAvailable(action: PlayerControlAction, event: PlayerEvent): Boolean {
            if (action !in customAvailable) return unavailable()
            viewModel.onEvent(event)
            return true
        }
        val skipActive = skipButtonActuallyVisible ||
            (uiState.activeSkipInterval != null && !uiState.skipIntervalDismissed)
        if (uiState.showPauseOverlay && !job.keepsPauseOverlay) {
            viewModel.onEvent(PlayerEvent.OnDismissPauseOverlay)
        }
        when (job) {
            RemoteButtonJob.NOTHING, RemoteButtonJob.LEAVE -> true
            RemoteButtonJob.SUBTITLES_UP, RemoteButtonJob.SUBTITLES_DOWN -> {
                showSubtitleNudge(subtitleNudge.press(uiState.subtitleStyle.verticalOffset,
                    up = job == RemoteButtonJob.SUBTITLES_UP, nowMs = native.eventTime))
                true
            }
            RemoteButtonJob.SEEK_FORWARD, RemoteButtonJob.SEEK_BACK -> {
                viewModel.onEvent(scrubEvent(native, forward = job == RemoteButtonJob.SEEK_FORWARD))
                true
            }
            RemoteButtonJob.PLAY_PAUSE -> {
                viewModel.onEvent(PlayerEvent.OnPlayPause)
                true
            }
            RemoteButtonJob.STATS -> {
                viewModel.onEvent(PlayerEvent.OnTogglePlaybackStats)
                true
            }
            RemoteButtonJob.NIGHT_MODE -> {
                val label = context.getString(R.string.audio_night_mode_label)
                if (!uiState.isAudioAmplificationAvailable) {
                    banner("$label · ${context.getString(R.string.audio_night_mode_unavailable)}")
                } else {
                    val next = uiState.nightMode.nextForKey(uiState.nightModeLastOn)
                    viewModel.onEvent(PlayerEvent.OnSetNightMode(next))
                    banner(context.getString(R.string.remote_buttons_banner_night_mode, context.getString(nightModeLabelRes(next))))
                }
                true
            }
            RemoteButtonJob.INFO -> {
                hideControlsAfterStreamInfo = !uiState.showControls
                restoreStreamInfoFocus = uiState.showControls && !isV2 && uiState.controlLayout == null
                viewModel.onEvent(PlayerEvent.OnShowStreamInfo)
                true
            }
            RemoteButtonJob.SHOW_DETAILS -> {
                if (!uiState.showPauseOverlay && (uiState.showLoadingOverlay || uiState.playbackEnded)) {
                    return@runRemoteButton unavailable()
                }
                viewModel.onEvent(PlayerEvent.OnToggleDetails)
                true
            }
            RemoteButtonJob.SOURCES -> {
                viewModel.onEvent(PlayerEvent.OnShowSourcesPanel)
                true
            }
            RemoteButtonJob.SPEED -> {
                viewModel.onEvent(PlayerEvent.OnShowSpeedDialog)
                true
            }
            RemoteButtonJob.WATCH_PARTY -> {
                viewModel.onEvent(PlayerEvent.OnShowPartyPanel)
                true
            }
            RemoteButtonJob.EPISODES -> whenAvailable(PlayerControlAction.EPISODES, PlayerEvent.OnShowEpisodesPanel)
            RemoteButtonJob.CHAPTERS_LIST -> whenAvailable(PlayerControlAction.CHAPTERS, PlayerEvent.OnShowChaptersPanel)
            RemoteButtonJob.NEXT_CHAPTER -> whenAvailable(PlayerControlAction.CHAPTERS, PlayerEvent.OnNextChapter)
            RemoteButtonJob.PREVIOUS_CHAPTER -> whenAvailable(PlayerControlAction.CHAPTERS, PlayerEvent.OnPreviousChapter)
            RemoteButtonJob.AUDIO -> whenAvailable(PlayerControlAction.AUDIO, PlayerEvent.OnShowAudioOverlay)
            RemoteButtonJob.SUBTITLES -> whenAvailable(PlayerControlAction.SUBTITLES, PlayerEvent.OnShowSubtitleOverlay)
            RemoteButtonJob.ASPECT -> whenAvailable(PlayerControlAction.ASPECT, PlayerEvent.OnToggleAspectRatio)
            RemoteButtonJob.NEXT_EPISODE -> whenAvailable(PlayerControlAction.NEXT_EPISODE, PlayerEvent.OnPlayNextEpisode)
            RemoteButtonJob.RESTART -> whenAvailable(PlayerControlAction.RESTART, PlayerEvent.OnSeekTo(0L))
            RemoteButtonJob.EXTERNAL -> {
                if (PlayerControlAction.EXTERNAL in customAvailable) {
                    openInExternalPlayer()
                    true
                } else unavailable()
            }
            RemoteButtonJob.SKIP_INTRO_OR_NEXT -> when {
                skipActive -> {
                    viewModel.onEvent(PlayerEvent.OnSkipIntro)
                    true
                }
                PlayerControlAction.NEXT_EPISODE in customAvailable -> {
                    viewModel.onEvent(PlayerEvent.OnPlayNextEpisode)
                    true
                }
                else -> unavailable()
            }
            RemoteButtonJob.SKIP_FORWARD, RemoteButtonJob.SKIP_BACK -> {
                if (!customHasTimeline) return@runRemoteButton unavailable()
                val forward = job == RemoteButtonJob.SKIP_FORWARD
                viewModel.onEvent(PlayerEvent.OnSeekBy(if (forward) binding.skipMs else -binding.skipMs))
                banner(
                    context.getString(
                        if (forward) R.string.remote_buttons_banner_skip_forward else R.string.remote_buttons_banner_skip_back,
                        remoteSkipSizeLabel(context, binding.skipMs)
                    )
                )
                true
            }
            RemoteButtonJob.JUMP_TO_PERCENT -> {
                val number = RemoteButtonMap.numberOf(native.keyCode)
                if (number == null || !customHasTimeline) return@runRemoteButton unavailable()
                viewModel.onEvent(PlayerEvent.OnSeekTo(controlsTimeline.duration * number / 10))
                banner(context.getString(R.string.remote_buttons_banner_jump, number * 10))
                true
            }
            RemoteButtonJob.NEXT_AUDIO -> {
                val server = uiState.serverAudioTracks.isNotEmpty()
                val tracks = if (server) uiState.serverAudioTracks else uiState.audioTracks
                when {
                    tracks.isEmpty() -> unavailable()
                    tracks.size == 1 -> {
                        banner(context.getString(R.string.remote_buttons_banner_one_audio))
                        true
                    }
                    else -> {
                        val current = tracks.indexOfFirst {
                            if (server) it.isSelected else it.index == uiState.selectedAudioTrackIndex || it.isSelected
                        }
                        val next = tracks[(current + 1).mod(tracks.size)]
                        viewModel.onEvent(PlayerEvent.OnSelectAudioTrack(next.index))
                        banner(context.getString(R.string.remote_buttons_banner_audio, next.remoteLabel()))
                        true
                    }
                }
            }
            RemoteButtonJob.SUBTITLES_TOGGLE -> {
                val server = uiState.serverSubtitleTracks.isNotEmpty()
                val tracks = if (server) uiState.serverSubtitleTracks else uiState.subtitleTracks
                val addon = uiState.selectedAddonSubtitle
                val internal = if (server) {
                    tracks.firstOrNull { it.isSelected }
                } else {
                    tracks.firstOrNull { it.index == uiState.selectedSubtitleTrackIndex }
                }
                if (addon != null || internal != null) {
                    remoteSubtitleRestore = addon ?: internal
                    viewModel.onEvent(PlayerEvent.OnDisableSubtitlesQuietly)
                    banner(context.getString(R.string.remote_buttons_banner_subtitles_off))
                    return@runRemoteButton true
                }
                val restore = remoteSubtitleRestore
                val wanted = with(uiState.subtitleStyle) {
                    listOfNotNull(preferredLanguageBeforeOff, preferredLanguage, secondaryPreferredLanguage, tertiaryPreferredLanguage)
                        .filter { it != "none" && it != SUBTITLE_LANGUAGE_FORCED && it != SubtitleLanguageOption.DEVICE }
                }
                val pick: Any? = when {
                    restore is TrackInfo && tracks.any { it.index == restore.index } -> restore
                    restore is Subtitle && restore in uiState.addonSubtitles -> restore
                    else -> wanted.firstNotNullOfOrNull { language ->
                        tracks.firstOrNull { !it.isForced && remoteLanguageMatches(it.language, language) }
                            ?: uiState.addonSubtitles.firstOrNull { remoteLanguageMatches(it.lang, language) }
                    } ?: tracks.firstOrNull { !it.isForced } ?: uiState.addonSubtitles.firstOrNull()
                }
                when (pick) {
                    is TrackInfo -> {
                        viewModel.onEvent(PlayerEvent.OnSelectSubtitleTrack(pick.index, openPanel = false))
                        banner(context.getString(R.string.remote_buttons_banner_subtitles_on, pick.remoteLabel()))
                        true
                    }
                    is Subtitle -> {
                        viewModel.onEvent(PlayerEvent.OnSelectAddonSubtitle(pick, openPanel = false))
                        banner(context.getString(R.string.remote_buttons_banner_subtitles_on, pick.lang))
                        true
                    }
                    else -> unavailable()
                }
            }
        }
    }
    // Repeats and the release of a claimed button.
    val finishRemoteButton: (RemoteButtonBinding, KeyEvent) -> Unit = { binding, native ->
        val seek = binding.job == RemoteButtonJob.SEEK_FORWARD || binding.job == RemoteButtonJob.SEEK_BACK
        val nudge = binding.job == RemoteButtonJob.SUBTITLES_UP || binding.job == RemoteButtonJob.SUBTITLES_DOWN
        when (native.action) {
            KeyEvent.ACTION_DOWN -> if (seek) {
                viewModel.onEvent(scrubEvent(native, forward = binding.job == RemoteButtonJob.SEEK_FORWARD))
            } else if (nudge) {
                subtitleNudge.repeat(up = binding.job == RemoteButtonJob.SUBTITLES_UP, nowMs = native.eventTime)
                    ?.let(showSubtitleNudge)
            }
            KeyEvent.ACTION_UP -> when {
                seek -> viewModel.onEvent(PlayerEvent.OnCommitPreviewSeek)
                binding.job == RemoteButtonJob.LEAVE && !externalHandoffInProgress -> exitPlayer()
            }
        }
    }

    val transparentLetterbox = uiState.transparentLetterbox &&
        uiState.internalPlayerEngine != InternalPlayerEngine.MVP_PLAYER
    DisposableEffect(transparentLetterbox) {
        if (transparentLetterbox) PlayerWindowBackdrop.acquireTransparent()
        onDispose { if (transparentLetterbox) PlayerWindowBackdrop.releaseTransparent() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (transparentLetterbox) Modifier else Modifier.background(Color.Black))
            .focusRequester(containerFocusRequester)
            .onFocusChanged { focusOnControl[0] = it.hasFocus && !it.isFocused }
            .focusable(enabled = uiState.error == null)
            .onPreviewKeyEvent { keyEvent ->
                if (PauseOnTimeline.firstPress(keyEvent.nativeKeyEvent.action, keyEvent.nativeKeyEvent.repeatCount)) {
                    keyPresses[0]++
                }
                when (PauseOnTimeline.release(swallowKeyUp, keyEvent.nativeKeyEvent.keyCode,
                    keyEvent.nativeKeyEvent.action, keyEvent.nativeKeyEvent.repeatCount)) {
                    PauseOnTimeline.Release.SWALLOW -> {
                        swallowKeyUp = -1
                        return@onPreviewKeyEvent true
                    }
                    PauseOnTimeline.Release.FORGET -> swallowKeyUp = -1
                    PauseOnTimeline.Release.PASS -> Unit
                }
                // While the thumbnail overlay shows, every key but Back belongs to it.
                if (prepareVisible &&
                    keyEvent.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_BACK &&
                    keyEvent.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_ESCAPE &&
                    (!PrepareOverlayFocus.hasFocus || keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_CAPTIONS)
                ) {
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        PrepareOverlayFocus.grab()
                        swallowKeyUp = keyEvent.nativeKeyEvent.keyCode
                    }
                    return@onPreviewKeyEvent true
                }
                // Consume the confirm KEY_UP that opened the subtitle timing dialog before
                // the newly focused "Sync" button can treat it as a second click. Preview
                // is required: after open, focus moves into the dialog so onKeyEvent on
                // this container no longer receives the release.
                if (subtitleTimingConsumeNextConfirmKeyUp &&
                    keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP &&
                    (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
                        keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                ) {
                    subtitleTimingConsumeNextConfirmKeyUp = false
                    return@onPreviewKeyEvent true
                }

                val postPlayHandlesBack = postPlayRecommendationState.isVisible ||
                    postPlayRecommendationState.isLoadingRecommendation ||
                    postPlayRecommendationState.isTrailerPlaying
                if (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ESCAPE ||
                    (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK && !postPlayHandlesBack)
                ) {
                    return@onPreviewKeyEvent when (keyEvent.nativeKeyEvent.action) {
                        KeyEvent.ACTION_DOWN -> true
                        KeyEvent.ACTION_UP -> {
                            handleBackPress()
                            true
                        }
                        else -> true
                    }
                }

                val remoteKey = keyEvent.nativeKeyEvent
                val firstDown = remoteKey.action == KeyEvent.ACTION_DOWN && remoteKey.repeatCount == 0
                remoteClaim.bindingFor(remoteKey.keyCode)?.let { binding ->
                    if (firstDown) {
                        remoteClaim.release()
                    } else {
                        if (remoteKey.action == KeyEvent.ACTION_UP) remoteClaim.release()
                        finishRemoteButton(binding, remoteKey)
                        return@onPreviewKeyEvent true
                    }
                }
                if (uiState.showStreamInfoOverlay && remoteKey.action == KeyEvent.ACTION_DOWN && remoteKey.repeatCount == 0) {
                    remoteButtons.overrideFor(remoteKey.keyCode)?.takeIf { it.job == RemoteButtonJob.INFO }?.let { binding ->
                        dismissStreamInfoOverlay()
                        remoteHold.forget(remoteKey.keyCode)
                        remoteClaim.claim(remoteKey.keyCode, binding)
                        return@onPreviewKeyEvent true
                    }
                }
                // A button with a hold job: its repeats and release decide between the hold and the press.
                if (firstDown) {
                    remoteHold.forget(remoteKey.keyCode)
                } else if (remoteHold.isTracking(remoteKey.keyCode)) {
                    val jobs = remoteHold.jobs
                    when (remoteHold.onKey(remoteKey)) {
                        RemotePressTracker.Outcome.HOLD -> jobs?.hold?.let { hold ->
                            if (runRemoteButton(hold, remoteKey)) {
                                if (remoteKey.action == KeyEvent.ACTION_UP) finishRemoteButton(hold, remoteKey)
                                else remoteClaim.claim(remoteKey.keyCode, hold)
                                remoteHold.reset()
                            }
                        }
                        RemotePressTracker.Outcome.PRESS -> {
                            val press = jobs?.press
                            if (press != null) {
                                if (runRemoteButton(press, remoteKey)) finishRemoteButton(press, remoteKey)
                            } else if (RemoteButtonMap.isHiddenControlsKey(remoteKey.keyCode)) {
                                viewModel.onEvent(
                                    if (uiState.showPauseOverlay) PlayerEvent.OnDismissPauseOverlay else PlayerEvent.OnToggleControls
                                )
                            }
                        }
                        else -> Unit
                    }
                    return@onPreviewKeyEvent true
                }
                if (remoteButtonsReady && firstDown) {
                    remoteButtons.takeOver(remoteKey.keyCode, controlsHidden = !uiState.showControls)?.let { jobs ->
                        if (jobs.hold != null) {
                            remoteHold.start(remoteKey.keyCode, remoteKey.eventTime, jobs)
                            return@onPreviewKeyEvent true
                        }
                        jobs.press?.let { binding ->
                            if (runRemoteButton(binding, remoteKey)) {
                                remoteClaim.claim(remoteKey.keyCode, binding)
                                return@onPreviewKeyEvent true
                            }
                        }
                    }
                }

                if (keyEvent.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_CAPTIONS) {
                    return@onPreviewKeyEvent false
                }

                if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_UP) {
                    return@onPreviewKeyEvent true
                }

                if (uiState.showSubtitleDelayOverlay) {
                    viewModel.onEvent(PlayerEvent.OnHideSubtitleDelayOverlay)
                } else if (
                    !uiState.showEpisodesPanel && !uiState.showChaptersPanel &&
                    !uiState.showSourcesPanel &&
                    !uiState.showAudioOverlay &&
                    !uiState.showSubtitleOverlay &&
                    !uiState.showSubtitleStylePanel &&
                    !uiState.showSubtitleTimingDialog &&
                    !uiState.showSpeedDialog
                ) {
                    viewModel.onEvent(PlayerEvent.OnShowSubtitleOverlay)
                }
                true
            }
            .onKeyEvent { keyEvent ->
                // KEY_UP confirm for Sync Line is consumed in onPreviewKeyEvent so it still
                // runs after focus moves into the timing dialog.
                if (uiState.showSubtitleDelayOverlay) {
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        when (subtitleDelayFocusTarget) {
                            SubtitleDelayFocusTarget.SLIDER -> {
                                when (keyEvent.nativeKeyEvent.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                                        viewModel.onEvent(PlayerEvent.OnAdjustSubtitleDelay(-SUBTITLE_DELAY_STEP_MS))
                                        return@onKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        viewModel.onEvent(PlayerEvent.OnAdjustSubtitleDelay(SUBTITLE_DELAY_STEP_MS))
                                        return@onKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                                        subtitleDelayFocusTarget = SubtitleDelayFocusTarget.SYNC_LINE
                                        return@onKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_CENTER,
                                    KeyEvent.KEYCODE_ENTER,
                                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                                    KeyEvent.KEYCODE_DPAD_UP -> {
                                        return@onKeyEvent true
                                    }
                                }
                            }
                            SubtitleDelayFocusTarget.RESET -> {
                                val towardSyncLine = if (isRtl) {
                                    KeyEvent.KEYCODE_DPAD_LEFT
                                } else {
                                    KeyEvent.KEYCODE_DPAD_RIGHT
                                }
                                val awayFromSyncLine = if (isRtl) {
                                    KeyEvent.KEYCODE_DPAD_RIGHT
                                } else {
                                    KeyEvent.KEYCODE_DPAD_LEFT
                                }
                                when (keyEvent.nativeKeyEvent.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_CENTER,
                                    KeyEvent.KEYCODE_ENTER,
                                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                        viewModel.onEvent(PlayerEvent.OnResetSubtitleDelay())
                                        subtitleDelayFocusTarget = SubtitleDelayFocusTarget.SLIDER
                                        return@onKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_UP -> {
                                        subtitleDelayFocusTarget = SubtitleDelayFocusTarget.SLIDER
                                        return@onKeyEvent true
                                    }
                                    towardSyncLine -> {
                                        subtitleDelayFocusTarget = SubtitleDelayFocusTarget.SYNC_LINE
                                        return@onKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_DOWN,
                                    awayFromSyncLine -> {
                                        return@onKeyEvent true
                                    }
                                }
                            }
                            SubtitleDelayFocusTarget.SYNC_LINE -> {
                                val towardReset = if (isRtl) {
                                    KeyEvent.KEYCODE_DPAD_RIGHT
                                } else {
                                    KeyEvent.KEYCODE_DPAD_LEFT
                                }
                                val awayFromReset = if (isRtl) {
                                    KeyEvent.KEYCODE_DPAD_LEFT
                                } else {
                                    KeyEvent.KEYCODE_DPAD_RIGHT
                                }
                                when (keyEvent.nativeKeyEvent.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_CENTER,
                                    KeyEvent.KEYCODE_ENTER,
                                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                        subtitleDelayFocusTarget = SubtitleDelayFocusTarget.SLIDER
                                        // KEY_DOWN open: swallow the trailing KEY_UP so the
                                        // dialog's Sync control does not treat it as a click.
                                        subtitleTimingConsumeNextConfirmKeyUp = true
                                        viewModel.onEvent(PlayerEvent.OnShowSubtitleTimingDialog)
                                        return@onKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_UP -> {
                                        subtitleDelayFocusTarget = SubtitleDelayFocusTarget.SLIDER
                                        return@onKeyEvent true
                                    }
                                    towardReset -> {
                                        subtitleDelayFocusTarget = SubtitleDelayFocusTarget.RESET
                                        return@onKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_DOWN,
                                    awayFromReset -> {
                                        return@onKeyEvent true
                                    }
                                }
                            }
                        }
                    }
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP &&
                        (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                            keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                    ) {
                        return@onKeyEvent true
                    }
                    if (keyEvent.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_BACK) {
                        // While open, consume all non-back keys to avoid accidental dismissal.
                        return@onKeyEvent true
                    }
                }

                // When a side panel or dialog is open, let it handle all keys
                val panelOrDialogOpen = uiState.showEpisodesPanel || uiState.showChaptersPanel || uiState.showSourcesPanel ||
                        uiState.showAudioOverlay || uiState.showSubtitleOverlay ||
                        uiState.showSubtitleStylePanel || uiState.showSpeedDialog ||
                        uiState.showSubtitleDelayOverlay || uiState.showSubtitleTimingDialog ||
                        uiState.showMoreDialog ||
                        shouldConfirmNextEpisodeOnEnd ||
                        uiState.postPlayMode is PostPlayMode.StillWatching ||
                        postPlayRecommendationState.isVisible ||
                        uiState.error != null
                if (panelOrDialogOpen) return@onKeyEvent false

                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (!uiState.showControls) {
                                viewModel.onEvent(PlayerEvent.OnCommitPreviewSeek)
                                return@onKeyEvent true
                            }
                        }
                        // Media FF/RW commit on release, matching
                        // the DPAD preview/commit model.
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                        KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            viewModel.onEvent(PlayerEvent.OnCommitPreviewSeek)
                            return@onKeyEvent true
                        }
                    }
                    return@onKeyEvent false
                }

                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    val firstPress = PauseOnTimeline.firstPress(
                        keyEvent.nativeKeyEvent.action, keyEvent.nativeKeyEvent.repeatCount
                    )
                    if (uiState.showPauseOverlay) {
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER,
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                // Resume directly from pause overlay in one click.
                                if (firstPress) {
                                    val code = keyEvent.nativeKeyEvent.keyCode
                                    if (PauseOnTimeline.isConfirmKey(code)) swallowKeyUp = code
                                    viewModel.onEvent(PlayerEvent.OnPlayPause)
                                }
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE,
                            KeyEvent.KEYCODE_MEDIA_STOP -> {
                            }
                            else -> {
                                viewModel.onEvent(PlayerEvent.OnDismissPauseOverlay)
                            }
                        }
                        return@onKeyEvent true
                    }
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (!uiState.showControls) {
                                // The release lands on the control that takes focus; it must not click it.
                                if (firstPress) {
                                    swallowKeyUp = keyEvent.nativeKeyEvent.keyCode
                                    viewModel.onEvent(PlayerEvent.OnPlayPause)
                                }
                                true
                            } else {
                                // Let the focused button handle it
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val overlayButtonsCoexist = skipButtonActuallyVisible &&
                                uiState.postPlayMode is PostPlayMode.AutoPlay
                            if (!uiState.showControls && !overlayButtonsCoexist) {
                                val isLeft =
                                    keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                                viewModel.onEvent(scrubEvent(keyEvent.nativeKeyEvent, forward = !isLeft))
                                true
                            } else {
                                // Let focus system handle navigation when controls are visible
                                // or both skip and next-episode buttons are on screen
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                                if (!uiState.showControls) {
                                    viewModel.onEvent(PlayerEvent.OnToggleControls)
                                } else {
                                    try {
                                        progressBarFocusRequester.requestFocus()
                                    } catch (_: Exception) {
                                        val skipVisible = skipButtonActuallyVisible
                                        if (skipVisible) {
                                            try {
                                                skipIntroFocusRequester.requestFocus()
                                            } catch (_: Exception) {
                                            }
                                        } else if (uiState.postPlayMode is PostPlayMode.AutoPlay) {
                                            try {
                                                nextEpisodeFocusRequester.requestFocus()
                                            } catch (_: Exception) {
                                            }
                                        } else {
                                            viewModel.hideControls()
                                        }
                                    }
                                }
                                true
                            }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (!uiState.showControls) {
                                viewModel.onEvent(PlayerEvent.OnToggleControls)
                                true
                            } else {
                                // Let focus system handle navigation when controls are visible
                                false
                            }
                        }
                        // A held media key toggles once, not on every repeat.
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            if (firstPress) viewModel.onEvent(PlayerEvent.OnPlayPause)
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY -> {
                            if (firstPress && !uiState.isPlaying) {
                                viewModel.onEvent(PlayerEvent.OnPlayPause)
                            }
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            if (firstPress && uiState.isPlaying) {
                                viewModel.onEvent(PlayerEvent.OnPlayPause)
                            }
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_STOP -> {
                            if (firstPress) viewModel.onEvent(PlayerEvent.OnPlayPause)
                            true
                        }
                        // A real seek on every ACTION_DOWN including auto-repeats
                        // would turn holding FF into 10-20 discrete seeks in a couple
                        // of seconds, each reopening the datasource chain (a 429
                        // generator against per-IP CDN limiters with parallel
                        // connections on). Route through the preview/commit
                        // machinery instead: accumulate on repeat, one network seek
                        // on key release.
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                        KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            val isRewind =
                                keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
                            viewModel.onEvent(scrubEvent(keyEvent.nativeKeyEvent, forward = !isRewind))
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        val mpvHdr by androidx.compose.runtime.produceState(false, uiState.internalPlayerEngine, uiState.dimHdrOverlays, uiState.currentStreamUrl) {
            value = false
            if (uiState.internalPlayerEngine == InternalPlayerEngine.MVP_PLAYER && uiState.dimHdrOverlays) {
                while (true) {
                    value = viewModel.controller.mpvView?.isHdrVideo() == true
                    kotlinx.coroutines.delay(1000)
                }
            }
        }
        val dimHdr = uiState.dimHdrOverlays && (if (uiState.internalPlayerEngine == InternalPlayerEngine.MVP_PLAYER) mpvHdr else uiState.isHdrVideo)
        // Video Player
        val postPlayRecommendationPlayerWidth by animateFloatAsState(
            targetValue = if (postPlayRecommendationState.isVisible) 0.32f else 1f,
            animationSpec = tween(durationMillis = POST_PLAY_RECOMMENDATION_TRANSITION_MS),
            label = "postPlayRecommendationPlayerWidth"
        )
        val postPlayRecommendationPlayerPadding by animateDpAsState(
            targetValue = if (postPlayRecommendationState.isVisible) NuvioTheme.spacing.xxl else 0.dp,
            animationSpec = tween(durationMillis = POST_PLAY_RECOMMENDATION_TRANSITION_MS),
            label = "postPlayRecommendationPlayerPadding"
        )
        val postPlayRecommendationPlayerCornerRadius by animateDpAsState(
            targetValue = if (postPlayRecommendationState.isVisible) 12.dp else 0.dp,
            animationSpec = tween(durationMillis = POST_PLAY_RECOMMENDATION_TRANSITION_MS),
            label = "postPlayRecommendationPlayerCornerRadius"
        )
        val postPlayRecommendationPlayerBorderAlpha by animateFloatAsState(
            targetValue = if (postPlayRecommendationState.isVisible) 0.2f else 0f,
            animationSpec = tween(durationMillis = POST_PLAY_RECOMMENDATION_TRANSITION_MS),
            label = "postPlayRecommendationPlayerBorderAlpha"
        )
        val playerSurfaceShape = RoundedCornerShape(postPlayRecommendationPlayerCornerRadius)
        val playerSurfaceIsFullscreen = !postPlayRecommendationState.isVisible &&
            postPlayRecommendationPlayerWidth >= 0.999f
        var subtitleControlsClearancePx by remember { mutableFloatStateOf(0f) }
        var progressBarSubtitleClearancePx by remember { mutableFloatStateOf(0f) }
        var controlsTitleRightPx by remember { mutableFloatStateOf(0f) }
        var subtitleTextMetrics by remember { mutableStateOf<SubtitleTextMetrics?>(null) }
        var skipClearancePx by remember { mutableFloatStateOf(0f) }
        var postPlayClearancePx by remember { mutableFloatStateOf(0f) }
        var seekClearancePx by remember { mutableFloatStateOf(0f) }
        val subtitleClearanceGapPx = with(LocalDensity.current) { SUBTITLE_CONTROLS_GAP.toPx() }
        val postPlayOverlayMode = uiState.postPlayMode.takeIf {
            uiState.error == null &&
                !postPlayRecommendationState.isVisible &&
                postPlayRecommendationState.recommendation == null &&
                !shouldConfirmNextEpisodeOnEnd &&
                !uiState.showLoadingOverlay &&
                !uiState.showPauseOverlay &&
                !uiState.showStreamInfoOverlay &&
                !uiState.showEpisodesPanel && !uiState.showChaptersPanel &&
                !uiState.showSourcesPanel &&
                !uiState.showPartyPanel &&
                !uiState.showAudioOverlay &&
                !uiState.showSubtitleOverlay &&
                !uiState.showSubtitleStylePanel &&
                !uiState.showSubtitleDelayOverlay &&
                !uiState.showSubtitleTimingDialog &&
                !uiState.showSpeedDialog &&
                !uiState.showMoreDialog
        }
        val seekOverlayVisible = uiState.showSeekOverlay && !uiState.showControls && uiState.error == null &&
            !uiState.showLoadingOverlay && !uiState.showPauseOverlay &&
            !uiState.showSubtitleDelayOverlay && !uiState.showSubtitleTimingDialog &&
            !uiState.showMoreDialog &&
            !uiState.isLive
        val playerControlsVisible = uiState.showControls && uiState.error == null &&
            !uiState.showLoadingOverlay && !uiState.showPauseOverlay &&
            !uiState.showStreamInfoOverlay &&
            !uiState.showPartyPanel &&
            !uiState.showSubtitleStylePanel &&
            !uiState.showSubtitleDelayOverlay &&
            !uiState.showEpisodesPanel && !uiState.showChaptersPanel &&
            !uiState.showSourcesPanel &&
            !uiState.showAudioOverlay &&
            !uiState.showSubtitleOverlay &&
            !uiState.showSpeedDialog &&
            !postPlayRecommendationState.isVisible &&
            uiState.postPlayMode !is PostPlayMode.StillWatching
        val latestPlayerControlsVisible by rememberUpdatedState(playerControlsVisible)
        LaunchedEffect(playerControlsVisible) {
            if (!playerControlsVisible) {
                subtitleControlsClearancePx = 0f
                progressBarSubtitleClearancePx = 0f
                controlsTitleRightPx = 0f
            }
        }
        val fallbackSubtitleLiftPx = with(LocalDensity.current) { SUBTITLE_CONTROLS_LIFT.toPx() }
        val playerSurfaceModifier = Modifier
            .align(Alignment.TopEnd)
            .padding(end = postPlayRecommendationPlayerPadding, top = postPlayRecommendationPlayerPadding)
            .fillMaxWidth(postPlayRecommendationPlayerWidth)
            .aspectRatio(16f / 9f)
            .clip(playerSurfaceShape)
            .border(
                BorderStroke(1.dp, Color.White.copy(alpha = postPlayRecommendationPlayerBorderAlpha)),
                playerSurfaceShape
            )
            .then(if (transparentLetterbox && playerSurfaceIsFullscreen) Modifier else Modifier.background(Color.Black))
            .zIndex(
                if (postPlayRecommendationState.isVisible || postPlayRecommendationPlayerWidth < 0.999f) {
                    2.2f
                } else {
                    0f
                }
            )

        if (!exitDispatched &&
            !postPlayRecommendationState.isTrailerPlaying &&
            (!postPlayRecommendationState.isVisible || !postPlayRecommendationState.hasAutoPlayedTrailer)
        ) {
            LaunchedEffect(postPlayRecommendationState.isVisible) {
                val playerView = viewModel.controller.exoPlayerView
                val vis = if (postPlayRecommendationState.isVisible) {
                    android.view.View.GONE
                } else {
                    android.view.View.VISIBLE
                }
                playerView?.subtitleView?.visibility = vis
                playerView?.setAssOverlayVisibility(vis)
            }

            Box(modifier = playerSurfaceModifier) {
                val previewConfiguredSubtitlePosition = uiState.showSubtitleOverlay || uiState.showSubtitleStylePanel
                val subtitleCueState by viewModel.controller.subtitleCueTracker.state.collectAsState()
                val prescannedSubtitleTexts by viewModel.controller.subtitleCueTracker.prescannedTexts.collectAsState()
                val subtitleWidthKey = listOf(uiState.selectedSubtitleTrackIndex, uiState.selectedAddonSubtitle, subtitleTextMetrics)
                var widestSubtitleBlockPx by remember(subtitleWidthKey) { mutableStateOf<Float?>(null) }
                val subtitleMeasurePaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
                LaunchedEffect(subtitleWidthKey, prescannedSubtitleTexts) {
                    val metrics = subtitleTextMetrics ?: return@LaunchedEffect
                    if (prescannedSubtitleTexts.isEmpty()) return@LaunchedEffect
                    val widest = withContext(Dispatchers.Default) {
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                        prescannedSubtitleTexts.maxOf { measureSubtitleBlockPx(paint, it, metrics) }
                    }
                    widestSubtitleBlockPx = maxOf(widestSubtitleBlockPx ?: 0f, widest)
                }
                LaunchedEffect(subtitleWidthKey, subtitleCueState) {
                    val metrics = subtitleTextMetrics ?: return@LaunchedEffect
                    if (!subtitleCueState.visible) return@LaunchedEffect
                    val width = if (subtitleCueState.hasBitmap) {
                        Float.MAX_VALUE
                    } else {
                        measureSubtitleBlockPx(subtitleMeasurePaint, subtitleCueState.text, metrics)
                    }
                    widestSubtitleBlockPx = maxOf(widestSubtitleBlockPx ?: 0f, width)
                }
                val subtitleTitleMarginPx = with(LocalDensity.current) { SUBTITLE_TITLE_MARGIN.toPx() }
                val subtitleClearsControlsTitle = subtitleTextMetrics?.let {
                    subtitleClearsTitle(widestSubtitleBlockPx, it.centerXPx, controlsTitleRightPx, subtitleTitleMarginPx)
                } == true
                val effectiveControlsClearancePx = effectiveSubtitleControlsClearancePx(
                    fullClearancePx = subtitleControlsClearancePx,
                    progressBarClearancePx = progressBarSubtitleClearancePx,
                    clearsTitle = subtitleClearsControlsTitle
                )
                var heldControlsClearancePx by remember { mutableFloatStateOf(0f) }
                val controlsClearanceMeasured = playerControlsVisible && subtitleControlsClearancePx > 0f
                LaunchedEffect(controlsClearanceMeasured, effectiveControlsClearancePx) {
                    heldControlsClearancePx = if (controlsClearanceMeasured) {
                        maxOf(heldControlsClearancePx, effectiveControlsClearancePx)
                    } else {
                        0f
                    }
                }
                val targetSubtitleLiftPx = computeSubtitleLiftPx(
                    previewConfiguredPosition = previewConfiguredSubtitlePosition,
                    controlsVisible = controlsClearanceMeasured,
                    controlsClearancePx = maxOf(heldControlsClearancePx, effectiveControlsClearancePx),
                    fallbackControlsLiftPx = fallbackSubtitleLiftPx,
                    skipVisible = skipButtonActuallyVisible,
                    skipClearancePx = skipClearancePx,
                    postPlayVisible = postPlayOverlayMode != null,
                    postPlayClearancePx = postPlayClearancePx,
                    seekVisible = seekOverlayVisible,
                    seekClearancePx = seekClearancePx
                )
                var subtitleLiftPx by remember { mutableFloatStateOf(0f) }
                var pendingDropCueVersion by remember { mutableStateOf<Long?>(null) }
                var subtitleDropTimedOut by remember { mutableStateOf(false) }
                var controlsHideAnimationFinished by remember { mutableStateOf(false) }
                LaunchedEffect(playerControlsVisible) {
                    controlsHideAnimationFinished = false
                    if (!playerControlsVisible) {
                        delay(PLAYER_CONTROLS_FADE_DURATION_MS.toLong())
                        controlsHideAnimationFinished = true
                    }
                }
                val applySubtitleLiftImmediately = shouldApplySubtitleLiftImmediately(
                    isPlaying = uiState.isPlaying,
                    previewConfiguredPosition = previewConfiguredSubtitlePosition,
                    controlsVisible = playerControlsVisible || !controlsHideAnimationFinished
                )
                LaunchedEffect(
                    targetSubtitleLiftPx,
                    subtitleCueState,
                    applySubtitleLiftImmediately,
                    subtitleDropTimedOut
                ) {
                    val decision = nextSubtitleLift(
                        targetLiftPx = targetSubtitleLiftPx,
                        appliedLiftPx = subtitleLiftPx,
                        cueVersion = subtitleCueState.version,
                        cueVisible = subtitleCueState.visible,
                        applyImmediately = applySubtitleLiftImmediately,
                        pendingDropCueVersion = pendingDropCueVersion,
                        dropTimedOut = subtitleDropTimedOut
                    )
                    subtitleLiftPx = decision.appliedLiftPx
                    pendingDropCueVersion = decision.pendingDropCueVersion
                    if (decision.pendingDropCueVersion == null) subtitleDropTimedOut = false
                }
                LaunchedEffect(pendingDropCueVersion) {
                    if (pendingDropCueVersion != null) {
                        delay(SUBTITLE_LIFT_DROP_TIMEOUT_MS)
                        subtitleDropTimedOut = true
                    }
                }
                if (uiState.internalPlayerEngine == InternalPlayerEngine.MVP_PLAYER) {
                    MpvPlayerSurface(
                        viewModel = viewModel,
                        isPlaying = uiState.isPlaying,
                        isBuffering = uiState.isBuffering,
                        aspectMode = uiState.aspectMode,
                        subtitleStyle = uiState.effectiveSubtitleStyle,
                        subtitleLiftPx = subtitleLiftPx,
                        subtitleTrackKey = subtitleWidthKey.take(2),
                        onSubtitleTextMetricsChanged = { subtitleTextMetrics = it },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    viewModel.exoPlayer?.let { player ->
                        ExoPlayerSurface(
                            player = player,
                            controller = viewModel.controller,
                            isPlaying = uiState.isPlaying,
                            isBuffering = uiState.isBuffering,
                            aspectMode = uiState.aspectMode,
                            tunnelingEnabled = uiState.tunnelingEnabled,
                            tunneledSurfaceFill = uiState.tunneledSurfaceFill,
                            useLibass = uiState.useLibass,
                            libassRenderMode = uiState.libassRenderMode,
                            subtitleStyle = uiState.effectiveSubtitleStyle,
                            dimAssOnHdr = uiState.isEditingHdrSubtitleStyle,
                            subtitleLiftPx = subtitleLiftPx,
                            onBindSubtitleView = viewModel::bindExoSubtitleView,
                            onSubtitleTextMetricsChanged = { subtitleTextMetrics = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                if (postPlayRecommendationState.canReturnToPlayer) {
                    PostPlayRecommendationPlayerWindow(
                        focusRequester = postPlayRecommendationPlayerWindowFocusRequester,
                        downFocusRequester = postPlayRecommendationFocusRequester,
                        onBack = handleBackPress,
                        onClick = {
                            returnToPlayerFromPostPlay()
                            if (!uiState.showControls) {
                                viewModel.onEvent(PlayerEvent.OnToggleControls)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        if (!exitDispatched) {
            PostPlayRecommendationOverlay(
                state = postPlayRecommendationState,
                currentTitle = uiState.contentName ?: uiState.title,
                showManualPlayOption = effectiveAutoplayEnabled,
                playFocusRequester = postPlayRecommendationFocusRequester,
                playerWindowFocusRequester = postPlayRecommendationPlayerWindowFocusRequester,
                onBack = handleBackPress,
                onForceExit = { exitPlayer() },
                onStopTrailer = viewModel::onPostPlayTrailerEnded,
                onPlay = { recommendation ->
                    if (!exitDispatched) {
                        exitDispatched = true
                        viewModel.stopAndRelease()
                        currentOnPlayRecommendation(recommendation, false)
                    }
                },
                onPlayManually = { recommendation ->
                    if (!exitDispatched) {
                        exitDispatched = true
                        viewModel.stopAndRelease()
                        currentOnPlayRecommendation(recommendation, true)
                    }
                },
                onOpenDetails = { recommendation ->
                    if (!exitDispatched) {
                        exitDispatched = true
                        viewModel.stopAndRelease()
                        currentOnOpenRecommendationDetails(recommendation)
                    }
                },
                onPlayTrailer = viewModel::playPostPlayTrailer,
                onTrailerEnded = viewModel::onPostPlayTrailerEnded,
                onPreviousRecommendation = viewModel::showPreviousPostPlayRecommendation,
                onNextRecommendation = viewModel::showNextPostPlayRecommendation,
                mdbListRatingOrder = postPlayRecommendationState.mdbListRatingOrder,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f)
            )
        }

        // Keep recommendation trailers and the small video window outside the UI dim layer.
        // Controls remain above that window, preserving the existing overlay ordering.
        Box(Modifier.fillMaxSize().zIndex(3f).hdrOverlayDim(dimHdr)) {
            LoadingOverlay(
                visible = uiState.showLoadingOverlay && uiState.error == null && !postPlayRecommendationState.isVisible,
                backdropUrl = uiState.backdrop,
                logoUrl = uiState.logo,
                title = uiState.title,
                message = uiState.loadingMessage.takeIf { uiState.showPlayerLoadingStatus || uiState.isTorrentStream },
                sourceLine = if (!uiState.showPlayerLoadingSource) null else run {
                    val provider = resolveStreamProvider(
                        streamName = uiState.currentStreamName,
                        streamDescription = null,
                        addonName = uiState.currentStreamAddonName,
                        host = null
                    )
                    listOfNotNull(uiState.currentStreamAddonName?.takeIf { it.isNotBlank() }, provider)
                        .distinct()
                        .joinToString(" \u00b7 ")
                        .takeIf { it.isNotBlank() }
                },
                filename = viewModel.currentFilename.takeIf { uiState.showPlayerLoadingSource },
                progress = uiState.loadingProgress,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2f)
            )

            if (uiState.playbackIssueReportsEnabled &&
                uiState.showLoadingOverlay &&
                uiState.error == null &&
                uiState.loadingIssueReportVisible &&
                !postPlayRecommendationState.isVisible
            ) {
                LoadingIssueReportAction(
                    elapsedMs = uiState.loadingIssueElapsedMs,
                    reportStatus = uiState.playbackIssueReportStatus,
                    reportId = uiState.playbackIssueReportId,
                    reportError = uiState.playbackIssueReportError,
                    onReport = { viewModel.onEvent(PlayerEvent.OnReportPlaybackIssue) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 72.dp)
                        .zIndex(2.4f)
                )
            }

            PauseOverlay(
                visible = uiState.showPauseOverlay && uiState.error == null &&
                    !uiState.showLoadingOverlay && !postPlayRecommendationState.isVisible && !prepareVisible,
                onClose = { viewModel.onEvent(PlayerEvent.OnDismissPauseOverlay) },
                title = uiState.title,
                logo = uiState.logo,
                episodeTitle = uiState.currentEpisodeTitle,
                season = uiState.currentSeason,
                episode = uiState.currentEpisode,
                year = uiState.releaseYear,
                type = uiState.contentType,
                description = uiState.description,
                cast = uiState.castMembers,
                showClock = !uiState.isLive,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2.5f)
            )

            StreamInfoOverlay(
                visible = uiState.showStreamInfoOverlay && uiState.error == null &&
                    !uiState.showLoadingOverlay && !postPlayRecommendationState.isVisible,
                onClose = dismissStreamInfoOverlay,
                data = uiState.streamInfoData,
                hudEnabled = uiState.playerStatsHudEnabled,
                hudButtonShown = uiState.playerStatsHudButtonAvailable,
                onToggleHud = { viewModel.onEvent(PlayerEvent.OnTogglePlayerStatsHud) },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2.6f)
            )

            PartyPanelOverlay(
                visible = uiState.showPartyPanel && uiState.error == null &&
                    !uiState.showLoadingOverlay && !postPlayRecommendationState.isVisible,
                onDismiss = { viewModel.onEvent(PlayerEvent.OnDismissPartyPanel) },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2.6f)
            )

            if (partyState.isActive && uiState.error == null && !uiState.showLoadingOverlay && !uiState.showPartyPanel) {
                if (partyState.hold == null) {
                    PartyHostNotice(
                        state = partyState,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = NuvioTheme.spacing.xl)
                            .zIndex(2.2f)
                    )
                }
                if (partyState.hold != null) {
                    PartyHoldBanner(
                        state = partyState,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = NuvioTheme.spacing.xl)
                            .zIndex(2.2f)
                    )
                } else if (uiState.showControls && !uiState.showPauseOverlay) {
                    PartyStatusChip(
                        state = partyState,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 28.dp, top = NuvioTheme.spacing.xl)
                            .zIndex(2.15f)
                    )
                }
            }

            if (uiState.playerStatsHudEnabled && uiState.playerStatsHudButtonAvailable && uiState.error == null) {
                PlayerDebugStatsOverlay(
                    viewModel = viewModel,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = NuvioTheme.spacing.xl, top = NuvioTheme.spacing.xl)
                        .zIndex(2.75f)
                )
            }

            // Torrent stats overlay (top-right corner)
            TorrentOverlay(
                visible = uiState.isTorrentStream && uiState.showTorrentStats &&
                    !uiState.hideTorrentStats && uiState.error == null && !postPlayRecommendationState.isVisible,
                downloadSpeed = uiState.torrentDownloadSpeed,
                uploadSpeed = uiState.torrentUploadSpeed,
                peers = uiState.torrentPeers,
                seeds = uiState.torrentSeeds,
                totalProgress = uiState.torrentTotalProgress,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = NuvioTheme.spacing.lg, end = NuvioTheme.spacing.lg)
                    .zIndex(2.7f)
            )

            // Live playback stats overlay, sampled at ~1 Hz while
            // visible. The sample lives in local state so the 1 Hz tick recomposes
            // only this small subtree, not anything that reads uiState. The owned TCP
            // probe runs separately so DNS/connect delays cannot stall these samples.
            var playbackStatsSample by remember { mutableStateOf<PlaybackStatsSample?>(null) }
            val statsHostView = LocalView.current
            LaunchedEffect(uiState.showPlaybackStatsOverlay) {
                if (!uiState.showPlaybackStatsOverlay) {
                    playbackStatsSample = null
                    return@LaunchedEffect
                }
                pollPlaybackStats(probe = { viewModel.samplePing() }, sample = { lastPingMs ->
                    val statsDisplay = statsHostView.display
                    // Active Display.Mode, not DisplayMetrics/Configuration: on Amlogic-class
                    // boxes the app framebuffer commonly renders at 1080p while HDMI outputs
                    // 4K: the metrics APIs report the framebuffer; Display.getMode() reports
                    // the negotiated output mode, the same object AFR switches through
                    // preferredDisplayModeId, so the row stays self-consistent with its dot.
                    val statsActiveMode = statsDisplay?.mode
                    val refreshRateHz = statsActiveMode?.refreshRate
                    // Modes on offer at the current resolution. When there is only one, no
                    // app-side mechanism can change the display rate: preferredDisplayModeId
                    // has nothing to switch to, so the HUD must not judge the rate as if the
                    // app could have fixed it.
                    val displayRateOptions = statsDisplay?.let { d ->
                        val active = d.mode
                        d.supportedModes.count {
                            it.physicalWidth == active.physicalWidth &&
                                it.physicalHeight == active.physicalHeight
                        }
                    }
                    playbackStatsSample = viewModel.samplePlaybackStats(
                        refreshRateHz,
                        displayRateOptions,
                        statsActiveMode?.physicalWidth,
                        statsActiveMode?.physicalHeight,
                        lastPingMs
                    )
                })
            }
            SeekThumbnailOverlayHost(uiState = uiState, viewModel = viewModel, modifier = Modifier.zIndex(2.65f))
            if (!prepareVisible) {
                SeekThumbnailProgressHint(
                    uiState = uiState,
                    modifier = Modifier.zIndex(2.65f),
                    covered = shouldConfirmNextEpisodeOnEnd || postPlayRecommendationState.isVisible ||
                        postPlayRecommendationState.isLoadingRecommendation
                )
            }

            // Keep the selected HUD mode, but give utility sheets exclusive visual space.
            val v2UtilityVisible = isV2 && (
                uiState.showAudioOverlay || uiState.showSubtitleOverlay || uiState.showSubtitleStylePanel ||
                    uiState.showSourcesPanel || uiState.showEpisodesPanel || uiState.showChaptersPanel || uiState.showSpeedDialog ||
                    uiState.showStreamInfoOverlay || uiState.showSubtitleDelayOverlay || uiState.showSubtitleTimingDialog
                )
            PlaybackStatsOverlay(
                visible = uiState.showPlaybackStatsOverlay && uiState.error == null && !v2UtilityVisible,
                sample = playbackStatsSample,
                compact = false,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = NuvioTheme.spacing.sm, end = NuvioTheme.spacing.lg)
                    .zIndex(2.7f)
            )

            // Buffering indicator — isolated in its own composable scope so that
            // isBuffering state changes only recompose this small subtree instead
            // of the entire PlayerScreen.
            PlayerBufferingIndicator(
                isBuffering = uiState.isBuffering && !postPlayRecommendationState.isVisible,
                showLoadingOverlay = uiState.showLoadingOverlay,
                isTorrentStream = uiState.isTorrentStream,
                torrentBufferingMessage = uiState.torrentBufferingMessage,
                torrentBufferingProgress = uiState.torrentBufferingProgress
            )

            // Error state
            if (uiState.error != null) {
                ErrorOverlay(
                    message = uiState.error!!,
                    showSwitchToMpvAction = uiState.showSwitchToMpvErrorAction &&
                        uiState.internalPlayerEngine != InternalPlayerEngine.MVP_PLAYER,
                    onSwitchToMpv = { viewModel.onEvent(PlayerEvent.OnSwitchToMpvPlayer) },
                    showReportAction = uiState.playbackIssueReportsEnabled,
                    reportStatus = uiState.playbackIssueReportStatus,
                    reportId = uiState.playbackIssueReportId,
                    reportError = uiState.playbackIssueReportError,
                    onReport = { viewModel.onEvent(PlayerEvent.OnReportPlaybackIssue) },
                    onBack = exitPlayerFromError
                )
            }

            val endPromptEpisode = nextEpisodeForEndPrompt.takeIf { shouldConfirmNextEpisodeOnEnd }
            if (endPromptEpisode != null) {
                NextEpisodeEndPromptOverlay(
                    nextEpisode = endPromptEpisode,
                    onContinue = continueToNextEpisodeFromEndPrompt,
                    onReturnToDetails = returnToDetailsFromEndPrompt
                )
            }

            // When controls are visible the skip button must clear the scrubber and sit
            // where the title block does (the title hides while a skip interval is active).
            // PlayerControlsOverlay measures the exact clearance (flat AND letterbox-aware)
            // and reports it here; 148.dp is a safe flat-case default for the first frame
            // before measurement arrives.
            val skipButtonBottomPadding by animateDpAsState(
                targetValue = if (uiState.showControls) controlsSkipAnchor else 30.dp,
                animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.fast),
                label = "skipButtonBottomPadding"
            )

            // Skip Intro button (bottom-left, lifted when controls are visible)
            val skipIntroCanFocus = isSkipIntroCanFocus(
                subtitleOverlayVisible = uiState.showSubtitleOverlay,
            )
            SkipIntroButton(
                interval = if (uiState.showPauseOverlay || uiState.showLoadingOverlay || postPlayRecommendationState.isVisible) {
                    null
                } else {
                    uiState.activeSkipInterval
                },
                dismissed = uiState.skipIntervalDismissed,
                targetsPostCredits = uiState.activeSkipTargetsPostCredits,
                controlsVisible = uiState.showControls,
                // Autoplay next-episode card owns focus; subtitle menu must keep D-pad focus (#2874).
                suppressFocus = (uiState.postPlayMode is PostPlayMode.AutoPlay &&
                    postPlayRecommendationState.recommendation == null) || !skipIntroCanFocus,
                canFocus = skipIntroCanFocus,
                onSkip = { viewModel.onEvent(PlayerEvent.OnSkipIntro) },
                onDismiss = { viewModel.onEvent(PlayerEvent.OnDismissSkipIntro) },
                onVisibilityChanged = { skipButtonActuallyVisible = it },
                onFocused = { viewModel.scheduleHideControls() },
                focusRequester = skipIntroFocusRequester,
                downFocusRequester = if (uiState.showControls) progressBarFocusRequester else null,
                upFocusRequester = if (uiState.showSubtitleDelayOverlay || uiState.showSubtitleTimingDialog) {
                    if (subtitleDelayFocusTarget == SubtitleDelayFocusTarget.RESET) {
                        subtitleDelayResetFocusRequester
                    } else {
                        subtitleDelaySyncLineFocusRequester
                    }
                } else if (uiState.showControls) {
                    // Controls visible: UP reaches the icon cluster (Info is its leftmost,
                    // always-present button) instead of falling through to hide-controls,
                    // which would trap focus on the Skip button. Nav becomes
                    // scrubber -> skip -> cluster, cluster -> down -> scrubber.
                    streamInfoFocusRequester
                } else {
                    null
                },
                rightFocusRequester = if (uiState.postPlayMode is PostPlayMode.AutoPlay) nextEpisodeFocusRequester else null,
                onHideControls = {
                    if (uiState.showControls) viewModel.hideControls()
                    else viewModel.onEvent(PlayerEvent.OnToggleControls)
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = NuvioTheme.spacing.xxl, bottom = skipButtonBottomPadding)
                    .zIndex(2.1f)
                    .reportSubtitleClearance(subtitleClearanceGapPx) { skipClearancePx = it }
            )
            PostPlayOverlay(
                mode = postPlayOverlayMode,
                controlsVisible = uiState.showControls,
                blurUnwatchedEpisodes = uiState.blurUnwatchedEpisodes,
                nextEpisodeFocusRequester = nextEpisodeFocusRequester,
                progressBarFocusRequester = if (uiState.showControls) progressBarFocusRequester else null,
                leftFocusRequester = if (skipButtonActuallyVisible) skipIntroFocusRequester else null,
                onPlayNext = { viewModel.onEvent(PlayerEvent.OnPlayNextEpisode) },
                onContinueStillWatching = { viewModel.onEvent(PlayerEvent.OnStillWatchingContinue) },
                onDismissStillWatching = { viewModel.onEvent(PlayerEvent.OnDismissStillWatchingPrompt) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 26.dp, bottom = if (uiState.showControls) 122.dp else 30.dp)
                    .zIndex(2.1f)
                    .reportSubtitleClearance(subtitleClearanceGapPx) { postPlayClearancePx = it },
            )

            // Parental guide overlay (shows when video first starts playing)
            ParentalGuideOverlay(
                warnings = uiState.parentalWarnings,
                isVisible = uiState.showParentalGuide,
                onAnimationComplete = {
                    viewModel.onEvent(PlayerEvent.OnParentalGuideHide)
                },
                modifier = Modifier.align(Alignment.TopStart)
            )

            DisplayModeOverlay(
                info = uiState.displayModeInfo,
                // Gate on !showLoadingOverlay so the badge (and its 5 s timer)
                // only start once the picture is up. Preflight AFR sets
                // showDisplayModeInfo before playback begins; without this gate the
                // timer runs and expires over the loading screen on slow (e.g.
                // debrid) starts, so the badge is already gone by the time the
                // first frame renders. Matches the StreamInfoOverlay gating above.
                isVisible = uiState.showDisplayModeInfo && !uiState.showLoadingOverlay,
                onAnimationComplete = {
                    viewModel.onEvent(PlayerEvent.OnHideDisplayModeInfo)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .zIndex(2.2f)
            )

            val showClockOverlay = uiState.showControls &&
                (!isV2 || !uiState.showPlaybackStatsOverlay) &&
                uiState.osdClockEnabled &&
                uiState.error == null &&
                !uiState.showLoadingOverlay &&
                !uiState.showPauseOverlay &&
                !uiState.showEpisodesPanel && !uiState.showChaptersPanel &&
                !uiState.showSourcesPanel &&
                !uiState.showAudioOverlay &&
                !uiState.showSubtitleOverlay &&
                !uiState.showSubtitleStylePanel &&
                !uiState.showSpeedDialog &&
                !uiState.showMoreDialog &&
                !uiState.showDisplayModeInfo &&
                !postPlayRecommendationState.isVisible

            AnimatedVisibility(
                visible = showClockOverlay,
                enter = fadeIn(animationSpec = tween(150)),
                exit = fadeOut(animationSpec = tween(150)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 28.dp, top = NuvioTheme.spacing.xl)
                    .zIndex(2.15f)
            ) {
                PlayerClockOverlayHost(
                    viewModel = viewModel,
                    playbackSpeed = uiState.playbackSpeed
                )
            }

            // Controls overlay
            AnimatedVisibility(
                visible = playerControlsVisible,
                enter = fadeIn(animationSpec = tween(PLAYER_CONTROLS_FADE_DURATION_MS)),
                exit = fadeOut(animationSpec = tween(PLAYER_CONTROLS_FADE_DURATION_MS))
            ) {
                val context = LocalContext.current
                PlayerControlsOverlay(
                    uiState = uiState,
                    playerBadges = playerBadges,
                    viewModel = viewModel,
                    v2Focus = v2Focus,
                    customFocus = customFocus,
                    customAvailable = customAvailable,
                    customHasTimeline = customHasTimeline,
                    containerFocusRequester = containerFocusRequester,
                    playPauseFocusRequester = playPauseFocusRequester,
                    progressBarFocusRequester = progressBarFocusRequester,
                    streamInfoFocusRequester = streamInfoFocusRequester,
                    reportCodeVisible = reportCodeVisible,
                    progressBarUpFocusRequester = when {
                        skipButtonActuallyVisible -> skipIntroFocusRequester
                        uiState.postPlayMode is PostPlayMode.AutoPlay -> nextEpisodeFocusRequester
                        else -> null
                    },
                    onSkipAnchorChanged = { controlsSkipAnchor = it },
                    subtitleReportingActive = playerControlsVisible,
                    onSubtitleClearanceChanged = {
                        if (latestPlayerControlsVisible) subtitleControlsClearancePx = it
                    },
                    onSubtitleTitleBoundsChanged = { progressBarClearance, titleRight ->
                        if (latestPlayerControlsVisible) {
                            progressBarSubtitleClearancePx = progressBarClearance
                            controlsTitleRightPx = titleRight
                        }
                    },
                    onPlayPause = { viewModel.onEvent(PlayerEvent.OnPlayPause) },
                    onPlayNextEpisode = { viewModel.onEvent(PlayerEvent.OnPlayNextEpisode) },
                    onSeekForward = { viewModel.onEvent(PlayerEvent.OnSeekForward) },
                    onSeekBackward = { viewModel.onEvent(PlayerEvent.OnSeekBackward) },
                    onSeekTo = { viewModel.onEvent(PlayerEvent.OnSeekTo(it)) },
                    onShowEpisodesPanel = { viewModel.onEvent(PlayerEvent.OnShowEpisodesPanel) },
                    onShowChaptersPanel = { viewModel.onEvent(PlayerEvent.OnShowChaptersPanel) },
                    onShowSourcesPanel = { viewModel.onEvent(PlayerEvent.OnShowSourcesPanel) },
                    onShowAudioDialog = { viewModel.onEvent(PlayerEvent.OnShowAudioOverlay) },
                    onShowSubtitleDialog = { viewModel.onEvent(PlayerEvent.OnShowSubtitleOverlay) },
                    onShowSpeedDialog = { viewModel.onEvent(PlayerEvent.OnShowSpeedDialog) },
                    onToggleAspectRatio = {
                        Log.d("PlayerScreen", "onToggleAspectRatio called - dispatching event")
                        viewModel.onEvent(PlayerEvent.OnToggleAspectRatio)
                    },
                    onSwitchPlayerEngine = { viewModel.onEvent(PlayerEvent.OnSwitchInternalPlayerEngine) },
                    onReportPlaybackIssue = { viewModel.onEvent(PlayerEvent.OnReportPlaybackIssue) },
                    onToggleMoreActions = {
                        if (uiState.showMoreDialog) {
                            viewModel.onEvent(PlayerEvent.OnDismissMoreDialog)
                        } else {
                            viewModel.onEvent(PlayerEvent.OnShowMoreDialog)
                        }
                    },
                    onOpenInExternalPlayer = openInExternalPlayer,
                    onShowStreamInfo = {
                        restoreStreamInfoFocus = !isV2 && uiState.controlLayout == null
                        viewModel.onEvent(PlayerEvent.OnShowStreamInfo)
                    },
                    onTogglePlaybackStats = { viewModel.onEvent(PlayerEvent.OnTogglePlaybackStats) },
                    onResetHideTimer = {
                        viewModel.scheduleHideControls()
                        viewModel.onUserInteraction()
                    },
                    onHideControls = { viewModel.hideControls() },
                    onBack = { exitPlayer() },
                    skipButtonVisible = skipButtonActuallyVisible
                )
            }

            // Aspect ratio indicator (floating pill)
            AnimatedVisibility(
                visible = uiState.showAspectRatioIndicator,
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(200)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
            ) {
                AspectRatioIndicator(text = uiState.aspectRatioIndicatorText)
            }

            LaunchedEffect(remoteButtonBanner) {
                if (remoteButtonBanner != null) {
                    delay(REMOTE_BUTTON_BANNER_MS)
                    remoteButtonBanner = null
                }
            }
            AnimatedVisibility(
                visible = remoteButtonBanner != null && !uiState.showAspectRatioIndicator,
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(200)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
                    .zIndex(3f)
            ) {
                RemoteButtonBannerPill(text = remoteButtonBannerText)
            }

            AnimatedVisibility(
                visible = uiState.showStreamSourceIndicator,
                enter = fadeIn(animationSpec = tween(NuvioMotion.tokens.durations.fast)),
                exit = fadeOut(animationSpec = tween(NuvioMotion.tokens.durations.fast)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 128.dp)
            ) {
                StreamSourceIndicator(text = uiState.streamSourceIndicatorText)
            }

            AnimatedVisibility(
                visible = uiState.showPlayerEngineSwitchInfo && uiState.error == null,
                enter = fadeIn(animationSpec = tween(NuvioMotion.tokens.durations.fast)),
                exit = fadeOut(animationSpec = tween(NuvioMotion.tokens.durations.fast)),
                modifier = Modifier
                    .align(Alignment.Center)
                    .zIndex(2.35f)
            ) {
                PlayerEngineSwitchIndicator(
                    title = stringResource(R.string.player_engine_switching_title),
                    message = uiState.playerEngineSwitchInfoText
                )
            }

            // Seek-only overlay (progress bar + time) when controls are hidden
            AnimatedVisibility(
                visible = uiState.showSubtitleDelayOverlay &&
                    !uiState.showControls &&
                    uiState.error == null &&
                    !uiState.showLoadingOverlay &&
                    !uiState.showPauseOverlay &&
                    !uiState.showSubtitleStylePanel &&
                    !uiState.showEpisodesPanel && !uiState.showChaptersPanel &&
                    !uiState.showSourcesPanel &&
                    !uiState.showAudioOverlay &&
                    !uiState.showSubtitleOverlay &&
                    !uiState.showSubtitleTimingDialog &&
                    !uiState.showSpeedDialog,
                enter = fadeIn(animationSpec = tween(120)),
                exit = fadeOut(animationSpec = tween(120)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 44.dp)
                    .zIndex(2.3f)
            ) {
                SubtitleDelayOverlay(
                    subtitleDelayMs = uiState.subtitleDelayMs,
                    isResetButtonFocused = subtitleDelayFocusTarget == SubtitleDelayFocusTarget.RESET,
                    isSyncLineButtonFocused = subtitleDelayFocusTarget == SubtitleDelayFocusTarget.SYNC_LINE,
                    isSliderFocused = subtitleDelayFocusTarget == SubtitleDelayFocusTarget.SLIDER,
                    resetFocusRequester = subtitleDelayResetFocusRequester,
                    syncLineFocusRequester = subtitleDelaySyncLineFocusRequester,
                    onResetFocused = { subtitleDelayFocusTarget = SubtitleDelayFocusTarget.RESET },
                    onSyncLineFocused = { subtitleDelayFocusTarget = SubtitleDelayFocusTarget.SYNC_LINE },
                    onResetDelay = {
                        viewModel.onEvent(PlayerEvent.OnResetSubtitleDelay())
                        subtitleDelayFocusTarget = SubtitleDelayFocusTarget.SLIDER
                    },
                    onOpenSyncByLine = {
                        subtitleDelayFocusTarget = SubtitleDelayFocusTarget.SLIDER
                        // Card onClick already runs on confirm KEY_UP — no trailing release
                        // to swallow. KEY_DOWN open (SYNC_LINE branch above) sets the flag.
                        viewModel.onEvent(PlayerEvent.OnShowSubtitleTimingDialog)
                    }
                )
            }

            AnimatedVisibility(
                visible = seekOverlayVisible,
                enter = fadeIn(animationSpec = tween(150)),
                exit = fadeOut(animationSpec = tween(150)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .reportSubtitleClearance(subtitleClearanceGapPx) { seekClearancePx = it }
            ) {
                SeekOverlayHost(viewModel = viewModel)
            }

            // Episodes/streams side panel (slides in from right)
            AnimatedVisibility(
                visible = uiState.showEpisodesPanel && uiState.error == null,
                enter = fadeIn(animationSpec = tween(120)),
                exit = fadeOut(animationSpec = tween(120))
            ) {
                // Scrim (fades in/out, no slide)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = if (cinematicGlass) .08f else .45f))
                )
            }

            // Panel itself (slides in from right)
            AnimatedVisibility(
                visible = uiState.showEpisodesPanel && uiState.error == null,
                enter = slideInHorizontally(
                    animationSpec = tween(220),
                    initialOffsetX = { it }
                ),
                exit = slideOutHorizontally(
                    animationSpec = tween(220),
                    targetOffsetX = { it }
                )
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    EpisodesSidePanel(
                        uiState = uiState,
                        episodesFocusRequester = episodesFocusRequester,
                        streamsFocusRequester = streamsFocusRequester,
                        onClose = { viewModel.onEvent(PlayerEvent.OnDismissEpisodesPanel) },
                        onBackToEpisodes = { viewModel.onEvent(PlayerEvent.OnBackFromEpisodeStreams) },
                        onReloadEpisodeStreams = { viewModel.onEvent(PlayerEvent.OnReloadEpisodeStreams) },
                        onSeasonSelected = { viewModel.onEvent(PlayerEvent.OnEpisodeSeasonSelected(it)) },
                        onAddonFilterSelected = { viewModel.onEvent(PlayerEvent.OnEpisodeAddonFilterSelected(it)) },
                        onEpisodeSelected = { viewModel.onEvent(PlayerEvent.OnEpisodeSelected(it)) },
                        onEpisodeManualPlay = {
                            viewModel.onEvent(PlayerEvent.OnEpisodeSelected(it, manualSelection = true))
                        },
                        onStreamSelected = { viewModel.onEvent(PlayerEvent.OnEpisodeStreamSelected(it)) },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }

            AnimatedVisibility(
                visible = uiState.showChaptersPanel && uiState.chapters.isNotEmpty() && uiState.error == null,
                enter = fadeIn(animationSpec = tween(120)),
                exit = fadeOut(animationSpec = tween(120))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = if (cinematicGlass) .08f else .45f))
                )
            }
            AnimatedVisibility(
                visible = uiState.showChaptersPanel && uiState.chapters.isNotEmpty() && uiState.error == null,
                enter = slideInHorizontally(animationSpec = tween(220), initialOffsetX = { it }),
                exit = slideOutHorizontally(animationSpec = tween(220), targetOffsetX = { it })
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    ChaptersSidePanel(
                        chapters = uiState.chapters,
                        currentPositionMs = viewModel.playbackTimeline.value.playbackPosition,
                        onChapterSelected = { viewModel.onEvent(PlayerEvent.OnSelectChapter(it.startMs)) },
                        onClose = { viewModel.onEvent(PlayerEvent.OnDismissChaptersPanel) },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }

            // Sources panel scrim
            AnimatedVisibility(
                visible = uiState.showSourcesPanel && uiState.error == null,
                enter = fadeIn(animationSpec = tween(120)),
                exit = fadeOut(animationSpec = tween(120))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                        .background(Color.Black.copy(alpha = if (cinematicGlass) .08f else .45f))
                )
            }

            // Sources panel (slides in from right)
            AnimatedVisibility(
                visible = uiState.showSourcesPanel && uiState.error == null,
                enter = slideInHorizontally(
                    animationSpec = tween(220),
                    initialOffsetX = { it }
                ),
                exit = slideOutHorizontally(
                    animationSpec = tween(220),
                    targetOffsetX = { it }
                )
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    StreamSourcesSidePanel(
                        uiState = uiState,
                        streamsFocusRequester = sourceStreamsFocusRequester,
                        onClose = {
                            if (uiState.currentStreamUrl.isNullOrBlank()) {
                                exitPlayer()
                            } else {
                                viewModel.onEvent(PlayerEvent.OnDismissSourcesPanel)
                            }
                        },
                        onReload = { viewModel.onEvent(PlayerEvent.OnReloadSourceStreams) },
                        onAddonFilterSelected = { viewModel.onEvent(PlayerEvent.OnSourceAddonFilterSelected(it)) },
                        onStreamSelected = { viewModel.onEvent(PlayerEvent.OnSourceStreamSelected(it)) },
                        onExpandStreams = { viewModel.controller.expandSourceFilteredStreamsIfNeeded() },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }

            // Subtitle style panel scrim
            AnimatedVisibility(
                visible = uiState.showSubtitleStylePanel && uiState.error == null,
                enter = fadeIn(animationSpec = tween(120)),
                exit = fadeOut(animationSpec = tween(120))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = if (cinematicGlass) .08f else .35f))
                )
            }

            // Subtitle style panel
            AnimatedVisibility(
                visible = uiState.showSubtitleStylePanel && uiState.error == null,
                enter = slideInVertically(
                    animationSpec = tween(220),
                    initialOffsetY = { -it }
                ),
                exit = slideOutVertically(
                    animationSpec = tween(220),
                    targetOffsetY = { -it }
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val isCurrentSubtitleAss = run {
                        val addon = uiState.selectedAddonSubtitle
                        if (addon != null) {
                            val url = addon.url.lowercase(java.util.Locale.US)
                            return@run url.contains(".ass") || url.contains(".ssa")
                        }
                        val track = uiState.subtitleTracks.getOrNull(uiState.selectedSubtitleTrackIndex)
                        if (track != null) {
                            val codec = track.codec?.lowercase(java.util.Locale.US).orEmpty()
                            return@run codec.contains("ass") || codec.contains("ssa") || track.name.contains("ASS", ignoreCase = true)
                        }
                        false
                    }
                    val isUsingMpv = uiState.internalPlayerEngine == InternalPlayerEngine.MVP_PLAYER
                    val isAssDisabled = isCurrentSubtitleAss && (isUsingMpv || uiState.useLibass)

                    SubtitleStyleSidePanel(
                        subtitleStyle = uiState.editableSubtitleStyle,
                        isEditingHdrStyle = uiState.isEditingHdrSubtitleStyle,
                        onEvent = { if (!isAssDisabled) viewModel.onEvent(it) },
                        isStyleDisabledByLibass = isAssDisabled,
                        bitmapScaleAvailable = !isUsingMpv,
                        bitmapTrack = uiState.selectedAddonSubtitle == null && isBitmapSubtitleCodec(
                            uiState.subtitleTracks.getOrNull(uiState.selectedSubtitleTrackIndex)?.codec),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                    )
                }
            }

            // Audio track dialog
            AudioSelectionOverlay(
                visible = uiState.showAudioOverlay,
                tracks = uiState.serverAudioTracks.ifEmpty { uiState.audioTracks },
                selectedIndex = uiState.serverAudioTracks.firstOrNull { it.isSelected }?.index ?: uiState.selectedAudioTrackIndex,
                audioDelayMs = uiState.audioDelayMs,
                audioAmplificationDb = uiState.audioAmplificationDb,
                isAmplificationAvailable = uiState.isAudioAmplificationAvailable,
                nightMode = uiState.nightMode,
                persistAmplification = uiState.persistAudioAmplification,
                centerMixLevelDb = uiState.centerMixLevelDb,
                isCenterMixAvailable = uiState.isCenterMixAvailable,
                dialogueBoostState = uiState.dialogueBoostState,
                onTrackSelected = { viewModel.onEvent(PlayerEvent.OnSelectAudioTrack(it)) },
                onAudioDelayChange = { viewModel.onEvent(PlayerEvent.OnSetAudioDelayMs(it)) },
                onAmplificationChange = { viewModel.onEvent(PlayerEvent.OnSetAudioAmplificationDb(it)) },
                onNightModeChange = { viewModel.onEvent(PlayerEvent.OnSetNightMode(it)) },
                onPersistAmplificationChange = {
                    viewModel.onEvent(PlayerEvent.OnSetPersistAudioAmplification(it))
                },
                onCenterMixLevelChange = {
                    viewModel.onEvent(PlayerEvent.OnSetCenterMixLevelDb(it))
                },
                onDismiss = { viewModel.onEvent(PlayerEvent.OnDismissTransientOverlay) },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2.6f)
            )

            SubtitleSelectionOverlay(
                visible = uiState.showSubtitleOverlay,
                internalTracks = uiState.serverSubtitleTracks.ifEmpty { uiState.subtitleTracks },
                selectedInternalIndex = if (uiState.serverSubtitleTracks.isEmpty()) {
                    uiState.selectedSubtitleTrackIndex
                } else {
                    uiState.serverSubtitleTracks.firstOrNull { it.isSelected }?.index ?: -1
                },
                addonSubtitles = uiState.addonSubtitles,
                selectedAddonSubtitle = uiState.selectedAddonSubtitle,
                subtitleStyle = uiState.editableSubtitleStyle,
                isEditingHdrStyle = uiState.isEditingHdrSubtitleStyle,
                subtitleDelayMs = uiState.subtitleDelayMs,
                installedSubtitleAddonOrder = uiState.installedSubtitleAddonOrder,
                isLoadingAddons = uiState.isLoadingAddonSubtitles,
                useLibass = uiState.useLibass,
                isUsingMpv = uiState.internalPlayerEngine == InternalPlayerEngine.MVP_PLAYER,
                onInternalTrackSelected = { viewModel.onEvent(PlayerEvent.OnSelectSubtitleTrack(it)) },
                onAddonSubtitleSelected = { viewModel.onEvent(PlayerEvent.OnSelectAddonSubtitle(it)) },
                onDisableSubtitles = { viewModel.onEvent(PlayerEvent.OnDisableSubtitles) },
                onEvent = { viewModel.onEvent(it) },
                onDismiss = { viewModel.onEvent(PlayerEvent.OnDismissTransientOverlay) },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2.6f)
            )

            PlayerOverlayScaffold(
                visible = uiState.showSubtitleTimingDialog &&
                    uiState.error == null &&
                    !uiState.showLoadingOverlay &&
                    !uiState.showPauseOverlay &&
                    !uiState.showEpisodesPanel && !uiState.showChaptersPanel &&
                    !uiState.showSourcesPanel &&
                    !uiState.showAudioOverlay &&
                    !uiState.showSubtitleOverlay &&
                    !uiState.showSubtitleStylePanel &&
                    !uiState.showSubtitleDelayOverlay &&
                    !uiState.showSpeedDialog &&
                    !uiState.showMoreDialog,
                onDismiss = { viewModel.onEvent(PlayerEvent.OnDismissSubtitleTimingDialog) },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2.35f),
                captureKeys = false,
                contentPadding = PaddingValues(top = 44.dp)
            ) {
                SubtitleTimingDialogHost(
                    viewModel = viewModel,
                    modifier = Modifier.align(Alignment.TopCenter),
                    subtitleDelayMs = uiState.subtitleDelayMs,
                    selectedAddonSubtitle = uiState.selectedAddonSubtitle,
                    cues = uiState.subtitleAutoSyncCues,
                    capturedVideoMs = uiState.subtitleAutoSyncCapturedVideoMs,
                    statusMessage = uiState.subtitleAutoSyncStatus,
                    errorMessage = uiState.subtitleAutoSyncError,
                    isLoadingCues = uiState.subtitleAutoSyncLoading,
                    onCaptureNow = { viewModel.onEvent(PlayerEvent.OnCaptureSubtitleAutoSyncTime) },
                    onCueSelected = { cue ->
                        viewModel.onEvent(PlayerEvent.OnApplySubtitleAutoSyncCue(cue.startTimeMs))
                    }
                )
            }

            if (uiState.showSpeedDialog) {
                SpeedSelectionDialog(
                    currentSpeed = uiState.playbackSpeed,
                    onSpeedSelected = { viewModel.onEvent(PlayerEvent.OnSetPlaybackSpeed(it)) },
                    onDismiss = { viewModel.onEvent(PlayerEvent.OnDismissTransientOverlay) },
                    brightness = if (dimHdr) HDR_OVERLAY_COLOR_SCALE else 1f
                )
            }
        }
        ThumbnailPrepareOverlay(backdropUrl = uiState.backdrop, modifier = Modifier.zIndex(3.5f))
    }
}

@Composable
private fun MpvPlayerSurface(
    viewModel: PlayerViewModel,
    isPlaying: Boolean,
    isBuffering: Boolean,
    aspectMode: AspectMode,
    subtitleStyle: SubtitleStyleSettings,
    subtitleLiftPx: Float,
    subtitleTrackKey: Any?,
    onSubtitleTextMetricsChanged: (SubtitleTextMetrics?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val latestAspectMode by rememberUpdatedState(aspectMode)
    val latestSubtitleStyle by rememberUpdatedState(subtitleStyle)
    val latestMetricsCallback by rememberUpdatedState(onSubtitleTextMetricsChanged)
    val mpvView = remember(context) {
        NuvioMpvSurfaceView(context).apply {
            isFocusable = false
            isFocusableInTouchMode = false
        }
    }

    AndroidView(
        factory = { mpvView },
        modifier = modifier.focusProperties { canFocus = false }
    )

    DisposableEffect(viewModel, mpvView) {
        viewModel.attachMpvView(mpvView)
        onDispose {
            viewModel.attachMpvView(null)
        }
    }

    DisposableEffect(mpvView) {
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            mpvView.applyAspectMode(latestAspectMode)
            latestMetricsCallback(mpvView.subtitleTextMetrics(latestSubtitleStyle))
        }
        mpvView.addOnLayoutChangeListener(listener)
        onDispose {
            mpvView.removeOnLayoutChangeListener(listener)
        }
    }

    LaunchedEffect(mpvView, isPlaying, isBuffering) {
        val shouldKeepScreenOn = isPlaying || isBuffering
        if (mpvView.keepScreenOn != shouldKeepScreenOn) {
            mpvView.keepScreenOn = shouldKeepScreenOn
        }
    }

    LaunchedEffect(mpvView, aspectMode) {
        mpvView.applyAspectMode(aspectMode)
    }

    LaunchedEffect(mpvView, subtitleStyle, subtitleLiftPx) {
        val height = mpvView.height
        mpvView.subtitleLiftFraction = if (subtitleLiftPx > 0f && height > 0) {
            (subtitleLiftPx / height).toDouble()
        } else {
            0.0
        }
        mpvView.applySubtitleStyle(subtitleStyle)
    }

    LaunchedEffect(mpvView, subtitleStyle, subtitleTrackKey) {
        latestMetricsCallback(mpvView.subtitleTextMetrics(subtitleStyle))
    }
}

@Composable
private fun ExoPlayerSurface(
    player: ExoPlayer,
    controller: PlayerRuntimeController,
    isPlaying: Boolean,
    isBuffering: Boolean,
    aspectMode: AspectMode,
    tunnelingEnabled: Boolean,
    tunneledSurfaceFill: Boolean,
    useLibass: Boolean,
    libassRenderMode: LibassRenderMode,
    subtitleStyle: SubtitleStyleSettings,
    dimAssOnHdr: Boolean,
    subtitleLiftPx: Float,
    onBindSubtitleView: (androidx.media3.ui.SubtitleView?) -> Unit = {},
    onSubtitleTextMetricsChanged: (SubtitleTextMetrics?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val latestAspectMode by rememberUpdatedState(aspectMode)
    val latestTunnelingEnabled by rememberUpdatedState(tunnelingEnabled)
    val latestTunneledSurfaceFill by rememberUpdatedState(tunneledSurfaceFill)
    val latestBindSubtitleView by rememberUpdatedState(onBindSubtitleView)
    val latestSubtitleStyle by rememberUpdatedState(subtitleStyle)
    val latestSubtitleLiftPx by rememberUpdatedState(subtitleLiftPx)
    val latestDimAssOnHdr by rememberUpdatedState(dimAssOnHdr)
    val latestUseLibass by rememberUpdatedState(useLibass)
    val latestLibassRenderMode by rememberUpdatedState(libassRenderMode)
    val latestMetricsCallback by rememberUpdatedState(onSubtitleTextMetricsChanged)
    val playerView = remember(context, player) {
        PlayerView(context).apply {
            useController = false
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            keepScreenOn = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            enableComposeSurfaceSyncWorkaroundIfAvailable()
            this.player = player
        }
    }

    AndroidView(
        factory = { playerView },
        modifier = modifier.focusProperties { canFocus = false },
        update = {
            it.syncLibassOverlay(
                player = player,
                enabled = useLibass,
                renderMode = libassRenderMode
            )
            latestBindSubtitleView(it.subtitleView)
        }
    )

    DisposableEffect(playerView, player) {
        if (playerView.player !== player) {
            playerView.player = player
        }
        latestBindSubtitleView(playerView.subtitleView)
        onDispose {
            latestBindSubtitleView(null)
            if (playerView.player === player) {
                playerView.player = null
            }
        }
    }

    DisposableEffect(playerView) {
        controller.exoPlayerView = playerView
        onDispose {
            if (controller.exoPlayerView === playerView) {
                controller.exoPlayerView = null
            }
        }
    }

    DisposableEffect(player, playerView) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                controller.videoAspectRatio = if (videoSize.width > 0 && videoSize.height > 0) {
                    videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio / videoSize.height.toFloat()
                } else {
                    0f
                }
                playerView.post {
                    playerView.syncExoSurfaceLayout(
                        tunnelingEnabled = latestTunnelingEnabled,
                        tunneledSurfaceFill = latestTunneledSurfaceFill,
                        aspectMode = latestAspectMode
                    )
                    controller.refreshVideoBottomFraction()
                }
            }

            override fun onRenderedFirstFrame() {
                playerView.post {
                    playerView.syncExoSurfaceLayout(
                        tunnelingEnabled = latestTunnelingEnabled,
                        tunneledSurfaceFill = latestTunneledSurfaceFill,
                        aspectMode = latestAspectMode
                    )
                    controller.refreshVideoBottomFraction()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                // Re-apply subtitle style when tracks change so style is applied
                // even when subtitles are enabled after initial player setup.
                playerView.post {
                    playerView.applySubtitleStyleIfNeeded(
                        latestSubtitleStyle,
                        force = true,
                        liftPx = latestSubtitleLiftPx,
                        dimAssOnHdr = latestDimAssOnHdr
                    )
                    playerView.syncLibassOverlay(
                        player = player,
                        enabled = latestUseLibass,
                        renderMode = latestLibassRenderMode
                    )
                }
            }
        }
        player.addListener(listener)
        playerView.post {
            playerView.syncExoSurfaceLayout(
                tunnelingEnabled = latestTunnelingEnabled,
                tunneledSurfaceFill = latestTunneledSurfaceFill,
                aspectMode = latestAspectMode
            )
        }
        onDispose {
            player.removeListener(listener)
        }
    }

    // AM9 native FEL: the picture is on the hardware video plane below the UI, so the SurfaceView must be
    // a transparent hole. With the default opaque format Android 14 shows a black "Background for
    // SurfaceView" colour layer behind it, which covers the video plane (blank screen, DV logo only).
    // Switch to TRANSLUCENT only while the amstream FEL decoder runs in transparent-hole mode (the fallback
    // when the sideband stream could not be attached); opaque for the sideband mode
    // (DECODER_NAME_SIDEBAND: HWC places the video plane in the SurfaceView's rect) and for MediaCodec.
    DisposableEffect(player, playerView) {
        val analyticsListener = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                val nativeFel = decoderName == com.nuvio.tv.core.player.amlfel.AmlDvFelVideoRenderer.DECODER_NAME
                val surfaceView = playerView.videoSurfaceView as? android.view.SurfaceView ?: return
                playerView.post {
                    if (playerView.player !== player || playerView.videoSurfaceView !== surfaceView) return@post
                    surfaceView.holder.setFormat(
                        if (nativeFel) android.graphics.PixelFormat.TRANSLUCENT else android.graphics.PixelFormat.OPAQUE
                    )
                }
            }
        }
        player.addAnalyticsListener(analyticsListener)
        onDispose {
            player.removeAnalyticsListener(analyticsListener)
        }
    }

    DisposableEffect(playerView) {
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            playerView.post {
                playerView.syncExoSurfaceLayout(
                    tunnelingEnabled = latestTunnelingEnabled,
                    tunneledSurfaceFill = latestTunneledSurfaceFill,
                    aspectMode = latestAspectMode
                )
            }
        }
        val removeListener = addExoAspectLayoutChangeListener(playerView, listener)
        onDispose {
            removeListener()
        }
    }

    LaunchedEffect(playerView, isPlaying, isBuffering) {
        val shouldKeepScreenOn = isPlaying || isBuffering
        if (playerView.keepScreenOn != shouldKeepScreenOn) {
            playerView.keepScreenOn = shouldKeepScreenOn
        }
    }

    LaunchedEffect(playerView, aspectMode, tunnelingEnabled, tunneledSurfaceFill) {
        playerView.syncExoSurfaceLayout(
            tunnelingEnabled = tunnelingEnabled,
            tunneledSurfaceFill = tunneledSurfaceFill,
            aspectMode = aspectMode
        )
    }

    LaunchedEffect(playerView, player, useLibass, libassRenderMode) {
        playerView.syncLibassOverlay(
            player = player,
            enabled = useLibass,
            renderMode = libassRenderMode
        )
    }

    DisposableEffect(playerView) {
        val callback: (SubtitleTextMetrics?) -> Unit = { latestMetricsCallback(it) }
        playerView.setTag(R.id.player_view_subtitle_metrics_callback_tag, callback)
        onDispose {
            playerView.setTag(R.id.player_view_subtitle_metrics_callback_tag, null)
        }
    }

    LaunchedEffect(playerView, subtitleStyle, subtitleLiftPx, dimAssOnHdr) {
        playerView.applySubtitleStyleIfNeeded(subtitleStyle, liftPx = subtitleLiftPx, dimAssOnHdr = dimAssOnHdr)
        if (!controller.isSidecarAddonSubtitleActive()) {
            playerView.subtitleView?.setCues(player.currentCues.cues.map { controller.presentSubtitleCue(it) })
        } else {
            controller.renderSidecarCuesAtCurrentPosition()
        }
    }
}

private fun PlayerView.enableComposeSurfaceSyncWorkaroundIfAvailable() {
    runCatching {
        javaClass
            .getMethod("setEnableComposeSurfaceSyncWorkaround", java.lang.Boolean.TYPE)
            .invoke(this, false)
    }
}

private fun PlayerView.applyExoAspectMode(mode: AspectMode) {
    setTag(R.id.player_view_aspect_mode_tag, mode)
    applyExoAspectMode(this, mode)
}

private fun PlayerView.syncExoSurfaceLayout(
    tunnelingEnabled: Boolean,
    tunneledSurfaceFill: Boolean,
    aspectMode: AspectMode
) {
    val targetResizeMode = PlayerDisplayModeUtils.exoSurfaceResizeMode(
        tunnelingEnabled = tunnelingEnabled,
        tunneledSurfaceFill = tunneledSurfaceFill
    )
    if (resizeMode != targetResizeMode) {
        resizeMode = targetResizeMode
    }
    val appliedAspectMode = aspectModeAppliedToExoSurface(tunnelingEnabled, aspectMode)
    applyExoAspectMode(appliedAspectMode)
    setTag(
        R.id.player_view_libass_geometry_context_tag,
        LibassOverlayGeometryContext(
            requestedAspectMode = aspectMode,
            appliedAspectMode = appliedAspectMode,
            tunnelingEnabled = tunnelingEnabled,
            tunneledSurfaceFill = tunneledSurfaceFill
        )
    )
    syncLibassOverlayGeometry()
    reportSubtitleTextMetrics()
}

private data class LibassOverlayGeometryContext(
    val requestedAspectMode: AspectMode,
    val appliedAspectMode: AspectMode,
    val tunnelingEnabled: Boolean,
    val tunneledSurfaceFill: Boolean
)

private const val LIBASS_OVERLAY_GEOMETRY_TAG = "LibassOverlayGeometry"

private fun PlayerView.syncLibassOverlayGeometry() {
    val overlay = findViewById<android.widget.FrameLayout>(R.id.libass_overlay_container_gl)
    val assOverlayView = overlay?.findAssOverlayChild()
    val contentFrame = findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
    val exoPlayer = player as? ExoPlayer
    val assHandler = exoPlayer?.getAssHandlerCompat()
    val context = getTag(R.id.player_view_libass_geometry_context_tag) as? LibassOverlayGeometryContext
    val videoSize = exoPlayer?.videoSize
    val overlayWidth = overlay?.width ?: 0
    val overlayHeight = overlay?.height ?: 0
    val videoBounds = contentFrame?.let(::transformedChildBoundsInParent)
    val margins = videoBounds?.let { libassOverlayMargins(overlayWidth, overlayHeight, it) }

    if (assOverlayView == null || assHandler == null || margins == null) {
        logLibassOverlayGeometry(
            "event=skip overlay=${overlayWidth}x${overlayHeight} hasOverlay=${assOverlayView != null} " +
                "handler=${assHandler != null} videoBounds=$videoBounds"
        )
        return
    }

    assHandler.setMargins(margins.top, margins.bottom, margins.left, margins.right)
    assHandler.setUseMargins(false)
    assOverlayView.invalidateSubtitles()
    logLibassOverlayGeometry(
        "event=apply requested=${context?.requestedAspectMode} applied=${context?.appliedAspectMode} " +
            "tunneling=${context?.tunnelingEnabled} tunneledFill=${context?.tunneledSurfaceFill} " +
            "overlay=${overlayWidth}x${overlayHeight} video=${videoSize?.width}x${videoSize?.height} " +
            "par=${videoSize?.pixelWidthHeightRatio} content=${describeRect(videoBounds)} " +
            "margins=top:${margins.top},bottom:${margins.bottom},left:${margins.left},right:${margins.right}"
    )
}

private fun PlayerView.logLibassOverlayGeometry(message: String) {
    if (!BuildConfig.IS_DEBUG_BUILD) return
    if (getTag(R.id.player_view_libass_geometry_snapshot_tag) == message) return
    setTag(R.id.player_view_libass_geometry_snapshot_tag, message)
    Log.d(LIBASS_OVERLAY_GEOMETRY_TAG, message)
}

private fun describeRect(rect: RectF?): String =
    rect?.let { "${it.left},${it.top},${it.right},${it.bottom}" } ?: "null"

private data class SubtitleAppliedConfig(
    val style: SubtitleStyleSettings,
    val isAss: Boolean,
    val liftPx: Float,
    val dimAssOnHdr: Boolean
)

private val SUBTITLE_CONTROLS_LIFT = 130.dp
private val SUBTITLE_CONTROLS_GAP = 8.dp
private val SUBTITLE_TITLE_MARGIN = 16.dp
private const val EXO_SUBTITLE_BASE_SP = 24f
private const val EXO_SUBTITLE_INNER_PADDING_RATIO = 0.125f

@Composable
private fun Modifier.reportRightEdge(key: String, rights: MutableMap<String, Float>): Modifier {
    DisposableEffect(key, rights) {
        onDispose { rights.remove(key) }
    }
    return onGloballyPositioned { rights[key] = it.boundsInWindow().right }
}

private fun Modifier.reportSubtitleClearance(gapPx: Float, onClearance: (Float) -> Unit): Modifier =
    onGloballyPositioned { coordinates ->
        val rootHeight = coordinates.findRootCoordinates().size.height
        if (rootHeight > 0 && coordinates.size.height > 0) {
            onClearance((rootHeight - coordinates.boundsInRoot().top + gapPx).coerceAtLeast(0f))
        }
    }

private fun PlayerView.isAssOrSsaSubtitleSelected(): Boolean {
    val currentTracks = player?.currentTracks
    if (currentTracks != null) {
        for (group in currentTracks.groups) {
            if (group.type != androidx.media3.common.C.TRACK_TYPE_TEXT) continue
            for (index in 0 until group.length) {
                if (!group.isTrackSelected(index)) continue
                val format = group.getTrackFormat(index)
                if (format.sampleMimeType == androidx.media3.common.MimeTypes.TEXT_SSA) return true
                val hasAssCodec = format.codecs
                    ?.split(',')
                    ?.asSequence()
                    ?.map { it.trim().lowercase(java.util.Locale.US) }
                    ?.any { codec ->
                        codec == androidx.media3.common.MimeTypes.TEXT_SSA ||
                            codec == "s_text/ass" ||
                            codec == "s_text/ssa" ||
                            codec.endsWith("/x-ssa")
                    } == true
                if (hasAssCodec) return true
            }
        }
    }
    val sidecarKey = subtitleView?.getTag(R.id.player_view_sidecar_generation_tag) as? String
    if (sidecarKey != null && (sidecarKey.contains(".ass", ignoreCase = true) || sidecarKey.contains(".ssa", ignoreCase = true))) {
        return true
    }
    return false
}

internal fun PlayerView.applySubtitleStyleIfNeeded(
    subtitleStyle: SubtitleStyleSettings,
    force: Boolean = false,
    liftPx: Float = 0f,
    dimAssOnHdr: Boolean = false
) {
    val isAss = isAssOrSsaSubtitleSelected()
    val config = SubtitleAppliedConfig(subtitleStyle, isAss, liftPx, isAss && dimAssOnHdr)
    if (!force && getTag(R.id.player_view_subtitle_style_tag) == config) {
        return
    }
    val subView = subtitleView
    if (subView == null) {
        // SubtitleView not yet available (no track selected yet). Don't set the
        // tag so that when subtitles become active the style is re-applied.
        return
    }
    setTag(R.id.player_view_subtitle_style_tag, config)
    subView.apply {
        if (isAss) {
            applyEmbeddedAssStyle(dimHdr = dimAssOnHdr)
            reportSubtitleTextMetrics()
            return
        }
        val baseFontSize = EXO_SUBTITLE_BASE_SP
        val scaledFontSize = baseFontSize * (subtitleStyle.size / 100f)
        setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, scaledFontSize)
        setApplyEmbeddedFontSizes(false)

        val typeface = subtitleTypeface(context, subtitleStyle.font, subtitleStyle.bold)
        val edgeType = subtitleCaptionEdge(subtitleStyle.effectiveEdgeStyle)
        val colors = exoSubtitleColors(subtitleStyle)

        setStyle(
            androidx.media3.ui.CaptionStyleCompat(
                subtitleStyle.textColor,
                colors.background,
                colors.window,
                edgeType,
                colors.edgeColor,
                typeface
            )
        )

        setApplyEmbeddedStyles(true)

        val bottomPaddingFraction =
            (0.06f + (subtitleStyle.verticalOffset / 250f)).coerceIn(0f, 0.4f)
        val applyPosition = {
            val extraPadding = (height * (subtitleStyle.verticalOffset / 400f)).toInt().coerceAtLeast(0)
            setPadding(paddingLeft, paddingTop, paddingRight, extraPadding)
            val availableHeight = height - extraPadding
            setBottomPaddingFraction(
                if (liftPx > 0f && availableHeight > 0) {
                    maxOf(bottomPaddingFraction, (liftPx - extraPadding) / availableHeight).coerceIn(0f, 0.5f)
                } else {
                    bottomPaddingFraction
                }
            )
        }
        if (height > 0) {
            applyPosition()
        } else {
            post {
                // Ignore padding queued before a newer track/style application.
                if (this@applySubtitleStyleIfNeeded.getTag(R.id.player_view_subtitle_style_tag) === config) applyPosition()
            }
        }
    }
    if (subView.width > 0) reportSubtitleTextMetrics() else post { reportSubtitleTextMetrics() }
}

private fun PlayerView.reportSubtitleTextMetrics() {
    @Suppress("UNCHECKED_CAST")
    val callback = getTag(R.id.player_view_subtitle_metrics_callback_tag) as? (SubtitleTextMetrics?) -> Unit ?: return
    callback(exoSubtitleTextMetrics())
}

private fun PlayerView.exoSubtitleTextMetrics(): SubtitleTextMetrics? {
    val applied = getTag(R.id.player_view_subtitle_style_tag) as? SubtitleAppliedConfig ?: return null
    if (applied.isAss) return null
    val subView = subtitleView ?: return null
    if (subView.width <= 0) return null
    val textSizePx = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP,
        EXO_SUBTITLE_BASE_SP * (applied.style.size / 100f),
        resources.displayMetrics
    )
    val innerPaddingPx = textSizePx * EXO_SUBTITLE_INNER_PADDING_RATIO
    val location = IntArray(2)
    subView.getLocationInWindow(location)
    return SubtitleTextMetrics(
        textSizePx = textSizePx,
        bold = applied.style.bold,
        scaleX = 1f,
        innerPaddingPx = innerPaddingPx,
        wrapWidthPx = (subView.width - subView.paddingLeft - subView.paddingRight - innerPaddingPx * 2f).coerceAtLeast(0f),
        centerXPx = location[0] + subView.width / 2f
    )
}

private fun measureSubtitleBlockPx(
    paint: android.graphics.Paint,
    text: String,
    metrics: SubtitleTextMetrics
): Float {
    paint.textSize = metrics.textSizePx
    paint.typeface = if (metrics.bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
    return subtitleBlockWidthPx(text, metrics) { paint.measureText(it) }
}

private fun PlayerView.syncLibassOverlay(
    player: ExoPlayer,
    enabled: Boolean,
    renderMode: LibassRenderMode
) {
    val containerId = R.id.libass_overlay_container_gl
    val overlayContainer = findViewById<android.widget.FrameLayout>(containerId) ?: return
    val needsOverlay = enabled && renderMode == LibassRenderMode.OVERLAY && isAssOrSsaSubtitleSelected()
    val boundPlayer = getTag(R.id.libass_overlay_bound_player) as? ExoPlayer
    val hasOverlayChild = overlayContainer.hasAssOverlayChild()

    if (!needsOverlay) {
        if (hasOverlayChild) {
            overlayContainer.removeAssOverlayChildren()
        }
        if (boundPlayer != null) {
            boundPlayer.getAssHandlerCompat()?.videoFrameCallback = null
            setTag(R.id.libass_overlay_bound_player, null)
        }
        return
    }

    val assHandler = player.getAssHandlerCompat() ?: return
    if (boundPlayer === player && hasOverlayChild) {
        return
    }

    overlayContainer.removeAssOverlayChildren()
    // SurfaceView with atlas GL pipeline for best performance.
    // AssPositionRenderer provides position updates when VideoFrameMetadataListener
    // is not active (tunneled playback).
    val surfaceView = AssSubtitleSurfaceView(overlayContainer.context, assHandler)
    assHandler.videoFrameCallback = player.assVideoFrameCallbackCompat { presentationTimeUs, releaseTimeNs ->
        surfaceView.requestRender(presentationTimeUs, releaseTimeNs)
    }
    overlayContainer.addView(
        surfaceView,
        android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
    )
    setTag(R.id.libass_overlay_bound_player, player)
    post { syncLibassOverlayGeometry() }
}

private fun android.widget.FrameLayout.hasAssOverlayChild(): Boolean {
    for (index in 0 until childCount) {
        val child = getChildAt(index)
        if (child is AssSubtitleSurfaceView) {
            return true
        }
    }
    return false
}

private fun android.widget.FrameLayout.findAssOverlayChild(): AssSubtitleSurfaceView? {
    for (index in 0 until childCount) {
        val child = getChildAt(index)
        if (child is AssSubtitleSurfaceView) {
            return child
        }
    }
    return null
}

private fun android.widget.FrameLayout.removeAssOverlayChildren() {
    for (index in childCount - 1 downTo 0) {
        val child = getChildAt(index)
        if (child is AssSubtitleSurfaceView) {
            removeViewAt(index)
        }
    }
}

/**
 * Remove ASS overlay views when hiding so the libass render thread stops.
 * [syncLibassOverlay] re-creates them on the next Compose update cycle.
 */
private fun PlayerView.setAssOverlayVisibility(visibility: Int) {
    if (visibility == android.view.View.GONE) {
        for (containerId in intArrayOf(R.id.libass_overlay_container_gl)) {
            val container = findViewById<android.widget.FrameLayout>(containerId) ?: continue
            container.removeAssOverlayChildren()
        }
        setTag(R.id.libass_overlay_bound_player, null)
        val player = player as? ExoPlayer ?: return
        player.getAssHandlerCompat()?.videoFrameCallback = null
    }
}

@Composable
private fun PlayerControlsOverlay(
    uiState: PlayerUiState,
    playerBadges: List<PlayerBadge>,
    viewModel: PlayerViewModel,
    v2Focus: V2PlayerFocusState,
    customFocus: PlayerCustomFocusState,
    customAvailable: Set<PlayerControlAction>,
    customHasTimeline: Boolean,
    containerFocusRequester: FocusRequester,
    playPauseFocusRequester: FocusRequester,
    progressBarFocusRequester: FocusRequester,
    streamInfoFocusRequester: FocusRequester,
    progressBarUpFocusRequester: FocusRequester? = null,
    onSkipAnchorChanged: (Dp) -> Unit = {},
    onPlayPause: () -> Unit,
    onPlayNextEpisode: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onShowEpisodesPanel: () -> Unit,
    onShowChaptersPanel: () -> Unit,
    onShowSourcesPanel: () -> Unit,
    onShowAudioDialog: () -> Unit,
    onShowSubtitleDialog: () -> Unit,
    onShowSpeedDialog: () -> Unit,
    onToggleAspectRatio: () -> Unit,
    onSwitchPlayerEngine: () -> Unit,
    onReportPlaybackIssue: () -> Unit,
    onToggleMoreActions: () -> Unit,
    onOpenInExternalPlayer: () -> Unit,
    onShowStreamInfo: () -> Unit,
    onTogglePlaybackStats: () -> Unit,
    onResetHideTimer: () -> Unit,
    onHideControls: () -> Unit,
    onBack: () -> Unit,
    reportCodeVisible: Boolean,
    skipButtonVisible: Boolean = false,
    subtitleReportingActive: Boolean = true,
    onSubtitleClearanceChanged: (Float) -> Unit = {},
    onSubtitleTitleBoundsChanged: (Float, Float) -> Unit = { _, _ -> }
) {
    val customIsV2 = LocalV2Appearance.current != null
    val clearanceDensity = LocalDensity.current
    val subtitleGapPx = with(clearanceDensity) { SUBTITLE_CONTROLS_GAP.toPx() }
    val latestClearanceCallback by rememberUpdatedState(onSubtitleClearanceChanged)
    val latestTitleBoundsCallback by rememberUpdatedState(onSubtitleTitleBoundsChanged)
    val titleRights = remember { mutableStateMapOf<String, Float>() }
    var progressBarClearancePx by remember { mutableFloatStateOf(0f) }
    val titleRightPx = titleRights.values.maxOrNull() ?: 0f
    var controlsClearancePx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(subtitleReportingActive, controlsClearancePx) {
        if (subtitleReportingActive && controlsClearancePx > 0f) latestClearanceCallback(controlsClearancePx)
    }
    LaunchedEffect(subtitleReportingActive, progressBarClearancePx, titleRightPx) {
        if (subtitleReportingActive) latestTitleBoundsCallback(progressBarClearancePx, titleRightPx)
    }
    // The deck layouts measure the room above the scrubber for the Skip button; subtitles use the same line.
    val reportSkipAnchor: (Dp) -> Unit = { anchor ->
        onSkipAnchorChanged(anchor)
        controlsClearancePx = with(clearanceDensity) { (anchor + SUBTITLE_CONTROLS_GAP).toPx() }
    }
    var showEngineSwitchConfirm by remember { mutableStateOf(false) }
    val requestEngineSwitch: () -> Unit = { showEngineSwitchConfirm = true }
    if (showEngineSwitchConfirm) {
        LaunchedEffect(Unit) {
            while (true) {
                onResetHideTimer()
                delay(1_000L)
            }
        }
        EngineSwitchConfirmDialog(
            switchingToMpv = uiState.internalPlayerEngine != InternalPlayerEngine.MVP_PLAYER,
            onConfirm = {
                showEngineSwitchConfirm = false
                onSwitchPlayerEngine()
            },
            onDismiss = { showEngineSwitchConfirm = false }
        )
    }
    uiState.controlLayout?.let { layout ->
        PlayerCustomControls(uiState, viewModel, layout, customAvailable, customFocus,
            progressBarFocusRequester, progressBarUpFocusRequester, containerFocusRequester, customHasTimeline,
            playerBadges,
            reportSkipAnchor, V2PlayerActions(
                playPause = onPlayPause, playNext = onPlayNextEpisode, seekToStart = {
                    onSeekTo(0L)
                    if (!customIsV2 && !uiState.isPlaying) onPlayPause()
                }, episodes = onShowEpisodesPanel, chapters = onShowChaptersPanel, sources = onShowSourcesPanel,
                audio = onShowAudioDialog, subtitles = onShowSubtitleDialog, speed = onShowSpeedDialog,
                aspect = onToggleAspectRatio, engine = requestEngineSwitch, report = onReportPlaybackIssue,
                more = onToggleMoreActions, external = onOpenInExternalPlayer, info = onShowStreamInfo,
                stats = onTogglePlaybackStats, interaction = onResetHideTimer, hide = onHideControls,
                party = { viewModel.onEvent(PlayerEvent.OnShowPartyPanel) }), reportCodeVisible)
        return
    }
    if (LocalV2Appearance.current != null) {
        V2PlayerControls(
            uiState, viewModel, v2Focus, playPauseFocusRequester, progressBarFocusRequester,
            streamInfoFocusRequester, progressBarUpFocusRequester, reportSkipAnchor,
            V2PlayerActions(
                playPause = onPlayPause, playNext = onPlayNextEpisode, seekToStart = { onSeekTo(0L) },
                episodes = onShowEpisodesPanel, chapters = onShowChaptersPanel, sources = onShowSourcesPanel,
                audio = onShowAudioDialog, subtitles = onShowSubtitleDialog, speed = onShowSpeedDialog,
                aspect = onToggleAspectRatio, engine = requestEngineSwitch, report = onReportPlaybackIssue,
                more = onToggleMoreActions, external = onOpenInExternalPlayer, info = onShowStreamInfo,
                stats = onTogglePlaybackStats, interaction = onResetHideTimer, hide = onHideControls
            ), reportCodeVisible, playerBadges
        )
        return
    }
    val customPlayPainter = rememberRawSvgPainter(R.raw.ic_player_play)
    val customPausePainter = rememberRawSvgPainter(R.raw.ic_player_pause)
    val customSubtitlePainter = rememberRawSvgPainter(R.raw.ic_player_subtitles)
    val customAudioPainter = rememberRawSvgPainter(R.raw.ic_player_audio_filled)
    val customSourcePainter = rememberRawSvgPainter(R.raw.ic_player_source)
    val customAspectPainter = rememberRawSvgPainter(R.raw.ic_player_aspect_ratio)
    val customEpisodesPainter = rememberRawSvgPainter(R.raw.ic_player_episodes)

    val density = LocalDensity.current
    val clearanceOffsetPx = with(density) { (NuvioTheme.spacing.xl - SUBTITLE_CONTROLS_GAP).toPx() }
    val rootView = LocalView.current
    val videoBottomFraction = viewModel.controller.videoBottomFractionState.value
    val bandDp = videoBottomFraction?.let { f ->
        if (f > 0.5f && f < 0.995f && rootView.height > 0) {
            with(density) { ((1f - f) * rootView.height).toDp() }
        } else {
            null
        }
    }
    val letterboxActive = bandDp != null && bandDp >= 64.dp &&
        uiState.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT
    var belowBarHeightPx by remember { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Top gradient
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.7f),
                            Color.Transparent
                        )
                    )
                )
        )

        // playerMetaChips
        uiState.streamInfoData?.let { info ->
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = NuvioTheme.spacing.xxl, top = NuvioTheme.spacing.xl),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val resChip = if (info.videoWidth != null && info.videoHeight != null) {
                    formatResolution(info.videoWidth, info.videoHeight).substringAfter("(").removeSuffix(")")
                } else null
                val sizeChip = info.fileSize?.let { bytes ->
                    if (bytes >= 1_073_741_824L) "%.1f GB".format(bytes / 1_073_741_824.0)
                    else "%.0f MB".format(bytes / 1_048_576.0)
                }
                val audioChip = info.audioCodec?.let { codec ->
                    info.audioChannels?.let { ch -> "$codec $ch" } ?: codec
                }
                listOfNotNull(resChip, sizeChip, audioChip).forEach { label ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.White.copy(alpha = 0.12f))
                            .padding(horizontal = NuvioTheme.spacing.sm, vertical = 3.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Bottom gradient
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.8f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .onGloballyPositioned { coordinates ->
                    val rootHeight = coordinates.findRootCoordinates().size.height
                    val top = coordinates.boundsInRoot().top
                    if (rootHeight > 0) {
                        controlsClearancePx = (rootHeight - top - clearanceOffsetPx).coerceAtLeast(0f)
                    }
                }
                .padding(start = NuvioTheme.spacing.xxl, end = NuvioTheme.spacing.xxl, top = NuvioTheme.spacing.xl, bottom = 48.dp)
        ) {
            val skipIntroVisible = uiState.activeSkipInterval != null
            val hasEpisodeContext = uiState.currentSeason != null && uiState.currentEpisode != null
            val hasSubtitleControl = uiState.subtitleTracks.isNotEmpty() || uiState.addonSubtitles.isNotEmpty()
            val hasAudioControl = uiState.audioTracks.isNotEmpty()
            val showNextEpisodeButton = uiState.nextEpisode?.hasAired == true &&
                (uiState.postPlayMode as? PostPlayMode.AutoPlay)?.let {
                    !it.searching && it.countdownSec == null
                } != false

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                Box(modifier = Modifier.weight(1f)) {
            androidx.compose.animation.AnimatedVisibility(
                visible = !skipIntroVisible,
                enter = fadeIn(animationSpec = tween(NuvioMotion.tokens.durations.fast)),
                exit = fadeOut(animationSpec = tween(NuvioMotion.tokens.durations.fast))
            ) {
                Column(modifier = Modifier.fillMaxWidth().hideUnderSeekThumbnails()) {
                    val displayName = if (uiState.currentSeason != null && uiState.currentEpisode != null) {
                        uiState.contentName ?: uiState.title
                    } else {
                        uiState.title
                    }

                    val titleLogo = uiState.logo
                    var titleLogoFailed by remember(titleLogo) { mutableStateOf(false) }
                    if (!titleLogo.isNullOrBlank() && !titleLogoFailed) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(titleLogo)
                                .memoryCacheKey(titleLogo)
                                .build(),
                            contentDescription = displayName,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.BottomStart,
                            modifier = Modifier
                                .sizeIn(maxWidth = 340.dp, maxHeight = 72.dp)
                                .reportRightEdge("name", titleRights),
                            onError = { titleLogoFailed = true }
                        )
                    } else {
                        Text(
                            text = displayName,
                            modifier = Modifier.reportRightEdge("name", titleRights),
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    PlayerSecondaryMetadata(
                        uiState,
                        modifier = Modifier.reportRightEdge("episode", titleRights),
                        chapter = rememberCurrentChapter(viewModel, uiState.chapters)
                    )

                    if (playerBadges.isNotEmpty()) {
                        PlayerBadges(
                            badges = playerBadges,
                            modifier = Modifier
                                .padding(vertical = NuvioTheme.spacing.xs)
                                .reportRightEdge("badges", titleRights)
                        )
                    }
                }
            }
                }

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ControlButton(
                            icon = Icons.Default.Info,
                            contentDescription = stringResource(R.string.cd_playback_stats),
                            onClick = onTogglePlaybackStats,
                            focusRequester = streamInfoFocusRequester,
                            downFocusRequester = progressBarFocusRequester,
                            onUpKey = onHideControls,
                            onFocused = onResetHideTimer
                        )
                        if (hasAudioControl) {
                        ControlButton(
                            icon = Icons.Default.Speaker,
                            contentDescription = stringResource(R.string.cd_audio_tracks),
                            onClick = onShowAudioDialog,
                            downFocusRequester = progressBarFocusRequester,
                            onUpKey = onHideControls,
                            onFocused = onResetHideTimer
                        )
                        }
                        if (hasSubtitleControl) {
                        ControlButton(
                            icon = Icons.Default.Chat,
                            contentDescription = stringResource(R.string.cd_subtitles),
                            onClick = onShowSubtitleDialog,
                            downFocusRequester = progressBarFocusRequester,
                            onUpKey = onHideControls,
                            onFocused = onResetHideTimer
                        )
                        }
                        ControlButton(
                            icon = Icons.Default.Cloud,
                            contentDescription = stringResource(R.string.cd_sources),
                            onClick = onShowSourcesPanel,
                            downFocusRequester = progressBarFocusRequester,
                            onUpKey = onHideControls,
                            onFocused = onResetHideTimer
                        )
                        AnimatedVisibility(
                            visible = uiState.showMoreDialog,
                            enter = slideInHorizontally(
                                animationSpec = tween(NuvioMotion.tokens.durations.fast),
                                initialOffsetX = { it / 2 }
                            ) + fadeIn(animationSpec = tween(NuvioMotion.tokens.durations.fast)),
                            exit = slideOutHorizontally(
                                animationSpec = tween(160),
                                targetOffsetX = { it / 2 }
                            ) + fadeOut(animationSpec = tween(160))
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                        ControlButton(
                            icon = Icons.Default.Speed,
                            contentDescription = stringResource(R.string.cd_playback_speed),
                            onClick = onShowSpeedDialog,
                            downFocusRequester = progressBarFocusRequester,
                            onUpKey = onHideControls,
                            onFocused = onResetHideTimer
                        )
                        ControlButton(
                            icon = Icons.Default.AspectRatio,
                            iconPainter = customAspectPainter,
                            contentDescription = stringResource(R.string.cd_aspect_ratio),
                            onClick = onToggleAspectRatio,
                            downFocusRequester = progressBarFocusRequester,
                            onUpKey = onHideControls,
                            onFocused = onResetHideTimer
                        )
                        if (!uiState.isServerStream) {
                            ControlButton(
                                icon = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(R.string.cd_open_external_player),
                                onClick = onOpenInExternalPlayer,
                                downFocusRequester = progressBarFocusRequester,
                                onUpKey = onHideControls,
                                onFocused = onResetHideTimer
                            )
                        }
                        ControlButton(
                            icon = Icons.Default.SwapHoriz,
                            contentDescription = stringResource(R.string.cd_switch_player_engine),
                            onClick = requestEngineSwitch,
                            downFocusRequester = progressBarFocusRequester,
                            onUpKey = onHideControls,
                            onFocused = onResetHideTimer
                        )
                            if (uiState.playbackIssueReportsEnabled) {
                                ReportControlButton(
                                    reportId = uiState.playbackIssueReportId,
                                    showReportId = reportCodeVisible,
                                    onClick = onReportPlaybackIssue,
                                    enabled = uiState.playbackIssueReportStatus != PlaybackIssueReportStatus.Sending &&
                                        uiState.playbackIssueReportStatus != PlaybackIssueReportStatus.Sent,
                                    upFocusRequester = progressBarFocusRequester,
                                    onDownKey = onHideControls,
                                    onFocused = onResetHideTimer
                                )
                            }
                            }
                        }
                        ControlButton(
                            icon = if (uiState.showMoreDialog) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = if (uiState.showMoreDialog) stringResource(R.string.cd_close_more_actions) else stringResource(R.string.cd_more_actions),
                            onClick = onToggleMoreActions,
                            downFocusRequester = progressBarFocusRequester,
                            onUpKey = onHideControls,
                            onFocused = onResetHideTimer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))

            // Progress bar — always LTR regardless of locale
            DisposableEffect(Unit) {
                onDispose { progressBarClearancePx = 0f }
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            val rootHeight = coordinates.findRootCoordinates().size.height
                            if (rootHeight > 0) {
                                progressBarClearancePx =
                                    (rootHeight - coordinates.boundsInRoot().top + subtitleGapPx).coerceAtLeast(0f)
                            }
                        }
                ) {
                    PlayerControlsProgressBarHost(
                        viewModel = viewModel,
                        focusRequester = progressBarFocusRequester,
                        upFocusRequester = progressBarUpFocusRequester ?: streamInfoFocusRequester,
                        downFocusRequester = playPauseFocusRequester,
                        onUpKey = onHideControls,
                        onFocused = onResetHideTimer,
                        onClick = onPlayPause
                    )
                }
            }

            val edgeGap = if (bandDp != null && letterboxActive && belowBarHeightPx > 0) {
                (bandDp - with(density) { belowBarHeightPx.toDp() } - 48.dp).coerceAtLeast(NuvioTheme.spacing.xs)
            } else {
                NuvioTheme.spacing.xs
            }

            // Report where the Skip button's bottom should sit so it clears the scrubber
            // and aligns with the title-block bottom (title hides while skipping). Stack
            // from the screen bottom: 48dp column pad + below-bar block + edgeGap + the
            // 20dp scrubber + the xs spacer above it. Only report once measured, so the
            // first frame keeps PlayerScreen's safe default instead of a too-low value.
            if (belowBarHeightPx > 0) {
                val skipAnchor = 48.dp + with(density) { belowBarHeightPx.toDp() } +
                    edgeGap + 20.dp + NuvioTheme.spacing.xs
                LaunchedEffect(skipAnchor) { onSkipAnchorChanged(skipAnchor) }
            }
            Spacer(modifier = Modifier.height(edgeGap))

            Column(modifier = Modifier.onSizeChanged { belowBarHeightPx = it.height }) {
            PlayerControlsTimeTextHost(viewModel = viewModel)

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PillControlButton(
                        icon = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        iconPainter = if (uiState.isPlaying) customPausePainter else customPlayPainter,
                        label = if (uiState.isPlaying) stringResource(R.string.player_pill_pause) else stringResource(R.string.player_pill_play),
                        onClick = onPlayPause,
                        focusRequester = playPauseFocusRequester,
                        upFocusRequester = progressBarFocusRequester,
                        onDownKey = onHideControls,
                        onFocused = onResetHideTimer
                    )
                    PillControlButton(
                        icon = Icons.Default.RestartAlt,
                        label = stringResource(R.string.player_pill_restart),
                        onClick = { onSeekTo(0L); if (!uiState.isPlaying) onPlayPause() },
                        upFocusRequester = progressBarFocusRequester,
                        onDownKey = onHideControls,
                        onFocused = onResetHideTimer
                    )
                    if (hasEpisodeContext) {
                    PillControlButton(
                        icon = Icons.AutoMirrored.Filled.List,
                        iconPainter = customEpisodesPainter,
                        label = stringResource(R.string.player_pill_episodes),
                        onClick = onShowEpisodesPanel,
                        upFocusRequester = progressBarFocusRequester,
                        onDownKey = onHideControls,
                        onFocused = onResetHideTimer
                    )
                    }
                    if (uiState.chapters.isNotEmpty()) {
                    PillControlButton(
                        icon = Icons.Default.Bookmarks,
                        label = stringResource(R.string.player_chapters),
                        onClick = onShowChaptersPanel,
                        upFocusRequester = progressBarFocusRequester,
                        onDownKey = onHideControls,
                        onFocused = onResetHideTimer
                    )
                    }
                    if (showNextEpisodeButton) {
                    PillControlButton(
                        icon = Icons.Default.SkipNext,
                        label = stringResource(R.string.player_pill_next_episode),
                        onClick = onPlayNextEpisode,
                        upFocusRequester = progressBarFocusRequester,
                        onDownKey = onHideControls,
                        onFocused = onResetHideTimer
                    )
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun EngineSwitchConfirmDialog(
    switchingToMpv: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val confirmFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { confirmFocusRequester.requestFocus() }
    }
    val targetEngine = stringResource(
        if (switchingToMpv) R.string.playback_engine_mvplayer else R.string.playback_engine_exoplayer
    )
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.player_engine_switch_title, targetEngine),
        subtitle = stringResource(R.string.player_engine_switch_message)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            DialogButton(
                text = stringResource(R.string.action_switch),
                onClick = onConfirm,
                isPrimary = true,
                modifier = Modifier.focusRequester(confirmFocusRequester)
            )
            DialogButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                isPrimary = false
            )
        }
    }
}

/** A tap moves 10 s; a key repeat means the key is held and the cursor glides. */
private fun scrubEvent(event: KeyEvent, forward: Boolean): PlayerEvent =
    if (event.repeatCount == 0) PlayerEvent.OnPreviewSeekBy(PlayerScrubRates.tapDeltaMs(forward))
    else PlayerEvent.OnScrubHold(forward, event.downTime, event.eventTime)

@Composable
internal fun PlayerControlsProgressBarHost(
    viewModel: PlayerViewModel,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onUpKey: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val playbackTimeline by viewModel.playbackTimeline.collectAsState()
    val gliding = ScrubGlide.active
    val chapterFractions = rememberChapterFractions(viewModel, playbackTimeline.duration)

    ProgressBar(
        currentPosition = if (gliding) ScrubGlide.positionMs else playbackTimeline.currentPosition,
        playbackPosition = playbackTimeline.playbackPosition,
        duration = playbackTimeline.duration,
        onSeekPreview = { delta ->
            viewModel.onEvent(PlayerEvent.OnPreviewSeekBy(delta))
        },
        onSeekCommit = {
            viewModel.onEvent(PlayerEvent.OnCommitPreviewSeek)
        },
        onScrubHold = { forward, downTime, eventTime ->
            viewModel.onEvent(PlayerEvent.OnScrubHold(forward, downTime, eventTime))
        },
        gliding = gliding,
        focusRequester = focusRequester,
        upFocusRequester = upFocusRequester,
        downFocusRequester = downFocusRequester,
        onUpKey = onUpKey,
        onFocused = onFocused,
        onClick = onClick,
        bufferedPosition = playbackTimeline.bufferedPosition,
        chapterFractions = chapterFractions
    )
}

@Composable
internal fun PlayerControlsTimeTextHost(viewModel: PlayerViewModel) {
    val playbackTimeline by viewModel.playbackTimeline.collectAsState()
    val shownPosition = if (ScrubGlide.active) ScrubGlide.positionMs else playbackTimeline.currentPosition
    val remainingMs = (playbackTimeline.duration - shownPosition).coerceAtLeast(0L)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = formatSeekTime(shownPosition),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.9f)
        )
        Text(
            text = "-" + formatSeekTime(remainingMs),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.9f)
        )
    }
}

@Composable
private fun ReportControlButton(
    reportId: String?,
    showReportId: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
    upFocusRequester: FocusRequester,
    onDownKey: () -> Unit,
    onFocused: () -> Unit
) {
    Box(contentAlignment = Alignment.Center) {
        ControlButton(
            icon = Icons.Default.BugReport,
            contentDescription = stringResource(R.string.player_report_issue),
            onClick = onClick,
            enabled = enabled,
            upFocusRequester = upFocusRequester,
            onDownKey = onDownKey,
            onFocused = onFocused
        )
        AnimatedVisibility(
            visible = showReportId && !reportId.isNullOrBlank(),
            enter = fadeIn(animationSpec = tween(NuvioMotion.tokens.durations.fast)),
            exit = fadeOut(animationSpec = tween(NuvioMotion.tokens.durations.fast)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-30).dp)
        ) {
            Text(
                text = reportId.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
internal fun PillControlButton(
    icon: ImageVector,
    iconPainter: Painter? = null,
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    onDownKey: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    labelMaxLines: Int = Int.MAX_VALUE
) {
    val isV2 = LocalV2Appearance.current != null
    Button(
        onClick = onClick,
        modifier = modifier
            .nuvioControlSurface(RoundedCornerShape(50))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(
                if (upFocusRequester != null) Modifier.focusProperties { up = upFocusRequester }
                else Modifier
            )
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                    false
                } else when (keyEvent.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (upFocusRequester != null) {
                            try { upFocusRequester.requestFocus() } catch (_: Exception) {}
                            true
                        } else false
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (onDownKey != null) { onDownKey.invoke(); true } else false
                    }
                    else -> false
                }
            }
            .onFocusChanged { if (it.isFocused) onFocused?.invoke() },
        colors = ButtonDefaults.colors(
            containerColor = if (isV2) Color.Transparent else Color.White.copy(alpha = 0.14f),
            focusedContainerColor = if (isV2) Color.Transparent else Color.White,
            contentColor = Color.White,
            focusedContentColor = if (isV2) Color.White else Color.Black
        ),
        scale = if (isV2) ButtonDefaults.scale(focusedScale = 1f) else ButtonDefaults.scale(),
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50))
    ) {
        if (iconPainter != null) {
            Icon(painter = iconPainter, contentDescription = null, modifier = Modifier.size(18.dp))
        } else {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge, maxLines = labelMaxLines, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ControlButton(
    icon: ImageVector,
    iconPainter: Painter? = null,
    contentDescription: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    enabled: Boolean = true,
    downFocusRequester: FocusRequester? = null,
    onUpKey: (() -> Unit)? = null,
    onDownKey: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(NuvioTheme.spacing.xxxl)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .then(
                if (upFocusRequester != null || downFocusRequester != null) {
                    Modifier.focusProperties {
                        upFocusRequester?.let { up = it }
                        downFocusRequester?.let { down = it }
                    }
                } else {
                    Modifier
                }
            )
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                    false
                } else when (keyEvent.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (upFocusRequester != null) {
                            try { upFocusRequester.requestFocus() } catch (_: Exception) {}
                            true
                        } else if (onUpKey != null) { onUpKey.invoke(); true } else false
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (downFocusRequester != null) {
                            try { downFocusRequester.requestFocus() } catch (_: Exception) {}
                            true
                        } else if (onDownKey != null) { onDownKey.invoke(); true } else false
                    }
                    else -> false
                }
            }
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            },
        colors = IconButtonDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White,
            contentColor = Color.White,
            focusedContentColor = Color.Black
        ),
        shape = IconButtonDefaults.shape(shape = CircleShape)
    ) {
        if (iconPainter != null) {
            Icon(
                painter = iconPainter,
                contentDescription = contentDescription,
                modifier = Modifier.size(NuvioTheme.spacing.xl)
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun ProgressBar(
    currentPosition: Long,
    duration: Long,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    onScrubHold: (forward: Boolean, downTime: Long, eventTime: Long) -> Unit = { _, _, _ -> },
    gliding: Boolean = false,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onUpKey: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    /** Position (ms) up to which content is buffered. Pass 0 to skip the overlay. */
    bufferedPosition: Long = 0L,
    playbackPosition: Long = currentPosition,
    chapterFractions: List<Float> = emptyList()
) {
    val v2ProgressBrush = if (com.nuvio.tv.ui.v2.appearance.LocalV2Appearance.current != null) {
        com.nuvio.tv.ui.theme.createThemeBrush(com.nuvio.tv.ui.v2.appearance.v2AccentColors())
    } else androidx.compose.ui.graphics.SolidColor(Color.White)
    val geometry = PlaybackTimelineGeometry.from(playbackPosition, currentPosition, bufferedPosition, duration)
    val progress = geometry.played
    val bufferedProgress = geometry.buffered
    // A glide moves every frame on its own; a tap or the landing after a glide eases in.
    val animatedPreview by animateFloatAsState(
        targetValue = geometry.preview,
        animationSpec = if (gliding) snap() else tween(150, easing = FastOutSlowInEasing),
        label = "seekPreview"
    )
    val previewFraction = if (gliding) geometry.preview else animatedPreview
    val seekBarLabel = stringResource(R.string.cd_seek_bar)

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(100),
        label = "progress"
    )
    val animatedBufferedProgress by animateFloatAsState(
        targetValue = bufferedProgress,
        animationSpec = tween(100),
        label = "bufferedProgress"
    )
    var isFocused by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
            .onGloballyPositioned { SeekBarAnchor.bounds = it.boundsInRoot() }
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .then(
                if (upFocusRequester != null || downFocusRequester != null) {
                    Modifier.focusProperties {
                        upFocusRequester?.let { up = it }
                        downFocusRequester?.let { down = it }
                    }
                } else {
                    Modifier
                }
            )
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .focusable()
            .semantics {
                contentDescription = seekBarLabel
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
            }
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onSeekCommit()
                            return@onPreviewKeyEvent true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (onClick != null) return@onPreviewKeyEvent true
                        }
                    }
                    return@onPreviewKeyEvent false
                }

                // testing additional key handling for DPAD_LEFT and DPAD_RIGHT to allow seek in focus (check)
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (onClick != null) {
                                // Holding OK must not toggle on every repeat.
                                if (PauseOnTimeline.firstPress(keyEvent.nativeKeyEvent.action, keyEvent.nativeKeyEvent.repeatCount)) onClick()
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (downFocusRequester != null) {
                                try {
                                    downFocusRequester.requestFocus()
                                } catch (_: Exception) {
                                }
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (upFocusRequester != null) {
                                try {
                                    upFocusRequester.requestFocus()
                                } catch (_: Exception) {
                                }
                                true
                            } else if (onUpKey != null) {
                                onUpKey.invoke()
                                true
                            } else {
                                false
                            }
                        }
                        // A tap moves 10 s, a held key glides, as on the hidden-controls path.
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val forward = keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                            if (keyEvent.nativeKeyEvent.repeatCount == 0) {
                                onSeekPreview(PlayerScrubRates.tapDeltaMs(forward))
                            } else {
                                onScrubHold(forward, keyEvent.nativeKeyEvent.downTime, keyEvent.nativeKeyEvent.eventTime)
                            }
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        val trackWidth = maxWidth
        val trackHeight by animateDpAsState(
            targetValue = if (isFocused) 8.dp else 4.dp,
            animationSpec = tween(160),
            label = "trackHeight"
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = if (isFocused) 0.24f else 0.18f))
                // Neutral buffer tint remains distinct from both the dim track and themed played fill.
                .playerProgressFill(
                    progress = { animatedProgress },
                    bufferedProgress = { animatedBufferedProgress },
                    bufferedColor = Color.White,
                    playedBrush = v2ProgressBrush
                )
                .chapterGaps(chapterFractions)
        ) {
        // A lighter fill between playback and the seek cursor shows how far the seek goes.
        if (geometry.isPreviewing) {
            val rewinding = previewFraction < animatedProgress
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .offset(x = trackWidth * minOf(animatedProgress, previewFraction))
                    .width(trackWidth * abs(previewFraction - animatedProgress))
                    .then(
                        if (rewinding) Modifier.background(Color.Black.copy(alpha = 0.45f))
                        else Modifier.background(v2ProgressBrush, alpha = 0.45f)
                    )
            )
        }
        }
        if (geometry.hasDuration) {
            val markerSize = if (isFocused && !geometry.isPreviewing) 12.dp else 8.dp
            Box(
                modifier = Modifier.align(Alignment.CenterStart)
                    .offset {
                        val track = trackWidth.toPx()
                        val marker = markerSize.toPx()
                        IntOffset(
                            (track * animatedProgress - marker / 2).coerceIn(0f, (track - marker).coerceAtLeast(0f)).roundToInt(),
                            0
                        )
                    }
                    .size(markerSize).clip(CircleShape).background(Color.White)
            )
            if (geometry.isPreviewing) {
                // A hollow seek cursor leaves the solid marker at actual playback.
                Box(
                    modifier = Modifier.align(Alignment.CenterStart)
                        .offset {
                            val track = trackWidth.toPx()
                            val cursor = 12.dp.toPx()
                            IntOffset(
                                (track * previewFraction - cursor / 2).coerceIn(0f, (track - cursor).coerceAtLeast(0f)).roundToInt(),
                                0
                            )
                        }
                        .size(12.dp).border(2.dp, Color.White, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun SeekOverlay(
    currentPosition: Long,
    duration: Long,
    playbackPosition: Long = currentPosition,
    bufferedPosition: Long = 0L,
    gliding: Boolean = false,
    chapterFractions: List<Float> = emptyList(),
    chapterText: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.spacing.xxl, vertical = NuvioTheme.spacing.xl)
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            ProgressBar(
                currentPosition = currentPosition,
                duration = duration,
                onSeekPreview = {},
                onSeekCommit = {},
                gliding = gliding,
                playbackPosition = playbackPosition,
                bufferedPosition = bufferedPosition,
                chapterFractions = chapterFractions
            )

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (chapterText != null) {
                    Text(
                        text = chapterText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = "${formatSeekTime(currentPosition)} / ${formatSeekTime(duration)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
private fun SeekOverlayHost(viewModel: PlayerViewModel) {
    val playbackTimeline by viewModel.playbackTimeline.collectAsState()
    val gliding = ScrubGlide.active

    SeekOverlay(
        currentPosition = if (gliding) ScrubGlide.positionMs else playbackTimeline.currentPosition,
        playbackPosition = playbackTimeline.playbackPosition,
        duration = playbackTimeline.duration,
        bufferedPosition = playbackTimeline.bufferedPosition,
        gliding = gliding,
        chapterFractions = rememberChapterFractions(viewModel, playbackTimeline.duration),
        chapterText = rememberSeekChapter(viewModel)?.takeUnless { SeekThumbnailOverlay.visible }?.let { chapterLabel(it) }
    )
}

@Composable
private fun PlayerClockOverlay(
    currentPosition: Long,
    duration: Long,
    playbackSpeed: Float
) {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current
    val timeFormatter = remember(context) { DateFormat.getTimeFormat(context) }

    LaunchedEffect(Unit) {
        while (true) {
            val current = System.currentTimeMillis()
            nowMs = current
            val delayMs = (1_000L - (current % 1_000L)).coerceAtLeast(250L)
            delay(delayMs)
        }
    }

    val effectiveSpeed = playbackSpeed.takeIf { it > 0f } ?: 1f
    val remainingMediaMs = (duration - currentPosition).coerceAtLeast(0L)
    val remainingMs = kotlin.math.ceil(remainingMediaMs.toDouble() / effectiveSpeed.toDouble()).toLong()
    val endTimeText = if (duration > 0L) {
        timeFormatter.format(Date(nowMs + remainingMs))
    } else {
        "--:--"
    }

    val clockShadow = playerClockShadow(LocalDensity.current.density)
    Column(
        modifier = Modifier
            .padding(horizontal = NuvioTheme.spacing.xxs, vertical = NuvioTheme.spacing.xxs),
        horizontalAlignment = Alignment.End
    ) {
        Text(
            text = timeFormatter.format(Date(nowMs)),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = PLAYER_CLOCK_FONT_SIZE,
                fontWeight = FontWeight.SemiBold,
                shadow = clockShadow
            ),
            color = Color.White.copy(alpha = 0.96f)
        )
        Text(
            text = stringResource(R.string.player_ends_at, endTimeText),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = PLAYER_ENDS_AT_FONT_SIZE,
                fontWeight = FontWeight.Medium,
                shadow = clockShadow
            ),
            color = Color.White.copy(alpha = PLAYER_ENDS_AT_ALPHA)
        )
    }
}

@Composable
private fun PlayerClockOverlayHost(viewModel: PlayerViewModel, playbackSpeed: Float) {
    val playbackTimeline by viewModel.playbackTimeline.collectAsState()

    PlayerClockOverlay(
        currentPosition = playbackTimeline.currentPosition,
        duration = playbackTimeline.duration,
        playbackSpeed = playbackSpeed
    )
}

@Composable
private fun SubtitleTimingDialogHost(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    subtitleDelayMs: Int,
    selectedAddonSubtitle: Subtitle?,
    cues: List<SubtitleSyncCue>,
    capturedVideoMs: Long?,
    statusMessage: String?,
    errorMessage: String?,
    isLoadingCues: Boolean,
    onCaptureNow: () -> Unit,
    onCueSelected: (SubtitleSyncCue) -> Unit
) {
    val playbackTimeline by viewModel.playbackTimeline.collectAsState()

    SubtitleTimingDialog(
        modifier = modifier,
        currentPositionMs = playbackTimeline.currentPosition,
        subtitleDelayMs = subtitleDelayMs,
        selectedAddonSubtitle = selectedAddonSubtitle,
        cues = cues,
        capturedVideoMs = capturedVideoMs,
        statusMessage = statusMessage,
        errorMessage = errorMessage,
        isLoadingCues = isLoadingCues,
        onCaptureNow = onCaptureNow,
        onCueSelected = onCueSelected
    )
}

private const val REMOTE_BUTTON_BANNER_MS = 1_800L

/** A fresh instance per press, so the same text shown twice restarts its timer. */
private class RemoteButtonBanner(val text: String)

/** The button whose key-down ran a job; its repeats and release belong to that job, whatever opens meanwhile. */
private class RemoteKeyClaim {
    private var keyCode = KeyEvent.KEYCODE_UNKNOWN
    private var binding: RemoteButtonBinding? = null

    fun claim(keyCode: Int, binding: RemoteButtonBinding) {
        this.keyCode = keyCode
        this.binding = binding
    }

    fun bindingFor(keyCode: Int): RemoteButtonBinding? = binding?.takeIf { keyCode == this.keyCode }

    fun release() {
        keyCode = KeyEvent.KEYCODE_UNKNOWN
        binding = null
    }
}

private fun remoteLanguageMatches(trackLanguage: String?, wanted: String): Boolean {
    val track = trackLanguage?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
    val want = wanted.lowercase().takeIf { it.length >= 2 } ?: return false
    return track == want || track.substringBefore('-') == want.substringBefore('-') ||
        track.take(2) == want.take(2)
}

private fun TrackInfo.remoteLabel(): String = buildString {
    append(name.ifBlank { language.orEmpty() })
    val details = listOfNotNull(
        codec?.takeIf { it.isNotBlank() },
        channelCount?.let { if (it > 2) "${it - 1}.1" else if (it == 2) "2.0" else null }
    )
    if (details.isNotEmpty() && name.isNotBlank()) append(" · ").append(details.joinToString(" "))
}

@Composable
private fun RemoteButtonBannerPill(text: String) {
    Box(
        modifier = Modifier
            .background(
                color = Color.Black.copy(alpha = 0.85f),
                shape = RoundedCornerShape(NuvioTheme.spacing.xl)
            )
            .padding(horizontal = 20.dp, vertical = NuvioTheme.spacing.md)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = Color.White
        )
    }
}

@Composable
private fun AspectRatioIndicator(text: String) {
    val customAspectPainter = rememberRawSvgPainter(R.raw.ic_player_aspect_ratio)

    // Floating pill indicator for aspect ratio changes
    Row(
        modifier = Modifier
            .background(
                color = Color.Black.copy(alpha = 0.85f),
                shape = RoundedCornerShape(NuvioTheme.spacing.xl)
            )
            .padding(horizontal = 20.dp, vertical = NuvioTheme.spacing.md),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon background circle
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = Color(0xFF3B3B3B),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = customAspectPainter,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))

        // Text
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = Color.White
        )
    }
}

@Composable
private fun StreamSourceIndicator(text: String) {
    Row(
        modifier = Modifier
            .background(
                color = Color.Black.copy(alpha = 0.82f),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PlayerEngineSwitchIndicator(
    title: String,
    message: String
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.86f))
            .padding(horizontal = 22.dp, vertical = NuvioTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.92f),
            textAlign = TextAlign.Center
        )
    }
}

private enum class SubtitleDelayFocusTarget {
    SLIDER,
    RESET,
    SYNC_LINE
}

@Composable
private fun SubtitleDelayOverlay(
    subtitleDelayMs: Int,
    isResetButtonFocused: Boolean,
    isSyncLineButtonFocused: Boolean,
    isSliderFocused: Boolean,
    onResetDelay: () -> Unit,
    onOpenSyncByLine: () -> Unit,
    resetFocusRequester: FocusRequester,
    syncLineFocusRequester: FocusRequester,
    onResetFocused: () -> Unit = {},
    onSyncLineFocused: () -> Unit = {}
) {
    val fraction = ((subtitleDelayMs - SUBTITLE_DELAY_MIN_MS).toFloat() /
        (SUBTITLE_DELAY_MAX_MS - SUBTITLE_DELAY_MIN_MS).toFloat()).coerceIn(0f, 1f)
    val sliderAccent = if (isSliderFocused) NuvioTheme.colors.Secondary else Color.White

    Column(
        modifier = Modifier
            .fillMaxWidth(0.6f)
            .then(if (com.nuvio.tv.ui.v2.appearance.LocalV2Appearance.current != null)
                Modifier.nuvioGlass(GlassRole.PANEL, shape = RoundedCornerShape(20.dp))
                else Modifier.clip(RoundedCornerShape(26.dp)).background(Color(0xCC0F0F0F)))
            .padding(horizontal = 26.dp, vertical = 20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.player_subtitle_delay),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White
            )
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = formatSubtitleDelay(subtitleDelayMs),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.95f)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(NuvioTheme.spacing.xl)
        ) {
            val thumbWidth = 22.dp
            val thumbOffset = (maxWidth - thumbWidth) * fraction

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NuvioTheme.spacing.xs)
                    .clip(RoundedCornerShape(NuvioTheme.radii.xxs))
                    .align(AbsoluteAlignment.CenterLeft)
                    .background(Color.White.copy(alpha = 0.15f))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(5) { index ->
                    val tickHeight = if (index == 2) 13.dp else 9.dp
                    Box(
                        modifier = Modifier
                            .width(NuvioTheme.spacing.hairline)
                            .height(tickHeight)
                            .background(sliderAccent.copy(alpha = if (isSliderFocused) 0.52f else 0.22f))
                    )
                }
            }

            Box(
                modifier = Modifier
                    .absoluteOffset(x = thumbOffset)
                    .align(AbsoluteAlignment.CenterLeft)
                    .width(thumbWidth)
                    .height(NuvioTheme.spacing.sm)
                    .clip(RoundedCornerShape(NuvioTheme.radii.sm))
                    .background(sliderAccent.copy(alpha = 0.95f))
            )
        }

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Card(
                onClick = onResetDelay,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(resetFocusRequester)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            onResetFocused()
                        }
                    },
                colors = CardDefaults.colors(
                    containerColor = if (isResetButtonFocused) {
                        Color.White.copy(alpha = 0.22f)
                    } else {
                        Color.White.copy(alpha = 0.11f)
                    },
                    focusedContainerColor = Color.White.copy(alpha = 0.22f)
                ),
                shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.subtitle_delay_reset),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }

            Card(
                onClick = onOpenSyncByLine,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(syncLineFocusRequester)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            onSyncLineFocused()
                        }
                    },
                colors = CardDefaults.colors(
                    containerColor = if (isSyncLineButtonFocused) {
                        Color.White.copy(alpha = 0.22f)
                    } else {
                        Color.White.copy(alpha = 0.11f)
                    },
                    focusedContainerColor = Color.White.copy(alpha = 0.22f)
                ),
                shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.player_sync_line),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
internal fun rememberRawSvgPainter(@RawRes iconRes: Int): Painter {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sizePx = with(density) { NuvioTheme.spacing.xl.roundToPx() }
    val request = remember(iconRes, context, sizePx) {
        ImageRequest.Builder(context)
            .data(iconRes)
            .size(sizePx)
            .build()
    }
    return rememberAsyncImagePainter(model = request)
}

@Composable
private fun LoadingIssueReportAction(
    elapsedMs: Long,
    reportStatus: PlaybackIssueReportStatus,
    reportId: String?,
    reportError: String?,
    onReport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.lg))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        val reportMessage = when (reportStatus) {
            PlaybackIssueReportStatus.Sent -> stringResource(R.string.player_report_issue_sent, reportId.orEmpty())
            PlaybackIssueReportStatus.Failed -> reportError ?: stringResource(R.string.player_report_issue_failed)
            PlaybackIssueReportStatus.Sending -> stringResource(R.string.player_report_issue_sending)
            PlaybackIssueReportStatus.Idle -> {
                val elapsedSeconds = (elapsedMs / 1000L).coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                context.resources.getQuantityString(
                    R.plurals.player_report_loading_issue_prompt,
                    elapsedSeconds,
                    elapsedSeconds
                )
            }
        }
        Text(
            text = reportMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.86f),
            textAlign = TextAlign.Center
        )
        DialogButton(
            text = when (reportStatus) {
                PlaybackIssueReportStatus.Sending -> stringResource(R.string.player_report_issue_sending_button)
                PlaybackIssueReportStatus.Sent -> stringResource(R.string.player_report_issue_sent_button)
                else -> stringResource(R.string.player_report_loading_issue)
            },
            onClick = onReport,
            isPrimary = false,
            enabled = reportStatus != PlaybackIssueReportStatus.Sending &&
                reportStatus != PlaybackIssueReportStatus.Sent,
            modifier = Modifier.focusRequester(focusRequester)
        )
    }
}

@Composable
private fun ErrorOverlay(
    message: String,
    showSwitchToMpvAction: Boolean = false,
    onSwitchToMpv: (() -> Unit)? = null,
    showReportAction: Boolean,
    reportStatus: PlaybackIssueReportStatus,
    reportId: String?,
    reportError: String?,
    onReport: () -> Unit,
    onBack: () -> Unit
) {
    val exitFocusRequester = remember { FocusRequester() }
    val reportFocusRequester = remember { FocusRequester() }
    val mpvFocusRequester = remember { FocusRequester() }
    val onSwitchMpvAction = onSwitchToMpv.takeIf { showSwitchToMpvAction }
    val targetFocusRequester = if (onSwitchMpvAction != null) mpvFocusRequester else exitFocusRequester

    var targetButtonPlaced by remember(onSwitchMpvAction != null) { mutableStateOf(false) }
    var mpvFocused by remember(onSwitchMpvAction != null) { mutableStateOf(false) }
    var reportFocused by remember(onSwitchMpvAction != null) { mutableStateOf(false) }
    var exitFocused by remember(onSwitchMpvAction != null) { mutableStateOf(false) }
    val hasOverlayFocus = mpvFocused || reportFocused || exitFocused

    LaunchedEffect(targetButtonPlaced, onSwitchMpvAction != null) {
        if (!targetButtonPlaced) return@LaunchedEffect
        val focused = targetFocusRequester.requestFocusAfterFrames(frames = 0)
        if (!focused) {
            targetFocusRequester.requestFocusAfterFrames(frames = 2)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .zIndex(3f)
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                val isDirection = when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT -> true
                    else -> false
                }
                if (!isDirection || hasOverlayFocus) return@onPreviewKeyEvent false
                runCatching { targetFocusRequester.requestFocus() }.isSuccess
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.focusGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            Text(
                text = stringResource(R.string.player_error_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )

            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = NuvioTheme.spacing.xxl)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

            val reportMessage = when (reportStatus) {
                PlaybackIssueReportStatus.Idle -> null
                PlaybackIssueReportStatus.Sending -> stringResource(R.string.player_report_issue_sending)
                PlaybackIssueReportStatus.Sent -> stringResource(R.string.player_report_issue_sent, reportId.orEmpty())
                PlaybackIssueReportStatus.Failed -> reportError ?: stringResource(R.string.player_report_issue_failed)
            }
            if (showReportAction && reportMessage != null) {
                Text(
                    text = reportMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (reportStatus) {
                        PlaybackIssueReportStatus.Sent -> NuvioTheme.colors.Secondary
                        PlaybackIssueReportStatus.Failed -> Color(0xFFFF8A80)
                        else -> Color.White.copy(alpha = 0.7f)
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(horizontal = NuvioTheme.spacing.xxl)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onSwitchMpvAction != null) {
                    PlayerOverlayButton(
                        text = stringResource(R.string.player_switch_to_mpv),
                        onClick = onSwitchMpvAction,
                        primary = true,
                        modifier = Modifier
                            .focusRequester(mpvFocusRequester)
                            .onPlaced { targetButtonPlaced = true }
                            .onFocusChanged { mpvFocused = it.isFocused }
                            .focusProperties {
                                right = if (showReportAction) reportFocusRequester else exitFocusRequester
                            }
                    )
                }
                if (showReportAction) {
                    PlayerOverlayButton(
                        text = when (reportStatus) {
                            PlaybackIssueReportStatus.Sending -> stringResource(R.string.player_report_issue_sending_button)
                            PlaybackIssueReportStatus.Sent -> stringResource(R.string.player_report_issue_sent_button)
                            else -> stringResource(R.string.player_report_issue)
                        },
                        onClick = onReport,
                        primary = false,
                        enabled = reportStatus != PlaybackIssueReportStatus.Sending &&
                            reportStatus != PlaybackIssueReportStatus.Sent,
                        modifier = Modifier
                            .focusRequester(reportFocusRequester)
                            .onFocusChanged { reportFocused = it.isFocused }
                            .focusProperties {
                                if (onSwitchMpvAction != null) left = mpvFocusRequester
                                right = exitFocusRequester
                            }
                    )
                }
                PlayerOverlayButton(
                    text = stringResource(R.string.player_go_back),
                    onClick = onBack,
                    primary = onSwitchMpvAction == null,
                    modifier = Modifier
                        .focusRequester(exitFocusRequester)
                        .onPlaced {
                            if (onSwitchMpvAction == null) targetButtonPlaced = true
                        }
                        .onFocusChanged { exitFocused = it.isFocused }
                        .focusProperties {
                            left = if (showReportAction) {
                                reportFocusRequester
                            } else if (onSwitchMpvAction != null) {
                                mpvFocusRequester
                            } else {
                                FocusRequester.Default
                            }
                        }
                )
            }
        }
    }
}

@Composable
internal fun PlayerOverlayButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val isV2 = LocalV2Appearance.current != null
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(if (isV2) 10.dp else NuvioTheme.spacing.xxl)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.onFocusChanged { focused = it.isFocused }
            .then(if (isV2) Modifier.nuvioV2Focus(focused, shape, hardwareShadow = false)
                .nuvioGlass(GlassRole.CONTROL, focused, shape) else Modifier),
        colors = ButtonDefaults.colors(
            containerColor = if (isV2) Color.Transparent else if (primary) Color.White else NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = if (isV2) Color.Transparent else if (primary) Color.White else NuvioTheme.colors.Secondary,
            contentColor = if (isV2) Color.White else if (primary) Color.Black else NuvioTheme.colors.TextPrimary,
            focusedContentColor = if (isV2) Color.White else if (primary) Color.Black else NuvioTheme.colors.OnSecondary
        ),
        shape = ButtonDefaults.shape(shape = shape),
        border = ButtonDefaults.border(
            focusedBorder = if (isV2) Border.None else Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = shape
            )
        ),
        contentPadding = PaddingValues(
            horizontal = NuvioTheme.spacing.lg,
            vertical = 14.dp
        ),
        scale = ButtonDefaults.scale()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SpeedSelectionDialog(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
    brightness: Float = 1f
) {
    val isV2 = LocalV2Appearance.current != null
    val selectedIndex = remember(currentSpeed) {
        PLAYBACK_SPEEDS.indices.minByOrNull { index ->
            abs(PLAYBACK_SPEEDS[index] - currentSpeed)
        } ?: 0
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val speedFocusRequesters = remember {
        PLAYBACK_SPEEDS.map { FocusRequester() }
    }

    LaunchedEffect(selectedIndex) {
        if (isV2) {
            listState.scrollToItem(selectedIndex)
            repeat(2) { withFrameNanos { } }
        }
        runCatching { speedFocusRequesters[selectedIndex].requestFocus() }
    }

    if (isV2) {
        NuvioSideSheet(onDismiss, requestInitialFocus = false) {
            Text(stringResource(R.string.player_speed_title), style = MaterialTheme.typography.headlineSmall,
                color = Color.White, modifier = Modifier.padding(bottom = 20.dp))
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(PLAYBACK_SPEEDS) { index, speed ->
                    PlayerPanelRow(
                        title = if (speed == 1f) stringResource(R.string.player_speed_normal) else "${speed}x",
                        selected = speed == currentSpeed, onClick = { onSpeedSelected(speed) },
                        modifier = Modifier.focusRequester(speedFocusRequesters[index])
                    )
                }
            }
        }
        return
    }
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .playerControlsBrightness(brightness)
                .width(300.dp)
                .clip(RoundedCornerShape(NuvioTheme.radii.xxl))
                .background(Color.Black.copy(alpha = 0.85f))
        ) {
            Column(
                modifier = Modifier.padding(NuvioTheme.spacing.xl)
            ) {
                Text(
                    text = stringResource(R.string.player_speed_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.lg)
                )

                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(top = NuvioTheme.spacing.xs)
                ) {
                    itemsIndexed(PLAYBACK_SPEEDS) { index, speed ->
                        PlayerPanelRow(
                            title = if (speed == 1f) stringResource(R.string.player_speed_normal) else "${speed}x",
                            selected = speed == currentSpeed,
                            onClick = { onSpeedSelected(speed) },
                            modifier = Modifier.focusRequester(speedFocusRequesters[index])
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreActionsDialog(
    onPlaybackSpeed: () -> Unit,
    onToggleAspectRatio: () -> Unit,
    onOpenInExternalPlayer: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(NuvioTheme.radii.xxl))
                .background(Color.Black.copy(alpha = 0.85f))
        ) {
            Column(
                modifier = Modifier.padding(NuvioTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.player_more_actions_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm)
                )

                PanelActionRow(
                    label = stringResource(R.string.player_more_speed),
                    onClick = onPlaybackSpeed
                )
                PanelActionRow(
                    label = stringResource(R.string.player_more_aspect_ratio),
                    onClick = onToggleAspectRatio
                )
                PanelActionRow(
                    label = stringResource(R.string.player_more_open_external),
                    onClick = onOpenInExternalPlayer
                )
            }
        }
    }
}

@Composable
internal fun DialogButton(
    text: String,
    onClick: () -> Unit,
    isPrimary: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.nuvioControlSurface(RoundedCornerShape(NuvioTheme.radii.md)),
        colors = ButtonDefaults.colors(
            containerColor = if (LocalV2Appearance.current != null) Color.Transparent else if (isPrimary) NuvioTheme.colors.Secondary else NuvioTheme.colors.BackgroundCard,
            contentColor = if (LocalV2Appearance.current != null) Color.White else if (isPrimary) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextSecondary,
            focusedContainerColor = if (LocalV2Appearance.current != null) Color.Transparent else if (isPrimary) NuvioTheme.colors.SecondaryVariant else NuvioTheme.colors.FocusBackground,
            focusedContentColor = if (LocalV2Appearance.current != null) Color.White else if (isPrimary) NuvioTheme.colors.OnSecondaryVariant else NuvioTheme.colors.Primary
        ),
        border = if (LocalV2Appearance.current != null) ButtonDefaults.border(border = Border.None, focusedBorder = Border.None) else ButtonDefaults.border(
            focusedBorder = Border(
                border = if (isPrimary) {
                    BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.SecondaryVariant)
                } else {
                    NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs)
                },
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = ButtonDefaults.scale(focusedScale = 1f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatSubtitleDelay(delayMs: Int): String {
    return String.format(Locale.US, "%+.3fs", delayMs / 1000f)
}

/**
 * Buffering indicator extracted into its own composable to isolate
 * recomposition scope. When [isBuffering] toggles, only this subtree
 * is recomposed — the rest of [PlayerScreen] is skipped by Compose.
 */
@Composable
private fun PlayerBufferingIndicator(
    isBuffering: Boolean,
    showLoadingOverlay: Boolean,
    isTorrentStream: Boolean,
    torrentBufferingMessage: String?,
    torrentBufferingProgress: Float,
    modifier: Modifier = Modifier
) {
    if (!isBuffering || showLoadingOverlay) return

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (isTorrentStream && torrentBufferingMessage != null) {
            // Torrent rebuffer: spinner + download stats + progress bar
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LoadingIndicator()
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                Text(
                    text = torrentBufferingMessage,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
                if (torrentBufferingProgress > 0f) {
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                    Box(
                        modifier = Modifier
                            .width(200.dp)
                            .height(3.dp)
                            .background(
                                color = Color.White.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(NuvioTheme.radii.xxs)
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(torrentBufferingProgress.coerceIn(0f, 1f))
                                .height(3.dp)
                                .background(
                                    color = Color.White.copy(alpha = 0.85f),
                                    shape = RoundedCornerShape(NuvioTheme.radii.xxs)
                                )
                        )
                    }
                }
            }
        } else {
            LoadingIndicator()
        }
    }
}

/** A server check longer than this means playback already started: the own engine then never holds it. */
private const val SERVER_CHECK_HOLD_LIMIT_MS = 3_000L
