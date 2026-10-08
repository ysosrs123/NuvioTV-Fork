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
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
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
import com.nuvio.tv.data.iptv.IptvLocalTimeshiftBehindException
import com.nuvio.tv.data.iptv.IptvLocalTimeshiftClosedException
import com.nuvio.tv.data.iptv.IptvLocalTimeshiftConfig
import com.nuvio.tv.data.iptv.IptvLocalTimeshiftInput
import com.nuvio.tv.data.iptv.IptvLocalTimeshiftRouter
import com.nuvio.tv.data.iptv.IptvLocalTimeshiftSession
import com.nuvio.tv.data.iptv.IptvLocalTimeshiftStalledException
import com.nuvio.tv.data.iptv.IptvLiveSocketFactory
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.data.iptv.IptvResilientDataSource
import com.nuvio.tv.data.iptv.IptvStreamNetwork
import com.nuvio.tv.data.iptv.IptvStreamingPreferences
import com.nuvio.tv.core.iptv.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
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
    private val onAlternative: (Int) -> Unit = {}, private val onFailure: (LiveFailure) -> Unit = {},
    localTimeshift: IptvLocalTimeshiftConfig? = null, private val onLocalTimeshift: (Boolean) -> Unit = {},
    sourceUserAgent: String? = null, primary: Boolean = handleAudioFocus) : OwnedLivePlayback {
    private val fence = LiveRequestFence()
    val telemetry = LiveTelemetry()
    val host: String? get() = Uri.parse(locator).host
    val live: Boolean get() = isLive
    val activeRequests: Int get() = fence.active.value
    private val mainHandler = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    private val lowMemory = runCatching {
        val manager = requireNotNull(context.getSystemService(android.app.ActivityManager::class.java))
        val memory = android.app.ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        LiveBufferPolicy.lowMemory(memory.totalMem, manager.isLowRamDevice)
    }.getOrDefault(true)
    private val network = IptvStreamNetwork()
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(true).followSslRedirects(true)
        .socketFactory(IptvLiveSocketFactory(if (lowMemory) IptvLiveSocketFactory.LOW_MEMORY_BYTES else IptvLiveSocketFactory.NORMAL_BYTES))
        .addNetworkInterceptor(network).build()
    private val requestHeaders = headers.filterKeys { it in HEADER_NAMES }.mapNotNull { (name, value) -> StreamHeaders.clean(value)?.let { name to it } }.toMap()
    private val userAgent = LiveUserAgent.pick(requestHeaders["User-Agent"], sourceUserAgent?.let(StreamHeaders::clean), DEFAULT_USER_AGENT)
    private val plan = if (!primary) null else IptvStreamingPreferences(context).let { streaming ->
        LiveBufferPolicy.plan(streaming.start, if (isLive) streaming.cushion else LiveCushion.OFF,
            LiveBufferPolicy.capBytes(lowMemory, Runtime.getRuntime().maxMemory()), targetBufferBytes)
    }
    private val cushionSpeed = LiveCushionSpeed()
    private var cushionCleared = false
    private var hlsCushion = false
    @Volatile private var bufferedSnapshot = 0L
    @Volatile private var resilientUrl: String? = null
    @Volatile private var streamOpen = true
    private val loadedBytes = java.util.concurrent.atomic.AtomicLong()
    private var rateBits: Double? = null
    private var rateBytes = 0L
    private var rateAt = 0L
    private var wifiHeld = false
    @Volatile var reconnects = 0
        private set
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
    private var localConfig = localTimeshift
    @Volatile private var ring: IptvLocalTimeshiftSession? = null
    private var localGeneration = 0
    private var localAnchorTime: Long? = null
    private var behindJumps = 0
    private var currentFormat: IptvStreamFormat? = null
    private val reconnect = Runnable {
        if (!released && !failed) {
            val position = player.currentPosition
            if (player.playbackState != Player.STATE_IDLE) player.stop()
            val local = ring
            if (local != null) playRing(local, local.liveAnchor())
            else {
                hlsCushion = false
                if (isLive) player.seekToDefaultPosition() else player.seekTo(position)
                player.prepare()
            }
        }
    }
    private val stall = Runnable { if (!released && player.playbackState == Player.STATE_BUFFERING) retry() }
    private val steady = Runnable { attempts = 0; behindJumps = 0 }
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
            bufferedSnapshot = player.totalBufferedDuration
            tickCushion(android.os.SystemClock.elapsedRealtime())
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
                if (network) { telemetry.transferred(count); if (count > 0) loadedBytes.addAndGet(count.toLong()) }
            }
        })
        val sources = DataSource.Factory {
            val direct = IptvResilientDataSource(upstream.createDataSource(), { spec -> spec.position == 0L && spec.uri.toString() == resilientUrl },
                { bufferedSnapshot }, { streamOpen }, { reconnects++; IptvLog.info("live reconnect seamless count=$reconnects") })
            FencedSource(IptvLocalTimeshiftRouter(direct) { ring }, fence, { Uri.parse(current) }, !isLive)
        }
        val renderers = DefaultRenderersFactory(context).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        player = ExoPlayer.Builder(context, renderers)
            .setLoadControl(DefaultLoadControl.Builder().apply {
                if (plan == null) setBufferDurationsMs(1500, 8000, 500, 1000).setTargetBufferBytes(targetBufferBytes)
                else setBufferDurationsMs(plan.minMs, plan.maxMs, plan.startMs, plan.rebufferMs).setTargetBufferBytes(plan.targetBytes)
            }.setPrioritizeTimeOverSizeThresholds(false).build())
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
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { updateWifi(); if (!released) onPlayWhenReady(playWhenReady) }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) { if (!released) applyHlsCushion(timeline) }
            override fun onPlayerError(error: PlaybackException) {
                if (released) { releaseFailed = true; return }
                if (localError(error)) return
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
                updateWifi()
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
        if (ring != null) { attempts = 0; fallbackDirect("exhausted"); return }
        if (failure == LiveFailure.UNSUPPORTED && probed) probe.forget(current)
        if (alternative < alternatives.size) {
            val (next, format) = alternatives[alternative++]
            attempts = 0
            play(next, format)
            return
        }
        failed = true; streamOpen = false
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
        current = url; readyReported = false; firstFrame = false; currentFormat = format; hlsCushion = false
        resilientUrl = if (isLive && format == IptvStreamFormat.MPEG_TS) url else null
        mainHandler.removeCallbacks(startCheck)
        if (isLive && format == IptvStreamFormat.MPEG_TS && url == locator && localConfig != null && ring == null) { player.playWhenReady = true; startLocal(url); return }
        val mimeType = when (format) {
            IptvStreamFormat.AUTO -> null
            IptvStreamFormat.HLS -> MimeTypes.APPLICATION_M3U8
            IptvStreamFormat.MPEG_TS -> MimeTypes.VIDEO_MP2T
        }
        player.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(mimeType).apply {
            if (isLive) setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(LIVE_MIN_SPEED).setMaxPlaybackSpeed(LIVE_MAX_SPEED).build())
        }.build())
        player.prepare(); player.playWhenReady = true
        if (format != IptvStreamFormat.HLS && extractors.lenient) mainHandler.postDelayed(startCheck, START_CHECK_MS)
    }
    val protocol: String? get() = network.protocol
    val altSvcH3: Boolean? get() = network.h3(current)
    val playbackSpeed: Float get() = player.playbackParameters.speed
    val bufferTargetMs: Long get() = if (currentFormat == IptvStreamFormat.HLS && isLive && ring == null && !cushionCleared) plan?.cushionMs ?: 0L else cushionTargetMs()
    fun behindLiveMs(): Long? {
        if (released || !isLive || ring != null || player.playbackState == Player.STATE_IDLE) return null
        if (currentFormat != IptvStreamFormat.HLS) return player.totalBufferedDuration.coerceAtLeast(0)
        player.currentLiveOffset.takeIf { it != C.TIME_UNSET && it >= 0 }?.let { return it }
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null
        val window = timeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
        return if (window.isLive() && window.durationMs != C.TIME_UNSET) (window.durationMs - player.currentPosition).coerceAtLeast(0) else null
    }
    fun goLive(): Boolean {
        if (released || failed || !isLive || ring != null || currentFormat == null) return false
        if (bufferTargetMs <= 0 || (behindLiveMs() ?: 0L) < GO_LIVE_MIN_MS) return false
        cushionCleared = true
        cushionSpeed.reset()
        if (player.playbackParameters.speed != 1f) player.playbackParameters = PlaybackParameters.DEFAULT
        if (currentFormat == IptvStreamFormat.HLS) player.seekToDefaultPosition()
        else { mainHandler.removeCallbacks(reconnect); mainHandler.post(reconnect) }
        IptvLog.info("live go live format=${currentFormat?.name?.lowercase()}")
        return true
    }
    private fun cushionTargetMs(): Long {
        val active = plan != null && !cushionCleared && !failed && isLive && ring == null && currentFormat == IptvStreamFormat.MPEG_TS
        return if (active && plan != null) LiveBufferPolicy.targetMs(plan.cushionMs, rateBits, plan.targetBytes) else 0L
    }
    private fun tickCushion(now: Long) {
        val bytes = loadedBytes.get()
        if (rateAt == 0L || bytes < rateBytes) { rateAt = now; rateBytes = bytes }
        else if (now - rateAt >= RATE_MS) {
            val sample = (bytes - rateBytes) * 8_000.0 / (now - rateAt)
            rateBits = rateBits?.let { it * 0.7 + sample * 0.3 } ?: sample
            rateAt = now; rateBytes = bytes
        }
        val target = cushionTargetMs()
        val speed = if (target <= 0) { cushionSpeed.reset(); 1f }
            else if (player.playbackState == Player.STATE_READY && player.playWhenReady) cushionSpeed.update(bufferedSnapshot, target) else cushionSpeed.speed
        if (player.playbackParameters.speed != speed) player.playbackParameters = PlaybackParameters(speed)
    }
    private fun applyHlsCushion(timeline: Timeline) {
        val cushion = plan?.cushionMs ?: return
        if (hlsCushion || cushionCleared || cushion <= 0 || !isLive || ring != null || currentFormat != IptvStreamFormat.HLS || timeline.isEmpty) return
        val window = timeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
        if (window.isPlaceholder || !window.isLive() || !window.isDynamic || window.durationMs == C.TIME_UNSET) return
        hlsCushion = true
        LiveBufferPolicy.hlsStartMs(window.defaultPositionMs, window.durationMs, cushion)?.let { player.seekTo(it) }
    }
    private fun updateWifi() {
        val wanted = !released && player.playWhenReady && (player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY)
        if (wanted == wifiHeld) return
        wifiHeld = wanted
        IptvLiveWifiLock.hold(appContext, this, wanted)
    }
    val localTimeshift: Boolean get() = ring != null
    fun localPosition(): Long? = if (ring == null) null else localAnchorTime?.let { it + player.currentPosition.coerceAtLeast(0) }
    fun localOldest(): Long? = ring?.oldestTime()
    fun localSeek(timeMillis: Long): Boolean {
        val local = ring ?: return false
        behindJumps = 0
        playRing(local, local.anchorAt(timeMillis))
        return true
    }
    fun localLive(): Boolean {
        val local = ring ?: return false
        behindJumps = 0
        playRing(local, local.liveAnchor())
        return true
    }
    fun armLocalTimeshift(config: IptvLocalTimeshiftConfig) {
        if (released || failed || !isLive || ring != null || localConfig != null) return
        localConfig = config
        if (currentFormat == IptvStreamFormat.MPEG_TS && current == locator) startLocal(locator)
    }
    private fun startLocal(url: String) {
        val config = localConfig ?: return
        val generation = ++localGeneration
        mainHandler.removeCallbacks(reconnect); mainHandler.removeCallbacks(startCheck)
        if (player.playbackState != Player.STATE_IDLE) player.stop()
        if (!reconnecting) { reconnecting = true; onReconnecting(true) }
        thread(name = "IptvTimeshiftStart", isDaemon = true) {
            val idle = awaitIdle()
            if (idle) client.connectionPool.evictAll()
            val session = if (!idle) null else try { IptvLocalTimeshiftSession.create(config) } catch (error: Exception) {
                IptvLog.failure("local timeshift start", error); null
            }
            mainHandler.post {
                if (released || generation != localGeneration || session == null) {
                    session?.close()
                    if (!released && generation == localGeneration) {
                        localConfig = null
                        IptvLog.info("local timeshift unavailable reason=${if (idle) "storage" else "busy"}")
                        play(url, IptvStreamFormat.MPEG_TS)
                    }
                    return@post
                }
                ring = session
                behindJumps = 0
                session.start(LocalInput(url)) { reason -> mainHandler.post { if (ring === session) fallbackDirect(reason?.name?.lowercase() ?: "ended") } }
                IptvLog.info("local timeshift started length=${config.length.name.lowercase()}")
                onLocalTimeshift(true)
                playRing(session, 0)
            }
        }
    }
    private fun awaitIdle(): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + IDLE_WAIT_MS
        while (fence.active.value != 0) {
            if (android.os.SystemClock.elapsedRealtime() >= deadline) return false
            try { Thread.sleep(50) } catch (_: InterruptedException) { return false }
        }
        return true
    }
    private fun playRing(session: IptvLocalTimeshiftSession, anchor: Long) {
        mainHandler.removeCallbacks(reconnect); mainHandler.removeCallbacks(startCheck)
        firstFrame = false
        localAnchorTime = session.timeAt(anchor) ?: System.currentTimeMillis()
        if (player.playbackState != Player.STATE_IDLE) player.stop()
        player.setMediaItem(MediaItem.Builder().setUri(session.uri(anchor)).setMimeType(MimeTypes.VIDEO_MP2T).build())
        player.prepare()
        if (extractors.lenient) mainHandler.postDelayed(startCheck, START_CHECK_MS)
    }
    private fun localError(error: PlaybackException): Boolean {
        val local = ring ?: return false
        val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
        val action = when {
            local.failure != null || causes.any { it is IptvLocalTimeshiftClosedException } -> LocalTimeshiftAction.Direct
            causes.any { it is IptvLocalTimeshiftBehindException } -> LocalTimeshiftPolicy.onReaderBehind(behindJumps++)
            causes.any { it is IptvLocalTimeshiftStalledException } -> LocalTimeshiftPolicy.onReaderStalled(local.running)
            else -> return false
        }
        when (action) {
            LocalTimeshiftAction.Direct -> fallbackDirect("reader")
            LocalTimeshiftAction.Oldest -> { IptvLog.info("local timeshift reader behind"); playRing(local, local.oldest()) }
            LocalTimeshiftAction.Live -> playRing(local, local.liveAnchor())
        }
        return true
    }
    private fun fallbackDirect(reason: String) {
        val session = ring ?: return
        ring = null; localConfig = null; localAnchorTime = null
        val generation = ++localGeneration
        IptvLog.info("local timeshift fallback reason=$reason")
        onLocalTimeshift(false)
        mainHandler.removeCallbacks(reconnect); mainHandler.removeCallbacks(startCheck)
        if (player.playbackState != Player.STATE_IDLE) player.stop()
        if (!reconnecting) { reconnecting = true; onReconnecting(true) }
        thread(name = "IptvTimeshiftStop", isDaemon = true) {
            session.stop()
            session.awaitStopped(IDLE_WAIT_MS)
            session.close()
            mainHandler.post { if (!released && !failed && generation == localGeneration) play(locator, IptvStreamFormat.MPEG_TS) }
        }
    }
    private inner class LocalInput(private val url: String) : IptvLocalTimeshiftInput {
        @Volatile private var call: okhttp3.Call? = null
        @Volatile private var cancelled = false
        @Volatile private var ticket: LiveRequestFence.Ticket? = null
        override fun open(): java.io.InputStream {
            ticket = fence.enter() ?: throw IOException("Live session has closed")
            val request = okhttp3.Request.Builder().url(url).apply { (requestHeaders + ("User-Agent" to userAgent)).forEach { (name, value) -> header(name, value) } }.build()
            val response = client.newCall(request).also { call = it; if (cancelled) it.cancel() }.execute()
            if (!response.isSuccessful) { response.close(); throw IOException("Local timeshift HTTP ${response.code}") }
            return object : java.io.FilterInputStream(response.body.byteStream()) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also { telemetry.transferred(it) }
            }
        }
        override fun cancel() { cancelled = true; call?.cancel() }
        override fun release() { ticket?.let { fence.leave(it); ticket = null } }
    }
    override suspend fun close(): Boolean {
        mainHandler.removeCallbacks(reconnect); mainHandler.removeCallbacks(stall); mainHandler.removeCallbacks(steady)
        mainHandler.removeCallbacks(startCheck); mainHandler.removeCallbacks(watchdog)
        streamOpen = false
        if (wifiHeld) { wifiHeld = false; IptvLiveWifiLock.hold(appContext, this, false) }
        fence.stopAccepting()
        client.dispatcher.cancelAll()
        localGeneration++
        ring?.let { session -> ring = null; session.stop(); thread(name = "IptvTimeshiftClose", isDaemon = true) { session.awaitStopped(IDLE_WAIT_MS); session.close() } }
        if (!released) {
            released = true
            runCatching { enhancer?.release() }; enhancer = null
            try { player.release() } catch (_: Exception) { releaseFailed = true }
        }

        if (releaseFailed) return false
        val closed = withTimeoutOrNull(15_000) {
            while (fence.active.value != 0 && (client.dispatcher.runningCallsCount() > 0 || client.dispatcher.queuedCallsCount() > 0)) delay(40)
            true
        } ?: false
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
        const val IDLE_WAIT_MS = 5_000L
        const val LIVE_MIN_SPEED = 0.97f
        const val LIVE_MAX_SPEED = 1.03f
        const val RATE_MS = 5_000L
        const val GO_LIVE_MIN_MS = 3_000L
    }
}

internal object IptvLiveWifiLock {
    private val holds = LiveHolds<Any>()
    private var locks: List<android.net.wifi.WifiManager.WifiLock> = emptyList()

    @Synchronized fun hold(context: Context, holder: Any, wanted: Boolean) {
        if (wanted) { if (holds.acquire(holder)) acquire(context) } else if (holds.release(holder)) release()
    }

    @Suppress("DEPRECATION")
    private fun acquire(context: Context) {
        val manager = runCatching { context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java) }.getOrNull() ?: return
        val modes = listOfNotNull(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY else null)
        locks = modes.mapNotNull { mode ->
            runCatching { manager.createWifiLock(mode, "nuvio:iptv-live-$mode").apply { setReferenceCounted(false); acquire() } }
                .onFailure { IptvLog.failure("live wifi lock", it) }.getOrNull()
        }
    }

    private fun release() {
        locks.forEach { lock -> runCatching { if (lock.isHeld) lock.release() } }
        locks = emptyList()
    }
}
