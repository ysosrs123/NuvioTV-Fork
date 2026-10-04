package com.nuvio.tv.core.player

import androidx.media3.extractor.SeekPoint
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AviVideoSeekTableTest {

    // 25 fps XviD, key frames at frames 0, 4342 and 4407 as in the sample around 176 s.
    private val video = AviHeader.Stream(0, "vids", 1, 25, 178_944, 0)
    private val keyFrames = AviIndex.KeyFrames(intArrayOf(0, 4342, 4407), longArrayOf(10_268, 5_000_000, 5_100_000))
    private val table = AviVideoSeekTable.create(video, keyFrames)!!

    @Test
    fun aKeyFrameTimeGivesThatKeyFrameAlone() {
        val points = table.seekPoints(176_280_000)
        assertEquals(SeekPoint(176_280_000, 5_100_000), points.first)
        assertEquals(points.first, points.second)
    }

    @Test
    fun aTimeBetweenKeyFramesGivesBothNeighbours() {
        val points = table.seekPoints(176_249_000)
        assertEquals(SeekPoint(173_680_000, 5_000_000), points.first)
        assertEquals(SeekPoint(176_280_000, 5_100_000), points.second)
        assertEquals(SeekPoint(176_280_000, 5_100_000), table.seekPoints(200_000_000).first)
    }

    @Test
    fun onlyKeyFramesAndPlacesBeforeTheFirstAreSafeStarts() {
        assertTrue(table.isSafeStart(5_100_000))
        assertTrue(table.isSafeStart(10_252))
        // The audio chunk stored just before the key frame at 176.28 s, which the stock seek map picks.
        assertFalse(table.isSafeStart(5_099_000))
    }

    @Test
    fun unusableHeadersOrKeyFramesGiveNoTable() {
        assertNull(AviVideoSeekTable.create(video.copy(rate = 0), keyFrames))
        assertNull(AviVideoSeekTable.create(video.copy(length = 0), keyFrames))
        assertNull(AviVideoSeekTable.create(video, AviIndex.KeyFrames(intArrayOf(5, 2), longArrayOf(1, 2))))
        assertNull(AviVideoSeekTable.create(video, AviIndex.KeyFrames(IntArray(0), LongArray(0))))
    }

    private fun ByteArrayOutputStream.tag(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))

    private fun ByteArrayOutputStream.u32(v: Long) {
        for (shift in 0 until 32 step 8) write(((v shr shift) and 0xFF).toInt())
    }

    @Test
    fun keyFramesAreNumberedAmongAllChunksOfTheVideoStream() {
        val body = ByteArrayOutputStream().apply {
            listOf(
                Triple("01wb", 0x10L, 4L),
                Triple("00dc", 0x10L, 8_076L),
                Triple("00db", 0L, 9_000L),
                Triple("01wb", 0x10L, 9_500L),
                Triple("00dc", 0L, 10_000L),
                Triple("00dc", 0x10L, 11_000L),
            ).forEach { (id, flags, offset) ->
                tag(id); u32(flags); u32(offset); u32(100)
            }
        }.toByteArray()
        val frames = AviIndex.videoKeyFrames(body, 0, body.size, 10_240L, 0)!!
        assertEquals(listOf(0, 3), frames.chunkIndices.toList())
        assertEquals(listOf(18_324L, 21_248L), frames.positions.toList())
        assertNull(AviIndex.videoKeyFrames(body, 0, body.size, 10_240L, 2))
    }
}
