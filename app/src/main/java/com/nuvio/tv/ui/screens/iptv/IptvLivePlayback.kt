@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import com.nuvio.tv.data.iptv.IptvStreamFormat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.nuvio.tv.core.iptv.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

class IptvLivePlayback(context: Context, private val locator: String, purpose: PlaybackPurpose,
    private val streamFormat: IptvStreamFormat = IptvStreamFormat.AUTO,
    private val onPlaying: (Boolean) -> Unit, private val onError: () -> Unit) : OwnedLivePlayback {
    private val fence = LiveRequestFence()
    val telemetry = LiveTelemetry()
    val activeRequests: Int get() = fence.active.value
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(true).followSslRedirects(true).build()
    private var released = false
    private var attempts = 0
    private val reconnect = Runnable { if (!released) { player.seekToDefaultPosition(); player.prepare(); player.playWhenReady = true } }
    private val stall = Runnable { if (!released && player.playbackState == Player.STATE_BUFFERING) retry() }
    private var releaseFailed = false
    val player: ExoPlayer
    init {
        require(purpose == PlaybackPurpose.LIVE_CHANNEL)
        require(Uri.parse(locator).scheme?.lowercase() in setOf("http", "https"))
        val upstream = OkHttpDataSource.Factory(client).setUserAgent("Nuvio-Live/1")
        upstream.setTransferListener(object : TransferListener {
            override fun onTransferInitializing(source: DataSource, spec: DataSpec, network: Boolean) = Unit
            override fun onTransferStart(source: DataSource, spec: DataSpec, network: Boolean) = Unit
            override fun onTransferEnd(source: DataSource, spec: DataSpec, network: Boolean) = Unit
            override fun onBytesTransferred(source: DataSource, spec: DataSpec, network: Boolean, count: Int) {
                if (network) telemetry.transferred(count)
            }
        })
        val sources = DataSource.Factory { FencedSource(upstream.createDataSource(), fence, Uri.parse(locator)) }
        val renderers = DefaultRenderersFactory(context).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        player = ExoPlayer.Builder(context, renderers)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1500, 8000, 500, 1000)
                .setTargetBufferBytes(12 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
            .setMediaSourceFactory(DefaultMediaSourceFactory(sources).setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy(0) {
                override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long = C.TIME_UNSET

                override fun getFallbackSelectionFor(fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
                    loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): LoadErrorHandlingPolicy.FallbackSelection? = null
            }))
            .build()
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!released) onPlaying(isPlaying) }
            override fun onPlayerError(error: PlaybackException) {
                when {
                    released -> releaseFailed = true
                    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW || error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT ||
                        error.errorCode in 2000..2999 -> retry()
                    else -> reportFailure()
                }
            }
            override fun onRenderedFirstFrame() { telemetry.firstFrame(android.os.SystemClock.elapsedRealtime()) }
            override fun onPlaybackStateChanged(playbackState: Int) {
                telemetry.buffering(playbackState == Player.STATE_BUFFERING, android.os.SystemClock.elapsedRealtime())
                mainHandler.removeCallbacks(stall)
                if (released) return
                when (playbackState) {
                    Player.STATE_READY -> attempts = 0
                    Player.STATE_BUFFERING -> mainHandler.postDelayed(stall, STALL_MS)
                    Player.STATE_ENDED -> retry()
                }
            }
        })
    }
    private fun retry() {
        if (released) return
        if (attempts >= RETRY_DELAYS_MS.size) { reportFailure(); return }
        val delay = RETRY_DELAYS_MS[attempts++]
        mainHandler.removeCallbacks(reconnect)
        mainHandler.postDelayed(reconnect, delay)
    }
    private fun reportFailure() {
        mainHandler.post { if (!released) onError() }
    }
    override fun start() {
        check(!released)
        val mimeType = when (streamFormat) {
            IptvStreamFormat.AUTO -> null
            IptvStreamFormat.HLS -> MimeTypes.APPLICATION_M3U8
            IptvStreamFormat.MPEG_TS -> MimeTypes.VIDEO_MP2T
        }

        player.setMediaItem(MediaItem.Builder().setUri(locator).setMimeType(mimeType)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(6_000).setMinOffsetMs(2_000).setMaxOffsetMs(15_000)
                .setMinPlaybackSpeed(0.97f).setMaxPlaybackSpeed(1.03f).build()).build())
        telemetry.start(android.os.SystemClock.elapsedRealtime())
        player.prepare(); player.playWhenReady = true
    }
    override suspend fun close(): Boolean {
        mainHandler.removeCallbacks(reconnect); mainHandler.removeCallbacks(stall)
        fence.stopAccepting()
        client.dispatcher.cancelAll()
        if (!released) {
            released = true
            try { player.release() } catch (_: Exception) { releaseFailed = true }
        }

        if (releaseFailed) return false
        val closed = withTimeoutOrNull(15_000) { fence.active.first { it == 0 }; true } ?: false
        if (closed) client.connectionPool.evictAll()
        return closed
    }
    private class FencedSource(private val delegate: DataSource, private val fence: LiveRequestFence, private val entryPoint: Uri) : DataSource {
        private var ticket: LiveRequestFence.Ticket? = null
        override fun open(dataSpec: DataSpec): Long {
            check(ticket == null)

            val entry = dataSpec.uri == entryPoint
            if (entry && dataSpec.position != 0L) throw IOException("Live entry point cannot be range-probed")
            ticket = fence.enter() ?: throw IOException("Live session has closed")
            return try {
                val length = delegate.open(dataSpec)
                if (entry && dataSpec.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else length
            } catch (failure: Exception) {
                try { close() } catch (_: Exception) {}
                throw failure
            }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = delegate.read(buffer, offset, length)
        override fun getUri(): Uri? = delegate.uri
        override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
        override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)
        override fun close() {
            delegate.close()
            ticket?.let { fence.leave(it); ticket = null }
        }
    }
    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(1_000, 2_000, 3_000, 5_000, 5_000, 5_000)
        const val STALL_MS = 20_000L
    }
}
