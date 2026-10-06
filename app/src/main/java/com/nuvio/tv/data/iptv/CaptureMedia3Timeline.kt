package com.nuvio.tv.data.iptv

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.iptv.CaptureSampleTimeline
import com.nuvio.tv.core.iptv.CaptureTransportState
import java.io.IOException

@UnstableApi
internal class CaptureMedia3TimelineFactory(private val source: CaptureSampleTimeline) {
    private val uids = mutableMapOf<Long, Any>()
    private var lastEpoch: Long? = null

    @Synchronized fun snapshot(batches: Collection<CapturedSampleBatch>, producer: CaptureTransportState): Timeline {
        require(batches.size <= 4096) { "Staged batch collection limit" }
        val retained = source.snapshot()
        retained.latestEpoch?.let { lastEpoch = it }
        val currentEpochs = retained.windows.map { it.epoch }.toSet() + listOfNotNull(lastEpoch)
        uids.keys.retainAll(currentEpochs)

        val eligible = retained.windows.mapNotNull { window -> batches.lastOrNull { it.window === window } }
        val epochs = eligible.groupBy { it.window.epoch }.map { (epoch, rows) ->
            rows.zipWithNext().forEach { (a,b) ->
                if (b.window.proof.segment.sequence != a.window.proof.segment.sequence + 1 ||
                    b.window.start90k != a.window.endExclusive90k) throw IOException("Unstaged capture gap cannot be published")
            }
            val first = rows.first().window; val last = rows.last().window
            val start = ticksToUs(first.start90k)
            val end = ticksToUs(maxOf(last.endExclusive90k, last.audioEnd90k))
            val default = ticksToUs(last.lastVideo90k) - start
            val dynamic = epoch == retained.latestEpoch && producer in setOf(CaptureTransportState.NEW, CaptureTransportState.RUNNING)
            Epoch(uids.getOrPut(epoch) { Any() }, epoch, start, end, default, dynamic)
        }
        return StagedTimeline(epochs)
    }

    private data class Epoch(val uid: Any, val epoch: Long, val startUs: Long, val endUs: Long,
        val defaultUs: Long, val dynamic: Boolean)

    private class StagedTimeline(private val epochs: List<Epoch>) : Timeline() {
        override fun getWindowCount() = epochs.size
        override fun getPeriodCount() = epochs.size
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
            val e = at(windowIndex)

            return window.set(e.uid, MediaItem.EMPTY, null, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET,
                true, e.dynamic, null, e.defaultUs, e.endUs - e.startUs, windowIndex, windowIndex, e.startUs)
        }
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
            val e = at(periodIndex)
            return period.set(if (setIds) e.epoch else null, if (setIds) e.uid else null,
                periodIndex, e.endUs, -e.startUs)
        }
        override fun getIndexOfPeriod(uid: Any) = epochs.indexOfFirst { it.uid === uid }
        override fun getUidOfPeriod(periodIndex: Int): Any = at(periodIndex).uid
        override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int { at(windowIndex); return C.INDEX_UNSET }
        override fun getPreviousWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int { at(windowIndex); return C.INDEX_UNSET }
        private fun at(index: Int): Epoch {
            if (index !in epochs.indices) throw IndexOutOfBoundsException()
            return epochs[index]
        }
    }

    private fun ticksToUs(ticks: Long) = Math.addExact(Math.multiplyExact(ticks / 90_000, 1_000_000), (ticks % 90_000) * 1_000_000 / 90_000)
}
