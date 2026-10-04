package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import java.io.IOException

/** A 206 probe must describe the exact requested identity-encoded window. */
internal object ParallelRangeProbeResponse {
    private val range = Regex("^bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)$", RegexOption.IGNORE_CASE)
    private val decimal = Regex("^[0-9]+$")

    fun validatedTotal(spec: DataSpec, responseCode: Int, headers: Map<String, List<String>>): Long {
        // A successful ordinary response is still readable through the single
        // upstream path, but advertised range support is not proof of a 206.
        if (responseCode != 206) return C.LENGTH_UNSET.toLong()
        fun values(name: String) = headers.entries.filter { it.key.equals(name, ignoreCase = true) }.flatMap { it.value }
        fun reject(label: String): Nothing = throw IOException("Invalid parallel probe $label")
        val ranges = values("Content-Range")
        if (ranges.size != 1) reject("range")
        val match = range.matchEntire(ranges.single().trim()) ?: reject("range")
        val start = match.groupValues[1].toLongOrNull() ?: reject("range")
        val end = match.groupValues[2].toLongOrNull() ?: reject("range")
        val totalText = match.groupValues[3]
        val total = if (totalText == "*") null else totalText.toLongOrNull() ?: reject("range")
        if (start != spec.position || end < start || (total != null && end >= total)) reject("geometry")
        val difference = end - start
        if (difference == Long.MAX_VALUE) reject("geometry")
        val span = difference + 1
        if (total != null) {
            val available = total - start
            val requested = if (spec.length == C.LENGTH_UNSET.toLong()) available else minOf(spec.length, available)
            if (requested <= 0 || span != requested) reject("geometry")
        } else if (spec.length != C.LENGTH_UNSET.toLong() && span > spec.length) {
            reject("geometry")
        }
        val encodings = values("Content-Encoding")
        if (encodings.any { !it.trim().equals("identity", ignoreCase = true) }) reject("encoding")
        val lengths = values("Content-Length")
        if (lengths.isNotEmpty()) {
            if (lengths.size != 1) reject("length")
            val value = lengths.single().trim()
            if (!decimal.matches(value) || value.toLongOrNull() != span) reject("length")
        }
        return total ?: C.LENGTH_UNSET.toLong()
    }
}
