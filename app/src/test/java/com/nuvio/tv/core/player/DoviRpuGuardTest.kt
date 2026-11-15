package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.DoviRpuGuard.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoviRpuGuardTest {
    private data class Case(val name: String, val verdict: Verdict, val profile: Int, val escaped: Boolean, val bytes: ByteArray)

    // The same cases the native guard is checked against on the PC.
    private val cases: List<Case> by lazy {
        javaClass.classLoader!!.getResourceAsStream("dovi/rpu-guard-cases.txt")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val (name, verdict, profile, escaped, hex) = line.trim().split(Regex("\\s+"))
                Case(name, Verdict.entries[verdict.toInt()], profile.toInt(), escaped == "1", hex.hexToBytes())
            }
    }

    private fun String.hexToBytes() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun case(name: String) = cases.single { it.name == name }

    @Test
    fun `every shared case gets the expected verdict and profile`() {
        assertTrue(cases.size >= 27)
        for (c in cases) {
            assertEquals(c.name, DoviRpuGuard.Result(c.verdict, c.profile), DoviRpuGuard.check(c.bytes, escaped = c.escaped))
        }
    }

    @Test
    fun `valid RPUs of profiles 5, 7 and 8 pass and report their profile`() {
        assertEquals(DoviRpuGuard.Result(Verdict.OK, 5), DoviRpuGuard.check(case("p5").bytes))
        assertEquals(DoviRpuGuard.Result(Verdict.OK, 7), DoviRpuGuard.check(case("p7").bytes))
        assertEquals(DoviRpuGuard.Result(Verdict.OK, 8), DoviRpuGuard.check(case("p8").bytes))
    }

    @Test
    fun `the shapes that abort libdovi are refused`() {
        assertEquals(Verdict.TOO_SHORT, DoviRpuGuard.check(case("mostly-zero").bytes, escaped = false).verdict)
        assertEquals(Verdict.BAD_CURVE, DoviRpuGuard.check(case("curve-type-2").bytes).verdict)
        assertEquals(Verdict.BAD_CURVE, DoviRpuGuard.check(case("linear-polynomial").bytes).verdict)
        assertEquals(Verdict.BAD_PIECES, DoviRpuGuard.check(case("pivots-1000").bytes).verdict)
        assertEquals(Verdict.BAD_BLOCKS, DoviRpuGuard.check(case("block-count-huge").bytes).verdict)
        assertEquals(Verdict.BAD_BLOCK_SIZE, DoviRpuGuard.check(case("l8-length-11").bytes).verdict)
    }

    @Test
    fun `any changed byte fails the checksum`() {
        val good = case("p8").bytes
        for (i in 8 until good.size - 6 step 7) {
            val bad = good.copyOf()
            bad[i] = (bad[i].toInt() xor 0x40).toByte()
            val verdict = DoviRpuGuard.check(bad).verdict
            assertTrue("byte $i: $verdict", verdict != Verdict.OK)
        }
    }

    @Test
    fun `an RPU inside a larger buffer is read at its offset`() {
        val rpu = case("p8").bytes
        val sample = ByteArray(10) { 0x55 } + rpu + ByteArray(6) { 0x66 }
        assertEquals(DoviRpuGuard.Result(Verdict.OK, 8), DoviRpuGuard.check(sample, 10, rpu.size))
        assertEquals(Verdict.TOO_SHORT, DoviRpuGuard.check(sample, sample.size - 5, 20).verdict)
        assertEquals(Verdict.TOO_SHORT, DoviRpuGuard.check(sample, -1, 30).verdict)
    }

    @Test
    fun `trailing zero bytes after the end byte are allowed`() {
        val padded = case("p8").bytes + ByteArray(8)
        assertEquals(DoviRpuGuard.Result(Verdict.OK, 8), DoviRpuGuard.check(padded))
    }

    @Test
    fun `oversized input is refused without reading it`() {
        val big = case("p8").bytes.copyOf(DoviRpuGuard.MAX_RPU_BYTES + 1)
        assertEquals(Verdict.TOO_LARGE, DoviRpuGuard.check(big).verdict)
    }

    @Test
    fun `checksum matches the MPEG-2 CRC check value`() {
        val data = "123456789".toByteArray()
        assertEquals(0x0376E6E7, DoviRpuGuard.crc32Mpeg2(data, 0, data.size))
    }
}
