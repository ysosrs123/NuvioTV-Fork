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
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.nuvio.tv.data.iptv.IptvFormatProbe
import com.nuvio.tv.core.iptv.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

class IptvLivePlayback(context: Context, private val locator: String, purpose: PlaybackPurpose,
    val streamFormat: IptvStreamFormat = IptvStreamFormat.AUTO,
    private val onPlaying: (Boolean) -> Unit, private val onError: () -> Unit,
    private val onReconnecting: (Boolean) -> Unit = {}, private val isLive: Boolean = true,
    private val onEnded: () -> Unit = {}, private val handleAudioFocus: Boolean = true,
    private val onPlayWhenReady: (Boolean) -> Unit = {}, boostDb: Int = 0,
    private val maxVideoHeight: Int? = null, private val targetBufferBytes: Int = 12 * 1024 * 1024,
    headers: Map<String, String> = emptyMap(), private val alternatives: List<Pair<String, IptvStreamFormat>> = emptyList(),
    private val onAlternative: (Int) -> Unit = {}, private val onFailure: (LiveFailure) -> Unit = {}) : OwnedLivePlayback {
    private val fence = LiveRequestFence()
    val telemetry = LiveTelemetry()
    val host: String? get() = Uri.parse(locator).host
    val live: Boolean get() = isLive
    val activeRequests: Int get() = fence.active.value
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(true).followSslRedirects(true).build()
    private val requestHeaders = headers.filterKeys { it in HEADER_NAMES }.mapNotNull { (name, value) -> StreamHeaders.clean(value)?.let { name to it } }.toMap()
    private val userAgent = requestHeaders["User-Agent"] ?: DEFAULT_USER_AGENT
    private val probe = IptvFormatProbe.shared(context)
    private val extractors = LiveExtractors()
    private val frozen = FrozenVideoWatch(FROZEN_MS)
    @Volatile private var current = locator
    private var alternative = 0
    private var probed = false
    private var released = false
    private var failed = false
    private var reconnecting = false
    private var readyReported = false
    private var firstFrame = false
    private var attempts = 0
    private val reconnect = Runnable {
        if (!released && !failed) {
            val position = player.currentPosition
            if (player.playbackState != Player.STATE_IDLE) player.stop()
            if (isLive) player.seekToDefaultPosition() else player.seekTo(position)
            player.prepare()
        }
    }
    private val stall = Runnable { if (!released && player.playbackState == Player.STATE_BUFFERING) retry() }
    private val steady = Runnable { attempts = 0 }
    private val startCheck = Runnable {
        if (!released && !failed && !firstFrame && extractors.lenient && player.videoFormat != null) {
            extractors.lenient = false
            mainHandler.removeCallbacks(reconnect)
            mainHandler.post(reconnect)
        }
    }
    private val watchdog = object : Runnable {
        override fun run() {
            if (released) return
            val counters = player.videoDecoderCounters
            val progress = if (player.videoFormat != null && counters != null) {
                counters.ensureUpdated()
                counters.renderedOutputBufferCount.toLong() + counters.skippedOutputBufferCount + counters.droppedBufferCount
            } else player.currentPosition
            val active = !failed && !reconnecting && player.playbackState == Player.STATE_READY && player.isPlaying
            if (frozen.frozen(android.os.SystemClock.elapsedRealtime(), active, progress)) { frozen.reset(); retry() }
            mainHandler.postDelayed(this, WATCH_MS)
        }
    }
    private var releaseFailed = false
    private val audioSession = androidx.media3.common.util.Util.generateAudioSessionIdV21(context)
    private var enhancer: android.media.audiofx.LoudnessEnhancer? = null
    var boostDb: Int = boostDb
        private set
    val player: ExoPlayer
    init {
        require(purpose == PlaybackPurpose.LIVE_CHANNEL)
        require((listOf(locator) + alternatives.map { it.first }).all { Uri.parse(it).scheme?.lowercase() in setOf("http", "https") })
        val upstream = OkHttpDataSource.Factory(client).setUserAgent(userAgent).setDefaultRequestProperties(requestHeaders - "User-Agent")
        upstream.setTransferListener(object : TransferListener {
            override fun onTransferInitializing(source: DataSource, spec: DataSpec, network: Boolean) = Unit
            override fun onTransferStart(source: DataSource, spec: DataSpec, network: Boolean) = Unit
            override fun onTransferEnd(source: DataSource, spec: DataSpec, network: Boolean) = Unit
            override fun onBytesTransferred(source: DataSource, spec: DataSpec, network: Boolean, count: Int) {
                if (network) telemetry.transferred(count)
            }
        })
        val sources = DataSource.Factory { FencedSource(upstream.createDataSource(), fence, { Uri.parse(current) }, !isLive) }
        val renderers = DefaultRenderersFactory(context).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        player = ExoPlayer.Builder(context, renderers)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1500, 8000, 500, 1000)
                .setTargetBufferBytes(targetBufferBytes).setPrioritizeTimeOverSizeThresholds(false).build())
            .setMediaSourceFactory(DefaultMediaSourceFactory(sources, extractors).setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy(0) {
                override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long = C.TIME_UNSET

                override fun getFallbackSelectionFor(fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
                    loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): LoadErrorHandlingPolicy.FallbackSelection? = null
            }))
            .build()
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), handleAudioFocus)
        player.setAudioSessionId(audioSession)
        setBoost(boostDb)
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!released) onPlaying(isPlaying) }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (!released) onPlayWhenReady(playWhenReady) }
            override fun onPlayerError(error: PlaybackException) {
                if (released) { releaseFailed = true; return }
                val response = generateSequence<Throwable>(error) { it.cause }.take(8).filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                retry(error.errorCode, response?.responseCode,
                    response?.headerFields?.entries?.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value?.firstOrNull())
            }
            override fun onRenderedFirstFrame() {
                firstFrame = true
                mainHandler.removeCallbacks(startCheck)
                telemetry.firstFrame(android.os.SystemClock.elapsedRealtime())
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                telemetry.buffering(playbackState == Player.STATE_BUFFERING, android.os.SystemClock.elapsedRealtime())
                mainHandler.removeCallbacks(stall); mainHandler.removeCallbacks(steady)
                if (released) return
                when (playbackState) {
                    Player.STATE_READY -> {
                        if (reconnecting) { reconnecting = false; onReconnecting(false) }
                        if (!readyReported) { readyReported = true; onAlternative(alternative) }
                        mainHandler.postDelayed(steady, STEADY_MS)
                    }
                    Player.STATE_BUFFERING -> mainHandler.postDelayed(stall, STALL_MS)
                    Player.STATE_ENDED -> if (isLive) retry() else mainHandler.post { if (!released) onEnded() }
                }
            }
        })
    }
    private fun retry(errorCode: Int? = null, httpStatus: Int? = null, retryAfter: String? = null) {
        if (released || failed) return
        when (val decision = LiveRetry.decide(isLive, attempts, errorCode, httpStatus, retryAfter, System.currentTimeMillis())) {
            is LiveRetryDecision.Fail -> fail(decision.failure)
            is LiveRetryDecision.Retry -> {
                attempts++
                if (!reconnecting) { reconnecting = true; onReconnecting(true) }
                mainHandler.removeCallbacks(reconnect)
                mainHandler.postDelayed(reconnect, decision.delayMs)
            }
        }
    }
    private fun fail(failure: LiveFailure) {
        mainHandler.removeCallbacks(reconnect); mainHandler.removeCallbacks(startCheck)
        if (failure == LiveFailure.UNSUPPORTED && probed) probe.forget(current)
        if (alternative < alternatives.size) {
            val (next, format) = alternatives[alternative++]
            attempts = 0
            play(next, format)
            return
        }
        failed = true
        mainHandler.post { if (!released) { onFailure(failure); onError() } }
    }
    fun setBoost(db: Int) {
        if (released) return
        boostDb = db.coerceIn(0, MAX_BOOST_DB)
        runCatching {
            if (boostDb == 0) { enhancer?.release(); enhancer = null }
            else (enhancer ?: android.media.audiofx.LoudnessEnhancer(audioSession).also { enhancer = it }).apply { setTargetGain(boostDb * 100); enabled = true }
        }.onFailure { enhancer = null }
    }
    fun limitHeight(height: Int?) {
        mainHandler.post {
            if (released) return@post
            val builder = player.trackSelectionParameters.buildUpon()
            player.trackSelectionParameters = (if (height == null) builder.clearVideoSizeConstraints() else builder.setMaxVideoSize(height * 16 / 9, height)).build()
        }
    }
    val pixelRate: Long? get() = player.videoFormat?.takeIf { it.width > 0 && it.height > 0 }?.let {
        it.width.toLong() * it.height * (it.frameRate.takeIf { rate -> rate > 0 }?.toInt() ?: MULTIVIEW_FRAME_RATE)
    }
    override fun start() {
        check(!released)
        maxVideoHeight?.let { height ->
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setMaxVideoSize(height * 16 / 9, height).build()
        }
        telemetry.start(android.os.SystemClock.elapsedRealtime())
        mainHandler.postDelayed(watchdog, WATCH_MS)
        if (streamFormat != IptvStreamFormat.AUTO) { play(locator, streamFormat); return }
        StreamFormatSniff.fromUrl(locator)?.let { play(locator, if (it == SniffedFormat.HLS) IptvStreamFormat.HLS else IptvStreamFormat.MPEG_TS); return }
        probe.known(locator)?.let { probed = true; play(locator, it); return }
        val headers = requestHeaders + ("User-Agent" to userAgent)
        thread(name = "IptvProbe", isDaemon = true) {
            val found = fence.enter()?.let { ticket -> try { probe.probe(client, locator, headers) } finally { fence.leave(ticket) } }
            mainHandler.post { if (!released) { probed = found != null; play(locator, found ?: IptvStreamFormat.AUTO) } }
        }
    }
    private fun play(url: String, format: IptvStreamFormat) {
        current = url; readyReported = false; firstFrame = false
        mainHandler.removeCallbacks(startCheck)
        val mimeType = when (format) {
            IptvStreamFormat.AUTO -> null
            IptvStreamFormat.HLS -> MimeTypes.APPLICATION_M3U8
            IptvStreamFormat.MPEG_TS -> MimeTypes.VIDEO_MP2T
        }
        player.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(mimeType).apply {
            if (isLive) setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(6_000).setMinOffsetMs(2_000).setMaxOffsetMs(15_000)
                .setMinPlaybackSpeed(0.97f).setMaxPlaybackSpeed(1.03f).build())
        }.build())
        player.prepare(); player.playWhenReady = true
        if (format != IptvStreamFormat.HLS && extractors.lenient) mainHandler.postDelayed(startCheck, START_CHECK_MS)
    }
    override suspend fun close(): Boolean {
        mainHandler.removeCallbacks(reconnect); mainHandler.removeCallbacks(stall); mainHandler.removeCallbacks(steady)
        mainHandler.removeCallbacks(startCheck); mainHandler.removeCallbacks(watchdog)
        fence.stopAccepting()
        client.dispatcher.cancelAll()
        if (!released) {
            released = true
            runCatching { enhancer?.release() }; enhancer = null
            try { player.release() } catch (_: Exception) { releaseFailed = true }
        }

        if (releaseFailed) return false
        val closed = withTimeoutOrNull(15_000) { fence.active.first { it == 0 }; true } ?: false
        if (closed) client.connectionPool.evictAll()
        return closed
    }
    private class FencedSource(private val delegate: DataSource, private val fence: LiveRequestFence, private val entryPoint: () -> Uri,
        private val seekable: Boolean) : DataSource {
        private var ticket: LiveRequestFence.Ticket? = null
        override fun open(dataSpec: DataSpec): Long {
            check(ticket == null)

            val entry = dataSpec.uri == entryPoint()
            if (entry && !seekable && dataSpec.position != 0L) throw IOException("Live entry point cannot be range-probed")
            ticket = fence.enter() ?: throw IOException("Live session has closed")
            return try {
                val length = delegate.open(dataSpec)
                if (entry && !seekable && dataSpec.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else length
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
    private class LiveExtractors : ExtractorsFactory {
        @Volatile var lenient = true
        private val fast = DefaultExtractorsFactory().setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        private val strict = DefaultExtractorsFactory()
        private fun current() = if (lenient) fast else strict
        override fun createExtractors(): Array<Extractor> = current().createExtractors()
        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> = current().createExtractors(uri, responseHeaders)
        override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory = apply {
            fast.setSubtitleParserFactory(subtitleParserFactory); strict.setSubtitleParserFactory(subtitleParserFactory)
        }
        override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory = apply {
            fast.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled); strict.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        }
        override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory = apply {
            fast.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
            strict.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
        }
    }
    private companion object {
        val HEADER_NAMES = setOf("User-Agent", "Referer", "Origin")
        const val DEFAULT_USER_AGENT = "Nuvio-Live/1"
        const val STALL_MS = 20_000L
        const val STEADY_MS = 15_000L
        const val START_CHECK_MS = 8_000L
        const val FROZEN_MS = 8_000L
        const val WATCH_MS = 1_000L
        const val MAX_BOOST_DB = 12
    }
}
