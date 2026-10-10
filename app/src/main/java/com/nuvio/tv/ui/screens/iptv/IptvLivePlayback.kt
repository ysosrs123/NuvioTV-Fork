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
import android.media.MediaFormat
import androidx.media3.common.Format
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.common.TrackGroup
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.nuvio.tv.core.player.AudioPassthroughPolicy
import com.nuvio.tv.data.local.AudioOutputChannels
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.ui.screens.player.AudioOutputRouteDetector
import com.nuvio.tv.ui.screens.player.PassthroughOutputReturn
import com.nuvio.tv.ui.screens.player.SurroundResolveInputs
import com.nuvio.tv.ui.screens.player.applyDownmixSettings
import com.nuvio.tv.ui.screens.player.resolveSurroundForRoute
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
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
import com.nuvio.tv.data.iptv.IptvClaimedCalls
import com.nuvio.tv.data.iptv.IptvLiveCalls
import com.nuvio.tv.data.iptv.IptvLiveNet
import com.nuvio.tv.data.iptv.IptvLiveSocketFactory
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.data.iptv.IptvResilientDataSource
import com.nuvio.tv.data.iptv.IptvStreamNetwork
import com.nuvio.tv.data.iptv.IptvStreamingPreferences
import com.nuvio.tv.core.iptv.*
import java.io.IOException
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    private val lowMemory = liveLowMemory(context)
    private val network = IptvStreamNetwork()
    private val calls = IptvLiveCalls()
    private val client = IptvLiveNet.client(liveReceiveBytes(lowMemory)).newBuilder().eventListenerFactory(calls).addNetworkInterceptor(network).build()
    private val requestHeaders = headers.filterKeys { it in HEADER_NAMES }.mapNotNull { (name, value) -> StreamHeaders.clean(value)?.let { name to it } }.toMap()
    private val userAgent = LiveUserAgent.pick(requestHeaders["User-Agent"], sourceUserAgent?.let(StreamHeaders::clean), DEFAULT_USER_AGENT)
    private val streaming = IptvStreamingPreferences(context)
    private val plan = if (!primary) null else LiveBufferPolicy.plan(streaming.start, if (isLive) streaming.cushion else LiveCushion.OFF,
        LiveBufferPolicy.capBytes(lowMemory, Runtime.getRuntime().maxMemory()), targetBufferBytes)
    private val audioPlan = LiveAudioOptions.audio(streaming.passthrough, streaming.tunnelling, streaming.audioDecoder, streaming.surroundLift, primary, streaming.cornerPicture)
    private val output = if (audioPlan.passthrough) IptvLiveNuvioAudio.output(context) else null
    private var tunnel = audioPlan.tunnelling
    @Volatile private var audioTunnel: Boolean? = null
    private val tunnelActive: Boolean get() = tunnel && (audioTunnel ?: runCatching { player.isTunnelingEnabled }.getOrDefault(false))
    val tunnelling: Boolean get() = tunnelActive
    private val trackSelector = LiveTrackSelector(context, streaming.preferSurround).apply {
        setParameters(buildUponParameters().setTunnelingEnabled(tunnel)
            .setPreferredAudioLanguages(*LiveAudioOptions.languages(streaming.audioLanguage, androidx.media3.common.util.Util.getSystemLanguageCodes().toList()).toTypedArray()))
    }
    private val cushionSpeed = LiveCushionSpeed()
    private var cushionCleared = false
    private val behind = LiveBehindClock()
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
    private var startedAt = 0L
    @Volatile private var firstByteAt = 0L
    private var readyAt = 0L
    private var frameAt = 0L
    private var closeStartedAt = 0L
    private var closeLogged: String? = null
    private val reconnect = Runnable {
        if (!released && !failed) {
            val position = player.currentPosition
            if (player.playbackState != Player.STATE_IDLE) player.stop()
            val local = ring
            if (local != null) playRing(local, local.liveAnchor())
            else {
                behind.reset()
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
            val progress = if (!tunnelActive && player.videoFormat != null && counters != null) {
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
    private val audioSession = androidx.media3.common.util.Util.generateAudioSessionIdV21(context)
    private var enhancer: android.media.audiofx.LoudnessEnhancer? = null
    var boostDb: Int = boostDb
        private set
    private val surroundLift = streaming.surroundLift
    @Volatile private var pcmOutput = false
    @Volatile var autoBoostDb: Int = 0
        private set
    val appliedBoostDb: Int get() = if (!pcmOutput) 0 else (boostDb + autoBoostDb).coerceAtMost(MAX_BOOST_DB)
    private var frames = 0
    private var pictureCheck: Runnable? = null
    val player: ExoPlayer
    private val playbackThread: Thread
    init {
        require(purpose == PlaybackPurpose.LIVE_CHANNEL)
        require((listOf(locator) + alternatives.map { it.first }).all { Uri.parse(it).scheme?.lowercase() in setOf("http", "https") })
        val upstream = OkHttpDataSource.Factory(IptvClaimedCalls(client)).setUserAgent(userAgent).setDefaultRequestProperties(requestHeaders - "User-Agent")
        upstream.setTransferListener(object : TransferListener {
            override fun onTransferInitializing(source: DataSource, spec: DataSpec, network: Boolean) = Unit
            override fun onTransferStart(source: DataSource, spec: DataSpec, network: Boolean) = Unit
            override fun onTransferEnd(source: DataSource, spec: DataSpec, network: Boolean) = Unit
            override fun onBytesTransferred(source: DataSource, spec: DataSpec, network: Boolean, count: Int) {
                if (network) {
                    telemetry.transferred(count)
                    if (count > 0) { loadedBytes.addAndGet(count.toLong()); if (firstByteAt == 0L) firstByteAt = android.os.SystemClock.elapsedRealtime() }
                }
            }
        })
        val sources = DataSource.Factory {
            val direct = IptvResilientDataSource(upstream.createDataSource(), { spec -> spec.position == 0L && spec.uri.toString() == resilientUrl },
                { bufferedSnapshot }, { streamOpen }, { reconnects++; IptvLog.info("live reconnect seamless count=$reconnects") })
            FencedSource(IptvLocalTimeshiftRouter(direct) { ring }, fence, { Uri.parse(current) }, !isLive)
        }
        val renderers = LiveRenderersFactory(context, output).setExtensionRendererMode(
            if (audioPlan.preferAppDecoder) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        player = ExoPlayer.Builder(context, renderers).setTrackSelector(trackSelector).setReleaseTimeoutMs(RELEASE_BLOCK_MS)
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
        playbackThread = player.playbackLooper.thread
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), handleAudioFocus)
        player.setAudioSessionId(audioSession)
        setBoost(boostDb)
        player.setHandleAudioBecomingNoisy(true)
        if (primary) IptvLog.info("live audio passthrough=${output != null} tunnel=$tunnel decoder=${if (audioPlan.preferAppDecoder) "app" else "auto"} " +
            "surround=${streaming.preferSurround} language=${streaming.audioLanguage}${output?.let { " " + it.describe() } ?: ""}")
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                updateLift(format)
            }
            override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                val pcm = androidx.media3.common.util.Util.isEncodingLinearPcm(audioTrackConfig.encoding) && !audioTrackConfig.tunneling
                val tunnelled = audioTrackConfig.tunneling
                if (tunnelled != audioTunnel) { audioTunnel = tunnelled; IptvLog.info("live tunnel engaged=$tunnelled") }
                if (pcm != pcmOutput) pcmOutput = pcm
                updateLift(player.audioFormat)
                applyBoost()
            }
        })
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!released) onPlaying(isPlaying) }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { updateWifi(); if (!released) onPlayWhenReady(playWhenReady) }
            override fun onPlayerError(error: PlaybackException) {
                if (released) return
                if (localError(error)) return
                val response = generateSequence<Throwable>(error) { it.cause }.take(8).filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                retry(error.errorCode, response?.responseCode,
                    response?.headerFields?.entries?.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value?.firstOrNull())
            }
            override fun onRenderedFirstFrame() {
                firstFrame = true
                frames++
                if (frameAt == 0L) frameAt = android.os.SystemClock.elapsedRealtime()
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
                        if (readyAt == 0L) readyAt = android.os.SystemClock.elapsedRealtime()
                        if (!firstFrame && tunnelActive) {
                            firstFrame = true
                            if (frameAt == 0L) frameAt = android.os.SystemClock.elapsedRealtime()
                            mainHandler.removeCallbacks(startCheck)
                            telemetry.firstFrame(android.os.SystemClock.elapsedRealtime())
                        }
                        behind.ready(android.os.SystemClock.elapsedRealtime(), player.currentPosition)
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
        applyBoost()
    }
    private fun updateLift(format: Format?) {
        val lift = if (surroundLift && !tunnelActive && format != null && format.sampleMimeType == MimeTypes.AUDIO_AAC && format.channelCount > 2) SURROUND_LIFT_DB else 0
        if (lift != autoBoostDb) { autoBoostDb = lift; applyBoost() }
    }
    private fun leaveTunnel() {
        if (!tunnel || released) return
        tunnel = false; audioTunnel = null
        trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingEnabled(false))
        IptvLog.info("live tunnel off reason=tile")
        updateLift(player.audioFormat)
        applyBoost()
    }
    private fun applyBoost() {
        if (released) return
        val gain = appliedBoostDb
        runCatching {
            if (gain == 0) { enhancer?.release(); enhancer = null }
            else (enhancer ?: android.media.audiofx.LoudnessEnhancer(audioSession).also { enhancer = it }).apply { setTargetGain(gain * 100); enabled = true }
        }.onFailure { enhancer = null }
    }
    fun limitHeight(height: Int?) {
        mainHandler.post {
            if (released) return@post
            leaveTunnel()
            val builder = player.trackSelectionParameters.buildUpon()
            player.trackSelectionParameters = (if (height == null) builder.clearVideoSizeConstraints() else builder.setMaxVideoSize(height * 16 / 9, height)).build()
        }
    }
    fun expectPicture(onMissing: () -> Unit) {
        if (released) return
        pictureCheck?.let(mainHandler::removeCallbacks)
        val mark = frames
        val queued = decodedInput()
        pictureCheck = Runnable {
            pictureCheck = null
            val fed = queued != null && (decodedInput() ?: 0) > queued
            if (!released && !failed && tunnelActive && frames == mark && !fed && player.playbackState == Player.STATE_READY && player.playWhenReady) {
                IptvLog.info("live tunnel picture missing after return")
                onMissing()
            }
        }.also { mainHandler.postDelayed(it, PICTURE_RETURN_MS) }
    }
    private fun decodedInput(): Int? = player.videoDecoderCounters?.let { it.ensureUpdated(); it.queuedInputBufferCount }
    val pixelRate: Long? get() = player.videoFormat?.takeIf { it.width > 0 && it.height > 0 }?.let {
        it.width.toLong() * it.height * (it.frameRate.takeIf { rate -> rate > 0 }?.toInt() ?: MULTIVIEW_FRAME_RATE)
    }
    override fun start() {
        check(!released)
        maxVideoHeight?.let { height ->
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setMaxVideoSize(height * 16 / 9, height).build()
        }
        startedAt = android.os.SystemClock.elapsedRealtime()
        telemetry.start(startedAt)
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
        current = url; readyReported = false; firstFrame = false; currentFormat = format; behind.reset()
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
        if (currentFormat != IptvStreamFormat.HLS) return behind.behindMs(android.os.SystemClock.elapsedRealtime(), player.currentPosition)
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null
        val window = timeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
        if (window.isPlaceholder || !window.isLive() || window.defaultPositionMs == C.TIME_UNSET) return null
        return (window.defaultPositionMs - player.currentPosition).coerceAtLeast(0)
    }
    fun startSummary(): String {
        fun since(at: Long) = if (at == 0L || startedAt == 0L) "-" else (at - startedAt).toString()
        return "bytes ms=${since(firstByteAt)} ready ms=${since(readyAt)} frame ms=${since(frameAt)} warm=${IptvLiveNet.warmUsed}"
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
        firstFrame = false; behind.reset()
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
    override fun interrupt() { if (!released) shutdown() }
    override val decoderReleased: Boolean get() = released && !playbackThread.isAlive
    override fun abandon() {
        IptvLog.info("live close detached ms=${android.os.SystemClock.elapsedRealtime() - closeStartedAt} ${calls.describe()} requests=${fence.active.value}")
    }
    private fun shutdown() {
        closeStartedAt = android.os.SystemClock.elapsedRealtime()
        mainHandler.removeCallbacks(reconnect); mainHandler.removeCallbacks(stall); mainHandler.removeCallbacks(steady)
        mainHandler.removeCallbacks(startCheck); mainHandler.removeCallbacks(watchdog)
        pictureCheck?.let(mainHandler::removeCallbacks); pictureCheck = null
        streamOpen = false
        if (wifiHeld) { wifiHeld = false; IptvLiveWifiLock.hold(appContext, this, false) }
        fence.stopAccepting()
        calls.cancelAll()
        localGeneration++
        ring?.let { session -> ring = null; session.stop(); thread(name = "IptvTimeshiftClose", isDaemon = true) { session.awaitStopped(IDLE_WAIT_MS); session.close() } }
        released = true
        runCatching { enhancer?.release() }; enhancer = null
        try { player.release() } catch (error: Exception) { IptvLog.failure("live release", error) }
        IptvLog.info("live close started ${calls.describe()} requests=${fence.active.value} thread=${playbackThread.isAlive}")
    }
    private fun settled(): Boolean = !playbackThread.isAlive && calls.open == 0
    override suspend fun close(): Boolean {
        if (!released) shutdown() else calls.cancelAll()
        val settled = withTimeoutOrNull(CLOSE_WAIT_MS) {
            while (!settled()) delay(CLOSE_POLL_MS)
            true
        } ?: false
        val elapsed = android.os.SystemClock.elapsedRealtime() - closeStartedAt
        val forced = !settled && !playbackThread.isAlive && elapsed >= ORPHAN_MS
        if (settled || forced) {
            IptvLog.info("live close ms=$elapsed${if (forced) " forced" else ""} ${calls.describe()} left=${calls.count}")
            return true
        }
        val reason = if (playbackThread.isAlive) "release" else "calls"
        if (closeLogged != reason) {
            closeLogged = reason
            IptvLog.info("live close unconfirmed reason=$reason ms=$elapsed ${calls.describe()} requests=${fence.active.value}")
        }
        return false
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
    private class LiveRenderersFactory(context: Context, private val output: LiveAudioOutput?) : DefaultRenderersFactory(context) {
        override fun buildVideoRenderers(context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
            eventHandler: Handler, eventListener: VideoRendererEventListener, allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>) =
            super.buildVideoRenderers(context, if (extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER) EXTENSION_RENDERER_MODE_ON else extensionRendererMode,
                mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out)

        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
            val capabilities = when {
                output == null || output.bluetooth -> AudioOutputRouteDetector.bluetoothPcmOnlyCapabilities()
                output.television -> runCatching { PassthroughOutputReturn.capabilities(context) }.getOrNull()
                else -> null
            }
            val sink = (if (capabilities != null) DefaultAudioSink.Builder().setAudioCapabilities(capabilities) else DefaultAudioSink.Builder(context))
                .setEnableFloatOutput(enableFloatOutput).setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams).build()
            return if (output == null || output.bluetooth) sink else LiveAudioSink(sink, output.policy, output.forceAc3)
        }

        override fun buildAudioRenderers(context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
            audioSink: AudioSink, eventHandler: Handler, eventListener: AudioRendererEventListener, out: ArrayList<Renderer>) {
            val start = out.size
            super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
            (start until out.size).firstOrNull { out[it] is MediaCodecAudioRenderer }?.let { index ->
                out[index] = LiveAudioRenderer(context, getCodecAdapterFactory(), mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink)
            }
            if (output == null || out.size <= start) return
            out.subList(start, out.size).filterIsInstance<FfmpegAudioRenderer>().forEach {
                it.applyDownmixSettings(output.downmix, output.channels, output.normalise, output.forceOptical, output.transcode)
            }
            if (output.forceOptical) {
                val block = out.subList(start, out.size)
                val ordered = block.sortedByDescending { it is FfmpegAudioRenderer }
                ordered.forEachIndexed { i, renderer -> block[i] = renderer }
            }
        }
    }
    private class LiveAudioSink(sink: AudioSink, private val policy: AudioPassthroughPolicy, private val forceAc3: Boolean) : ForwardingAudioSink(sink) {
        override fun getFormatSupport(format: Format): Int {
            if (policy.deniesPassthrough(format.sampleMimeType)) return AudioSink.SINK_FORMAT_UNSUPPORTED
            val support = super.getFormatSupport(format)
            return if (forceAc3 && support == AudioSink.SINK_FORMAT_UNSUPPORTED && format.sampleMimeType == MimeTypes.AUDIO_AC3 && format.channelCount <= 6)
                AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else support
        }
        override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
            if (policy.deniesPassthrough(format.sampleMimeType)) AudioOffloadSupport.DEFAULT_UNSUPPORTED else super.getFormatOffloadSupport(format)
    }
    private class LiveTrackSelector(context: Context, private val preferSurround: Boolean) : DefaultTrackSelector(context) {
        override fun selectAllTracks(mappedTrackInfo: MappedTrackInfo, rendererFormatSupports: Array<out Array<out IntArray>>,
            rendererMixedMimeTypeAdaptationSupports: IntArray, params: DefaultTrackSelector.Parameters): Array<ExoTrackSelection.Definition?> {
            val definitions = super.selectAllTracks(mappedTrackInfo, rendererFormatSupports, rendererMixedMimeTypeAdaptationSupports, params)
            if (!preferSurround) return definitions
            val slots = ArrayList<Triple<Int, TrackGroup, Int>>()
            val candidates = ArrayList<LiveAudioCandidate>()
            var chosen: Int? = null
            for (renderer in 0 until mappedTrackInfo.rendererCount) {
                if (mappedTrackInfo.getRendererType(renderer) != C.TRACK_TYPE_AUDIO) continue
                val groups = mappedTrackInfo.getTrackGroups(renderer)
                val selected = definitions[renderer]
                for (g in 0 until groups.length) {
                    val group = groups[g]
                    for (t in 0 until group.length) {
                        if (selected != null && selected.group === group && selected.tracks.firstOrNull() == t) chosen = candidates.size
                        val format = group.getFormat(t)
                        slots += Triple(renderer, group, t)
                        candidates += LiveAudioCandidate(format.channelCount, format.language,
                            RendererCapabilities.getFormatSupport(rendererFormatSupports[renderer][g][t]) == C.FORMAT_HANDLED)
                    }
                }
            }
            val pick = LiveAudioOptions.surround(candidates, chosen)
            if (pick == null || pick == chosen) return definitions
            val (renderer, group, track) = slots[pick]
            for (r in definitions.indices) if (mappedTrackInfo.getRendererType(r) == C.TRACK_TYPE_AUDIO) definitions[r] = null
            definitions[renderer] = ExoTrackSelection.Definition(group, track)
            return definitions
        }
    }
    private class LiveAudioRenderer(context: Context, codecAdapterFactory: MediaCodecAdapter.Factory, mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean, eventHandler: Handler?, eventListener: AudioRendererEventListener?, audioSink: AudioSink) :
        MediaCodecAudioRenderer(context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink) {
        override fun getMediaFormat(format: Format, codecMimeType: String, codecMaxInputSize: Int, codecOperatingRate: Float): MediaFormat =
            super.getMediaFormat(format, codecMimeType, codecMaxInputSize, codecOperatingRate).apply {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && codecMimeType == MimeTypes.AUDIO_AAC)
                    setInteger(MediaFormat.KEY_AAC_DRC_TARGET_REFERENCE_LEVEL, AAC_TARGET_LEVEL)
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
        const val SURROUND_LIFT_DB = 4
        const val AAC_TARGET_LEVEL = 64
        const val IDLE_WAIT_MS = 5_000L
        const val LIVE_MIN_SPEED = 0.97f
        const val LIVE_MAX_SPEED = 1.03f
        const val RATE_MS = 5_000L
        const val GO_LIVE_MIN_MS = 3_000L
        const val RELEASE_BLOCK_MS = 250L
        const val CLOSE_WAIT_MS = 500L
        const val CLOSE_POLL_MS = 25L
        const val ORPHAN_MS = 20_000L
        const val PICTURE_RETURN_MS = 10_000L
    }
}

internal class LiveAudioOutput(val bluetooth: Boolean, val television: Boolean, val forceOptical: Boolean, val policy: AudioPassthroughPolicy,
    val downmix: Boolean, val channels: AudioOutputChannels, val normalise: Boolean, val transcode: Set<String>) {
    val forceAc3: Boolean get() = forceOptical || transcode.isNotEmpty()
    fun describe(): String = "bluetooth=$bluetooth pinned=$television ac3=$forceOptical downmix=${if (downmix) channels.settingValue else "off"} " +
        "denied=${listOf("ac3" to policy.allowAc3, "eac3" to policy.allowEac3, "dts" to policy.allowDts).filterNot { it.second }.joinToString(",") { it.first }.ifEmpty { "none" }}"
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface IptvLiveAudioEntryPoint {
    fun playerSettings(): PlayerSettingsDataStore
}

internal object IptvLiveNuvioAudio {
    @Volatile private var latest: PlayerSettings? = null
    private var watching = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun watch(store: PlayerSettingsDataStore) {
        synchronized(this) { if (watching) return; watching = true }
        scope.launch { store.playerSettings.collect { latest = it } }
    }

    suspend fun ready(store: PlayerSettingsDataStore) {
        watch(store)
        if (latest != null) return
        withTimeoutOrNull(FIRST_READ_MS) { store.playerSettings.first() }?.let { if (latest == null) latest = it }
    }

    fun settings(context: Context): PlayerSettings {
        latest?.let { return it }
        runCatching { EntryPointAccessors.fromApplication(context.applicationContext, IptvLiveAudioEntryPoint::class.java).playerSettings() }.getOrNull()?.let(::watch)
        IptvLog.info("live audio settings not loaded yet")
        return latest ?: PlayerSettings()
    }

    fun output(context: Context): LiveAudioOutput = runCatching {
        val settings = settings(context)
        val route = AudioOutputRouteDetector.detect(context)
        val bluetooth = route?.isBluetooth == true
        val forceOptical = !bluetooth && settings.forceOpticalPassthrough
        val channels = if (bluetooth) AudioOutputChannels.CHANNELS_2_0 else settings.audioOutputChannels
        val surround = resolveSurroundForRoute(context, settings, SurroundResolveInputs(route?.key, bluetooth, true, forceOptical, settings.downmixEnabled || bluetooth, channels))
        LiveAudioOutput(bluetooth, context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK), forceOptical,
            surround.resolution.policy, surround.downmixEnabled, surround.audioOutputChannels, !settings.maintainOriginalAudioOnDownmix, surround.deniedTranscodeMimes)
    }.getOrElse { error ->
        IptvLog.failure("live audio output", error)
        LiveAudioOutput(false, false, false, AudioPassthroughPolicy.ALLOW_ALL, false, AudioOutputChannels.default, false, emptySet())
    }

    private const val FIRST_READ_MS = 1_500L
}

internal fun liveLowMemory(context: Context): Boolean = runCatching {
    val manager = requireNotNull(context.getSystemService(android.app.ActivityManager::class.java))
    val memory = android.app.ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
    LiveBufferPolicy.lowMemory(memory.totalMem, manager.isLowRamDevice)
}.getOrDefault(true)

internal fun liveReceiveBytes(lowMemory: Boolean): Int = if (lowMemory) IptvLiveSocketFactory.LOW_MEMORY_BYTES else IptvLiveSocketFactory.NORMAL_BYTES

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
