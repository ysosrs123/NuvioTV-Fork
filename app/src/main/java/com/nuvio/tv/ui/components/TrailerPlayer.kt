package com.nuvio.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import com.nuvio.tv.core.player.LetterboxDetector
import com.nuvio.tv.core.player.LetterboxSampler
import com.nuvio.tv.core.player.LetterboxTracker
import com.nuvio.tv.core.player.LocalTrailerPlayerPool
import com.nuvio.tv.core.player.TrailerPlayerPool
import com.nuvio.tv.data.trailer.TrailerPlaybackFailures
import com.nuvio.tv.data.trailer.YoutubeChunkedDataSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import android.view.LayoutInflater
import android.view.TextureView
import com.nuvio.tv.R
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
/**
 * Fork: uniform zoom applied to every trailer surface (hero, detail, shared
 * overlay, poster card). 1.0 = no crop. A value like 1.10 trims most of the
 * letterbox on 2.39:1 trailers but loses ~5% off every edge, including the
 * network logos in the safe area. Tune
 * here; callers may still override per surface.
 */
const val TRAILER_OVERSCAN_ZOOM = 1.0f

@Composable
fun TrailerPlayer(
    trailerUrl: String?,
    trailerAudioUrl: String? = null,
    isPlaying: Boolean,
    isPaused: Boolean = false,
    onEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit = {},
    muted: Boolean = false,
    seekRequestToken: Int = 0,
    seekDeltaMs: Long = 0L,
    onProgressChanged: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    onRemoteKey: (keyCode: Int, action: Int, repeatCount: Int) -> Boolean = { _, _, _ -> false },
    cropToFill: Boolean = false,
    transparentVideoBackground: Boolean = false,
    onVideoAspectRatioChanged: (Float) -> Unit = {},
    overscanZoom: Float = TRAILER_OVERSCAN_ZOOM,
    autoCropLetterbox: Boolean = false,
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn(animationSpec = tween(800)),
    exit: ExitTransition = fadeOut(animationSpec = tween(500)),
    trailerPlayerPool: TrailerPlayerPool? = null
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val playbackActive = isPlaying && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val playerOwner = remember { Any() }
    val currentIsPaused by rememberUpdatedState(isPaused)
    val currentOnEnded by rememberUpdatedState(onEnded)
    val currentOnFirstFrameRendered by rememberUpdatedState(onFirstFrameRendered)
    val currentOnProgressChanged by rememberUpdatedState(onProgressChanged)
    val currentOnRemoteKey by rememberUpdatedState(onRemoteKey)
    val currentOnVideoAspectRatioChanged by rememberUpdatedState(onVideoAspectRatioChanged)
    var hasRenderedFirstFrame by remember(trailerUrl) { mutableStateOf(false) }
    val popupBackdrop = com.nuvio.tv.ui.v2.components.LocalPopupGlassBackdrop.current
    val rendersLiveFrames = playbackActive && !isPaused && hasRenderedFirstFrame
    DisposableEffect(popupBackdrop, rendersLiveFrames) {
        if (rendersLiveFrames) popupBackdrop?.attachTrailer()
        onDispose { if (rendersLiveFrames) popupBackdrop?.detachTrailer() }
    }
    val playerAlphaState = animateFloatAsState(
        targetValue = if (isPlaying && hasRenderedFirstFrame) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "trailerFirstFrameAlpha"
    )
    val playerViewRef = remember { mutableStateOf<PlayerView?>(null) }
    var letterboxZoom by remember(trailerUrl) { mutableFloatStateOf(1f) }
    val letterboxZoomState = animateFloatAsState(
        targetValue = if (autoCropLetterbox) letterboxZoom else 1f,
        animationSpec = tween(durationMillis = 400),
        label = "trailerLetterboxZoom"
    )

    // Resolve pool: explicit parameter > CompositionLocal
    val resolvedPool = trailerPlayerPool ?: LocalTrailerPlayerPool.current

    // Acquire only for a visible, resumed preview. A resolved URL alone must not
    // allocate a player during Details metadata loading or an outgoing transition.
    var trailerPlayer by remember(resolvedPool) { mutableStateOf<ExoPlayer?>(null) }
    var playbackGeneration by remember { mutableIntStateOf(0) }
    var loadedTrailerUrl by remember { mutableStateOf<String?>(null) }
    val hasTrailer = !trailerUrl.isNullOrBlank()
    DisposableEffect(resolvedPool, lifecycleOwner, isPlaying, hasTrailer) {
        val binding = com.nuvio.tv.core.player.TrailerLifecycleBinding(
            lifecycleOwner.lifecycle,
            resume = {
                if (isPlaying && hasTrailer) {
                    trailerPlayer = resolvedPool?.acquire(playerOwner)
                    playbackGeneration++
                }
            },
            pause = { resolvedPool?.stop(playerOwner) }
        )
        onDispose { binding.close() }
    }

    // Configure player settings when acquired
    LaunchedEffect(trailerPlayer, playbackGeneration, muted, cropToFill) {
        val player = trailerPlayer ?: return@LaunchedEffect
        if (resolvedPool?.isOwner(playerOwner) != true) return@LaunchedEffect
        player.volume = if (muted) 0f else 1f
        player.videoScalingMode = if (cropToFill) {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        } else {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }
    }

    LaunchedEffect(playbackActive, playbackGeneration, trailerUrl, trailerAudioUrl, trailerPlayer) {
        hasRenderedFirstFrame = false
        val player = trailerPlayer ?: return@LaunchedEffect
        if (resolvedPool?.isOwner(playerOwner) != true) return@LaunchedEffect
        if (playbackActive && trailerUrl != null) {
            if (!trailerAudioUrl.isNullOrBlank()) {
                val mediaSourceFactory = DefaultMediaSourceFactory(YoutubeChunkedDataSourceFactory())
                val videoSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(trailerUrl))
                val audioSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(trailerAudioUrl))
                player.setMediaSource(MergingMediaSource(videoSource, audioSource))
            } else {
                player.setMediaItem(MediaItem.fromUri(trailerUrl))
            }
            loadedTrailerUrl = trailerUrl
            player.prepare()
            player.playWhenReady = !currentIsPaused
        }
    }

    LaunchedEffect(isPaused, playbackActive, playbackGeneration, trailerPlayer) {
        val player = trailerPlayer ?: return@LaunchedEffect
        if (!playbackActive || resolvedPool?.isOwner(playerOwner) != true) return@LaunchedEffect
        player.playWhenReady = !isPaused
    }

    LaunchedEffect(autoCropLetterbox, hasRenderedFirstFrame, trailerPlayer) {
        letterboxZoom = 1f
        if (!autoCropLetterbox || !hasRenderedFirstFrame) return@LaunchedEffect
        val player = trailerPlayer ?: return@LaunchedEffect
        val tracker = LetterboxTracker()
        val sampler = LetterboxSampler()
        try {
            while (true) {
                delay(LetterboxDetector.SAMPLE_INTERVAL_MS)
                if (LetterboxDetector.isSampleWindowOver(player.currentPosition, player.duration)) break
                if (!player.isPlaying) continue
                val textureView = playerViewRef.value?.videoSurfaceView as? TextureView ?: continue
                val bar = sampler.sample(textureView) ?: continue
                letterboxZoom = tracker.onSample(bar) ?: continue
                break
            }
        } finally {
            sampler.release()
        }
    }

    LaunchedEffect(seekRequestToken, seekDeltaMs, trailerPlayer) {
        val player = trailerPlayer ?: return@LaunchedEffect
        if (seekRequestToken <= 0 || resolvedPool?.isOwner(playerOwner) != true) return@LaunchedEffect
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        val current = player.currentPosition
        val target = (current + seekDeltaMs).coerceIn(0L, duration.coerceAtLeast(0L))
        player.seekTo(target)
    }

    LaunchedEffect(trailerPlayer, playbackActive, playbackGeneration) {
        val player = trailerPlayer ?: return@LaunchedEffect
        while (playbackActive && resolvedPool?.isOwner(playerOwner) == true) {
            val position = player.currentPosition.coerceAtLeast(0L)
            val duration = player.duration.takeIf { it > 0 } ?: 0L
            currentOnProgressChanged(position, duration)
            delay(250)
        }
        currentOnProgressChanged(0L, 0L)
    }

    DisposableEffect(trailerPlayer) {
        val player = trailerPlayer ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (resolvedPool?.isOwner(playerOwner) == true && videoSize.width > 0 && videoSize.height > 0) {
                    currentOnVideoAspectRatioChanged(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (resolvedPool?.isOwner(playerOwner) == true && playbackState == Player.STATE_ENDED) {
                    currentOnEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (resolvedPool?.isOwner(playerOwner) != true) return
                val failedUrl = loadedTrailerUrl ?: return
                loadedTrailerUrl = null
                TrailerPlaybackFailures.report(failedUrl)
            }

            override fun onRenderedFirstFrame() {
                if (resolvedPool?.isOwner(playerOwner) != true) return
                onVideoSizeChanged(player.videoSize)
                hasRenderedFirstFrame = true
                currentOnFirstFrameRendered()
            }
        }
        player.addListener(listener)
        onDispose {
            runCatching { player.removeListener(listener) }
            // The acquisition effect relinquishes only this screen's ownership.
        }
    }

    if (trailerPlayer != null) {
        AnimatedVisibility(
            visible = playbackActive && hasTrailer,
            enter = enter,
            exit = exit
        ) {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.trailer_player_view, null) as PlayerView).apply {
                        playerViewRef.value = this
                        if (transparentVideoBackground) {
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                            (videoSurfaceView as? android.view.TextureView)?.isOpaque = false
                        }
                        player = trailerPlayer
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setOnKeyListener { _, keyCode, event ->
                            currentOnRemoteKey(keyCode, event.action, event.repeatCount)
                        }
                        keepScreenOn = true
                        resizeMode = if (cropToFill) {
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        } else {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    }
                },
                update = { view ->
                    // A layout preference can change without replacing this AndroidView.
                    (view.videoSurfaceView as? android.view.TextureView)?.let { texture ->
                        if (texture.isOpaque == transparentVideoBackground) {
                            texture.isOpaque = !transparentVideoBackground
                        }
                    }
                    // Re-attach player in case it was reclaimed after yield
                    if (view.player !== trailerPlayer) {
                        view.player = trailerPlayer
                    }
                    view.resizeMode = if (cropToFill) {
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    } else {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                onRelease = { view ->
                    playerViewRef.value = null
                    view.player = null
                    view.keepScreenOn = false
                },
                modifier = modifier
                    .clipToBounds()
                    .graphicsLayer {
                        alpha = playerAlphaState.value
                        scaleX = overscanZoom * letterboxZoomState.value
                        scaleY = overscanZoom * letterboxZoomState.value
                    }
            )
        }
    }
}
