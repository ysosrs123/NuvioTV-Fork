package com.nuvio.tv.core.player.avi

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Log
import androidx.media3.common.util.ParsableByteArray
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class StreamFormatChunkTest {

    @Before
    fun silenceLog() = Log.setLogLevel(Log.LOG_LEVEL_OFF)

    @After
    fun restoreLog() = Log.setLogLevel(Log.LOG_LEVEL_ALL)

    private fun bytes(hex: String): ByteArray =
        hex.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun waveFormat(hex: String) =
        StreamFormatChunk.parseFrom(C.TRACK_TYPE_AUDIO, ParsableByteArray(bytes(hex))) as StreamFormatChunk?

    private val extensibleHead = "fe ff 06 00 80 bb 00 00 40 19 01 00 00 12 10 00"
    private val guidTail = "00 00 00 00 10 00 80 00 00 aa 00 38 9b 71"

    @Test
    fun readsAacFromAnExtensibleFormatWithItsConfig() {
        val chunk = waveFormat("$extensibleHead 18 00 10 00 3f 00 00 00 ff 00 $guidTail 11 b0")!!
        assertEquals(MimeTypes.AUDIO_AAC, chunk.format.sampleMimeType)
        assertEquals(6, chunk.format.channelCount)
        assertEquals(48_000, chunk.format.sampleRate)
        assertArrayEquals(bytes("11 b0"), chunk.format.initializationData.single())
    }

    @Test
    fun readsPcmFromAnExtensibleFormat() {
        val chunk = waveFormat("$extensibleHead 16 00 10 00 3f 00 00 00 01 00 $guidTail")!!
        assertEquals(MimeTypes.AUDIO_RAW, chunk.format.sampleMimeType)
        assertEquals(C.ENCODING_PCM_16BIT, chunk.format.pcmEncoding)
    }

    @Test
    fun ignoresAnUnknownSubFormatAndAShortExtension() {
        assertNull(waveFormat("$extensibleHead 16 00 10 00 3f 00 00 00 03 00 $guidTail"))
        assertNull(waveFormat("$extensibleHead 02 00 11 b0"))
    }

    @Test
    fun plainAacKeepsItsConfig() {
        val chunk = waveFormat("ff 00 02 00 80 bb 00 00 00 7d 00 00 00 06 10 00 02 00 11 90")!!
        assertEquals(MimeTypes.AUDIO_AAC, chunk.format.sampleMimeType)
        assertArrayEquals(bytes("11 90"), chunk.format.initializationData.single())
    }
}
