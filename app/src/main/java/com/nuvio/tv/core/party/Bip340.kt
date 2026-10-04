package com.nuvio.tv.core.party

import java.math.BigInteger
import java.security.MessageDigest

/** Schnorr signatures over secp256k1 as specified by BIP340. */
internal object Bip340 {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val GX = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val GY = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)
    private val SEVEN = BigInteger.valueOf(7)
    private val EIGHT = BigInteger.valueOf(8)
    private val SQRT_EXPONENT = P.add(BigInteger.ONE).shiftRight(2)

    private class Affine(val x: BigInteger, val y: BigInteger)

    private class Jacobian(val x: BigInteger, val y: BigInteger, val z: BigInteger) {
        val isInfinity: Boolean get() = z.signum() == 0
    }

    private val G = Affine(GX, GY)
    private val INFINITY = Jacobian(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    fun publicKey(secretKey: ByteArray): ByteArray {
        val d = scalar(secretKey)
        return bytes32(requireNotNull(toAffine(multiply(G, d))).x)
    }

    fun sign(message: ByteArray, secretKey: ByteArray, auxRand: ByteArray): ByteArray {
        require(auxRand.size == 32) { "aux rand must be 32 bytes" }
        val d0 = scalar(secretKey)
        val p = requireNotNull(toAffine(multiply(G, d0)))
        val d = if (p.y.testBit(0)) N.subtract(d0) else d0
        val px = bytes32(p.x)
        val t = xor(bytes32(d), taggedHash("BIP0340/aux", auxRand))
        val k0 = BigInteger(1, taggedHash("BIP0340/nonce", t, px, message)).mod(N)
        check(k0.signum() != 0) { "nonce is zero" }
        val r = requireNotNull(toAffine(multiply(G, k0)))
        val k = if (r.y.testBit(0)) N.subtract(k0) else k0
        val rx = bytes32(r.x)
        val e = BigInteger(1, taggedHash("BIP0340/challenge", rx, px, message)).mod(N)
        val signature = rx + bytes32(k.add(e.multiply(d)).mod(N))
        check(verify(message, px, signature)) { "signature self-check failed" }
        return signature
    }

    fun verify(message: ByteArray, publicKey: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val p = liftX(BigInteger(1, publicKey)) ?: return false
        val r = BigInteger(1, signature.copyOfRange(0, 32))
        val s = BigInteger(1, signature.copyOfRange(32, 64))
        if (r >= P || s >= N) return false
        val e = BigInteger(1, taggedHash("BIP0340/challenge", signature.copyOfRange(0, 32), publicKey, message)).mod(N)
        val sG = multiply(G, s)
        val eP = toAffine(multiply(p, e))
        val sum = if (eP == null) sG else addMixed(sG, Affine(eP.x, P.subtract(eP.y).mod(P)))
        val point = toAffine(sum) ?: return false
        return !point.y.testBit(0) && point.x == r
    }

    /** x coordinate of secret × the point with x-only [publicKey]; the same from either side of a pair. */
    fun sharedX(secretKey: ByteArray, publicKey: ByteArray): ByteArray? {
        if (publicKey.size != 32) return null
        val d = runCatching { scalar(secretKey) }.getOrNull() ?: return null
        val point = liftX(BigInteger(1, publicKey)) ?: return null
        return toAffine(multiply(point, d))?.let { bytes32(it.x) }
    }

    private fun scalar(secretKey: ByteArray): BigInteger {
        require(secretKey.size == 32) { "secret key must be 32 bytes" }
        val d = BigInteger(1, secretKey)
        require(d.signum() != 0 && d < N) { "secret key out of range" }
        return d
    }

    private fun liftX(x: BigInteger): Affine? {
        if (x >= P) return null
        val c = x.modPow(THREE, P).add(SEVEN).mod(P)
        val y = c.modPow(SQRT_EXPONENT, P)
        if (y.modPow(TWO, P) != c) return null
        return Affine(x, if (y.testBit(0)) P.subtract(y) else y)
    }

    private fun multiply(base: Affine, k: BigInteger): Jacobian {
        var result = INFINITY
        for (i in k.bitLength() - 1 downTo 0) {
            result = double(result)
            if (k.testBit(i)) result = addMixed(result, base)
        }
        return result
    }

    private fun double(a: Jacobian): Jacobian {
        if (a.isInfinity || a.y.signum() == 0) return INFINITY
        val ySquared = a.y.multiply(a.y).mod(P)
        val s = a.x.multiply(ySquared).shiftLeft(2).mod(P)
        val m = a.x.multiply(a.x).multiply(THREE).mod(P)
        val x = m.multiply(m).subtract(s.shiftLeft(1)).mod(P)
        val y = m.multiply(s.subtract(x)).subtract(ySquared.multiply(ySquared).multiply(EIGHT)).mod(P)
        val z = a.y.multiply(a.z).shiftLeft(1).mod(P)
        return Jacobian(x, y, z)
    }

    private fun addMixed(a: Jacobian, b: Affine): Jacobian {
        if (a.isInfinity) return Jacobian(b.x, b.y, BigInteger.ONE)
        val zSquared = a.z.multiply(a.z).mod(P)
        val u2 = b.x.multiply(zSquared).mod(P)
        val s2 = b.y.multiply(zSquared).multiply(a.z).mod(P)
        val h = u2.subtract(a.x).mod(P)
        val r = s2.subtract(a.y).mod(P)
        if (h.signum() == 0) {
            return if (r.signum() == 0) double(a) else INFINITY
        }
        val hSquared = h.multiply(h).mod(P)
        val hCubed = hSquared.multiply(h).mod(P)
        val v = a.x.multiply(hSquared).mod(P)
        val x = r.multiply(r).subtract(hCubed).subtract(v.shiftLeft(1)).mod(P)
        val y = r.multiply(v.subtract(x)).subtract(a.y.multiply(hCubed)).mod(P)
        val z = a.z.multiply(h).mod(P)
        return Jacobian(x, y, z)
    }

    private fun toAffine(a: Jacobian): Affine? {
        if (a.isInfinity) return null
        val zInverse = a.z.modInverse(P)
        val zInverseSquared = zInverse.multiply(zInverse).mod(P)
        return Affine(
            a.x.multiply(zInverseSquared).mod(P),
            a.y.multiply(zInverseSquared).multiply(zInverse).mod(P),
        )
    }

    private fun taggedHash(tag: String, vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val tagHash = digest.digest(tag.toByteArray(Charsets.UTF_8))
        digest.update(tagHash)
        digest.update(tagHash)
        parts.forEach(digest::update)
        return digest.digest()
    }

    private fun bytes32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        if (raw.size == 32) return raw
        val out = ByteArray(32)
        if (raw.size > 32) {
            System.arraycopy(raw, raw.size - 32, out, 0, 32)
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
        }
        return out
    }

    private fun xor(a: ByteArray, b: ByteArray): ByteArray = ByteArray(a.size) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }
}
