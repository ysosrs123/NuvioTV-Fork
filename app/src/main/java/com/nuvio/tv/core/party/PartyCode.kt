package com.nuvio.tv.core.party

import java.security.SecureRandom

object PartyCode {
    const val LENGTH = 6
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    fun generate(random: SecureRandom = SecureRandom()): String = buildString {
        repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    fun normalize(input: String): String? {
        val code = input.uppercase().filter { it.isLetterOrDigit() }
        if (code.length != LENGTH || code.any { it !in ALPHABET }) return null
        return code
    }

    fun isAllowedChar(char: Char): Boolean = char.uppercaseChar() in ALPHABET

    fun display(code: String): String = code.chunked(LENGTH / 2).joinToString(" ")
}
