package com.nuvio.tv.core.image

import android.content.Context
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.memory.MemoryCache
import coil3.network.HttpException
import coil3.network.NetworkResponse
import coil3.request.Options
import coil3.toUri
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SavedCopyFirstTest {
    private val context = mockk<Context>(relaxed = true)
    private val url = "https://posters.example/p.jpg".toUri()

    private fun withCache(block: suspend TestScope.(DiskCache, ImageLoader) -> Unit) = runTest {
        val dir = kotlin.io.path.createTempDirectory("saved-first").toFile()
        try {
            val cache = DiskCache.Builder().directory(dir.toOkioPath()).maxSizeBytes(1L shl 20).build()
            cache.openEditor("key")!!.apply {
                cache.fileSystem.write(data) { writeUtf8("old") }
                cache.fileSystem.write(metadata) {}
                commit()
            }
            block(cache, mockk { every { diskCache } returns cache })
            cache.shutdown()
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun network(onFetch: () -> Unit) = object : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher = object : Fetcher {
            override suspend fun fetch() = onFetch().let { null }
        }
    }

    @Test fun aSavedPictureDueForACheckIsShownAtOnceAndCheckedAfter() = withCache { cache, loader ->
        var online = 0
        val checks = SavedImageChecks(TestScope(StandardTestDispatcher(testScheduler)))
        val factory = SavedCopyOnErrorFetcher.Factory(network { online++ }, checks) { _, _, _, _ -> false }
        val result = factory.create(url, Options(context, diskCacheKey = "key"), loader)!!.fetch() as SourceFetchResult
        assertEquals(DataSource.DISK, result.dataSource)
        assertEquals("old", result.source.source().readUtf8())
        result.source.close()
        assertEquals(0, online)
        testScheduler.runCurrent()
        assertEquals(1, online)
        factory.create(url, Options(context, diskCacheKey = "key"), loader)!!.fetch().let { (it as SourceFetchResult).source.close() }
        testScheduler.runCurrent()
        assertEquals("checked once per cooldown", 1, online)
        assertTrue(cache.openSnapshot("key")!!.use { true })
    }

    @Test fun aFreshSavedPictureGoesThroughTheNormalPath() = withCache { _, loader ->
        var online = 0
        val checks = SavedImageChecks(TestScope(StandardTestDispatcher(testScheduler)))
        val fresh = SavedCopyOnErrorFetcher.Factory(network { online++ }, checks) { _, _, _, _ -> true }
        fresh.create(url, Options(context, diskCacheKey = "key"), loader)!!.fetch()
        assertEquals(1, online)
        testScheduler.runCurrent()
        assertEquals("no background check", 1, online)
    }

    @Test fun aMissingSavedPictureGoesOnline() = withCache { _, loader ->
        var online = 0
        val checks = SavedImageChecks(TestScope(StandardTestDispatcher(testScheduler)))
        val due = SavedCopyOnErrorFetcher.Factory(network { online++ }, checks) { _, _, _, _ -> false }
        due.create(url, Options(context, diskCacheKey = "other"), loader)!!.fetch()
        assertEquals(1, online)
    }

    @Test fun checksOfOnePictureAreMergedAndTwoRunAtATime() = runTest {
        var running = 0
        var most = 0
        val done = mutableListOf<String>()
        val checks = SavedImageChecks(TestScope(StandardTestDispatcher(testScheduler)), now = { testScheduler.currentTime })
        fun check(key: String): suspend () -> Unit = {
            running++
            most = maxOf(most, running)
            delay(1_000)
            running--
            done += key
        }
        checks.schedule("a", check = check("a"))
        checks.schedule("a", check = check("a"))
        checks.schedule("b", check = check("b"))
        checks.schedule("c", check = check("c"))
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), done)
        assertEquals(2, most)
        checks.schedule("a", check = check("a"))
        testScheduler.advanceUntilIdle()
        assertEquals("cooldown", 3, done.size)
    }

    @Test fun checksWaitWhileHomeIsScrolling() = runTest {
        var scrolling = true
        var online = 0
        val checks = SavedImageChecks(TestScope(StandardTestDispatcher(testScheduler)), scrolling = { scrolling })
        checks.schedule("a") { online++ }
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()
        assertEquals(0, online)
        scrolling = false
        testScheduler.advanceUntilIdle()
        assertEquals(1, online)
    }

    private fun rewriting(cache: DiskCache, body: String, onFetch: () -> Unit = {}) = object : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher = object : Fetcher {
            override suspend fun fetch() = onFetch().let {
                cache.openEditor("key")!!.apply {
                    cache.fileSystem.write(this.data) { writeUtf8(body) }
                    cache.fileSystem.write(metadata) {}
                    commit()
                }
                null
            }
        }
    }

    private suspend fun TestScope.checkOnce(cache: DiskCache, loader: ImageLoader, body: String): Int {
        var changed = 0
        val checks = SavedImageChecks(TestScope(StandardTestDispatcher(testScheduler)))
        val factory = SavedCopyOnErrorFetcher.Factory(rewriting(cache, body), checks, { _, _ -> changed++ }) { _, _, _, _ -> false }
        (factory.create(url, Options(context, diskCacheKey = "key"), loader)!!.fetch() as SourceFetchResult).source.close()
        testScheduler.advanceUntilIdle()
        return changed
    }

    @Test fun theSamePictureSentAgainWithoutValidatorsIsNotRedrawn() = withCache { cache, loader ->
        assertEquals(0, checkOnce(cache, loader, "old"))
    }

    @Test fun aChangedPictureIsRedrawnOnce() = withCache { cache, loader ->
        assertEquals(1, checkOnce(cache, loader, "new"))
    }

    @Test fun aPictureChangedOffScreenComesBackNewFromEveryKey() = runTest {
        val poster = "https://posters.example/p.jpg"
        val memory = ArtworkMemoryCache(16L * 1024 * 1024, now = { 0L })
        val kept = listOf(MemoryCache.Key("https://posters.example/p.jpg2_200x300_v0"), MemoryCache.Key("https://other.example/p.jpg"))
        val dropped = listOf(
            MemoryCache.Key("${poster}_200x300_v0"),
            MemoryCache.Key("${poster}_300x450_v1"),
            MemoryCache.Key("home-hero:$poster:1920x1080"),
            MemoryCache.Key(poster),
        )
        (kept + dropped).forEach { memory[it] = MemoryCache.Value(FakeImage()) }
        artworkChanged(poster, memory, SavedImageChecks(TestScope(StandardTestDispatcher(testScheduler))))
        testScheduler.runCurrent()
        dropped.forEach { assertNotNull("old picture stays a moment as the placeholder: $it", memory[it]) }
        testScheduler.advanceUntilIdle()
        dropped.forEach { assertNull("back on screen reads the new file: $it", memory[it]) }
        kept.forEach { assertNotNull(it.toString(), memory[it]) }
    }

    @Test fun smallerTmdbSizesAlsoDropTheOriginal() {
        assertEquals(
            listOf("https://image.tmdb.org/t/p/w342/a.jpg", "https://image.tmdb.org/t/p/original/a.jpg"),
            changedArtworkUrls("https://image.tmdb.org/t/p/w342/a.jpg"),
        )
        assertTrue(memoryKeyShows("title-logo:https://a.example/l.png", "https://a.example/l.png"))
        assertFalse(memoryKeyShows("https://a.example/l.png2", "https://a.example/l.png"))
    }

    @Test fun aBusySiteIsLeftAloneForTenMinutes() = runTest {
        var online = 0
        val checks = SavedImageChecks(TestScope(StandardTestDispatcher(testScheduler)), now = { testScheduler.currentTime })
        checks.schedule("a", host = "busy.example") { online++; throw HttpException(NetworkResponse(code = 429)) }
        testScheduler.advanceUntilIdle()
        checks.schedule("b", host = "busy.example") { online++ }
        checks.schedule("c", host = "calm.example") { online++ }
        testScheduler.advanceUntilIdle()
        assertEquals(2, online)
        testScheduler.advanceTimeBy(SavedImageChecks.SITE_REST_MS + 1)
        checks.schedule("b", host = "busy.example") { online++ }
        testScheduler.advanceUntilIdle()
        assertEquals(3, online)
    }

    @Test fun checksAlreadyWaitingForASlotSkipASiteThatTurnedBusy() = runTest {
        val hits = mutableListOf<String>()
        val checks = SavedImageChecks(TestScope(StandardTestDispatcher(testScheduler)), now = { testScheduler.currentTime })
        checks.schedule("p0", host = "busy.example") {
            hits += "p0"
            delay(100)
            throw HttpException(NetworkResponse(code = 429))
        }
        checks.schedule("p1", host = "busy.example") { hits += "p1"; delay(1_000) }
        (2..6).forEach { n -> checks.schedule("p$n", host = "busy.example") { hits += "p$n" } }
        checks.schedule("other", host = "calm.example") { hits += "other" }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("p0", "p1", "other"), hits)
    }

    @Test fun anAddressThatStartsAnotherIsKeptApart() {
        val keys = setOf(
            MemoryCache.Key("https://a.example/p_200x300_v0"),
            MemoryCache.Key("https://a.example/p_big.jpg_200x300_v0"),
            MemoryCache.Key("https://a.example/p.jpg_200x300_v0"),
        )
        assertEquals(
            listOf("https://a.example/p.jpg_200x300_v0"),
            memoryKeysOf(keys, listOf("https://a.example/p.jpg")).map { it.key },
        )
        assertEquals(
            setOf("https://a.example/p_200x300_v0", "https://a.example/p_big.jpg_200x300_v0"),
            memoryKeysOf(keys, listOf("https://a.example/p")).map { it.key }.toSet(),
        )
    }

    @Test fun checksWaitWhileHomeIsStillSettling() = runTest {
        var settling = true
        var online = 0
        val checks = SavedImageChecks(TestScope(StandardTestDispatcher(testScheduler)), settling = { settling })
        checks.schedule("a") { online++ }
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()
        assertEquals(0, online)
        settling = false
        testScheduler.advanceUntilIdle()
        assertEquals(1, online)
    }

    private class FakeImage : coil3.Image {
        override val size = 1_000L
        override val width = 10
        override val height = 10
        override val shareable = true
        override fun draw(canvas: android.graphics.Canvas) = Unit
    }

    @Test fun onlyNoCacheOrMaxAgeZeroAskForTheServerFirst() {
        assertTrue(onlineCheckRequested("no-cache"))
        assertTrue(onlineCheckRequested("max-age=0"))
        assertFalse(onlineCheckRequested("max-age=3600"))
        assertFalse(onlineCheckRequested(null))
    }
}
