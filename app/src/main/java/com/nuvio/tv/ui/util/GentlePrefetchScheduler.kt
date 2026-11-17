package com.nuvio.tv.ui.util

import android.annotation.SuppressLint
import android.view.Choreographer
import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.layout.PrefetchRequest
import androidx.compose.foundation.lazy.layout.PrefetchRequestScope
import androidx.compose.foundation.lazy.layout.PrefetchScheduler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import java.util.concurrent.TimeUnit

/** Time a still screen gives built-ahead work before a key press or a frame can get in. */
internal const val PREFETCH_IDLE_SLICE_NS = 6_000_000L

/** After this many slices in a row too short for the next step, the step runs without a limit. */
internal const val PREFETCH_MAX_STALLS = 3

internal enum class PrefetchNext { Done, NextFrame, Soon }

/**
 * The queue behind [GentlePrefetchScheduler], kept free of Android so its time rules can be tested.
 *
 * While frames are being drawn a request may use the time left until the next frame, as in Compose's own
 * scheduler. Once nothing has been drawn for two frames, Compose's scheduler lets the next request run with no
 * limit, so a whole row and all its posters are built in one go and a press arriving meanwhile waits for it.
 * Here a still screen runs the work in slices, one straight after the other, so a press waits at most one slice.
 * A request that hands back at once had a step bigger than its slice; it gets a bigger one for the rest of its
 * work, so nothing is left waiting for good.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class PrefetchQueue<T : Any>(
    private val clock: () -> Long,
    private val runOne: (T, PrefetchRequestScope) -> Boolean
) {
    private val high = ArrayDeque<T>()
    private val low = ArrayDeque<T>()
    private var deadline = 0L
    private var grownFor: T? = null
    var stalls = 0
        private set

    val scope = object : PrefetchRequestScope {
        override fun availableTimeNanos(): Long = (deadline - clock()).coerceAtLeast(0L)
    }

    val isEmpty: Boolean get() = high.isEmpty() && low.isEmpty()

    fun addHigh(request: T) = high.addLast(request)

    fun addLow(request: T) = low.addLast(request)

    private fun head(): T? = high.firstOrNull() ?: low.firstOrNull()

    /** Runs requests until the time is used up and says when to come back. */
    fun run(lastDrawNs: Long, frameStartNs: Long, frameIntervalNs: Long): PrefetchNext {
        val now = clock()
        val idle = now > lastDrawNs + 2 * frameIntervalNs
        val first = head() ?: return PrefetchNext.Done
        if (!idle || first !== grownFor) {
            stalls = 0
            grownFor = first
        }
        deadline = when {
            !idle -> maxOf(frameStartNs, lastDrawNs) + frameIntervalNs
            stalls >= PREFETCH_MAX_STALLS -> Long.MAX_VALUE
            else -> now + (PREFETCH_IDLE_SLICE_NS shl stalls)
        }
        val later = if (idle) PrefetchNext.Soon else PrefetchNext.NextFrame
        while (true) {
            val request = head() ?: return PrefetchNext.Done
            if (scope.availableTimeNanos() <= 0L) return later
            val start = clock()
            if (!runOne(request, scope)) {
                if (!high.remove(request)) low.remove(request)
                continue
            }
            val quick = clock() - start < PREFETCH_IDLE_SLICE_NS / 4
            if (idle && request === grownFor && quick) {
                // No progress even without a limit: try again next frame rather than spin.
                if (stalls >= PREFETCH_MAX_STALLS) return PrefetchNext.NextFrame
                stalls++
            }
            return later
        }
    }
}

/**
 * Compose's built-ahead work for every list on [view], run in slices by [PrefetchQueue]. Same order and the same
 * time per frame as Compose's own scheduler while the screen moves; only a still screen differs.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class GentlePrefetchScheduler(private val view: View) :
    PrefetchScheduler, Runnable, Choreographer.FrameCallback, View.OnAttachStateChangeListener {

    private val queue = PrefetchQueue<PrefetchRequest>(System::nanoTime) { request, scope -> with(request) { scope.execute() } }
    private val choreographer = Choreographer.getInstance()
    private var scheduled = false
    private var active = view.isAttachedToWindow
    private var frameStartNs = 0L
    private val frameIntervalNs: Long by lazy {
        val rate = view.display?.refreshRate ?: 60f
        (1_000_000_000L / (if (rate >= 30f) rate else 60f)).toLong()
    }

    init {
        view.addOnAttachStateChangeListener(this)
    }

    // Compose's priority form of this interface is not open to apps, so every request comes in here, in order.
    override fun schedulePrefetch(prefetchRequest: PrefetchRequest) {
        queue.addHigh(prefetchRequest)
        start()
    }

    private fun start() {
        if (!scheduled) {
            scheduled = true
            view.post(this)
        }
    }

    override fun run() {
        if (queue.isEmpty || !scheduled || !active || view.windowVisibility != View.VISIBLE) {
            scheduled = false
            return
        }
        val lastDrawNs = TimeUnit.MILLISECONDS.toNanos(view.drawingTime)
        when (queue.run(lastDrawNs, frameStartNs, frameIntervalNs)) {
            PrefetchNext.Done -> scheduled = false
            PrefetchNext.NextFrame -> choreographer.postFrameCallback(this)
            // Posted behind any key press already waiting; a frame due meanwhile goes first anyway.
            PrefetchNext.Soon -> view.post(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (active) {
            frameStartNs = frameTimeNanos
            view.post(this)
        }
    }

    override fun onViewAttachedToWindow(v: View) {
        active = true
    }

    override fun onViewDetachedFromWindow(v: View) {
        active = false
        scheduled = false
        view.removeCallbacks(this)
        choreographer.removeFrameCallback(this)
    }
}

/**
 * Puts [GentlePrefetchScheduler] in the place Compose looks for its scheduler on this view. Call it before the
 * first lazy list is composed; lists composed earlier keep the scheduler they found. If a later Compose stops
 * reading it from there, the lists simply use Compose's own scheduler again.
 */
@SuppressLint("PrivateResource") // Compose's own key for its scheduler
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InstallGentlePrefetchScheduler() {
    val view = LocalView.current
    remember(view) {
        val id = androidx.compose.foundation.R.id.compose_prefetch_scheduler
        if (view.getTag(id) == null) view.setTag(id, GentlePrefetchScheduler(view))
    }
}
