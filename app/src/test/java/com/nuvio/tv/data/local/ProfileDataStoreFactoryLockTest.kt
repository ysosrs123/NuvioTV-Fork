package com.nuvio.tv.data.local

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ProfileDataStoreFactoryLockTest {
    private val dir: File = Files.createTempDirectory("profile-store-lock").toFile()
    private val executor = Executors.newFixedThreadPool(8)

    @After fun cleanup() {
        executor.shutdownNow()
        dir.deleteRecursively()
    }

    private fun context(filesDir: () -> File = { dir }): Context = mockk {
        every { this@mockk.filesDir } answers { filesDir() }
        every { applicationContext } returns this@mockk
    }

    @Test fun `callers racing for one store get the same instance`() {
        val factory = ProfileDataStoreFactory(context())
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val results = (1..threads).map {
            executor.submit<Any> {
                barrier.await(5, TimeUnit.SECONDS)
                factory.get(2, "lock_test")
            }
        }.map { it.get(5, TimeUnit.SECONDS) }
        results.forEach { assertSame(results.first(), it) }
        assertTrue(File(dir, "datastore/lock_test_p2.preferences_pb").exists())
    }

    @Test fun `a slow new store does not block lookups of a cached store`() {
        val armed = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val factory = ProfileDataStoreFactory(context {
            if (armed.getAndSet(false)) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            dir
        })
        val cached = factory.get(1, "cached_store")

        armed.set(true)
        val slow = executor.submit<Any> { factory.get(2, "slow_store") }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            val lookup = executor.submit<Any> { factory.get(1, "cached_store") }
            assertSame(cached, lookup.get(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
        }
        val slowStore = slow.get(5, TimeUnit.SECONDS)
        assertSame(slowStore, factory.get(2, "slow_store"))
    }
}
