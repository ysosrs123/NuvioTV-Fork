package com.nuvio.tv.data.iptv

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.iptv.*
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.nuvio.tv.core.iptv.RetainedCaptureFixtures.add

@UnstableApi
class LocalCaptureSampleStagerAndroidTest {
    @get:Rule val temp = TemporaryFolder()
    private val normal = CaptureSampleStagingLimits(2L * 1024 * 1024)

    @Test fun realShippedExtractionReturnsEverySampleOnlyAfterVerifiedEofAndUsesStablePositions() {
        CaptureSegmentStore(temp.newFolder(), 1048576, 524288).use { store ->
            val index = CaptureTsInspectionIndex(store); val timeline = CaptureSampleTimeline(index)
            for (n in 0..2) {
                store.add(n); val window = timeline.accept(index.inspect(n.toLong()))
                index.open(window.proof).use { input ->
                    val batch = LocalCaptureSampleStager(normal).stage(window, input)
                    assertTrue(input.verified); assertEquals(50, batch.video.samples.size)
                    assertEquals(listOf(95,94,94)[n], batch.audio.samples.size)
                    assertEquals(320, batch.video.format.width); assertEquals(180, batch.video.format.height)
                    assertEquals(48000, batch.audio.format.sampleRate); assertEquals(2, batch.audio.format.channelCount)
                    assertEquals(n * 2000000L, batch.video.samples.first().timeUs)
                    assertEquals(n * 2000000L + 1960000, batch.video.samples.last().timeUs)
                    assertEquals(listOf(-21333L,2005333L,4010667L)[n], batch.audio.samples.first().timeUs)
                    assertTrue(batch.video.samples.first().flags and C.BUFFER_FLAG_KEY_FRAME != 0)
                    assertTrue(batch.chargedBytes in 1..normal.maxBatchBytes)
                    val first = batch.video.samples.first(); val bytes = ByteBuffer.allocate(first.size)
                    first.copyTo(bytes); val original = bytes.array().copyOf(); bytes.array().fill(0)
                    val again = ByteBuffer.allocate(first.size); first.copyTo(again); assertArrayEquals(original, again.array())
                    try { (batch.video.samples as MutableList).clear(); fail() } catch (_: UnsupportedOperationException) { }
                }
            }
        }
    }

    @Test fun bothFirstArrivingTrackOrdersAtPtsWrapKeepOneClockAndNegativeAudioPhase() {
        val shifted = RetainedCaptureFixtures.transformPts(RetainedCaptureFixtures.bytes()) { it - 127920 }
        val packets = (shifted.indices step 188).map { shifted.copyOfRange(it,it+188) }
        fun pid(b: ByteArray) = (b[1].toInt() and 31) * 256 + (b[2].toInt() and 255)
        val audioFirst = (packets.filter { pid(it) !in 256..257 } + packets.filter { pid(it) == 257 } + packets.filter { pid(it) == 256 })
            .fold(java.io.ByteArrayOutputStream()) { out,p -> out.apply { write(p) } }.toByteArray()
        for (media in listOf(shifted,audioFirst)) CaptureSegmentStore(temp.newFolder(),1048576,524288).use { store ->
            store.add(0,media=media); val index=CaptureTsInspectionIndex(store); val window=CaptureSampleTimeline(index).accept(index.inspect(0))
            index.open(window.proof).use { input ->
                val batch=LocalCaptureSampleStager(normal).stage(window,input)
                assertEquals(0L,batch.video.samples.first().timeUs); assertEquals(-21333L,batch.audio.samples.first().timeUs)
                assertEquals(50,batch.video.samples.size); assertEquals(95,batch.audio.samples.size)
            }
        }
    }

    @Test fun encodedByteSampleCountInitializationAndDimensionBudgetsRejectWithoutReturningABatch() {
        val limits=listOf(normal.copy(maxBatchBytes=100000,maxSampleBytes=100000), normal.copy(maxSampleBytes=32),
            normal.copy(maxSamplesPerTrack=1),normal.copy(maxInitializationBytes=1),normal.copy(maxVideoPixels=1))
        for (limit in limits) CaptureSegmentStore(temp.newFolder(),1048576,524288).use { store ->
            store.add(0); val index=CaptureTsInspectionIndex(store); val window=CaptureSampleTimeline(index).accept(index.inspect(0))
            index.open(window.proof).use { input ->
                var batch:CapturedSampleBatch?=null
                try { batch=LocalCaptureSampleStager(limit).stage(window,input); fail() } catch (_:IOException) { }
                assertNull(batch)
            }
        }
    }

    @Test fun cancellationRetainsCallerOwnershipUntilExplicitCloseAndNeverPublishesSamples() {
        CaptureSegmentStore(temp.newFolder(),210000,210000).use { store ->
            store.add(0); val index=CaptureTsInspectionIndex(store); val window=CaptureSampleTimeline(index).accept(index.inspect(0))
            val input=index.open(window.proof); var checks=0; var batch:CapturedSampleBatch?=null
            try { batch=LocalCaptureSampleStager(normal).stage(window,input) { if (++checks==7) throw InterruptedException() }; fail() }
            catch (_:InterruptedException) { }
            assertNull(batch)
            try { store.add(1); fail() } catch (_:CaptureRetentionBlocked) { }
            input.close(); store.add(1)
        }
    }

    @Test fun sameLengthExternalReplacementMustFailTheHashFenceBeforePublication() {
        val directory=temp.newFolder()
        CaptureSegmentStore(directory,1048576,524288).use { store ->
            store.add(0); val index=CaptureTsInspectionIndex(store); val window=CaptureSampleTimeline(index).accept(index.inspect(0))
            val changed=RetainedCaptureFixtures.bytes().also { it[100]=(it[100].toInt() xor 1).toByte() }
            File(directory,window.proof.segment.fileName).writeBytes(changed)
            index.open(window.proof).use { input ->
                var batch:CapturedSampleBatch?=null
                try { batch=LocalCaptureSampleStager(normal).stage(window,input); fail() } catch (_:IOException) { }
                assertNull(batch); assertFalse(input.verified)
            }
        }
    }

    @Test fun aDifferentPinnedProofCannotBeUsedForTheWindow() {
        CaptureSegmentStore(temp.newFolder(),1048576,524288).use { store ->
            store.add(0); store.add(1); val index=CaptureTsInspectionIndex(store); val window=CaptureSampleTimeline(index).accept(index.inspect(0))
            index.open(index.inspect(1)).use { input ->
                try { LocalCaptureSampleStager(normal).stage(window,input); fail() } catch (_:IllegalArgumentException) { }
                assertFalse(input.verified)
            }
        }
    }
}
