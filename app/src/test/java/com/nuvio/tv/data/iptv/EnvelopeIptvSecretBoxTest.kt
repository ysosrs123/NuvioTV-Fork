package com.nuvio.tv.data.iptv

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class EnvelopeIptvSecretBoxTest {
    private class CountingRoot : IptvSecretBox {
        var seals = 0; var opens = 0
        override fun seal(context: String, plaintext: String): ByteArray { seals++; return byteArrayOf(1) + "$context|$plaintext".toByteArray() }
        override fun open(context: String, ciphertext: ByteArray): String {
            opens++
            val text = String(ciphertext, 1, ciphertext.size - 1)
            if (ciphertext[0] != 1.toByte() || !text.startsWith("$context|")) throw IOException("wrong context")
            return text.removePrefix("$context|")
        }
    }

    @Test fun manyRowsUseTheRootKeyOnceAndOpenAfterRestart() {
        val root = CountingRoot()
        val sealed = EnvelopeIptvSecretBox(root).let { box -> (0 until 500).map { box.seal("channel:$it", "row $it") } }
        assertEquals(1, root.seals)
        assertTrue(sealed.none { String(it, Charsets.ISO_8859_1).contains("row ") })
        val restarted = EnvelopeIptvSecretBox(root)
        sealed.forEachIndexed { index, bytes -> assertEquals("row $index", restarted.open("channel:$index", bytes)) }
        assertEquals(1, root.opens)
    }

    @Test fun contextAndTamperingAreRejected() {
        val box = EnvelopeIptvSecretBox(CountingRoot())
        val sealed = box.seal("channel:1", "secret")
        assertThrows(IOException::class.java) { box.open("channel:2", sealed) }
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(IOException::class.java) { box.open("channel:1", tampered) }
        assertThrows(IOException::class.java) { box.open("channel:1", sealed.copyOf(20)) }
    }

    @Test fun reusedCipherRecoversAfterARejectedValueAndAcrossThreads() {
        val box = EnvelopeIptvSecretBox(CountingRoot())
        val first = box.seal("channel:1", "one")
        assertThrows(IOException::class.java) { box.open("channel:1", first.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }) }
        val second = box.seal("channel:2", "two")
        assertEquals("one", box.open("channel:1", first))
        assertEquals("two", box.open("channel:2", second))
        val results = java.util.concurrent.ConcurrentHashMap<Int, String>()
        (0 until 4).map { worker -> Thread { repeat(200) { results[worker * 1000 + it] = box.open("w$worker:$it", box.seal("w$worker:$it", "v$it")) } } }
            .onEach(Thread::start).forEach(Thread::join)
        assertEquals(800, results.size)
        assertTrue(results.all { (key, value) -> value == "v${key % 1000}" })
    }

    @Test fun olderRootSealedValuesStillOpen() {
        val root = CountingRoot()
        val legacy = root.seal("connection", "value")
        assertEquals("value", EnvelopeIptvSecretBox(root).open("connection", legacy))
    }
}
