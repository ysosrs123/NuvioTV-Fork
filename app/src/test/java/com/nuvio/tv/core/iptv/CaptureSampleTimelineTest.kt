package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.nuvio.tv.core.iptv.RetainedCaptureFixtures.add

class CaptureSampleTimelineTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun actualSamplesDeterminePositionsWhileAudioKeepsItsIndependentPhase() {
        CaptureSegmentStore(temp.newFolder(), 1048576, 524288).use { store ->
            val index = CaptureTsInspectionIndex(store); val timeline = CaptureSampleTimeline(index)
            for (n in 0..2) { store.add(n, n * 1000L, (n + 1) * 1000L); timeline.accept(index.inspect(n.toLong())) }
            val rows = timeline.snapshot().windows
            assertEquals(listOf(0L, 180000L, 360000L), rows.map { it.start90k })
            assertEquals(listOf(180000L, 360000L, 540000L), rows.map { it.endExclusive90k })
            assertEquals(listOf(-1920L, 180480L, 360960L), rows.map { it.audioStart90k })
            assertEquals(listOf(180480L, 360960L, 541440L), rows.map { it.audioEnd90k })
            assertTrue(rows.all { it.epoch == 0L }); assertEquals(CaptureEpochStart.INITIAL, rows.first().epochStart)
            assertNull(rows[1].epochStart)
        }
    }

    @Test fun evictionNeverRebasesPositionsAndExpiredWindowsCannotOpenAnotherFile() {
        CaptureSegmentStore(temp.newFolder(), 400000, 210000).use { store ->
            val index = CaptureTsInspectionIndex(store); val timeline = CaptureSampleTimeline(index)
            store.add(0); val first = timeline.accept(index.inspect(0))
            store.add(1); timeline.accept(index.inspect(1)); store.add(2); timeline.accept(index.inspect(2))
            assertEquals(listOf(180000L, 360000L), timeline.snapshot().windows.map { it.start90k })
            try { timeline.open(first); fail() } catch (_: CaptureMediaExpired) { }
            timeline.open(timeline.snapshot().windows.first()).use { assertEquals(1L, it.segment.sequence) }
        }
    }

    @Test fun acceptedTailSurvivesEvenWhenAllPublishedRowsAreEvictedBeforeNextInspection() {
        CaptureSegmentStore(temp.newFolder(), 210000, 210000).use { store ->
            val index = CaptureTsInspectionIndex(store); val timeline = CaptureSampleTimeline(index)
            store.add(0); timeline.accept(index.inspect(0)); store.add(1)
            assertTrue(timeline.snapshot().windows.isEmpty())
            val next = timeline.accept(index.inspect(1)); assertEquals(0L, next.epoch); assertEquals(180000L, next.start90k)
        }
    }

    @Test fun ptsWrapAcrossAndWithinSegmentsUsesTheSameStableEpoch() {
        CaptureSegmentStore(temp.newFolder(), 1048576, 524288).use { store ->
            val index = CaptureTsInspectionIndex(store); val timeline = CaptureSampleTimeline(index)
            val shift = (1L shl 33) - 127920 - 90000
            for (n in 0..2) {
                store.add(n, media = RetainedCaptureFixtures.transformPts(RetainedCaptureFixtures.bytes(n)) { it + shift })
                timeline.accept(index.inspect(n.toLong()))
            }
            val rows = timeline.snapshot().windows
            assertTrue(rows.first().proof.inspection.videoLastPts90k >= (1L shl 33))
            assertEquals(90000L, rows[1].proof.inspection.videoFirstPts90k)
            assertEquals(listOf(0L, 180000L, 360000L), rows.map { it.start90k }); assertTrue(rows.all { it.epoch == 0L })
            assertEquals(-1920L, rows.first().audioStart90k)
        }
    }

    @Test fun captureGapAndDeclaredDiscontinuityStartExplicitEpochsDespiteAdjacentPts() {
        for (gap in listOf(false, true)) CaptureSegmentStore(temp.newFolder(), 1048576, 524288).use { store ->
            val index = CaptureTsInspectionIndex(store); val timeline = CaptureSampleTimeline(index)
            store.add(0); timeline.accept(index.inspect(0))
            store.add(1, start = if (gap) 2500 else 2000, continuity = if (gap) 0 else 1)
            val next = timeline.accept(index.inspect(1))
            assertEquals(1L, next.epoch); assertEquals(0L, next.start90k); assertEquals(CaptureEpochStart.CAPTURE_DISCONTINUITY, next.epochStart)
            assertEquals(listOf(0L, 1L), timeline.snapshot().windows.map { it.epoch })
        }
    }

    @Test fun missingInspectionAndChangedInitializationCannotBeJoined() {
        CaptureSegmentStore(temp.newFolder(), 1048576, 524288).use { store ->
            val index = CaptureTsInspectionIndex(store); val timeline = CaptureSampleTimeline(index)
            store.add(0); timeline.accept(index.inspect(0)); store.add(1); store.add(2)
            assertEquals(CaptureEpochStart.SEQUENCE_GAP, timeline.accept(index.inspect(2)).epochStart)
        }
        CaptureSegmentStore(temp.newFolder(), 1048576, 524288).use { store ->
            val index = CaptureTsInspectionIndex(store); val timeline = CaptureSampleTimeline(index)
            store.add(0); timeline.accept(index.inspect(0))
            store.add(1, media = RetainedCaptureFixtures.changedInitialization(RetainedCaptureFixtures.bytes(1)))
            assertEquals(CaptureEpochStart.INITIALIZATION_CHANGE, timeline.accept(index.inspect(1)).epochStart)
        }
    }

    @Test fun realVideoOrAudioTimestampGapsStartNewEpochsWithoutManifestInference() {
        for (pid in listOf(256, 257)) CaptureSegmentStore(temp.newFolder(), 1048576, 524288).use { store ->
            val index = CaptureTsInspectionIndex(store); val timeline = CaptureSampleTimeline(index)
            store.add(0); timeline.accept(index.inspect(0))
            store.add(1, media = RetainedCaptureFixtures.transformPts(RetainedCaptureFixtures.bytes(1), pid) { it + 1920 })
            val next = timeline.accept(index.inspect(1))
            assertEquals(CaptureEpochStart.TIMESTAMP_CHANGE, next.epochStart); assertEquals(1L, next.epoch)
        }
    }

    @Test fun cadenceChangeStartsAnEpochAndBudgetPruningKeepsPositionsStable() {
        CaptureSegmentStore(temp.newFolder(), 1048576, 524288).use { store ->
            val index = CaptureTsInspectionIndex(store); val timeline = CaptureSampleTimeline(index, maxWindows = 1)
            store.add(0); val first = timeline.accept(index.inspect(0))
            store.add(1); val second = timeline.accept(index.inspect(1)); assertEquals(180000L, second.start90k)
            assertEquals(1, timeline.snapshot().windows.size)
            try { timeline.accept(first.proof); fail() } catch (_: IllegalArgumentException) { }
            val bytes = RetainedCaptureFixtures.transformPts(RetainedCaptureFixtures.bytes(2), 256) { 487920 + (it - 487920) * 6 / 5 }
            store.add(2, media = bytes)
            assertEquals(CaptureEpochStart.TIMESTAMP_CHANGE, timeline.accept(index.inspect(2)).epochStart)
        }
    }

    @Test fun defaultWindowLimitKeepsEveryRetainedSegmentOpenable() {
        CaptureSegmentStore(temp.newFolder(), 60_000_000, 210000).use { store ->
            val index = CaptureTsInspectionIndex(store); val timeline = CaptureSampleTimeline(index)
            val media = RetainedCaptureFixtures.bytes(0)
            for (n in 0..256) { store.add(0, n * 2000L, media = media); timeline.accept(index.inspect(n.toLong())) }
            val rows = timeline.snapshot().windows
            assertEquals(257, rows.size)
            timeline.open(rows.first()).use { assertEquals(0L, it.segment.sequence) }
        }
    }

    @Test fun duplicateEvidenceIsIdempotentAndForeignOwnershipIsRejected() {
        CaptureSegmentStore(temp.newFolder(), 1048576, 524288).use { store ->
            store.add(0); val index = CaptureTsInspectionIndex(store); val timeline = CaptureSampleTimeline(index)
            val proof = index.inspect(0); val row = timeline.accept(proof); val revision = timeline.snapshot().revision
            assertSame(row, timeline.accept(proof)); assertEquals(revision, timeline.snapshot().revision)
            try { timeline.accept(CaptureTsInspectionIndex(store).inspect(0)); fail() } catch (_: IllegalArgumentException) { }
        }
    }
}
