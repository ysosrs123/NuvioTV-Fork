package com.nuvio.tv.core.image

import android.content.Context
import coil3.network.CacheStrategy
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.request.Options
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PosterUpdatesTest {
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    private val now = 400 * day
    private val start = now - 2 * hour
    private val options = Options(mockk<Context>(relaxed = true))

    private val tmdb = "https://image.tmdb.org/t/p/w342/abc.jpg"
    private val addon = "https://api.ratingposterdb.com/key/imdb/poster-default/tt1.jpg"

    private fun decide(url: String, ageMs: Long, interval: PosterUpdateInterval) =
        savedImageDecision(url, now - ageMs, now, interval, start)

    private fun saved(cacheControl: String?, ageMs: Long, etag: String? = null, lastModified: String? = null) =
        NetworkResponse(
            code = 200,
            requestMillis = now - ageMs,
            responseMillis = now - ageMs,
            headers = NetworkHeaders.Builder().apply {
                cacheControl?.let { set("Cache-Control", it) }
                etag?.let { set("ETag", it) }
                lastModified?.let { set("Last-Modified", it) }
            }.build(),
        )

    private fun strategy(interval: PosterUpdateInterval) =
        FreshnessFloorCacheStrategy(interval = { interval }, startMillis = { start }, now = { now })

    @Test fun artworkHostsStayOnTheWeekWhateverIsChosen() {
        PosterUpdateInterval.entries.forEach { interval ->
            assertEquals(interval.name, SavedImageDecision.Fresh, decide(tmdb, 6 * day, interval))
            assertEquals(interval.name, SavedImageDecision.ServerRule, decide(tmdb, 8 * day, interval))
        }
        assertEquals(SavedImageDecision.Fresh, decide(tmdb, 3 * hour, PosterUpdateInterval.EVERY_START))
    }

    @Test fun weeklyKeepsTheWeekAndThenTheServerRuleForEveryHost() {
        assertEquals(SavedImageDecision.Fresh, decide(addon, 6 * day, PosterUpdateInterval.WEEKLY))
        assertEquals(SavedImageDecision.ServerRule, decide(addon, 8 * day, PosterUpdateInterval.WEEKLY))
    }

    @Test fun dailyChecksOtherHostsAfterADay() {
        assertEquals(SavedImageDecision.Fresh, decide(addon, 23 * hour, PosterUpdateInterval.DAILY))
        assertEquals(SavedImageDecision.Check, decide(addon, 25 * hour, PosterUpdateInterval.DAILY))
    }

    @Test fun monthlyChecksOtherHostsAfterThirtyDays() {
        assertEquals(SavedImageDecision.Fresh, decide(addon, 8 * day, PosterUpdateInterval.MONTHLY))
        assertEquals(SavedImageDecision.Fresh, decide(addon, 29 * day, PosterUpdateInterval.MONTHLY))
        assertEquals(SavedImageDecision.Check, decide(addon, 31 * day, PosterUpdateInterval.MONTHLY))
    }

    @Test fun everyStartChecksCopiesSavedBeforeThisStartOnce() {
        val interval = PosterUpdateInterval.EVERY_START
        assertEquals(SavedImageDecision.Check, decide(addon, 3 * hour, interval))
        assertEquals(SavedImageDecision.Check, decide(addon, 20 * day, interval))
        assertEquals(SavedImageDecision.Fresh, decide(addon, hour, interval))
        assertEquals(SavedImageDecision.Fresh, decide(addon, 2 * hour, interval))
        assertEquals(SavedImageDecision.Check, savedImageDecision(addon, 0, now, interval, start))
    }

    @Test fun aCheckAsksWithTheSavedValidatorsEvenWhenTheServerAllowedLonger() = runTest {
        val copy = saved("max-age=31536000", 2 * day, etag = "\"r7\"", lastModified = "Sun, 15 Nov 2026 10:00:00 GMT")
        val result = strategy(PosterUpdateInterval.DAILY).read(copy, NetworkRequest(addon), options)
        assertNull(result.response)
        assertEquals("\"r7\"", result.request?.headers?.get("If-None-Match"))
        assertEquals("Sun, 15 Nov 2026 10:00:00 GMT", result.request?.headers?.get("If-Modified-Since"))
        assertNotNull(strategy(PosterUpdateInterval.WEEKLY).read(copy, NetworkRequest(addon), options).response)
    }

    @Test fun everyStartShowsCopiesSavedThisStartAndArtworkHostsWithoutAsking() = runTest {
        val s = strategy(PosterUpdateInterval.EVERY_START)
        assertNotNull(s.read(saved(null, hour), NetworkRequest(addon), options).response)
        assertNull(s.read(saved(null, 3 * hour), NetworkRequest(addon), options).response)
        assertNotNull(s.read(saved(null, 3 * hour), NetworkRequest(tmdb), options).response)
    }

    @Test fun anExplicitOnlineRequestStillGoesToTheServer() = runTest {
        val noCache = NetworkRequest(url = addon, headers = NetworkHeaders.Builder().set("Cache-Control", "no-cache").build())
        assertNull(strategy(PosterUpdateInterval.MONTHLY).read(saved("max-age=60", hour), noCache, options).response)
    }

    @Test fun aCopyWithoutValidatorsIsFetchedPlainly() {
        val request = conditionalRequest(NetworkRequest(addon), NetworkHeaders.Builder().set("Cache-Control", "public").build())
        assertNull(request.headers["If-None-Match"])
        assertNull(request.headers["If-Modified-Since"])
        assertEquals(addon, request.url)
    }

    @Test fun weeklyAnswersExactlyLikeTheStrategyBeforeTheSetting() = runTest {
        val clock = System.currentTimeMillis()
        suspend fun before(copy: NetworkResponse, request: NetworkRequest): CacheStrategy.ReadResult {
            val askServer = request.headers["Cache-Control"]?.lowercase()?.let { "no-cache" in it || "max-age=0" in it } == true
            return if (withinFreshnessFloor(copy.responseMillis, clock) && !askServer) {
                CacheStrategy.ReadResult(copy)
            } else {
                CacheControlCacheStrategy().read(copy, request, options)
            }
        }
        val weekly = FreshnessFloorCacheStrategy(
            interval = { PosterUpdateInterval.WEEKLY }, startMillis = { clock - hour / 2 }, now = { clock },
        )
        val noCache = NetworkHeaders.Builder().set("Cache-Control", "no-cache").build()
        for (url in listOf(addon, tmdb)) {
            for (rule in listOf("no-cache", "max-age=0", "public, max-age=31536000", "max-age=604800, public", null)) {
                for (age in listOf(hour, 3 * day, 8 * day, 30 * day, 400 * day)) {
                    val copy = NetworkResponse(
                        code = 200,
                        requestMillis = clock - age,
                        responseMillis = clock - age,
                        headers = NetworkHeaders.Builder().apply {
                            rule?.let { set("Cache-Control", it) }
                            set("ETag", "\"e\"")
                        }.build(),
                    )
                    for (request in listOf(NetworkRequest(url), NetworkRequest(url = url, headers = noCache))) {
                        val old = before(copy, request)
                        val new = weekly.read(copy, request, options)
                        val case = "$url $rule ${age / hour}h ${request.headers}"
                        assertEquals(case, old.response != null, new.response != null)
                        assertEquals(case, old.request?.headers, new.request?.headers)
                    }
                }
            }
        }
    }

    @Test fun aReturnAfterAnHourAwayCountsAsANewStart() {
        val state = PosterUpdateState()
        state.started(nowMillis = 1_000, nowElapsedMs = 10)
        state.wentAway(nowMillis = 2_000)
        state.cameBack(nowMillis = 2_000 + 59 * 60 * 1000L, nowElapsedMs = 500)
        assertEquals(1_000, state.startMillis)
        state.wentAway(nowMillis = 10_000)
        state.cameBack(nowMillis = 10_000 + hour, nowElapsedMs = 900)
        assertEquals(10_000 + hour, state.startMillis)
        assertEquals(Long.MIN_VALUE, state.memoryStoredBefore())
        state.interval = PosterUpdateInterval.EVERY_START
        assertEquals(900, state.memoryStoredBefore())
        state.cameBack(nowMillis = 100 * hour, nowElapsedMs = 2_000)
        assertEquals("a resume without going away first is not a new start", 900, state.memoryStoredBefore())
    }

    @Test fun storedChoiceFallsBackToWeekly() {
        assertEquals(PosterUpdateInterval.WEEKLY, PosterUpdateInterval.DEFAULT)
        assertEquals(PosterUpdateInterval.WEEKLY, PosterUpdateInterval.fromStorage(null))
        assertEquals(PosterUpdateInterval.WEEKLY, PosterUpdateInterval.fromStorage("hourly"))
        PosterUpdateInterval.entries.forEach { assertEquals(it, PosterUpdateInterval.fromStorage(it.storageValue)) }
    }

    @Test fun memoryFollowsTheChoiceAndWeeklyKeepsTheWeek() {
        assertEquals(IMAGE_FRESHNESS_FLOOR_MS, posterMemoryLimitMs(PosterUpdateInterval.WEEKLY))
        assertEquals(day, posterMemoryLimitMs(PosterUpdateInterval.DAILY))
        assertEquals(day, posterMemoryLimitMs(PosterUpdateInterval.EVERY_START))
        assertEquals(30 * day, posterMemoryLimitMs(PosterUpdateInterval.MONTHLY))
    }
}
