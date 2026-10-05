package com.nuvio.tv.core.iptv

import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureStoreConcurrencyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test(timeout = 10000) fun slowSourceDoesNotBlockLocalReadersAndPinsAreRecheckedAtPublication() {
        val store = CaptureSegmentStore(temp.newFolder(), 4, 4)
        store.append(0, 1000, 0, byteArrayOf(1, 2, 3, 4).inputStream())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        val append = threads.submit<Boolean> {
            try {
                store.append(1000, 2000, 0, object : ByteArrayInputStream(byteArrayOf(5, 6, 7, 8)) {
                    override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                        entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                        return super.read(bytes, off, len)
                    }
                })
                false
            } catch (_: CaptureRetentionBlocked) { true }
        }
        var pin: AutoCloseable? = null
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            pin = threads.submit<AutoCloseable> {
                assertEquals(listOf(0L), store.snapshot().map { it.sequence })
                assertEquals(CaptureBounds(0, 1000), store.contiguousBounds())
                store.openSnapshotFrom(0).use { assertArrayEquals(byteArrayOf(1, 2, 3, 4), it.readBytes()) }
                try { store.close(); fail("Append must retain exclusive directory ownership") } catch (_: IllegalStateException) { }
                store.pinFrom(0)
            }.get(2, TimeUnit.SECONDS)
            release.countDown()
            assertTrue("Pin acquired during copy prevents eviction at commit", append.get(2, TimeUnit.SECONDS))
            assertEquals(listOf(0L), store.snapshot().map { it.sequence })
            pin.close(); pin = null
            store.append(1000, 2000, 0, byteArrayOf(5, 6, 7, 8).inputStream())
            assertEquals(listOf(1L), store.snapshot().map { it.sequence })
        } finally {
            release.countDown(); append.get(3, TimeUnit.SECONDS)
            pin?.close(); threads.shutdownNow(); store.close()
        }
    }

    @Test(timeout = 10000) fun concurrentWritersRemainSerializedAndValidateOrderAfterAcquiringWriterOwnership() {
        val store = CaptureSegmentStore(temp.newFolder(), 8, 4)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        val first = threads.submit {
            store.append(0, 1000, 0, object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) {
                override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    return super.read(bytes, off, len)
                }
            })
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val second = threads.submit<Boolean> {
                try { store.append(0, 1000, 0, byteArrayOf(5).inputStream()); false }
                catch (_: IllegalArgumentException) { true }
            }
            release.countDown(); first.get(2, TimeUnit.SECONDS)
            assertTrue(second.get(2, TimeUnit.SECONDS))
            assertEquals(listOf(0L), store.snapshot().map { it.sequence })
        } finally { release.countDown(); first.get(3, TimeUnit.SECONDS); threads.shutdownNow(); store.close() }
    }
}
