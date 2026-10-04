package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * parseVideoFrameRateFromHead picks the VIDEO track's frame rate
 * from a Matroska head, skipping audio tracks, tolerating a torn tail, and
 * rejecting non-Matroska input. Built from MatroskaAfrProbe's own element
 * primitives so it needs no on-device bytes.
 *
 * Upstream: NuvioMedia/NuvioTV. Licensed under GPL-3.0.
 */
class MatroskaAfrProbeVideoFpsTest {

    private fun uintEl(id: Long, value: Long, width: Int): ByteArray {
        val payload = ByteArray(width)
        var v = value
        for (i in width - 1 downTo 0) { payload[i] = (v and 0xFFL).toByte(); v = v ushr 8 }
        return MatroskaAfrProbe.buildElement(id, payload)
    }

    private fun trackEntry(type: Long, defaultDurationNs: Long?, w: Int?, h: Int?): ByteArray {
        var payload = uintEl(0x83L, type, 1)
        if (defaultDurationNs != null) payload += uintEl(0x23E383L, defaultDurationNs, 4)
        if (w != null && h != null) {
            val video = uintEl(0xB0L, w.toLong(), 2) + uintEl(0xBAL, h.toLong(), 2)
            payload += MatroskaAfrProbe.buildElement(0xE0L, video)
        }
        return MatroskaAfrProbe.buildElement(0xAEL, payload)
    }

    private fun mkvHead(tracksPayload: ByteArray): ByteArray {
        val ebml = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_EBML,
            byteArrayOf(0x42.toByte(), 0x86.toByte(), 0x81.toByte(), 0x01)
        )
        val tracks = MatroskaAfrProbe.buildElement(MatroskaAfrProbe.ID_TRACKS, tracksPayload)
        val segment = MatroskaAfrProbe.buildElement(MatroskaAfrProbe.ID_SEGMENT, tracks)
        return ebml + segment
    }

    @Test
    fun `picks 23976 from a single video track`() {
        val head = mkvHead(trackEntry(1L, 41_708_333L, 3840, 2160))
        val hint = MatroskaAfrProbe.parseVideoFrameRateFromHead(head)
        assertNotNull(hint)
        assertEquals(23.976025f, hint!!.rawFps, 0.0005f)
        assertEquals(3840, hint.width)
        assertEquals(2160, hint.height)
    }

    @Test
    fun `skips an audio track ordered before the video track`() {
        // AC-3 track (type 2, DefaultDuration 32 ms => 31.25 fps) FIRST, then video.
        val payload = trackEntry(2L, 32_000_000L, null, null) +
            trackEntry(1L, 41_708_333L, 1920, 1080)
        val hint = MatroskaAfrProbe.parseVideoFrameRateFromHead(mkvHead(payload))
        assertNotNull(hint)
        assertEquals(23.976025f, hint!!.rawFps, 0.0005f)
        assertEquals(1920, hint.width)
    }

    @Test
    fun `returns null when the video track has no DefaultDuration`() {
        val hint = MatroskaAfrProbe.parseVideoFrameRateFromHead(
            mkvHead(trackEntry(1L, null, 1920, 1080))
        )
        assertNull(hint)
    }

    @Test
    fun `tolerates a head truncated after Tracks`() {
        // Full head, then chop the byte array to just past Tracks (no Cluster).
        val head = mkvHead(trackEntry(1L, 41_708_333L, 3840, 2160))
        val truncated = head.copyOf(head.size) // already Cluster-less here
        val hint = MatroskaAfrProbe.parseVideoFrameRateFromHead(truncated)
        assertNotNull(hint)
        assertEquals(23.976025f, hint!!.rawFps, 0.0005f)
    }

    @Test
    fun `rejects non-Matroska bytes`() {
        assertNull(MatroskaAfrProbe.parseVideoFrameRateFromHead(ByteArray(300) { 0x7A }))
        assertNull(MatroskaAfrProbe.parseVideoFrameRateFromHead(byteArrayOf(0, 0, 0, 0)))
    }
}
