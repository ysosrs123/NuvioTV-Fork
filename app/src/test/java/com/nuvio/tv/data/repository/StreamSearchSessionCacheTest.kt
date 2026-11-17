package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.AddonStreams
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StreamSearchSessionCacheTest {

    @Test
    fun `full search leaves an in-flight plugin continuation running`() = runTest {
        val cache = StreamSearchSessionCache()
        val pluginCancelled = AtomicBoolean(false)
        val pluginStarted = CompletableDeferred<Unit>()
        val releasePlugin = CompletableDeferred<Unit>()
        val key = StreamSearchRequestKey(
            profileId = 1,
            type = "series",
            videoId = "tt1",
            season = 1,
            episode = 5,
            sourceConfiguration = "config",
            searchMode = "continuePluginOnly"
        )

        val pluginJob = launch {
            cache.observe(key, forceRefresh = false) {
                flow {
                    emit(NetworkResult.Loading)
                    pluginStarted.complete(Unit)
                    try {
                        releasePlugin.await()
                        emit(NetworkResult.Success(emptyList()))
                    } catch (error: CancellationException) {
                        pluginCancelled.set(true)
                        throw error
                    }
                }
            }.collect { }
        }
        pluginStarted.await()

        cache.observe(key.copy(searchMode = ""), forceRefresh = false) {
            flow { emit(NetworkResult.Success(emptyList())) }
        }.first { it is NetworkResult.Success }

        withContext(Dispatchers.IO) { delay(200) }
        assertFalse(pluginCancelled.get())
        releasePlugin.complete(Unit)
        pluginJob.cancel()
    }

    @Test
    fun `a different plugin priority starts its own search`() = runTest {
        val cache = StreamSearchSessionCache()
        val producers = AtomicInteger(0)
        val key = mediaKey(searchMode = "preferredPluginOnly", priorityBingeGroup = "local-plugin-a")

        cache.observe(key, forceRefresh = false, producer = { countedResult(producers, "A") })
            .first { it is NetworkResult.Success }
        cache.observe(
            key.copy(priorityBingeGroup = "local-plugin-b"),
            forceRefresh = false,
            producer = { countedResult(producers, "B") }
        ).first { it is NetworkResult.Success }

        assertEquals(2, producers.get())
    }

    @Test
    fun `peek finds a plugin continuation stored under another search mode`() = runTest {
        val cache = StreamSearchSessionCache()
        val key = mediaKey(searchMode = "continuePluginOnly", priorityBingeGroup = "local-plugin-a")

        cache.observe(key, forceRefresh = false) {
            flow { emit(NetworkResult.Success(listOf(AddonStreams("Example", null, emptyList())))) }
        }.first { it is NetworkResult.Success }

        val peeked = cache.peek(mediaKey(searchMode = "", priorityBingeGroup = ""))
        assertEquals("Example", peeked?.single()?.addonName)
        assertNull(
            cache.peek(
                mediaKey(searchMode = "", priorityBingeGroup = "")
                    .copy(sourceConfiguration = "updated-config")
            )
        )
    }

    @Test
    fun `peek keeps the requested plugin when another priority is cached`() = runTest {
        val cache = StreamSearchSessionCache()
        cache.observe(mediaKey(searchMode = "continuePluginOnly", priorityBingeGroup = "local-plugin-a"), forceRefresh = false) {
            flow { emit(NetworkResult.Success(listOf(AddonStreams("A", null, emptyList())))) }
        }.first { it is NetworkResult.Success }
        cache.observe(mediaKey(searchMode = "continuePluginOnly", priorityBingeGroup = "local-plugin-b"), forceRefresh = false) {
            flow { emit(NetworkResult.Success(listOf(AddonStreams("B", null, emptyList())))) }
        }.first { it is NetworkResult.Success }

        assertNull(cache.peek(mediaKey(searchMode = "", priorityBingeGroup = "")))
        assertEquals(
            "A",
            cache.peek(mediaKey(searchMode = "", priorityBingeGroup = "local-plugin-a"))?.single()?.addonName
        )
    }

    @Test
    fun `cancelling the plugin continuation stops it, keeps nothing and leaves the full search alone`() = runTest {
        val cache = StreamSearchSessionCache()
        val pluginCancelled = CompletableDeferred<Unit>()
        val pluginStarted = CompletableDeferred<Unit>()
        val continuation = mediaKey(searchMode = "continuePluginOnly", priorityBingeGroup = "local-plugin-a")
        val full = mediaKey(searchMode = "", priorityBingeGroup = "")

        cache.observe(full, forceRefresh = false) {
            flow { emit(NetworkResult.Success(listOf(AddonStreams("Full", null, emptyList())))) }
        }.first { it is NetworkResult.Success }
        val collector = launch {
            cache.observe(continuation, forceRefresh = false) {
                flow {
                    emit(NetworkResult.Success(listOf(AddonStreams("Partial", null, emptyList()))))
                    pluginStarted.complete(Unit)
                    try {
                        CompletableDeferred<Unit>().await()
                    } catch (error: CancellationException) {
                        pluginCancelled.complete(Unit)
                        throw error
                    }
                }
            }.collect { }
        }
        pluginStarted.await()

        cache.cancelSearchMode("continuePluginOnly")

        withContext(Dispatchers.IO) { withTimeout(2_000) { pluginCancelled.await() } }
        withContext(Dispatchers.IO) { withTimeout(2_000) { collector.join() } }
        assertEquals("Full", cache.peek(full)?.single()?.addonName)

        val producers = AtomicInteger()
        cache.observe(continuation, forceRefresh = false) { countedResult(producers, "Fresh") }
            .first { it is NetworkResult.Success }
        assertEquals(1, producers.get())
    }

    private fun mediaKey(searchMode: String, priorityBingeGroup: String) = StreamSearchRequestKey(
        profileId = 1,
        type = "series",
        videoId = "tt1",
        season = 1,
        episode = 6,
        sourceConfiguration = "config",
        searchMode = searchMode,
        priorityBingeGroup = priorityBingeGroup
    )

    private fun countedResult(producers: AtomicInteger, name: String) = flow {
        producers.incrementAndGet()
        emit(NetworkResult.Success(listOf(AddonStreams(name, null, emptyList()))))
    }
}
