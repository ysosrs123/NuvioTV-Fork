package com.nuvio.tv.ui.brand

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.nuvio.tv.core.util.StartupLatencyTrace
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Plays the Arc entrance on its own thread and surface, so a busy main thread while Home loads
 * cannot hold a frame back. The settled state is the loading hold; [exit] fades it out.
 */
@SuppressLint("ViewConstructor")
class ArcIgnitionView(
    context: Context,
    val palette: ArcPalette,
    private val onStarted: () -> Unit,
    private val onFailed: (reason: String) -> Unit,
    /** Called on the intro's own thread right after the first frame was posted, so the sound lands with it. */
    private val onFirstFrame: () -> Unit = {},
    /** Starts from the start window's mark: the logo is already there and the frame fades in over the window. */
    private val handoff: Boolean = false
) : SurfaceView(context), SurfaceHolder.Callback {

    private val main = Handler(Looper.getMainLooper())
    private val reducedMotion = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
    private var renderer: Renderer? = null
    private var pendingExit: Pair<Long, () -> Unit>? = null
    private val tier = ArcIntroTier.forDevice(context)
    private val reducedSurface = tier.reducedBuffer
    @Volatile var stage = ArcIntroStage.Waiting
        private set

    init {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Fades the surface out over [durationMs] and calls [onFinished] on the main thread. */
    fun exit(durationMs: Long, onFinished: () -> Unit) {
        val r = renderer
        if (r == null) {
            pendingExit?.second?.invoke()
            pendingExit = durationMs to onFinished
            return
        }
        r.exit(durationMs, onFinished)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyBufferSize()
    }

    private fun applyBufferSize() {
        if (width <= 0 || height <= 0) return
        val cap = bufferCap()
        holder.setFixedSize(max(1, (width * cap).roundToInt()), max(1, (height * cap).roundToInt()))
    }

    private fun bufferCap(): Float = bufferCapFor(width, height, reducedSurface)

    private fun bufferScale(): Float = bufferScaleFor(width, height, reducedSurface)

    override fun surfaceCreated(holder: SurfaceHolder) {
        renderer = Renderer(holder).also { r ->
            pendingExit?.let { (duration, done) -> r.exit(duration, done) }
            pendingExit = null
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderer?.resize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val idle = renderer?.quit() ?: true
        renderer = null
        // Let go of the drawing context here, before the framework disconnects the surface: on the Shield the main
        // thread otherwise waits on the graphics driver for seconds while the view is removed.
        if (idle) runCatching { holder.surface.release() }
    }

    private inner class Renderer(private val holder: SurfaceHolder) : Choreographer.FrameCallback {
        private val thread = HandlerThread("ArcIntro").apply { start() }
        private val handler = Handler(thread.looper)
        private lateinit var choreographer: Choreographer
        private var scene: ArcIgnitionScene? = null
        @Volatile private var surfaceWidth = 0
        @Volatile private var surfaceHeight = 0
        private var startNanos = 0L
        private var lastNanos = 0L
        private var exitStartNanos = 0L
        private var exitDurationNanos = 0L
        private var exitDone: (() -> Unit)? = null
        private var exitDelivered = false
        private var running = true
        @Volatile private var quitting = false
        private val drawLock = ReentrantLock()
        private var drawn = false
        private var reportedStart = false
        private val quality = ArcQualitySteps(startDetail = tier.detail)
        private var frames = 0
        private var drops = 0
        private var worstMs = 0f
        private var drawNanos = 0L
        private var lastAlpha = -1f
        private var lastContent = -1f

        init {
            val pixelScale = bufferScale()
            handler.post {
                choreographer = Choreographer.getInstance()
                stage = ArcIntroStage.Preparing
                StartupLatencyTrace.cold("intro_prepare_start", palette.key)
                val prepareStart = System.nanoTime()
                val ahead = ArcIntroPrep.take(palette, pixelScale)
                scene = (ahead as? AheadOfTime.Take.Hit)?.value
                    ?: runCatching { ArcIgnitionScene.prepare(context.resources, palette, pixelScale) }
                        .onFailure { Log.w(Tag, "prepare failed", it) }
                        .getOrNull()
                val aheadName = when (ahead) {
                    is AheadOfTime.Take.Hit -> "hit"
                    AheadOfTime.Take.Failed -> "failed"
                    AheadOfTime.Take.Miss -> "miss"
                }
                val prepareMs = (System.nanoTime() - prepareStart) / 1_000_000
                StartupLatencyTrace.cold("intro_prepare_end", "${palette.key} $prepareMs ms ahead=$aheadName ok=${scene != null}")
                if (quitting) return@post
                if (scene == null) { fail("prepare failed"); return@post }
                scene?.detail = tier.detail
                stage = ArcIntroStage.Ready
                Log.i(Tag, "prepared ${palette.key} in $prepareMs ms (ahead=$aheadName), reduced motion=$reducedMotion, handoff=$handoff, start detail=${tier.detail} smallBuffer=${tier.reducedBuffer}")
                choreographer.postFrameCallback(this)
            }
        }

        fun resize(width: Int, height: Int) { surfaceWidth = width; surfaceHeight = height }

        fun exit(durationMs: Long, onFinished: () -> Unit) {
            handler.post {
                if (exitDone != null) return@post
                exitDone = onFinished
                exitDurationNanos = max(1L, durationMs) * 1_000_000L
                if (scene == null || !running) finishExit()
            }
        }

        /**
         * Waits only for a frame being drawn, never for [ArcIgnitionScene.prepare]. A gone surface counts as faded out.
         * False when a frame was still being drawn after the wait.
         */
        fun quit(): Boolean {
            quitting = true
            handler.post {
                running = false
                if (::choreographer.isInitialized) choreographer.removeFrameCallback(this)
                logSummary("closed")
                deliverExit()
            }
            val idle = drawLock.tryLock(500, TimeUnit.MILLISECONDS)
            if (idle) drawLock.unlock()
            thread.quitSafely()
            return idle
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!running || quitting) return
            val s = scene ?: return
            // Asked to leave before anything was shown: there is nothing to fade, so never put the stage up.
            if (exitDone != null && !drawn) { finishExit(); return }
            if (startNanos == 0L) {
                startNanos = frameTimeNanos
            } else {
                track((frameTimeNanos - lastNanos) / 1_000_000f, frameTimeNanos, s)
            }
            lastNanos = frameTimeNanos
            if (exitDone != null && exitStartNanos == 0L) exitStartNanos = frameTimeNanos

            val elapsed = (frameTimeNanos - startNanos) / 1_000_000_000f
            val clock = if (reducedMotion) ReducedMotionClock else elapsed
            var alpha = ArcStartHandoff.frameAlpha(elapsed, handoff, reducedMotion)
            var content = 1f
            if (exitStartNanos != 0L) {
                // First half: the mark, lettering and light fade out over the stage. Second half: the stage itself
                // dissolves into Home, so nothing of the mark is left once Home shows.
                val t = (frameTimeNanos - exitStartNanos).toFloat() / exitDurationNanos
                content = 1f - ArcIgnitionScene.smooth(t * 2f)
                alpha *= 1f - ArcIgnitionScene.smooth(t * 2f - 1f)
            }
            val still = reducedMotion && alpha == lastAlpha && content == lastContent
            lastAlpha = alpha
            lastContent = content
            if (!still && !render(s, clock, alpha, content)) { fail("draw failed"); return }
            if (drawn && !reportedStart) { reportedStart = true; main.post { onStarted() } }
            if (exitStartNanos != 0L && alpha <= 0f) { finishExit(); return }
            choreographer.postFrameCallback(this)
        }

        private fun render(s: ArcIgnitionScene, clock: Float, alpha: Float, content: Float): Boolean {
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return true
            val begin = System.nanoTime()
            drawLock.lock()
            return try {
                val surface = holder.surface
                if (quitting || !surface.isValid) return true
                val canvas = surface.lockHardwareCanvas()
                try {
                    // The canvas has the buffer's real size, also on the frame right after the buffer was made smaller.
                    s.draw(canvas, canvas.width, canvas.height, clock, alpha, content, handoff)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                if (!drawn) {
                    drawn = true
                    stage = ArcIntroStage.Drawn
                    StartupLatencyTrace.cold("intro_first_frame", palette.key)
                    runCatching(onFirstFrame).onFailure { Log.w(Tag, "first frame callback failed", it) }
                }
                val spent = System.nanoTime() - begin
                drawNanos += spent
                quality.onDraw(spent / 1_000_000f)
                true
            } catch (t: Throwable) {
                Log.w(Tag, "draw failed", t)
                false
            } finally {
                drawLock.unlock()
            }
        }

        private fun track(intervalMs: Float, frameTimeNanos: Long, s: ArcIgnitionScene) {
            frames++
            if (intervalMs > 25f) drops++
            worstMs = max(worstMs, intervalMs)
            when (quality.onFrame(intervalMs, frameTimeNanos)) {
                ArcQualitySteps.Step.LessDetail -> {
                    s.detail = quality.detail
                    Log.i(Tag, "detail ${s.detail} after $frames frames, drawAvg=${quality.drawAverageMs.roundToInt()} ms")
                }
                ArcQualitySteps.Step.None -> Unit
            }
        }

        private fun finishExit() {
            running = false
            logSummary("finished")
            deliverExit()
        }

        private fun fail(reason: String) {
            running = false
            stage = ArcIntroStage.Failed
            main.post { onFailed(reason) }
            deliverExit()
        }

        private fun deliverExit() {
            val done = exitDone ?: return
            if (exitDelivered) return
            exitDelivered = true
            main.post { done() }
        }

        private fun logSummary(reason: String) {
            if (frames == 0) return
            Log.i(Tag, "$reason: frames=$frames late=$drops worst=${worstMs.roundToInt()} ms drawAvg=${quality.drawAverageMs.roundToInt()} ms " +
                "draw=${drawNanos / max(1, frames) / 1000} us/frame detail=${scene?.detail} buffer=${surfaceWidth}x$surfaceHeight")
            frames = 0
        }
    }

    companion object {
        private const val Tag = "ArcIntro"
        /** Reduced motion shows the settled mark without the breathing. */
        private const val ReducedMotionClock = 5.4f

        /** A 1080p buffer keeps fill cost flat on 4K interfaces (the display scaler does the rest); light boxes use 720p. */
        private fun bufferCapFor(width: Int, height: Int, reduced: Boolean): Float =
            min(1f, min(1920f / width, 1080f / height)) * if (reduced) 2f / 3f else 1f

        /** Device pixels per stage unit in the buffer, so the scene can size its art to land pixel for pixel. */
        internal fun bufferScaleFor(width: Int, height: Int, reduced: Boolean): Float {
            if (width <= 0 || height <= 0) return if (reduced) 2f / 3f else 1f
            val cap = bufferCapFor(width, height, reduced)
            return min((width * cap).roundToInt() / 1920f, (height * cap).roundToInt() / 1080f)
        }
    }
}
