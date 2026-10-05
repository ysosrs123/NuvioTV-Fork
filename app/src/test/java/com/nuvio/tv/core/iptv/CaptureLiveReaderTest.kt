package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureLiveReaderTest {
    @get:Rule val temp = TemporaryFolder()
    private fun CaptureSegmentStore.add(start: Long, continuity: Long = 0) = append(start, start + 1000, continuity, byteArrayOf(1, 2, 3, 4).inputStream())

    @Test fun temporarilyEmptyTailIsWaitingAndCanReadFuturePublicationsUntilActualCompletion() {
        CaptureSegmentStore(temp.newFolder(), 16, 4).use { store ->
            var state = CaptureTransportState.NEW
            store.openLiveFrom(0) { state }.use { reader ->
                val bytes = ByteArray(16)
                assertEquals(CaptureLiveReadState.WAITING, reader.read(bytes).state)
                state = CaptureTransportState.RUNNING; store.add(0)
                assertEquals(4, reader.read(bytes).bytes)
                assertEquals(CaptureLiveReadState.WAITING, reader.read(bytes).state)
                store.add(1000)
                val next = reader.read(bytes)
                assertEquals(1L, next.segment?.sequence); assertEquals(4, next.bytes)
                assertEquals(CaptureLiveReadState.WAITING, reader.read(bytes).state)
                state = CaptureTransportState.COMPLETE
                assertEquals(CaptureLiveReadState.ENDED, reader.read(bytes).state)
                try { store.close(); fail() } catch (_: IllegalStateException) { }
            }
        }
    }

    @Test fun readingAndWaitingPinRetentionAndAdvancementReleasesOnlyOldSegments() {
        CaptureSegmentStore(temp.newFolder(), 8, 4).use { store ->
            store.add(0); val reader = store.openLiveFrom(0) { CaptureTransportState.RUNNING }
            val b = ByteArray(4)
            reader.read(b); assertEquals(CaptureLiveReadState.WAITING, reader.read(b).state)
            store.add(1000)
            try { store.add(2000); fail() } catch (_: CaptureRetentionBlocked) { }
            assertEquals(1L, reader.read(b).segment?.sequence)
            store.add(2000)
            assertEquals(listOf(1L, 2L), store.snapshot().map { it.sequence })
            reader.close(); reader.close()
            store.add(3000)
        }
    }

    @Test fun lazyReaderReportsEvictionInsteadOfSilentlySkippingToAvailableData() {
        CaptureSegmentStore(temp.newFolder(), 4, 4).use { store ->
            store.add(0)
            store.openLiveFrom(0) { CaptureTransportState.RUNNING }.use { reader ->
                store.add(1000)
                assertEquals(CaptureLiveReadState.EXPIRED, reader.read(ByteArray(4)).state)
            }
        }
    }

    @Test fun gapAndDiscontinuityRequireAnExplicitNewReader() {
        for (gap in listOf(false, true)) CaptureSegmentStore(temp.newFolder(), 12, 4).use { store ->
            store.add(0); store.add(if (gap) 2000 else 1000, if (gap) 0 else 1)
            store.openLiveFrom(0) { CaptureTransportState.RUNNING }.use { reader ->
                val b = ByteArray(4); reader.read(b)
                val result = reader.read(b)
                assertEquals(CaptureLiveReadState.DISCONTINUITY, result.state)
                assertEquals(1L, result.segment?.sequence)
                assertEquals(result, reader.read(b))
            }
        }
    }

    @Test fun failuresBackpressureAndClosureAreNotSuccessfulEofAndCommittedMediaCanDrain() {
        for (state in listOf(CaptureTransportState.FAILED, CaptureTransportState.BACKPRESSURE, CaptureTransportState.CLOSED)) {
            CaptureSegmentStore(temp.newFolder(), 8, 4).use { store ->
                store.add(0)
                store.openLiveFrom(0) { state }.use { reader ->
                    assertEquals(CaptureLiveReadState.DATA, reader.read(ByteArray(4)).state)
                    val result = reader.read(ByteArray(4))
                    assertEquals(CaptureLiveReadState.STOPPED, result.state); assertEquals(state, result.producerState)
                }
            }
        }
    }

    @Test fun producerCompletionSampleCannotHideAPublicationAndInvalidFutureStartsAreRejected() {
        CaptureSegmentStore(temp.newFolder(), 8, 4).use { store ->
            var sampled = false
            store.openLiveFrom(0) {
                if (!sampled) { sampled = true; store.add(0) }
                CaptureTransportState.COMPLETE
            }.use { reader ->
                assertEquals(CaptureLiveReadState.DATA, reader.read(ByteArray(4)).state)
                assertEquals(CaptureLiveReadState.ENDED, reader.read(ByteArray(4)).state)
                try { reader.read(ByteArray(4), Int.MAX_VALUE, 1); fail() } catch (_: IllegalArgumentException) { }
            }
            try { store.openLiveFrom(2) { CaptureTransportState.COMPLETE }; fail() } catch (_: IllegalArgumentException) { }
        }
    }
}
