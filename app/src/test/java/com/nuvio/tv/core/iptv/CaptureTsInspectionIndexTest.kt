package com.nuvio.tv.core.iptv

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.nuvio.tv.core.iptv.RetainedCaptureFixtures.add

internal object RetainedCaptureFixtures {
    fun bytes(n: Int = 0): ByteArray = requireNotNull(javaClass.getResourceAsStream("/iptv-ts/segment0$n.ts")).use { it.readBytes() }
    fun CaptureSegmentStore.add(n: Int, start: Long = n * 2000L, end: Long = start + 2000,
        continuity: Long = 0, media: ByteArray = bytes(n)) = append(start, end, continuity, media.inputStream())
    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 255
    fun transformPts(bytes: ByteArray, onlyPid: Int? = null, transform: (Long) -> Long): ByteArray = bytes.copyOf().also { b ->
        fun change(at: Int) {
            val old = ((u(b, at).toLong() and 14) shl 29) or (u(b, at + 1).toLong() shl 22) or
                ((u(b, at + 2).toLong() and 254) shl 14) or (u(b, at + 3).toLong() shl 7) or (u(b, at + 4).toLong() shr 1)
            val next = transform(old) and ((1L shl 33) - 1)
            b[at] = ((u(b, at) and 0xf1) or (((next shr 30).toInt() and 7) shl 1)).toByte()
            b[at + 1] = (next shr 22).toByte(); b[at + 2] = (((next shr 14).toInt() and 254) or 1).toByte()
            b[at + 3] = (next shr 7).toByte(); b[at + 4] = (((next shl 1).toInt() and 254) or 1).toByte()
        }
        for (at in b.indices step 188) {
            val pid = (u(b, at + 1) and 31) * 256 + u(b, at + 2)
            if (pid !in 256..257 || (onlyPid != null && pid != onlyPid) || u(b, at + 1) and 0x40 == 0) continue
            val payload = at + 4 + if (u(b, at + 3) and 32 != 0) 1 + u(b, at + 4) else 0
            val flags = u(b, payload + 7)
            check(flags == 0x80 || flags == 0xc0)
            change(payload + 9); if (flags == 0xc0) change(payload + 14)
        }
    }
    fun changedInitialization(bytes: ByteArray): ByteArray = bytes.copyOf().also { b ->
        for (i in 2 until b.size - 4) if (b[i - 2] == 0.toByte() && b[i - 1] == 0.toByte() &&
            b[i] == 1.toByte() && u(b, i + 1) == 0x67 && u(b, i + 2) == 66) b[i + 4] = (u(b, i + 4) xor 1).toByte()
    }
}

class CaptureTsInspectionIndexTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun evidenceUsesCommittedBytesAndOpenPinsUntilExplicitCloseEvenAtVerifiedEof() {
        CaptureSegmentStore(temp.newFolder(), 210000, 210000).use { store ->
            store.add(0); val index = CaptureTsInspectionIndex(store); val proof = index.inspect(0)
            assertEquals(50, proof.inspection.videoFrames)
            val input = index.open(proof)
            assertFalse(input.verified); assertArrayEquals(RetainedCaptureFixtures.bytes(), input.readBytes()); assertTrue(input.verified)
            try { store.add(1); fail() } catch (_: CaptureRetentionBlocked) { }
            try { store.close(); fail() } catch (_: IllegalStateException) { }
            input.close(); input.close(); store.add(1)
            assertEquals(0, index.retainedInspections().size)
            try { index.open(proof); fail() } catch (_: CaptureMediaExpired) { }
        }
    }

    @Test fun foreignEvidenceAndReopenedStoreCannotReuseOldOwnership() {
        val directory = temp.newFolder()
        val store = CaptureSegmentStore(directory, 1048576, 524288); store.add(0)
        val index = CaptureTsInspectionIndex(store); val proof = index.inspect(0)
        val other = CaptureTsInspectionIndex(store)
        try { other.open(proof); fail() } catch (_: IllegalArgumentException) { }
        store.close()
        CaptureSegmentStore(directory, 1048576, 524288).use { reopened ->
            val fresh = CaptureTsInspectionIndex(reopened)
            try { fresh.open(proof); fail() } catch (_: IllegalArgumentException) { }
            fresh.open(fresh.inspect(0)).use { assertArrayEquals(RetainedCaptureFixtures.bytes(), it.readBytes()) }
        }
    }

    @Test fun cancellationAndMalformedInputNeverCacheEvidenceOrLeakPins() {
        CaptureSegmentStore(temp.newFolder(), 210000, 210000).use { store ->
            store.add(0); val index = CaptureTsInspectionIndex(store); var checks = 0
            try { index.inspect(0) { if (++checks == 5) throw InterruptedException() }; fail() } catch (_: InterruptedException) { }
            assertTrue(index.retainedInspections().isEmpty())
            val bad = RetainedCaptureFixtures.bytes(1).dropLast(1).toByteArray()
            store.append(2000, 4000, 0, bad.inputStream())
            try { index.inspect(1); fail() } catch (_: TsCaptureInspectionException) { }
            assertTrue(index.retainedInspections().isEmpty()); store.add(2)
        }
    }

    @Test fun cacheBudgetIsBoundedAndDoesNotPinUnopenedEvidence() {
        CaptureSegmentStore(temp.newFolder(), 1048576, 524288).use { store ->
            store.add(0); store.add(1)
            val index = CaptureTsInspectionIndex(store, maxEntries = 1); val first = index.inspect(0)
            assertSame(first, index.inspect(0)); index.inspect(1)
            assertEquals(listOf(1L), index.retainedInspections().map { it.segment.sequence })
            assertNotSame(first, index.inspect(0))
            index.open(first).use { assertEquals(first.inspection.bytes, it.skip(Long.MAX_VALUE)); assertTrue(it.verified) }
        }
    }

    @Test fun skippedAndReplacedBytesMustStillMatchTheInspectedHash() {
        val directory = temp.newFolder()
        CaptureSegmentStore(directory, 1048576, 524288).use { store ->
            store.add(0); val index = CaptureTsInspectionIndex(store); val proof = index.inspect(0)
            index.open(proof).use { assertEquals(proof.inspection.bytes, it.skip(proof.inspection.bytes)); assertFalse(it.verified); assertEquals(-1, it.read()); assertTrue(it.verified) }
            val changed = RetainedCaptureFixtures.bytes().also { it[100] = (it[100].toInt() xor 1).toByte() }
            File(directory, proof.segment.fileName).writeBytes(changed)
            index.open(proof).use { input ->
                try { input.skip(Long.MAX_VALUE); fail() } catch (_: IOException) { }
                assertFalse(input.verified)
            }
        }
    }

    @Test fun slowInspectionKeepsStoreSnapshotsAndIndependentReadersAvailable() {
        CaptureSegmentStore(temp.newFolder(), 1048576, 524288).use { store ->
            store.add(0); val index = CaptureTsInspectionIndex(store)
            val entered = CountDownLatch(1); val resume = CountDownLatch(1); val checks = AtomicInteger()
            val pool = Executors.newFixedThreadPool(2)
            try {
                val inspection = pool.submit<InspectedCaptureSegment> { index.inspect(0) {
                    if (checks.incrementAndGet() == 3) { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
                } }
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                pool.submit {
                    assertEquals(1, store.snapshot().size)
                    store.open(0).use { assertEquals(0x47, it.read()) }; store.add(1)
                }.get(3, TimeUnit.SECONDS)
                resume.countDown(); assertEquals(50, inspection.get(3, TimeUnit.SECONDS).inspection.videoFrames)
            } finally { resume.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
        }
    }
}
