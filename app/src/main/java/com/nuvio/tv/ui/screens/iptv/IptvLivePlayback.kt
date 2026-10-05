@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
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

/** Foreground, single-view live adapter. No VOD probes, cache, range prefetch or thumbnail pipeline. */
class IptvLivePlayback(context: Context, private val locator: String, purpose: PlaybackPurpose,
    private val onPlaying: (Boolean) -> Unit, private val onError: () -> Unit) : OwnedLivePlayback {
    private val fence = LiveRequestFence()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    private var released = false
    private var releaseFailed = false
    val player: ExoPlayer
    init {
        require(purpose == PlaybackPurpose.LIVE_CHANNEL)
        require(Uri.parse(locator).scheme?.lowercase() in setOf("http", "https"))
        val upstream = OkHttpDataSource.Factory(client).setUserAgent("Nuvio-Live/1")
        val sources = DataSource.Factory { FencedSource(upstream.createDataSource(), fence, Uri.parse(locator)) }
        player = ExoPlayer.Builder(context)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1500, 8000, 500, 1000)
                .setTargetBufferBytes(12 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
            .setMediaSourceFactory(DefaultMediaSourceFactory(sources).setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy(0) {
                override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long = C.TIME_UNSET
            }))
            .build()
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!released) onPlaying(isPlaying) }
            override fun onPlayerError(error: PlaybackException) {
                // The bundled Media3 engine reports release timeout synchronously via this callback,
                // rather than throwing from release(). Never acknowledge that as decoder closure.
                if (released) releaseFailed = true else reportFailure()
            }
            override fun onPlaybackStateChanged(playbackState: Int) { if (!released && playbackState == Player.STATE_ENDED) reportFailure() }
        })
    }
    private fun reportFailure() {
        // Release outside ListenerSet dispatch so release-timeout callbacks are delivered before
        // release() returns, rather than being queued behind the currently dispatching error.
        mainHandler.post { if (!released) onError() }
    }
    override fun start() {
        check(!released)
        player.setMediaItem(MediaItem.fromUri(locator))
        player.prepare(); player.playWhenReady = true
    }
    override suspend fun close(): Boolean {
        fence.stopAccepting()
        client.dispatcher.cancelAll()
        if (!released) {
            released = true
            try { player.release() } catch (_: Exception) { releaseFailed = true }
        }
        // An uncertain decoder release retains the entire reservation, even if HTTP has closed.
        if (releaseFailed) return false
        val closed = withTimeoutOrNull(15_000) { fence.active.first { it == 0 }; true } ?: false
        if (closed) client.connectionPool.evictAll()
        return closed
    }
    private class FencedSource(private val delegate: DataSource, private val fence: LiveRequestFence, private val entryPoint: Uri) : DataSource {
        private var ticket: LiveRequestFence.Ticket? = null
        override fun open(dataSpec: DataSpec): Long {
            check(ticket == null)
            // A finite Content-Length on a live TS endpoint must not trigger extractor tail/range
            // probes. HLS segment byte ranges remain available on their distinct segment URIs.
            val entry = dataSpec.uri == entryPoint
            if (entry && dataSpec.position != 0L) throw IOException("Live entry point cannot be range-probed")
            ticket = fence.enter() ?: throw IOException("Live session has closed")
            return try {
                val length = delegate.open(dataSpec)
                if (entry && dataSpec.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else length
            } catch (failure: Exception) {
                try { close() } catch (_: Exception) { /* Retain request fence when close is uncertain. */ }
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
}
