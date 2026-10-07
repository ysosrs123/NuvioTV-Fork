package com.nuvio.tv.core.iptv

import java.io.BufferedReader
import java.io.Reader

class VodJsonNumber(val raw: String) {
    override fun equals(other: Any?) = other is VodJsonNumber && other.raw == raw
    override fun hashCode() = raw.hashCode()
    override fun toString() = raw
}

class VodJsonReader(input: Reader, private val maxString: Int = 65_536, private val maxItems: Int = 100_000, private val maxDepth: Int = 24) {
    private val reader = input as? BufferedReader ?: BufferedReader(input, 16 * 1024)
    private var peeked = NONE

    fun forEachRow(checkCancellation: () -> Unit = {}, block: (Any?) -> Unit) {
        when (space()) {
            '['.code -> {
                take()
                if (space() == ']'.code) take() else while (true) {
                    checkCancellation()
                    block(value(1))
                    if (separator(']')) break
                }
            }
            '{'.code -> {
                take()
                if (space() == '}'.code) take() else while (true) {
                    checkCancellation()
                    string(); expect(':'); block(value(1))
                    if (separator('}')) break
                }
            }
            else -> value(0)
        }
        end()
    }

    fun readDocument(): Any? = value(0).also { end() }

    private fun value(depth: Int): Any? {
        require(depth <= maxDepth) { "JSON depth" }
        return when (space()) {
            '{'.code -> objectValue(depth)
            '['.code -> arrayValue(depth)
            '"'.code -> string()
            't'.code -> literal("true", true)
            'f'.code -> literal("false", false)
            'n'.code -> literal("null", null)
            else -> number()
        }
    }

    private fun objectValue(depth: Int): Map<String, Any?> {
        take()
        val result = LinkedHashMap<String, Any?>()
        if (space() == '}'.code) { take(); return result }
        while (true) {
            val key = string(); expect(':'); result[key] = value(depth + 1)
            require(result.size <= maxItems) { "JSON size" }
            if (separator('}')) return result
        }
    }

    private fun arrayValue(depth: Int): List<Any?> {
        take()
        val result = ArrayList<Any?>()
        if (space() == ']'.code) { take(); return result }
        while (true) {
            result += value(depth + 1)
            require(result.size <= maxItems) { "JSON size" }
            if (separator(']')) return result
        }
    }

    private fun separator(close: Char): Boolean = when (space()) {
        ','.code -> { take(); false }
        close.code -> { take(); true }
        else -> throw IllegalArgumentException("JSON separator")
    }

    private fun literal(text: String, result: Any?): Any? {
        for (char in text) require(take() == char.code) { "JSON literal" }
        return result
    }

    private fun number(): VodJsonNumber {
        val text = StringBuilder()
        while (true) {
            val next = peek()
            if (next < 0 || next.toChar() !in NUMBER_CHARS) break
            text.append(take().toChar())
            require(text.length <= 64) { "JSON number" }
        }
        require(NUMBER.matches(text)) { "JSON number" }
        return VodJsonNumber(text.toString())
    }

    private fun string(): String {
        require(space() == '"'.code) { "JSON string" }
        take()
        val text = StringBuilder()
        while (true) {
            val next = take()
            require(next >= 0 && text.length <= maxString) { "JSON string" }
            when {
                next == '"'.code -> return text.toString()
                next == '\\'.code -> when (val escape = take()) {
                    '"'.code, '\\'.code, '/'.code -> text.append(escape.toChar())
                    'b'.code -> text.append('\b')
                    'f'.code -> text.append('\u000C')
                    'n'.code -> text.append('\n')
                    'r'.code -> text.append('\r')
                    't'.code -> text.append('\t')
                    'u'.code -> {
                        var code = 0
                        repeat(4) { code = code * 16 + Character.digit(take(), 16).also { require(it >= 0) { "JSON escape" } } }
                        text.append(code.toChar())
                    }
                    else -> throw IllegalArgumentException("JSON escape")
                }
                next < 0x20 -> throw IllegalArgumentException("JSON control character")
                else -> text.append(next.toChar())
            }
        }
    }

    private fun expect(char: Char) { require(space() == char.code) { "JSON $char" }; take() }
    private fun end() { require(space() < 0) { "JSON trailing data" } }
    private fun space(): Int {
        while (true) {
            val next = peek()
            if (next == ' '.code || next == '\t'.code || next == '\n'.code || next == '\r'.code || next == 0xFEFF) take() else return next
        }
    }
    private fun peek(): Int { if (peeked == NONE) peeked = reader.read(); return peeked }
    private fun take(): Int = peek().also { peeked = NONE }

    private companion object {
        const val NONE = -2
        const val NUMBER_CHARS = "-+0123456789.eE"
        val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    }
}
