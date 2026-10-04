package com.nuvio.tv.core.party

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Bip340Test {

    private class Vector(
        val index: String,
        val secretKey: String,
        val publicKey: String,
        val auxRand: String,
        val message: String,
        val signature: String,
        val valid: Boolean,
    )

    private fun vectors(): List<Vector> {
        val stream = javaClass.classLoader!!.getResourceAsStream("party/bip340-test-vectors.csv")!!
        return stream.bufferedReader().readLines().drop(1).filter { it.isNotBlank() }.map { line ->
            val cells = line.split(',')
            Vector(cells[0], cells[1], cells[2], cells[3], cells[4], cells[5], cells[6] == "TRUE")
        }
    }

    private fun bytes(hex: String): ByteArray = PartyCrypto.unhex(hex.lowercase())!!

    @Test
    fun `official vectors verify as specified`() {
        val all = vectors()
        assertEquals(19, all.size)
        all.forEach { vector ->
            val result = Bip340.verify(bytes(vector.message), bytes(vector.publicKey), bytes(vector.signature))
            assertEquals("vector ${vector.index}", vector.valid, result)
        }
    }

    @Test
    fun `official signing vectors produce the published signatures`() {
        val signing = vectors().filter { it.secretKey.isNotEmpty() }
        assertEquals(8, signing.size)
        signing.forEach { vector ->
            val secret = bytes(vector.secretKey)
            assertEquals("vector ${vector.index}", vector.publicKey.lowercase(), PartyCrypto.hex(Bip340.publicKey(secret)))
            val signature = Bip340.sign(bytes(vector.message), secret, bytes(vector.auxRand))
            assertEquals("vector ${vector.index}", vector.signature.lowercase(), PartyCrypto.hex(signature))
        }
    }

    @Test
    fun `random identities sign and verify`() {
        repeat(5) {
            val identity = PartyIdentity.random()
            val message = PartyCrypto.sha256("message $it".toByteArray())
            val signature = Bip340.sign(message, identity.secretKey, ByteArray(32) { i -> (i + it).toByte() })
            assertTrue(Bip340.verify(message, PartyCrypto.unhex(identity.publicKeyHex)!!, signature))
        }
    }
}
