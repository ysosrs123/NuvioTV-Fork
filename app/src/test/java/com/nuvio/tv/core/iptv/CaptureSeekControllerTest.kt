package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.nuvio.tv.core.iptv.RetainedCaptureFixtures.add

class CaptureSeekControllerTest {
    @get:Rule val temp = TemporaryFolder()
    private fun ready(preview: CaptureSeekPreview): CaptureSeekRequest { assertEquals(CaptureSeekState.READY, preview.state); return requireNotNull(preview.request) }

    @Test fun bothDirectionsUseThePendingAnchorUntilThatExactSeekIsAcknowledged() {
        CaptureSegmentStore(temp.newFolder(), 1048576, 524288).use { store ->
            val index = CaptureTsInspectionIndex(store); val timeline = CaptureSampleTimeline(index)
            for (n in 0..2) { store.add(n); timeline.accept(index.inspect(n.toLong())) }
            val seeks = CaptureSeekController(timeline); val current = CapturePlaybackPosition(0, 360000)
            val left = ready(seeks.move(current, -90000)); assertEquals(270000L, left.position90k)
            assertFalse(seeks.isCurrentCommitted(left))
            val first = requireNotNull(seeks.commit(left).input)
            assertTrue(seeks.isCurrentCommitted(left))
            val right = ready(seeks.move(current, 45000)); assertEquals(315000L, right.position90k)
            assertFalse(seeks.isCurrentCommitted(left)); assertFalse(seeks.acknowledge(left)); assertEquals(CaptureSeekState.STALE, seeks.commit(left).state)
            val second = requireNotNull(seeks.commit(right).input)
            assertTrue(seeks.isCurrentCommitted(right)); assertTrue(seeks.acknowledge(right)); assertFalse(seeks.isCurrentCommitted(right)); assertFalse(seeks.acknowledge(right))
            first.close(); second.close()
            assertEquals(405000L, ready(seeks.move(current, 45000)).position90k)
        }
    }

    @Test fun previewMayClampButCommitOfAnEvictedTargetReportsExpiryWithoutJumping() {
        CaptureSegmentStore(temp.newFolder(), 400000, 210000).use { store ->
            val index = CaptureTsInspectionIndex(store); val timeline = CaptureSampleTimeline(index)
            store.add(0); timeline.accept(index.inspect(0)); store.add(1); timeline.accept(index.inspect(1))
            val seeks = CaptureSeekController(timeline); val request = ready(seeks.begin(0, -90000))
            assertTrue(request.clamped); assertEquals(0L, request.position90k)
            store.add(2); timeline.accept(index.inspect(2))
            assertEquals(CaptureSeekState.EXPIRED, seeks.commit(request).state)
            assertEquals(CaptureSeekState.EXPIRED, seeks.move(CapturePlaybackPosition(0, 360000), 90000).state)
            assertEquals(listOf(1L, 2L), store.snapshot().map { it.sequence })
            val explicit = ready(seeks.returnToCapturedTail()); assertTrue(explicit.returnToTail)
            assertEquals(536400L, explicit.position90k); assertEquals(2L, explicit.window.proof.segment.sequence)
            requireNotNull(seeks.commit(explicit).input).use { assertEquals(360000L, it.decodeStart90k) }
        }
    }

    @Test fun committedSeekPinsItsDecodeStartAndAlignsTheRequestedPositionToARealSample() {
        CaptureSegmentStore(temp.newFolder(), 210000, 210000).use { store ->
            store.add(0); val index = CaptureTsInspectionIndex(store); val timeline = CaptureSampleTimeline(index)
            timeline.accept(index.inspect(0)); val seeks = CaptureSeekController(timeline)
            val request = ready(seeks.begin(0, 10000)); val result = seeks.commit(request)
            assertEquals(CaptureSeekState.READY, result.state)
            val input = requireNotNull(result.input); assertEquals(0L, input.decodeStart90k); assertEquals(7200L, input.samplePosition90k)
            assertEquals(10000L, input.request.position90k)
            input.media.readBytes(); assertTrue(input.media.verified)
            try { store.add(1); fail() } catch (_: CaptureRetentionBlocked) { }
            assertEquals(CaptureSeekState.STALE, seeks.commit(request).state)
            input.close(); store.add(1)
        }
    }

    @Test fun foreignSupersededCancelledAndUncommittedAcknowledgementsCannotAct() {
        CaptureSegmentStore(temp.newFolder(), 1048576, 524288).use { store ->
            store.add(0); val index = CaptureTsInspectionIndex(store); val timeline = CaptureSampleTimeline(index)
            timeline.accept(index.inspect(0)); val seeks = CaptureSeekController(timeline)
            val first = ready(seeks.begin(0, 0)); assertFalse(seeks.acknowledge(first))
            val second = ready(seeks.begin(0, 3600)); assertEquals(CaptureSeekState.STALE, seeks.commit(first).state)
            assertEquals(CaptureSeekState.STALE, CaptureSeekController(timeline).commit(second).state)
            seeks.cancelPending(); assertEquals(CaptureSeekState.STALE, seeks.commit(second).state)
        }
    }

    @Test fun returnToTailExplicitlySelectsTheNewestEpochAndEmptyTimelineIsUnavailable() {
        CaptureSegmentStore(temp.newFolder(), 1048576, 524288).use { store ->
            val index = CaptureTsInspectionIndex(store); val timeline = CaptureSampleTimeline(index); val seeks = CaptureSeekController(timeline)
            assertEquals(CaptureSeekState.UNAVAILABLE, seeks.returnToCapturedTail().state)
            store.add(0); timeline.accept(index.inspect(0)); store.add(1, continuity = 1); timeline.accept(index.inspect(1))
            val old = ready(seeks.begin(0, 90000)); val tail = ready(seeks.returnToCapturedTail())
            assertEquals(1L, tail.window.epoch); assertEquals(176400L, tail.position90k)
            assertEquals(CaptureSeekState.STALE, seeks.commit(old).state)
            requireNotNull(seeks.commit(tail).input).use { assertEquals(1L, it.media.segment.sequence) }
        }
    }

    @Test fun publishedWindowBudgetExpiryIsReportedEvenIfTheFileStillExists() {
        CaptureSegmentStore(temp.newFolder(), 1048576, 524288).use { store ->
            val index = CaptureTsInspectionIndex(store); val timeline = CaptureSampleTimeline(index, maxWindows = 1)
            store.add(0); timeline.accept(index.inspect(0)); val seeks = CaptureSeekController(timeline); val request = ready(seeks.begin(0, 90000))
            store.add(1); timeline.accept(index.inspect(1))
            assertEquals(2, store.snapshot().size); assertEquals(CaptureSeekState.EXPIRED, seeks.commit(request).state)
        }
    }
}
