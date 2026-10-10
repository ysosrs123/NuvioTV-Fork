package com.nuvio.tv.core.iptv

import java.io.IOException
import java.time.ZoneId
import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SetupRecordingDownloadsTest {
    private val part = SetupByteRange::Part

    @Test fun singleRangesFollowRfc9110() {
        assertEquals(SetupByteRange.Whole, SetupRecordingDownloads.range(null, 1000))
        assertEquals(part(0, 499), SetupRecordingDownloads.range("bytes=0-499", 1000))
        assertEquals(part(500, 999), SetupRecordingDownloads.range("bytes=500-", 1000))
        assertEquals(part(900, 999), SetupRecordingDownloads.range("bytes=-100", 1000))
        assertEquals(part(0, 999), SetupRecordingDownloads.range("bytes=-5000", 1000))
        assertEquals(part(990, 999), SetupRecordingDownloads.range("bytes=990-5000", 1000))
        assertEquals(part(999, 999), SetupRecordingDownloads.range("Bytes=999-999", 1000))
        assertEquals(500L, (SetupRecordingDownloads.range("bytes=0-499", 1000) as SetupByteRange.Part).length)
    }

    @Test fun unsatisfiableRangesAreReported() {
        assertEquals(SetupByteRange.Unsatisfiable, SetupRecordingDownloads.range("bytes=1000-", 1000))
        assertEquals(SetupByteRange.Unsatisfiable, SetupRecordingDownloads.range("bytes=1000-2000", 1000))
        assertEquals(SetupByteRange.Unsatisfiable, SetupRecordingDownloads.range("bytes=-0", 1000))
        assertEquals(SetupByteRange.Unsatisfiable, SetupRecordingDownloads.range("bytes=0-", 0))
        assertEquals(SetupByteRange.Unsatisfiable, SetupRecordingDownloads.range("bytes=-10", 0))
    }

    @Test fun malformedMultipleOrConditionalRangesFallBackToTheWholeFile() {
        for (header in listOf("bytes=", "bytes=-", "bytes=5-2", "bytes=a-b", "bytes=0-1,5-6", "items=0-1", "bytes=+1-2", "bytes=0x1-",
                "bytes=1234567890123456789-", "bytes 0-1", ""))
            assertEquals(header, SetupByteRange.Whole, SetupRecordingDownloads.range(header, 1000))
        assertEquals(SetupByteRange.Whole, SetupRecordingDownloads.range("bytes=0-1", 1000, ifRange = "\"x\""))
    }

    @Test fun contentRangeHeaders() {
        assertEquals("bytes 0-499/1000", SetupRecordingDownloads.contentRange(part(0, 499), 1000))
        assertEquals("bytes */1000", SetupRecordingDownloads.unsatisfiedRange(1000))
    }

    @Test fun fileNamesAreSafeAndCarryTheStartTime() {
        val start = 1_791_400_200_000L
        val utc = ZoneOffset.UTC
        assertEquals("News at Six 07-Oct-26 1910", SetupRecordingDownloads.fileName("News at Six", start, utc))
        assertEquals("a b c d 07-Oct-26 1910", SetupRecordingDownloads.fileName("a/b\\c:d", start, utc))
        assertEquals("Recording 07-Oct-26 1910", SetupRecordingDownloads.fileName(" ../.. ", start, utc))
        assertEquals("Recording 07-Oct-26 1910", SetupRecordingDownloads.fileName("\u0000\n\t", start, utc))
        assertFalse(SetupRecordingDownloads.fileName("x‮y", start, utc).contains('‮'))
        val long = SetupRecordingDownloads.fileName("é".repeat(200), start, utc)
        assertEquals(SetupRecordingDownloads.MAX_NAME + " 07-Oct-26 1910".length, long.codePointCount(0, long.length))
        val emoji = SetupRecordingDownloads.fileName("😀".repeat(100), start, utc)
        assertFalse(Character.isHighSurrogate(emoji[emoji.indexOf(' ') - 1]))
        assertEquals("Late 08-Oct-26 0610", SetupRecordingDownloads.fileName("Late", start, ZoneId.of("Australia/Sydney")))
    }

    @Test fun asciiFallbackAndRfc5987Name() {
        assertEquals("Cafe Muller _ Friends", SetupRecordingDownloads.asciiName("Café Müller & Friends"))
        assertEquals("Recording", SetupRecordingDownloads.asciiName("日本語"))
        assertEquals("Recording", SetupRecordingDownloads.asciiName("\"\";;"))
        val header = SetupRecordingDownloads.disposition("Café \"1\"; x 07-Oct-26 1910", attachment = true)
        assertTrue(header, header.startsWith("attachment; filename=\"Cafe _1_ x 07-Oct-26 1910.ts\"; filename*=UTF-8''"))
        assertTrue(header, header.endsWith("Caf%C3%A9%20%221%22%3B%20x%2007-Oct-26%201910.ts"))
        assertTrue(header.all { it.code in 32..126 })
        assertTrue(SetupRecordingDownloads.disposition("News", attachment = false).startsWith("inline; filename=\"News.ts\""))
    }

    @Test fun signedLinksVerifyOnlyForTheSameRecordingProfileAndTime() {
        val now = 1_791_400_200_000L
        val signer = SetupLinkSigner(ByteArray(32) { it.toByte() })
        val id = "abcd1234-0000"
        val query = signer.query(3, id, now)
        val expiry = query.substringAfter("e=").substringBefore('&')
        val signature = query.substringAfter("t=")
        assertEquals((now + SetupRecordingDownloads.LINK_MILLIS) / 1000, expiry.toLong())
        assertTrue(signer.verify(3, id, expiry, signature, now))
        assertTrue(signer.verify(3, id, expiry, signature, now + SetupRecordingDownloads.LINK_MILLIS - 1000))
        assertFalse(signer.verify(3, id, expiry, signature, now + SetupRecordingDownloads.LINK_MILLIS))
        assertFalse(signer.verify(4, id, expiry, signature, now))
        assertFalse(signer.verify(3, "abcd1234-0001", expiry, signature, now))
        assertFalse(signer.verify(3, id, (expiry.toLong() + 1).toString(), signature, now))
        assertFalse(signer.verify(3, id, expiry, signature.uppercase(), now))
        assertFalse(signer.verify(3, id, expiry, signature.dropLast(1) + if (signature.last() == '0') '1' else '0', now))
        assertFalse(signer.verify(3, id, null, signature, now))
        assertFalse(signer.verify(3, id, expiry, null, now))
        assertFalse(signer.verify(3, id, "-1", signature, now))
        assertFalse(SetupLinkSigner(ByteArray(32) { 7 }).verify(3, id, expiry, signature, now))
        val far = (now / 1000) + SetupRecordingDownloads.LINK_MILLIS / 1000 + 3600
        assertFalse(signer.verify(3, id, far.toString(), signer.sign(3, id, far), now))
        assertFalse(signer.verify(3, "../x", expiry, signer.sign(3, "../x", expiry.toLong()), now))
        assertFalse(signer.toString().contains(signature))
    }

    @Test fun listJsonHasNoPathsAndLinksOnlyAvailableRecordings() {
        val entries = listOf(
            SetupRecordingEntry("rec-0001aaaa", "News", "One", 1000, 60_000, 2048, partial = false, available = true),
            SetupRecordingEntry("rec-0002bbbb", "Film", "Two", 2000, 30_000, null, partial = true, available = false),
        )
        val root = JSONObject(SetupRecordingDownloads.json(entries) { "api/recordings/${it.id}/file?e=1&t=2" })
        val list = root.getJSONArray("recordings")
        assertEquals(2, list.length())
        val first = list.getJSONObject(0)
        assertEquals("done", first.getString("status"))
        assertEquals(2048L, first.getLong("size"))
        assertEquals("api/recordings/rec-0001aaaa/file?e=1&t=2", first.getString("file"))
        val second = list.getJSONObject(1)
        assertEquals("partial", second.getString("status"))
        assertFalse(second.has("size") || second.has("file"))
        assertFalse(second.getBoolean("available"))
        assertEquals(setOf("id", "title", "channel", "start", "duration", "status", "available", "size", "file"), first.keys().asSequence().toSet())
    }

    @Test fun rangeStreamReadsOnlyTheRequestedBytesAndClosesOnce() {
        val data = ByteArray(100_000) { (it % 251).toByte() }
        var closes = 0
        var reads = 0
        val stream = SetupRangeStream({ position, buffer, offset, length ->
            if (position >= data.size) -1 else minOf(length, 777, data.size - position.toInt()).also { System.arraycopy(data, position.toInt(), buffer, offset, it) }
        }, 1000, 50_000, onClose = { closes++ }, onRead = { reads++ })
        val out = stream.readBytes()
        assertArrayEquals(data.copyOfRange(1000, 51_000), out)
        assertTrue(reads > 1)
        stream.close()
        stream.close()
        assertEquals(1, closes)
        assertThrows(IOException::class.java) { stream.read(ByteArray(4), 0, 4) }
    }

    @Test fun rangeStreamEndsEarlyOnAShortFileAndHidesReaderErrors() {
        val short = SetupRangeStream({ _, _, _, _ -> -1 }, 0, 10, onClose = {})
        assertEquals(-1, short.read(ByteArray(10), 0, 10))
        val failing = SetupRangeStream({ _, _, _, _ -> throw IOException("smb://host/share/secret.ts") }, 0, 10, onClose = {})
        val error = assertThrows(IOException::class.java) { failing.read(ByteArray(10), 0, 10) }
        assertFalse(error.message.orEmpty().contains("secret"))
        assertNull(error.cause)
    }
}
