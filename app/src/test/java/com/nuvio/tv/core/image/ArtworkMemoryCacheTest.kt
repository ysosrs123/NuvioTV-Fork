package com.nuvio.tv.core.image

import android.graphics.Canvas
import coil3.Image
import coil3.memory.MemoryCache
import org.junit.Assert.*
import org.junit.Test

class ArtworkMemoryCacheTest {
    @Test fun smallerBudgetsKeepOneFullResolutionHeroAlongsidePosters() {
        val cache = ArtworkMemoryCache(16L * 1024 * 1024)
        val hero = MemoryCache.Key("1080p")
        cache[hero] = image(1920L * 1080 * 4)
        repeat(40) { cache[MemoryCache.Key("poster$it")] = image(180_928) }
        assertNotNull(cache[hero])
        assertNotNull(cache[MemoryCache.Key("poster39")])
        assertTrue(cache.size <= cache.maxSize)
    }
    private fun image(bytes: Long) = MemoryCache.Value(object : Image {
        override val size = bytes
        override val width = 100
        override val height = 100
        override val shareable = true
        override fun draw(canvas: Canvas) = Unit
    })
    @Test fun heroChurnKeepsPostersWithinTheOriginalTotalBudget() {
        val cache = ArtworkMemoryCache(16L * 1024 * 1024)
        val poster = MemoryCache.Key("poster")
        cache[poster] = image(200_000)
        repeat(12) { cache[MemoryCache.Key("hero$it")] = image(3_000_000) }
        assertNotNull(cache[poster])
        assertTrue(cache.size <= cache.maxSize)
        assertEquals(16L * 1024 * 1024, cache.maxSize)
        assertNotNull(cache[MemoryCache.Key("hero11")])
    }
    @Test fun replacementRemovalAndMemoryPressureApplyToBothPartitions() {
        val cache = ArtworkMemoryCache(16L * 1024 * 1024)
        val key = MemoryCache.Key("same-url")
        cache[key] = image(200_000)
        cache[key] = image(2_000_000)
        assertEquals(2_000_000L, cache[key]!!.image.size)
        assertTrue(cache.remove(key))
        assertNull(cache[key])
        repeat(10) { cache[MemoryCache.Key("small$it")] = image(600_000) }
        cache[MemoryCache.Key("large")] = image(3_000_000)
        cache.maxSize = 4L * 1024 * 1024
        assertTrue(cache.size <= cache.maxSize)
        cache.trimToSize(500_000)
        assertTrue(cache.size <= 500_000)
        cache.clear()
        assertEquals(0L, cache.size)
        assertTrue(cache.keys.isEmpty())
    }
    @Test fun artFromOtherHostsLeavesMemoryAfterTheLimitSoTheNextLoadChecksAgain() {
        var now = 0L
        val cache = ArtworkMemoryCache(16L * 1024 * 1024, undatedLimitMs = { 1_000 }, now = { now })
        val addon = MemoryCache.Key("https://top-today.example/p/tt1.jpg_300x450_v0")
        val tmdb = MemoryCache.Key("https://image.tmdb.org/t/p/w342/a.jpg_300x450_v0")
        val local = MemoryCache.Key("profile-background-sunset-v1")
        listOf(addon, tmdb, local).forEach { cache[it] = image(200_000) }
        now = 999
        assertNotNull(cache[addon])
        now = 1_000
        assertNull(cache[addon])
        assertFalse(addon in cache.keys)
        assertNotNull(cache[tmdb])
        assertNotNull(cache[local])
        cache[addon] = image(200_000)
        now = 1_500
        assertNotNull(cache[addon])
        val hero = MemoryCache.Key("home-hero:https://top-today.example/b.jpg:1920x1080")
        cache[hero] = image(1920L * 1080 * 4)
        now = 2_499
        assertNotNull(cache[hero])
        now = 2_500
        assertNull(cache[hero])
    }

    @Test fun localPicturesStayInMemory() {
        var now = 0L
        val cache = ArtworkMemoryCache(16L * 1024 * 1024, undatedLimitMs = { 1_000 }, now = { now })
        val key = MemoryCache.Key("content://media/external/images/7")
        cache[key] = image(200_000)
        now = 10_000
        assertNotNull(cache[key])
    }

    @Test fun storedTimeKeepsCoilExtras() {
        val cache = ArtworkMemoryCache(16L * 1024 * 1024, now = { 0L })
        val key = MemoryCache.Key("https://addon.example/p.jpg")
        cache[key] = MemoryCache.Value(image(1_000).image, mapOf("coil#is_sampled" to true))
        assertEquals(true, cache[key]!!.extras["coil#is_sampled"])
    }
    @Test fun everyStartLooksAgainAtPicturesKeptFromBeforeTheStart() {
        var now = 100L
        var cutoff = Long.MIN_VALUE
        val cache = ArtworkMemoryCache(16L * 1024 * 1024, now = { now }, storedBefore = { cutoff })
        val addon = MemoryCache.Key("https://addon.example/p.jpg")
        val tmdb = MemoryCache.Key("https://image.tmdb.org/t/p/w342/p.jpg")
        cache[addon] = image(1_000)
        cache[tmdb] = image(1_000)
        now = 200
        cutoff = 150
        assertNull(cache[addon])
        assertNotNull(cache[tmdb])
        cache[addon] = image(1_000)
        assertNotNull(cache[addon])
    }
}
