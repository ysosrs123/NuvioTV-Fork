package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Test

/** Each picture check is one line in the log: where, how far apart, the scores and what it counts as. */
class ReuseCheckLineTest {
    @Test
    fun aSameFrameMatchShowsItsScores() {
        assertEquals(
            "reuse check at 1240 s (keyframes 0 ms apart, same frame): score 0.97 (either side 0.41 / -) - a match",
            ReleaseMatch.checkLine(1240, 0, 0.97f, 0.41f, null, ReleaseMatch.Outcome.PASS),
        )
    }

    @Test
    fun aWidePairAndAMissingPictureSayWhatTheyCountAs() {
        assertEquals(
            "reuse check at 30 s (keyframes 640 ms apart): score 0.52 (either side 0.50 / 0.48) - decides nothing",
            ReleaseMatch.checkLine(30, 640_000, 0.52f, 0.50f, 0.48f, ReleaseMatch.Outcome.SKIP),
        )
        assertEquals(
            "reuse check at 30 s (keyframes 2 ms apart, same frame): score no picture (either side - / -) - decides nothing",
            ReleaseMatch.checkLine(30, 2_000, null, null, null, ReleaseMatch.Outcome.SKIP),
        )
        assertEquals(
            "reuse check at 600 s (keyframes 0 ms apart, same frame): score 0.12 (either side 0.10 / 0.08) - a mismatch",
            ReleaseMatch.checkLine(600, 0, 0.12f, 0.10f, 0.08f, ReleaseMatch.Outcome.MISMATCH),
        )
    }
}
