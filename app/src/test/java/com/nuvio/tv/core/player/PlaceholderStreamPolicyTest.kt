package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides whether a "stream" is really a provider error card.
 *
 * The two rejection cases below are real provider error files; the
 * season-pack case is the trap that rules out comparing actual
 * bytes against the advertised size as a ratio.
 */
class PlaceholderStreamPolicyTest {

    private val minute = 60_000L

    private fun rejected(verdict: PlaceholderStreamPolicy.Verdict) =
        verdict is PlaceholderStreamPolicy.Verdict.Reject

    // ------------------------------------------------------------ field cases

    @Test
    fun `rejects the Premiumize error card observed on device`() {
        // 406 KB, 1280x720, 17.876 fps avc, served for a 114 minute feature.
        val verdict = PlaceholderStreamPolicy.evaluate(
            contentLengthBytes = 406L * 1024L,
            durationMs = null,
            expectedRuntimeMs = 114 * minute
        )
        assertTrue(rejected(verdict))
        assertEquals(
            PlaceholderStreamPolicy.Reason.ImplausibleSize,
            (verdict as PlaceholderStreamPolicy.Verdict.Reject).reason
        )
    }

    @Test
    fun `rejects a StremThru no-matching-file placeholder`() {
        assertTrue(
            rejected(
                PlaceholderStreamPolicy.evaluate(
                    contentLengthBytes = 2L * 1024L * 1024L,
                    durationMs = null,
                    expectedRuntimeMs = 50 * minute
                )
            )
        )
    }

    // -------------------------------------------------- real content survives

    @Test
    fun `accepts a 4K remux`() {
        assertTrue(
            !rejected(
                PlaceholderStreamPolicy.evaluate(
                    contentLengthBytes = 68L * 1024L * 1024L * 1024L,
                    durationMs = 114 * minute,
                    expectedRuntimeMs = 114 * minute
                )
            )
        )
    }

    @Test
    fun `accepts a modest but real 720p episode`() {
        assertTrue(
            !rejected(
                PlaceholderStreamPolicy.evaluate(
                    contentLengthBytes = 180L * 1024L * 1024L,
                    durationMs = 42 * minute,
                    expectedRuntimeMs = 45 * minute
                )
            )
        )
    }

    @Test
    fun `accepts one episode served out of a much larger season pack`() {
        // The advertised size would be the whole 60 GB pack. Comparing actual
        // against advertised as a ratio would reject this; the absolute floor
        // does not care what was advertised.
        assertTrue(
            !rejected(
                PlaceholderStreamPolicy.evaluate(
                    contentLengthBytes = 6L * 1024L * 1024L * 1024L,
                    durationMs = 50 * minute,
                    expectedRuntimeMs = 52 * minute
                )
            )
        )
    }

    // ------------------------------------------------------ the runtime guard

    @Test
    fun `never judges a title with no known runtime`() {
        assertTrue(
            !rejected(
                PlaceholderStreamPolicy.evaluate(
                    contentLengthBytes = 400L * 1024L,
                    durationMs = 30_000L,
                    expectedRuntimeMs = null
                )
            )
        )
    }

    @Test
    fun `never judges short content such as a library extra`() {
        assertTrue(
            !rejected(
                PlaceholderStreamPolicy.evaluate(
                    contentLengthBytes = 400L * 1024L,
                    durationMs = 2 * minute,
                    expectedRuntimeMs = 2 * minute
                )
            )
        )
    }

    @Test
    fun `judges exactly at the runtime guard and not one millisecond below`() {
        assertTrue(
            rejected(
                PlaceholderStreamPolicy.evaluate(
                    contentLengthBytes = 400L * 1024L,
                    durationMs = null,
                    expectedRuntimeMs = PlaceholderStreamPolicy.MIN_GUARDED_RUNTIME_MS
                )
            )
        )
        assertTrue(
            !rejected(
                PlaceholderStreamPolicy.evaluate(
                    contentLengthBytes = 400L * 1024L,
                    durationMs = null,
                    expectedRuntimeMs = PlaceholderStreamPolicy.MIN_GUARDED_RUNTIME_MS - 1
                )
            )
        )
    }

    // ------------------------------------------------------------- boundaries

    @Test
    fun `rejects exactly at the byte floor and accepts one byte above`() {
        assertTrue(
            rejected(
                PlaceholderStreamPolicy.evaluate(
                    contentLengthBytes = PlaceholderStreamPolicy.MIN_PLAUSIBLE_BYTES,
                    durationMs = null,
                    expectedRuntimeMs = 114 * minute
                )
            )
        )
        assertTrue(
            !rejected(
                PlaceholderStreamPolicy.evaluate(
                    contentLengthBytes = PlaceholderStreamPolicy.MIN_PLAUSIBLE_BYTES + 1,
                    durationMs = null,
                    expectedRuntimeMs = 114 * minute
                )
            )
        )
    }

    @Test
    fun `treats an absent or zero content length as unknown`() {
        assertTrue(
            !rejected(
                PlaceholderStreamPolicy.evaluate(null, null, 114 * minute)
            )
        )
        assertTrue(
            !rejected(
                PlaceholderStreamPolicy.evaluate(0L, null, 114 * minute)
            )
        )
    }

    // -------------------------------------------------------- duration backstop

    @Test
    fun `rejects a thirty second clip standing in for a feature`() {
        val verdict = PlaceholderStreamPolicy.evaluate(
            contentLengthBytes = null,
            durationMs = 30_000L,
            expectedRuntimeMs = 114 * minute
        )
        assertTrue(rejected(verdict))
        assertEquals(
            PlaceholderStreamPolicy.Reason.ImplausibleDuration,
            (verdict as PlaceholderStreamPolicy.Verdict.Reject).reason
        )
    }

    @Test
    fun `accepts a short but not absurdly short cut of a feature`() {
        // 40 minutes of a 114 minute film fails the ratio test but passes the
        // absolute test. Both must agree, so this is accepted -- a mis-scraped
        // runtime cannot on its own cause a rejection.
        assertTrue(
            !rejected(
                PlaceholderStreamPolicy.evaluate(
                    contentLengthBytes = null,
                    durationMs = 40 * minute,
                    expectedRuntimeMs = 114 * minute
                )
            )
        )
    }

    @Test
    fun `accepts a full length feature`() {
        assertTrue(
            !rejected(
                PlaceholderStreamPolicy.evaluate(
                    contentLengthBytes = null,
                    durationMs = 100 * minute,
                    expectedRuntimeMs = 114 * minute
                )
            )
        )
    }

    // --------------------------------------------- size against decoded length

    @Test
    fun `rejects an error card read against a film's size with no runtime`() {
        // 19,717,426,246 bytes declared, 8 s decoded: about 19.7 Gbit/s.
        val verdict = PlaceholderStreamPolicy.evaluate(
            contentLengthBytes = 19_717_426_246L,
            durationMs = 8_000L,
            expectedRuntimeMs = null
        )
        assertEquals(
            PlaceholderStreamPolicy.Reason.ImplausibleBitrate,
            (verdict as PlaceholderStreamPolicy.Verdict.Reject).reason
        )
    }

    @Test
    fun `accepts real short clips with normal sizes and no runtime`() {
        val clips = listOf(
            400L * 1024L to 30_000L,                      // small status clip, 109 kbit/s
            375L * 1024L * 1024L to 30_000L,              // 30 s at about 105 Mbit/s
            2_200L * 1024L * 1024L to 2 * minute + 50_000L // UHD remux trailer, about 108 Mbit/s
        )
        for ((bytes, durationMs) in clips) {
            assertTrue(
                !rejected(PlaceholderStreamPolicy.evaluate(bytes, durationMs, expectedRuntimeMs = null))
            )
        }
    }

    @Test
    fun `bitrate check sits at one gigabit per second`() {
        val tenSeconds = 10_000L
        val atLimit = PlaceholderStreamPolicy.MAX_PLAUSIBLE_BITS_PER_SECOND / 8L * 10L
        assertTrue(!rejected(PlaceholderStreamPolicy.evaluate(atLimit, tenSeconds, null)))
        assertTrue(rejected(PlaceholderStreamPolicy.evaluate(atLimit + 1_000L, tenSeconds, null)))
    }

    @Test
    fun `bitrate check never judges a length of three minutes or more`() {
        assertTrue(
            !rejected(
                PlaceholderStreamPolicy.evaluate(
                    contentLengthBytes = 68L * 1024L * 1024L * 1024L,
                    durationMs = PlaceholderStreamPolicy.MIN_PLAUSIBLE_DURATION_MS,
                    expectedRuntimeMs = null
                )
            )
        )
    }

    @Test
    fun `bitrate check needs both a size and a length`() {
        assertTrue(!rejected(PlaceholderStreamPolicy.evaluate(19_717_426_246L, null, null)))
        assertTrue(!rejected(PlaceholderStreamPolicy.evaluate(null, 8_000L, null)))
        assertTrue(!rejected(PlaceholderStreamPolicy.evaluate(19_717_426_246L, 0L, null)))
    }

    @Test
    fun `a bitrate reject stops a film or episode but only logs for other content`() {
        val digger = PlaceholderStreamPolicy.evaluate(
            contentLengthBytes = 19_717_426_246L,
            durationMs = 8_000L,
            expectedRuntimeMs = null
        )
        assertTrue(PlaceholderStreamPolicy.stopsAtReady(digger, filmOrEpisode = true))
        assertTrue(!PlaceholderStreamPolicy.stopsAtReady(digger, filmOrEpisode = false))
    }

    @Test
    fun `size rejects still stop at ready and duration rejects wait for the tick`() {
        val size = PlaceholderStreamPolicy.evaluate(406L * 1024L, null, 114 * minute)
        val duration = PlaceholderStreamPolicy.evaluate(null, 30_000L, 114 * minute)
        assertTrue(PlaceholderStreamPolicy.stopsAtReady(size, filmOrEpisode = false))
        assertTrue(!PlaceholderStreamPolicy.stopsAtReady(duration, filmOrEpisode = true))
        assertTrue(!PlaceholderStreamPolicy.stopsAtReady(PlaceholderStreamPolicy.Verdict.Accept, filmOrEpisode = true))
    }

    @Test
    fun `first response length rejects a 4_5 MB notice for a long title`() {
        val verdict = PlaceholderStreamPolicy.rejectOnFirstLength(4_718_592L, 120 * minute, alreadyDecided = false)
        assertEquals(PlaceholderStreamPolicy.Reason.ImplausibleSize, verdict?.reason)
    }

    @Test
    fun `first response length leaves real files, short titles and decided streams alone`() {
        assertNull(PlaceholderStreamPolicy.rejectOnFirstLength(7_054_000_000L, 120 * minute, false))
        assertNull(PlaceholderStreamPolicy.rejectOnFirstLength(4_718_592L, 12 * minute, false))
        assertNull(PlaceholderStreamPolicy.rejectOnFirstLength(4_718_592L, null, false))
        assertNull(PlaceholderStreamPolicy.rejectOnFirstLength(4_718_592L, 120 * minute, alreadyDecided = true))
        assertNull(PlaceholderStreamPolicy.rejectOnFirstLength(PlaceholderStreamPolicy.MIN_PLAUSIBLE_BYTES + 1, 120 * minute, false))
    }
}
