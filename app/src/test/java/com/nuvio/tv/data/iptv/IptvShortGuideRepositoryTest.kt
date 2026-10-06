package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.GuideProgramme
import com.nuvio.tv.core.iptv.GuideTimestamp
import com.nuvio.tv.core.iptv.LocalizedGuideText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IptvShortGuideRepositoryTest {
    private val ref = IptvSourceRef(1, "source")
    private val connection = IptvSourceConnection("https://panel.invalid", "user", "secret")
    private fun programme(start: Long, stop: Long) =
        GuideProgramme("xtream:42", GuideTimestamp(start, 14, ""), GuideTimestamp(stop, 14, ""), listOf(LocalizedGuideText("At $start", null)), emptyList())

    private class Clock(var millis: Long = 1_000_000)

    @Test fun resultsAreCachedForAFewMinutesAndFilteredToNowAndNext() = runBlocking {
        val clock = Clock()
        val calls = mutableListOf<String>()
        val repository = IptvShortGuideRepository({ _, id -> IptvShortGuideTarget(connection, if (id == "a") "42" else "43") },
            { _, stream -> calls += stream; listOf(programme(900_000, 1_100_000), programme(1_100_000, 1_200_000), programme(1_200_000, 1_300_000)) },
            now = { clock.millis }, pause = { clock.millis += it })
        assertEquals(listOf(900_000L, 1_100_000L), repository.nowNext(ref, "a").map { it.start.epochMillis })
        clock.millis = 1_150_000
        assertEquals(listOf(1_100_000L, 1_200_000L), repository.nowNext(ref, "a").map { it.start.epochMillis })
        assertEquals(listOf("42"), calls)
        clock.millis += 5 * 60_000L
        repository.nowNext(ref, "a")
        assertEquals(listOf("42", "42"), calls)
    }

    @Test fun requestsAreSpacedAndOneChannelAtATime() = runBlocking {
        val clock = Clock()
        val pauses = mutableListOf<Long>()
        val repository = IptvShortGuideRepository({ _, id -> IptvShortGuideTarget(connection, id) }, { _, _ -> emptyList() },
            now = { clock.millis }, pause = { pauses += it; clock.millis += it }, requestGapMillis = 1_000)
        repository.nowNext(ref, "1"); clock.millis += 300
        repository.nowNext(ref, "2"); repository.nowNext(ref, "2")
        clock.millis += 5_000
        repository.nowNext(ref, "3")
        assertEquals(listOf(700L), pauses)
    }

    @Test fun nonXtreamChannelsAndFailuresYieldNothingAndFailuresAreNotRetriedAtOnce() = runBlocking {
        var calls = 0
        val repository = IptvShortGuideRepository({ _, id -> if (id == "m3u") null else IptvShortGuideTarget(connection, "1") },
            { _, _ -> calls++; throw MetadataException(MetadataFailure.AUTHENTICATION) }, pause = {})
        assertTrue(repository.nowNext(ref, "m3u").isEmpty())
        assertTrue(repository.nowNext(ref, "x").isEmpty())
        assertTrue(repository.nowNext(ref, "x").isEmpty())
        assertEquals(1, calls)
        repository.clear()
        repository.nowNext(ref, "x")
        assertEquals(2, calls)
    }

    @Test fun cacheIsBoundedAndKeyedBySource() = runBlocking {
        var calls = 0
        val repository = IptvShortGuideRepository({ _, _ -> IptvShortGuideTarget(connection, "1") }, { _, _ -> calls++; emptyList() },
            pause = {}, maxEntries = 2)
        repository.nowNext(ref, "a"); repository.nowNext(IptvSourceRef(2, "source"), "a"); repository.nowNext(ref, "b")
        assertEquals(3, calls)
        repository.nowNext(ref, "b"); repository.nowNext(IptvSourceRef(2, "source"), "a")
        assertEquals(3, calls)
        repository.nowNext(ref, "a")
        assertEquals(4, calls)
        assertFalse(IptvShortGuideTarget(connection, "1").toString().contains("secret"))
    }
}
