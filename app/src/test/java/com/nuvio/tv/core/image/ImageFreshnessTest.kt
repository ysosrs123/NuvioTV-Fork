package com.nuvio.tv.core.image

import android.content.Context
import coil3.annotation.InternalCoilApi
import coil3.disk.DiskCache
import coil3.network.CacheNetworkResponse
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.request.Options
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import okio.Path.Companion.toOkioPath
import okio.buffer

class ImageFreshnessTest {
    private val day = 24L * 60 * 60 * 1000
    private val options = Options(mockk<Context>(relaxed = true))

    private val tmdb = "https://image.tmdb.org/t/p/w342/abc.jpg"
    private val addon = "https://top-today.example/poster/tt123.jpg"

    private fun saved(cacheControl: String?, ageMs: Long, etag: String? = null) = NetworkResponse(
        code = 200,
        requestMillis = System.currentTimeMillis() - ageMs,
        responseMillis = System.currentTimeMillis() - ageMs,
        headers = NetworkHeaders.Builder().apply {
            cacheControl?.let { set("Cache-Control", it) }
            etag?.let { set("ETag", it) }
        }.build(),
    )

    private suspend fun fresh(response: NetworkResponse, request: NetworkRequest = NetworkRequest(tmdb)) =
        FreshnessFloorCacheStrategy().read(response, request, options).response != null

    private suspend fun freshFromAddon(response: NetworkResponse) = fresh(response, NetworkRequest(addon))

    @Test fun floorCoversAWeekFromTheLastDownloadOrCheck() {
        val now = 100 * day
        assertTrue(withinFreshnessFloor(now - 6 * day, now))
        assertTrue(withinFreshnessFloor(now - 7 * day + 1, now))
        assertFalse(withinFreshnessFloor(now - 7 * day, now))
        assertFalse(withinFreshnessFloor(0, now))
        assertTrue(withinFreshnessFloor(now + day, now))
    }

    @Test fun shortServerMaxAgeIsStillFreshWithinAWeek() = runTest {
        assertTrue(fresh(saved("max-age=60", 2 * day)))
        assertTrue(fresh(saved("public, max-age=3600", 6 * day)))
        assertFalse(fresh(saved("max-age=60", 8 * day)))
    }

    @Test fun longerServerRulesStillApplyAfterTheWeek() = runTest {
        assertTrue(fresh(saved("max-age=31536000", 30 * day)))
    }

    @Test fun explicitRevalidationRequestBypassesTheFloor() = runTest {
        val noCache = NetworkRequest(
            url = tmdb,
            headers = NetworkHeaders.Builder().set("Cache-Control", "no-cache").build(),
        )
        assertFalse(fresh(saved("max-age=60", 2 * day), noCache))
    }

    @Test fun staleCopyAsksTheServerWithConditions() = runTest {
        val response = NetworkResponse(
            code = 200,
            requestMillis = System.currentTimeMillis() - 9 * day,
            responseMillis = System.currentTimeMillis() - 9 * day,
            headers = NetworkHeaders.Builder().set("Cache-Control", "max-age=60").set("ETag", "\"a\"").build(),
        )
        val result = FreshnessFloorCacheStrategy(CacheControlCacheStrategy()).read(response, NetworkRequest(tmdb), options)
        assertNull(result.response)
        assertNotNull(result.request?.headers?.get("If-None-Match"))
    }

    @OptIn(InternalCoilApi::class)
    @Test fun onlyCopiesShownWithoutAnOnlineCheckUseTheDiskLanes() = runTest {
        val dir = kotlin.io.path.createTempDirectory("posters").toFile()
        try {
            val cache = DiskCache.Builder().directory(dir.toOkioPath()).maxSizeBytes(1L shl 20).build()
            fun put(key: String, response: NetworkResponse?) {
                val editor = cache.openEditor(key)!!
                cache.fileSystem.write(editor.data) { writeUtf8("jpeg") }
                cache.fileSystem.sink(editor.metadata).buffer().use { sink ->
                    response?.let { CacheNetworkResponse.writeTo(it, sink) }
                }
                editor.commit()
            }
            val freshKey = "https://image.tmdb.org/t/p/w342/fresh.jpg"
            val staleKey = "https://image.tmdb.org/t/p/w342/stale.jpg"
            val addonKey = "https://top-today.example/poster/tt1.jpg"
            put(freshKey, saved("max-age=60", 3 * day))
            put(staleKey, saved("max-age=60", 10 * day))
            put(addonKey, saved("max-age=60", 2 * day))
            put("https://top-today.example/poster/tt2.jpg", saved(null, 6 * day))
            put("https://top-today.example/poster/tt3.jpg", saved(null, 8 * day))
            put("manual", null)
            suspend fun fresh(key: String) = cache.openSnapshot(key)!!.use { savedImageIsFresh(it, cache.fileSystem, key, options) }
            assertTrue(fresh(freshKey))
            assertFalse(fresh(staleKey))
            assertTrue(fresh(addonKey))
            assertTrue(fresh("https://top-today.example/poster/tt2.jpg"))
            assertFalse(fresh("https://top-today.example/poster/tt3.jpg"))
            assertTrue(fresh("manual"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun onlyTheListedArtworkHostsCountAsNeverChanging() {
        listOf(
            "https://image.tmdb.org/t/p/original/a.jpg",
            "https://images.metahub.space/poster/medium/tt1/img",
            "https://episodes.metahub.space/tt1/1/1/w780.jpg",
            "https://assets.fanart.tv/fanart/movies/1/hdmovielogo/a.png",
            "https://artworks.thetvdb.com/banners/posters/a.jpg",
            "https://m.media-amazon.com/images/M/a.jpg",
            "HTTPS://Image.TMDB.org/t/p/w500/a.jpg",
            "https://image.tmdb.org/t/p/w342/a.jpg_300x450_v0",
        ).forEach { assertTrue(it, isImmutableArtworkUrl(it)) }
        listOf(
            addon,
            "https://api.ratingposterdb.com/key/imdb/poster-default/tt1.jpg",
            "https://notmetahub.space/poster.jpg",
            "https://image.tmdb.org.example.com/a.jpg",
            "https://example.com/?u=https://image.tmdb.org/a.jpg",
            "home-hero:catalog:1920x1080",
            "",
        ).forEach { assertFalse(it, isImmutableArtworkUrl(it)) }
    }

    @Test fun hostIsReadFromUrlsAndUrlBasedKeys() {
        assertEquals("top-today.example", imageHost("https://user@Top-Today.example:8443/p.jpg?x=1"))
        assertEquals("image.tmdb.org", imageHost("home-hero:https://image.tmdb.org/t/p/w1280/a.jpg:1920x1080"))
        assertNull(imageHost("custom-profile-background-abc"))
        assertNull(imageHost("://nohost"))
        assertNull(imageHost("content://media/external/images/1"))
        assertNull(imageHost("custom-profile-background-content://media/1"))
        assertEquals("addon.example", imageHost("title-logo:http://addon.example/l.png"))
    }

    @Test fun otherHostsAlsoGetAWeekWhateverTheirOwnRuleSays() = runTest {
        assertTrue(freshFromAddon(saved("max-age=300", 6 * day)))
        assertTrue(freshFromAddon(saved("no-cache", 6 * day)))
        assertTrue(freshFromAddon(saved("max-age=0", 6 * day)))
        assertTrue(freshFromAddon(saved("no-store", 6 * day)))
        assertTrue(freshFromAddon(saved(null, 6 * day)))
        assertFalse(freshFromAddon(saved("max-age=300", 8 * day)))
        assertFalse(freshFromAddon(saved(null, 8 * day)))
    }

    @Test fun otherHostsWithALongerRuleKeepItAfterTheWeek() = runTest {
        assertTrue(freshFromAddon(saved("public, max-age=2592000", 20 * day)))
    }

    @Test fun otherHostsAskWithTheirETagOnceTheWeekRunsOut() = runTest {
        val result = FreshnessFloorCacheStrategy().read(
            saved("max-age=3600", 8 * day, etag = "\"r4\""), NetworkRequest(addon), options,
        )
        assertNull(result.response)
        assertEquals("\"r4\"", result.request?.headers?.get("If-None-Match"))
    }
}
