package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.data.repository.SkipInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSkipIntervalsTest {
    private fun segment(kind: ServerSegmentKind, start: Long, end: Long) = ServerSegment(kind, start, end)

    @Test
    fun mapsServerMarkersToSkipIntervals() {
        val intervals = listOf(
            segment(ServerSegmentKind.INTRO, 30_000L, 90_500L),
            segment(ServerSegmentKind.RECAP, 0L, 25_000L),
            segment(ServerSegmentKind.OUTRO, 2_500_000L, 2_640_000L),
            segment(ServerSegmentKind.PREVIEW, 2_640_000L, 2_700_000L)
        ).toSkipIntervals(isMovie = false)

        assertEquals(
            listOf(
                SkipInterval(30.0, 90.5, "intro", "server"),
                SkipInterval(0.0, 25.0, "recap", "server"),
                SkipInterval(2500.0, 2640.0, "outro", "server")
            ),
            intervals
        )
    }

    @Test
    fun movieCreditsAndInvalidMarkers() {
        val intervals = listOf(
            segment(ServerSegmentKind.OUTRO, 6_000_000L, 6_300_000L),
            segment(ServerSegmentKind.INTRO, 10_000L, 11_000L),
            segment(ServerSegmentKind.RECAP, 50_000L, 40_000L),
            segment(ServerSegmentKind.OUTRO, 6_400_000L, 6_500_000L)
        ).toSkipIntervals(isMovie = true)

        assertEquals(listOf(SkipInterval(6000.0, 6300.0, "movie-credits", "server")), intervals)
    }

    @Test
    fun serverMarkersWinPerSegmentAndOtherProvidersFillTheGaps() {
        val server = listOf(SkipInterval(31.0, 92.0, "intro", "server"))
        val introDb = listOf(
            SkipInterval(28.0, 88.0, "intro", "introdb"),
            SkipInterval(2490.0, 2640.0, "outro", "introdb"),
            SkipInterval(6400.0, 6460.0, "post-credits", "introdb")
        )

        assertEquals(
            listOf(server.single(), introDb[1], introDb[2]),
            mergeServerSkipIntervals(server, introDb)
        )
        assertEquals(
            listOf(SkipInterval(6000.0, 6300.0, "movie-credits", "server")),
            mergeServerSkipIntervals(
                listOf(SkipInterval(6000.0, 6300.0, "movie-credits", "server")),
                listOf(SkipInterval(5990.0, 6290.0, "movie-credits", "introdb"))
            )
        )
    }

    @Test
    fun markersOfAnEarlierFileAreDropped() {
        val previous = listOf(SkipInterval(31.0, 92.0, "intro", "server"), SkipInterval(2490.0, 2640.0, "outro", "introdb"))
        assertEquals(listOf(previous[1]), mergeServerSkipIntervals(emptyList(), previous))
        assertTrue(mergeServerSkipIntervals(emptyList(), emptyList()).isEmpty())
    }
}
