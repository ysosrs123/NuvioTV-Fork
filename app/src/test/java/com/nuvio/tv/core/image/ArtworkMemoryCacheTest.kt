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
}
