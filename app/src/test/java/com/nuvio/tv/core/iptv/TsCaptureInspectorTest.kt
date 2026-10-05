package com.nuvio.tv.core.iptv

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class TsCaptureInspectorTest {
    private fun fixture(n: Int = 0): ByteArray = requireNotNull(javaClass.getResourceAsStream("/iptv-ts/segment0$n.ts")).use { it.readBytes() }
    private fun inspect(b: ByteArray) = TsCaptureInspector().inspect(b.inputStream())
    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 255
    private fun pid(b: ByteArray, i: Int) = (u(b, i + 1) and 31) * 256 + u(b, i + 2)
    private fun payload(b: ByteArray, i: Int) = i + 4 + if (u(b, i + 3) and 32 != 0) 1 + u(b, i + 4) else 0
    private fun packets(b: ByteArray, pid: Int) = (b.indices step 188).filter { pid(b, it) == pid }
    private fun rejected(b: ByteArray, reason: TsCaptureRejection? = null) {
        try { inspect(b); fail("Unsupported/damaged segment accepted") }
        catch (e: TsCaptureInspectionException) { if (reason != null) assertEquals(reason, e.reason) }
    }

    @Test fun independentlyGeneratedSegmentsExposeActualPtsAndStableInitialization() {
        val entries = (0..2).map { inspect(fixture(it)) }
        entries.forEachIndexed { i, e ->
            assertEquals(1, e.program); assertEquals(4096, e.pmtPid)
            assertEquals(256, e.videoPid); assertEquals(257, e.audioPid)
            assertEquals(50, e.videoFrames); assertEquals(3600L, e.videoStep90k)
            assertEquals(127920L + i * 180000, e.videoFirstPts90k)
            assertEquals(e.videoFirstPts90k + 49 * 3600, e.videoLastPts90k)
            assertEquals(48000, e.audioSampleRate); assertEquals(2, e.audioChannels)
            assertEquals(listOf(95, 94, 94)[i], e.audioFrames)
            assertEquals(listOf(126000L, 308400L, 488880L)[i], e.audioFirstPts90k)
            assertEquals(listOf(308400L, 488880L, 669360L)[i], e.audioEndPts90k)
            assertEquals(entries[0].initializationSha256, e.initializationSha256)
        }
        assertEquals(3, entries.map { it.sha256 }.distinct().size)
    }

    @Test fun rejectsMissingPatOrPmtEvenWhenIdrAndManifestCouldLookIndependent() {
        val b = fixture()
        for (removed in listOf(0, 4096)) rejected((b.indices step 188).filter { pid(b, it) != removed }
            .flatMap { b.copyOfRange(it, it + 188).asIterable() }.toByteArray(), TsCaptureRejection.PROGRAM)
    }

    @Test fun rejectsBadPsiCrcAndChangingProgram() {
        val b = fixture(); val pat = payload(b, packets(b, 0).first()) + 1
        b[pat + 9] = 2; rejected(b, TsCaptureRejection.PROGRAM)
    }

    @Test fun rejectsLossDuplicatesScramblingErrorFlagsAndMisalignment() {
        val b = fixture(); val at = packets(b, 256)[2]
        rejected(b.copyOfRange(1, b.size), TsCaptureRejection.TRANSPORT)
        rejected(b.copyOf(b.size - 1), TsCaptureRejection.TRANSPORT)
        rejected(b.copyOfRange(0, at) + b.copyOfRange(at + 188, b.size), TsCaptureRejection.TRANSPORT)
        rejected(b.copyOfRange(0, at) + b.copyOfRange(at, at + 188) + b.copyOfRange(at, b.size), TsCaptureRejection.TRANSPORT)
        rejected(b.copyOf().also { it[at + 1] = (u(it, at + 1) or 0x80).toByte() }, TsCaptureRejection.TRANSPORT)
        rejected(b.copyOf().also { it[at + 3] = (u(it, at + 3) or 0x80).toByte() }, TsCaptureRejection.TRANSPORT)
    }

    @Test fun rejectsPartialPesEntryInsteadOfScanningForwardToALaterIdr() {
        val b = fixture(); val at = packets(b, 256).first()
        rejected(b.copyOf().also { it[at + 1] = (u(it, at + 1) and 0xbf).toByte() }, TsCaptureRejection.PES)
    }

    private fun firstNal(b: ByteArray, type: Int): Int = (2 until b.size).first {
        b[it - 2] == 0.toByte() && b[it - 1] == 0.toByte() && b[it] == 1.toByte() &&
            it + 1 < b.size && u(b, it + 1) and 31 == type
    } + 1

    @Test fun rejectsMissingInitializationNonIdrAndUnsupportedCodecProfile() {
        val b = fixture()
        rejected(b.copyOf().also { it[firstNal(it, 7)] = 6 })
        rejected(b.copyOf().also { it[firstNal(it, 8)] = 6 }, TsCaptureRejection.INITIALIZATION)
        rejected(b.copyOf().also { it[firstNal(it, 5)] = 0x41 }, TsCaptureRejection.VIDEO)
        rejected(b.copyOf().also { it[firstNal(it, 7) + 1] = 100 }, TsCaptureRejection.INITIALIZATION)
    }

    @Test fun rejectsMissingPtsMarkersBackwardAndUnevenSampleTiming() {
        val b = fixture()
        val starts = packets(b, 256).filter { u(b, it + 1) and 64 != 0 }.map { payload(b, it) }
        rejected(b.copyOf().also { it[starts[0] + 9] = (u(it, starts[0] + 9) and 254).toByte() }, TsCaptureRejection.TIMESTAMP)
        rejected(b.copyOf().also { System.arraycopy(it, starts[0] + 9, it, starts[1] + 9, 5) }, TsCaptureRejection.TIMESTAMP)
        rejected(b.copyOf().also { it[starts[2] + 13] = (u(it, starts[2] + 13) xor 2).toByte() }, TsCaptureRejection.TIMESTAMP)
    }

    @Test fun rejectsTruncatedAudioPesAndChangedAdtsConfiguration() {
        val b = fixture(); val starts = packets(b, 257).filter { u(b, it + 1) and 64 != 0 }.map { payload(b, it) }
        rejected(b.copyOf().also { it[starts[0] + 5] = (u(it, starts[0] + 5) xor 1).toByte() }, TsCaptureRejection.PES)
        rejected(b.copyOf().also { val at = starts[1] + 9 + u(it, starts[1] + 8); it[at + 2] = ((u(it, at + 2) and 0xc3) or 16).toByte() }, TsCaptureRejection.INITIALIZATION)
    }

    @Test fun handlesPtsWrapWithoutInventingASegmentTimeOrigin() {
        val b = fixture(); val shift = (1L shl 33) - 180000
        for (stream in listOf(256, 257)) for (packet in packets(b, stream)) {
            if (u(b, packet + 1) and 64 == 0) continue
            val at = payload(b, packet) + 9
            val before = ((u(b, at).toLong() and 14) shl 29) or (u(b, at + 1).toLong() shl 22) or
                ((u(b, at + 2).toLong() and 254) shl 14) or (u(b, at + 3).toLong() shl 7) or (u(b, at + 4).toLong() shr 1)
            val pts = (before + shift) and ((1L shl 33) - 1)
            b[at] = (0x21 or ((pts shr 29).toInt() and 14)).toByte()
            b[at + 1] = (pts shr 22).toByte(); b[at + 2] = (((pts shr 14).toInt() and 254) or 1).toByte()
            b[at + 3] = (pts shr 7).toByte(); b[at + 4] = ((pts.toInt() shl 1) or 1).toByte()
        }
        val e = inspect(b)
        assertTrue(e.videoFirstPts90k < (1L shl 33)); assertTrue(e.videoLastPts90k > (1L shl 33))
        assertEquals(50, e.videoFrames); assertEquals(3600L, e.videoStep90k)
    }

    @Test fun byteAndPesBudgetsFailClosedAndCancellationDoesNotCloseCallerInput() {
        val bytes = fixture()
        for (inspector in listOf(TsCaptureInspector(188), TsCaptureInspector(maxPesBytes = 188))) {
            try { inspector.inspect(bytes.inputStream()); fail() } catch (e: TsCaptureInspectionException) { assertEquals(TsCaptureRejection.LIMIT, e.reason) }
        }
        var closed = false
        val input = object : ByteArrayInputStream(bytes) { override fun close() { closed = true; super.close() } }
        var checks = 0
        try { TsCaptureInspector().inspect(input) { if (++checks == 10) throw InterruptedException() }; fail() }
        catch (_: InterruptedException) { }
        assertFalse(closed)
        try { TsCaptureInspector().inspect(object : InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int) = 0
        }); fail() } catch (e: TsCaptureInspectionException) { assertEquals(TsCaptureRejection.TRANSPORT, e.reason) }
    }
    @Test fun configurationChangesAndMissingAudCannotBecomeSilentContinuity() {
        val first = fixture(0); val second = fixture(1)
        assertEquals(100, inspect(first + second).videoFrames)
        val changed = second.copyOf()
        changed[firstNal(changed, 7) + 2] = (u(changed, firstNal(changed, 7) + 2) xor 0x20).toByte()
        rejected(first + changed, TsCaptureRejection.INITIALIZATION)
        rejected(first.copyOf().also { it[firstNal(it, 9)] = 6 }, TsCaptureRejection.INITIALIZATION)
    }

    @Test fun truncatedAndMalformedPrefixesFailWithBoundedDomainErrors() {
        val b = fixture()
        for (size in 0..512) rejected(b.copyOf(size))
        for (at in 0..187) {
            val copy = b.copyOf(188)
            copy[at] = (u(copy, at) xor 255).toByte()
            rejected(copy)
        }
    }

}
