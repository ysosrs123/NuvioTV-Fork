package com.nuvio.tv.data.iptv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.nuvio.tv.core.iptv.OwnedCaptureConsumer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class CaptureVideoState { NEW, BUFFERING, READY, ENDED, FAILED, CLOSING, CLOSED }

@UnstableApi
internal class CaptureVideoPlayer(context: Context, private val source: CaptureEpochMediaSource,
    private val surface: Surface, override val minimumMemoryReservationBytes: Long,
    private val startPositionMs: Long? = null,
    private val frameListener: VideoFrameMetadataListener? = null,
    private val applicationLooper: Looper = Looper.getMainLooper(),
    private val closeTimeoutMs: Long = 15_000,
) : OwnedCaptureConsumer {
    init {
        require(minimumMemoryReservationBytes > 0 && (startPositionMs ?: 0) >= 0 && closeTimeoutMs in 1..120_000)
        require(source.retiresPlayedBatches) { "Capture playback requires played batch retirement" }
    }
    override val minimumDecoderReservationCount = 1
    private val context = context.applicationContext
    private val mutableState = MutableStateFlow(CaptureVideoState.NEW)
    val state = mutableState.asStateFlow()
    private val closeMutex = Mutex()
    private var player: ExoPlayer? = null
    private var started = false
    @Volatile private var stopping = false
    private var releaseCompletion: CompletableDeferred<Boolean>? = null
    private var releaseAttempted = false
    private var releaseFailed = false
    @Volatile var failure: PlaybackException? = null
        private set
    @Volatile var failureCode: Int? = null
        private set

    @Synchronized override fun start() {
        check(Looper.myLooper() === applicationLooper && !started && !stopping)
        check(surface.isValid); started = true
        val renderers = RenderersFactory { handler, video, _, _, _ ->
            arrayOf(MediaCodecVideoRenderer(context, MediaCodecSelector.DEFAULT, 0, false, handler, video, 50))
        }
        val next = ExoPlayer.Builder(context, renderers).setLooper(applicationLooper)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(500, 2000, 250, 250)
                .setTargetBufferBytes(2 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
            .build()
        player = next
        next.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failure = error; failureCode = error.errorCode
                if (releaseAttempted) releaseFailed = true
                else mutableState.value = CaptureVideoState.FAILED
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (!stopping && mutableState.value != CaptureVideoState.FAILED) mutableState.value = when(state) {
                    Player.STATE_BUFFERING -> CaptureVideoState.BUFFERING
                    Player.STATE_READY -> CaptureVideoState.READY
                    Player.STATE_ENDED -> CaptureVideoState.ENDED
                    else -> mutableState.value
                }
            }
        })
        next.setVideoSurface(surface)
        frameListener?.let(next::setVideoFrameMetadataListener)
        if (startPositionMs == null) next.setMediaSource(source) else next.setMediaSource(source, maxOf(startPositionMs, 1))
        next.prepare(); next.playWhenReady = true
    }

    fun describe(): String {
        check(Looper.myLooper() === applicationLooper)
        val owned = synchronized(this) { player } ?: return "state=${mutableState.value} player=none"
        val timeline = owned.currentTimeline
        val window = if (timeline.isEmpty) null else timeline.getWindow(owned.currentMediaItemIndex, Timeline.Window())
        val counters = owned.videoDecoderCounters
        return "state=${mutableState.value} playback=${owned.playbackState} playWhenReady=${owned.playWhenReady} " +
            "positionMs=${owned.currentPosition} bufferedMs=${owned.bufferedPosition} loading=${owned.isLoading} " +
            "windowDynamic=${window?.isDynamic} windowPlaceholder=${window?.isPlaceholder} windowDurationMs=${window?.durationMs} " +
            "queued=${counters?.queuedInputBufferCount} rendered=${counters?.renderedOutputBufferCount} " +
            "skipped=${counters?.skippedOutputBufferCount} dropped=${counters?.droppedBufferCount} failure=$failureCode"
    }

    override suspend fun close(): Boolean = closeMutex.withLock {
        val owned = synchronized(this) { stopping = true; player }
        if (owned == null) { mutableState.value = CaptureVideoState.CLOSED; return@withLock true }

        val completion = releaseCompletion ?: CompletableDeferred<Boolean>().also { result ->
            releaseCompletion = result

            val accepted = Handler(applicationLooper).post {
                releaseAttempted = true; mutableState.value = CaptureVideoState.CLOSING
                try { owned.release() } catch (_: Exception) { releaseFailed = true }
                if (!releaseFailed) {
                    synchronized(this) { player = null }
                    mutableState.value = CaptureVideoState.CLOSED
                }
                result.complete(!releaseFailed)
            }
            if (!accepted) result.complete(false)
        }
        withTimeoutOrNull(closeTimeoutMs) { completion.await() } ?: false
    }
}
